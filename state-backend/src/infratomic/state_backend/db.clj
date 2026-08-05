(ns infratomic.state-backend.db
  "Datomic dev-local storage setup, schema, and query helpers for the State
  Backend.

  No raw Terraform state JSON is ever stored: Datomic dev-local hard-enforces
  a 4096-byte limit per `:db.type/string` datom, and the sample app's real
  state document (~12.4KB) exceeds that on the very first apply. State is
  decomposed into a `state-version` entity (small top-level metadata only)
  and one `resource` entity per managed resource; `GET` reconstructs a state
  JSON document from these entities (see `infratomic.state-backend.handler`).
  See docs/adr/0002-reconstruct-state-instead-of-raw-storage.md for the
  resource attribute storage rationale."
  (:require [datomic.client.api :as d]))

(def db-name "state-backend")

(def schema
  [{:db/ident       :state-version/version
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Terraform's state format version (e.g. 4) for this state version."}
   {:db/ident       :state-version/terraform-version
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The Terraform CLI version that produced this state version."}
   {:db/ident       :state-version/serial
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Terraform's serial number for this state version."}
   {:db/ident       :state-version/lineage
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Terraform's lineage identifier for this state version."}
   {:db/ident       :state-version/outputs
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The state document's `outputs` map, JSON-encoded as a string."}

   {:db/ident       :resource/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Unique identifier for a resource, computed as \"type.name\"."}
   {:db/ident       :resource/type
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The Terraform resource type, e.g. \"aws_s3_bucket\"."}
   {:db/ident       :resource/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The resource's name within the Terraform config."}
   {:db/ident       :resource/attributes
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The resource's raw attribute map, JSON-encoded as a string."}
   {:db/ident       :resource/instance-meta
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "JSON-encoded {schema_version, provider, sensitive_attributes, private, dependencies} needed to reconstruct a Terraform-acceptable state document, beyond the raw attribute map."}
   {:db/ident       :resource/state-version
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The state version this resource was last seen in."}])

(defn storage-dir
  "Resolve the Datomic dev-local storage directory: `.datomic/` at the repo
  root. Assumes the process is started with `state-backend/` (this project's
  `deps.edn` directory) as the current working directory, per the documented
  `clojure -M -m infratomic.state-backend.main` invocation."
  []
  (str (System/getProperty "user.dir") "/../.datomic"))

(defn client
  "Build a Datomic dev-local client backed by `dir` (defaulting to
  `storage-dir`)."
  ([] (client (storage-dir)))
  ([dir]
   (d/client {:server-type :datomic-local
              :system      "infratomic"
              :storage-dir dir})))

(defn ensure-db!
  "Create the database if it doesn't already exist, connect, and ensure the
  schema is transacted. Idempotent - safe to call on every startup."
  [client]
  (d/create-database client {:db-name db-name})
  (let [conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    conn))

(defn latest-state-version-eid
  "The entity id of the most recently transacted state-version entity, or
  nil if none exists. `:state-version/outputs` is always transacted (see
  `infratomic.state-backend.handler/post-tx-data`, defaulting to an
  empty-map JSON string when the posted state has no outputs), so it's a
  reliable marker for \"this entity is a state-version\"."
  [db]
  (let [max-tx (ffirst (d/q '[:find (max ?tx)
                               :where [?e :state-version/outputs _ ?tx]]
                             db))]
    (when max-tx
      (ffirst (d/q '[:find ?e
                      :in $ ?tx
                      :where [?e :state-version/outputs _ ?tx]]
                    db max-tx)))))

(defn all-resource-eids
  "All resource entity ids currently in the database."
  [db]
  (map first (d/q '[:find ?e :where [?e :resource/id]] db)))

(defn resource-id->eid
  "Map of `:resource/id` -> entity id for every resource entity currently in
  the database. Used by `POST` to find resources that are in the database
  but no longer present in the newly posted state (e.g. destroyed/removed
  resources), so their stale entities can be retracted."
  [db]
  (into {} (d/q '[:find ?id ?e :where [?e :resource/id ?id]] db)))

(defn all-resources
  "Pull `:resource/type`, `:resource/name`, `:resource/attributes`, and
  `:resource/instance-meta` for every resource entity currently in the
  database. Used by `GET` to reconstruct a state document's `resources[]`."
  [db]
  (map #(d/pull db [:resource/type :resource/name :resource/attributes :resource/instance-meta] %)
       (all-resource-eids db)))

(defn all-state-version-eids
  "All state-version entity ids currently in the database. `DELETE` needs to
  retract every one of these, not just the latest, so that a subsequent
  `GET` correctly reports \"no state\" instead of falling back to an older,
  still-live version."
  [db]
  (map first (d/q '[:find ?e :where [?e :state-version/outputs]] db)))
