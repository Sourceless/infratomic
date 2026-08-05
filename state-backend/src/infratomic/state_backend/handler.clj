(ns infratomic.state-backend.handler
  "Ring handler implementing Terraform's `http` state backend protocol
  (GET/POST/DELETE on /state). LOCK/UNLOCK are out of scope.

  No raw Terraform state JSON is ever stored (Datomic dev-local's 4096-byte
  per-string limit makes that impossible for the sample app's real state
  size, ~12.4KB). `POST` decomposes the posted state into a state-version
  entity (top-level metadata) plus one resource entity per *managed*
  resource; `GET` reconstructs a state JSON document from those entities.
  Only `mode == \"managed\"` entries from the posted `resources[]` are
  persisted — Terraform always re-reads `mode == \"data\"` (data source)
  entries fresh on every plan/apply regardless of prior state, so omitting
  them causes no drift (verified against the real sample app). `POST` also
  retracts any resource entity whose `:resource/id` is not present in the
  newly posted `resources[]` (e.g. a destroyed resource), so a resource
  removed from state never lingers in subsequent `GET`s."
  (:require [cheshire.core :as json]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]))

(defn- managed?
  [resource]
  (= "managed" (get resource "mode")))

(defn- instance-meta
  "Everything about a resource's single instance needed to reconstruct a
  Terraform-acceptable state entry, other than its attributes: schema
  version, provider, sensitive attributes, private data, and dependencies.
  JSON-encoded as one opaque string, mirroring how `:resource/attributes`
  already stores the attribute map as an opaque string (attributes are not
  decomposed further, per the dual-storage ADR)."
  [resource instance]
  (json/generate-string
   {"schema_version"       (get instance "schema_version")
    "provider"             (get resource "provider")
    "sensitive_attributes" (get instance "sensitive_attributes" [])
    "private"              (get instance "private")
    "dependencies"         (get instance "dependencies")}))

(defn- resource-id
  [resource]
  (str (get resource "type") "." (get resource "name")))

(defn- managed-resources
  "The *managed* entries (`mode == \"data\"` entries excluded) from a parsed
  posted state's `resources[]`."
  [parsed]
  (filter managed? (get parsed "resources" [])))

(defn- resource->tx
  "Build an upsert tx-map for one *managed* entry in the posted state's
  `resources[]`, referencing the new state-version via its tempid."
  [state-version-tempid resource]
  (let [instance   (-> resource (get "instances") first)
        attributes (get instance "attributes" {})]
    {:resource/id             (resource-id resource)
     :resource/type           (get resource "type")
     :resource/name           (get resource "name")
     :resource/attributes     (json/generate-string attributes)
     :resource/instance-meta  (instance-meta resource instance)
     :resource/state-version  state-version-tempid}))

(defn- post-tx-data
  "Build the single transaction for a POST: a new state-version entity
  holding the state's top-level metadata, plus one upserted resource entity
  per *managed* `resources[]` entry (`mode == \"data\"` entries are
  skipped). Missing `resources`/`serial`/`lineage` are handled permissively
  per the state backend's protocol; `outputs` is always transacted
  (defaulting to an empty map) so a state-version entity is always
  identifiable by the presence of `:state-version/outputs`."
  [parsed]
  (let [sv-tempid  "new-state-version"
        resources  (managed-resources parsed)
        outputs    (get parsed "outputs" {})
        sv-tx      (cond-> {:db/id                 sv-tempid
                             :state-version/outputs (json/generate-string outputs)}
                     (contains? parsed "version")           (assoc :state-version/version (get parsed "version"))
                     (contains? parsed "terraform_version") (assoc :state-version/terraform-version (get parsed "terraform_version"))
                     (contains? parsed "serial")            (assoc :state-version/serial (get parsed "serial"))
                     (contains? parsed "lineage")           (assoc :state-version/lineage (get parsed "lineage")))]
    (into [sv-tx] (map (partial resource->tx sv-tempid) resources))))

(defn- stale-resource-retractions
  "Tx-data retracting resource entities that are currently in `db` but are
  no longer present in `parsed`'s managed `resources[]` - i.e. resources
  destroyed or otherwise removed from Terraform's state since the last
  `POST`. Without this, `GET`/`terraform state list` would keep serving
  \"ghost\" resources indefinitely, since `resource->tx` only ever upserts."
  [db parsed]
  (let [posted-ids (into #{} (map resource-id) (managed-resources parsed))
        existing   (db/resource-id->eid db)
        stale-eids (vals (apply dissoc existing posted-ids))]
    (mapv (fn [eid] [:db/retractEntity eid]) stale-eids)))

(defn- parse-json
  "Parse `s` as JSON, returning ::invalid instead of throwing on failure."
  [s]
  (try
    (json/parse-string s)
    (catch Exception _
      ::invalid)))

(defn- resource-entry
  "Reconstruct one `resources[]` entry (matching Terraform's own state JSON
  shape) from a pulled resource entity. `mode` is hardcoded to `\"managed\"`
  since only managed resources are ever persisted. `private`/`dependencies`
  keys are omitted when absent, matching Terraform's own output (it omits
  rather than nulls these keys for resources with none)."
  [{:resource/keys [type name attributes instance-meta]}]
  (let [meta (json/parse-string instance-meta)]
    {"mode"      "managed"
     "type"      type
     "name"      name
     "provider"  (get meta "provider")
     "instances" [(cond-> {"schema_version"       (get meta "schema_version")
                            "attributes"           (json/parse-string attributes)
                            "sensitive_attributes" (get meta "sensitive_attributes" [])}
                    (some? (get meta "private"))       (assoc "private" (get meta "private"))
                    (seq (get meta "dependencies"))    (assoc "dependencies" (get meta "dependencies")))]}))

(defn- reconstruct-state
  "Build a Terraform-state-JSON document from the latest state-version
  entity and the current resource entities. Not byte-identical to what was
  last posted, but semantically equivalent for Terraform's client, which
  parses this structurally rather than diffing it - verified empirically
  against the real sample app (no `terraform plan` drift after a service
  restart)."
  [db sv-eid]
  (let [sv (d/pull db [:state-version/version
                        :state-version/terraform-version
                        :state-version/serial
                        :state-version/lineage
                        :state-version/outputs]
                    sv-eid)]
    {"version"           (:state-version/version sv)
     "terraform_version" (:state-version/terraform-version sv)
     "serial"            (:state-version/serial sv)
     "lineage"           (:state-version/lineage sv)
     "outputs"           (json/parse-string (:state-version/outputs sv))
     "resources"         (mapv resource-entry (db/all-resources db))}))

(defn get-state
  [conn]
  (let [db  (d/db conn)
        eid (db/latest-state-version-eid db)]
    (if eid
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string (reconstruct-state db eid))}
      {:status 204 :headers {} :body ""})))

(defn post-state
  [conn raw-body]
  (let [parsed (parse-json raw-body)]
    (if (= parsed ::invalid)
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "invalid JSON"})}
      (let [db          (d/db conn)
            retractions (stale-resource-retractions db parsed)
            tx-data     (into (post-tx-data parsed) retractions)]
        (d/transact conn {:tx-data tx-data})
        {:status 200 :headers {} :body ""}))))

(defn delete-state
  [conn]
  (let [db               (d/db conn)
        state-version-eids (db/all-state-version-eids db)
        resource-eids    (db/all-resource-eids db)
        eids             (concat state-version-eids resource-eids)
        retractions      (mapv (fn [eid] [:db/retractEntity eid]) eids)]
    (when (seq retractions)
      (d/transact conn {:tx-data retractions}))
    {:status 200 :headers {} :body ""}))

(defn handler
  "Build the Ring handler for the State Backend, closing over `conn`."
  [conn]
  (fn [{:keys [request-method uri body]}]
    (if (= uri "/state")
      (case request-method
        :get    (get-state conn)
        :post   (post-state conn (slurp body))
        :delete (delete-state conn)
        {:status 405 :headers {} :body ""})
      {:status 404 :headers {} :body ""})))
