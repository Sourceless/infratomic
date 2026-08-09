(ns infratomic.state-backend.db
  "Datomic dev-local storage setup, schema, and attribute
  decomposition/reconstruction helpers for the State Backend.

  No raw Terraform state JSON is ever stored: Datomic dev-local hard-enforces
  a 4096-byte limit per `:db.type/string` datom, and the sample app's real
  state document (~12.4KB) exceeds that on the very first apply. State is
  decomposed into a `state-version` entity (small top-level metadata only)
  and one `resource` entity per managed resource. A resource's attributes
  are further decomposed into real Datomic datoms rather than stored as a
  single opaque JSON string: attributes covered by `resource-schema` are
  typed, structural attributes directly on the resource entity; every other
  attribute (including every attribute of an unmodeled resource type) is a
  generic `:resource/attribute` key/value sub-entity, with nested
  maps/vectors flattened into multiple dotted/indexed leaves. Any single
  value too large for the 4096-byte limit falls back to chunked opaque
  storage for that value alone. `GET` reconstructs a state JSON document
  from these entities (see `infratomic.state-backend.handler`). See
  docs/adr/0003-decompose-resource-attributes-into-datoms.md for the
  resource attribute storage rationale (superseding
  docs/adr/0002-reconstruct-state-instead-of-raw-storage.md)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [datomic.client.api :as d]))

(def db-name "state-backend")

(def resource-schema
  "Data-driven schema map, keyed by Terraform resource type string, valued
  by a map of Terraform attribute key -> {:ident <keyword> :value-type
  <db.type> :cardinality <db.cardinality, default :one>}. The single source
  of truth for (a) the extra `:db/ident` schema entries transacted alongside
  the fixed schema, and (b) which attributes `resource->tx` stores as typed
  datoms vs. the generic key/value escape hatch. Extending the modeled
  surface (a new resource type or attribute) is a data-only change - add a
  map entry, no new code branch.

  `aws_security_group`'s `id` and `aws_security_group_rule`'s
  `security_group_id` are both modeled (not left generic) specifically so
  the \"security groups with port 22 open to the internet\" query's join
  from rule back to owning security group is a real Datalog value-equality
  join on typed attributes."
  {"aws_security_group"
   {"id"     {:ident :aws-security-group/id :value-type :db.type/string}
    "vpc_id" {:ident :aws-security-group/vpc-id :value-type :db.type/string}}

   "aws_security_group_rule"
   {"from_port"                 {:ident :aws-security-group-rule/from-port :value-type :db.type/long}
    "to_port"                   {:ident :aws-security-group-rule/to-port :value-type :db.type/long}
    "protocol"                  {:ident :aws-security-group-rule/protocol :value-type :db.type/string}
    "security_group_id"         {:ident :aws-security-group-rule/security-group-id :value-type :db.type/string}
    "cidr_blocks"                {:ident :aws-security-group-rule/cidr-block :value-type :db.type/string :cardinality :db.cardinality/many}
    "type"                      {:ident :aws-security-group-rule/type :value-type :db.type/string}
    "source_security_group_id" {:ident :aws-security-group-rule/source-security-group-id :value-type :db.type/string}}

   "aws_vpc"
   {"id"         {:ident :aws-vpc/id :value-type :db.type/string}
    "cidr_block" {:ident :aws-vpc/cidr-block :value-type :db.type/string}}

   "aws_subnet"
   {"id"         {:ident :aws-subnet/id :value-type :db.type/string}
    "vpc_id"     {:ident :aws-subnet/vpc-id :value-type :db.type/string}
    "cidr_block" {:ident :aws-subnet/cidr-block :value-type :db.type/string}}

   "aws_route_table"
   {"id"     {:ident :aws-route-table/id :value-type :db.type/string}
    "vpc_id" {:ident :aws-route-table/vpc-id :value-type :db.type/string}}

   "aws_route"
   {"id"                        {:ident :aws-route/id :value-type :db.type/string}
    "route_table_id"            {:ident :aws-route/route-table-id :value-type :db.type/string}
    "destination_cidr_block"    {:ident :aws-route/destination-cidr-block :value-type :db.type/string}
    "gateway_id"                {:ident :aws-route/gateway-id :value-type :db.type/string}
    "vpc_peering_connection_id" {:ident :aws-route/vpc-peering-connection-id :value-type :db.type/string}}

   "aws_route_table_association"
   {"id"             {:ident :aws-route-table-association/id :value-type :db.type/string}
    "subnet_id"      {:ident :aws-route-table-association/subnet-id :value-type :db.type/string}
    "route_table_id" {:ident :aws-route-table-association/route-table-id :value-type :db.type/string}}

   "aws_internet_gateway"
   {"id"     {:ident :aws-internet-gateway/id :value-type :db.type/string}
    "vpc_id" {:ident :aws-internet-gateway/vpc-id :value-type :db.type/string}}

   "aws_vpc_peering_connection"
   {"id"           {:ident :aws-vpc-peering-connection/id :value-type :db.type/string}
    "vpc_id"       {:ident :aws-vpc-peering-connection/vpc-id :value-type :db.type/string}
    "peer_vpc_id"  {:ident :aws-vpc-peering-connection/peer-vpc-id :value-type :db.type/string}}

   "aws_instance"
   {"id"                     {:ident :aws-instance/id :value-type :db.type/string}
    "subnet_id"              {:ident :aws-instance/subnet-id :value-type :db.type/string}
    "vpc_security_group_ids" {:ident :aws-instance/vpc-security-group-id :value-type :db.type/string :cardinality :db.cardinality/many}}})

(defn- modeled-schema-entry
  [{:keys [ident value-type cardinality]}]
  {:db/ident       ident
   :db/valueType   value-type
   :db/cardinality (or cardinality :db.cardinality/one)})

(def ^:private modeled-schema
  "The extra `:db/ident` schema entries generated from `resource-schema`,
  transacted alongside the fixed schema below."
  (into [] (comp (mapcat vals) (map modeled-schema-entry)) (vals resource-schema)))

(def schema
  (into
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
     :db/doc         "Unique identifier for a resource, computed as \"type.name\" for a Terraform-managed resource, or \"type.discovered-<aws_id>\" for a Discovered Resource."}
    {:db/ident       :resource/managed?
     :db/valueType   :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc         "true for a Terraform-managed resource (set by resource->tx on the POST /state path); false for a Discovered Resource (set by Sync). Read by GET /state reconstruction and stale-resource-retractions to exclude Discovered Resources from what Terraform is told it owns and from the stale-sweep."}
    {:db/ident       :resource/type
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "The Terraform resource type, e.g. \"aws_s3_bucket\"."}
    {:db/ident       :resource/name
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "The resource's name within the Terraform config."}
    {:db/ident       :resource/instance-meta
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "JSON-encoded {schema_version, provider, sensitive_attributes, private, dependencies} needed to reconstruct a Terraform-acceptable state document, beyond the attribute map."}
    {:db/ident       :resource/state-version
     :db/valueType   :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/doc         "The state version this resource was last seen in."}

    {:db/ident       :resource/attribute
     :db/valueType   :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/isComponent true
     :db/doc         "Generic key/value sub-entities for a resource's attributes not covered by resource-schema, or too large to store via their normal typed/generic representation (see :resource.attribute/overflow-chunk). Component: retracting the resource entity retracts these too."}
    {:db/ident       :resource.attribute/key
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "The (possibly dotted/indexed, for flattened nested values) attribute key this generic sub-entity holds a value for."}
    {:db/ident       :resource.attribute/value
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "The attribute's JSON-encoded value, when it fits within the 4096-byte-per-string limit. Absent (see :resource.attribute/overflow-chunk instead) when the value was too large to store as a single string."}
    {:db/ident       :resource.attribute/overflow-chunk
     :db/valueType   :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/isComponent true
     :db/doc         "Ordered chunks of a JSON-encoded attribute value too large to fit within the 4096-byte-per-string limit as a single :resource.attribute/value. Present instead of :resource.attribute/value; concatenate chunk values in :overflow-chunk/index order and JSON-parse the result to recover the original value."}
    {:db/ident       :overflow-chunk/index
     :db/valueType   :db.type/long
     :db/cardinality :db.cardinality/one
     :db/doc         "This chunk's zero-based position within its parent attribute's original JSON-encoded value."}
    {:db/ident       :overflow-chunk/value
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "One chunk (<=4096 bytes) of the parent attribute's JSON-encoded value."}]
   modeled-schema))

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

(defn- untagged-resource-eids
  "Entity ids of every resource entity with no `:resource/managed?` value
  yet - i.e. every resource that predates that attribute. Every one of
  these was written before Sync (and Discovered Resources) existed, so by
  definition every one of them is Terraform-managed (see ADR-0005)."
  [db]
  (map first (d/q '[:find ?e
                     :where
                     [?e :resource/id]
                     (not [?e :resource/managed? _])]
                   db)))

(defn backfill-managed-flag!
  "One-time, idempotent migration step: tag every resource entity that
  predates `:resource/managed?` as Terraform-managed (`true`). Runs on
  every `ensure-db!` call (i.e. every process start), but after the first
  run post-deploy the query above finds nothing, making every subsequent
  call a no-op (see ADR-0005 and design.md's Migration Plan). Public
  (rather than `defn-`) so it's directly testable against an isolated
  test db, mirroring `ensure-db!`'s own step."
  [conn]
  (let [eids (untagged-resource-eids (d/db conn))]
    (when (seq eids)
      (d/transact conn {:tx-data (mapv (fn [eid] [:db/add eid :resource/managed? true]) eids)}))))

(defn ensure-db!
  "Create the database if it doesn't already exist, connect, ensure the
  schema is transacted, and backfill `:resource/managed? true` onto any
  resource entity written before that attribute existed. Idempotent - safe
  to call on every startup."
  [client]
  (d/create-database client {:db-name db-name})
  (let [conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    (backfill-managed-flag! conn)
    conn))

;; ---------------------------------------------------------------------------
;; Attribute decomposition (POST write path)
;; ---------------------------------------------------------------------------

(def max-string-bytes
  "Datomic dev-local's hard-enforced limit per `:db.type/string` datom."
  4096)

(defn- byte-length
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- fits?
  [^String s]
  (<= (byte-length s) max-string-bytes))

(def ^:private overflow-chunk-chars
  "Characters per overflow chunk. UTF-8 encodes a char as at most 4 bytes,
  so 1000 chars is at most 4000 bytes - safely under `max-string-bytes`."
  1000)

(defn- string-chunks
  [^String s]
  (mapv (fn [start] (subs s start (min (count s) (+ start overflow-chunk-chars))))
        (range 0 (count s) overflow-chunk-chars)))

(defn- overflow-chunks
  "Split a too-large JSON-encoded string `s` into ordered
  `:resource.attribute/overflow-chunk` sub-entity tx-maps, each within
  `max-string-bytes`."
  [s]
  (into []
        (map-indexed (fn [i chunk]
                       {:db/id                (str (random-uuid))
                        :overflow-chunk/index  i
                        :overflow-chunk/value  chunk}))
        (string-chunks s)))

(defn- generic-entry
  "Build one `:resource/attribute` sub-entity tx-map for `key` -> `value`,
  JSON-encoding `value` so its original type is recoverable on reconstruction
  (a boolean/number/string/nested-collection leaf all round-trip). Falls
  back to chunked overflow storage if the encoded value would exceed
  `max-string-bytes`."
  [key value]
  (let [encoded (json/generate-string value)]
    (merge {:db/id                    (str (random-uuid))
            :resource.attribute/key   key}
           (if (fits? encoded)
             {:resource.attribute/value encoded}
             {:resource.attribute/overflow-chunk (overflow-chunks encoded)}))))

(defn flatten-attribute-value
  "Recursively flatten a nested attribute `value` (map/vector) rooted at
  `key` into a seq of `[dotted-or-indexed-key leaf-value]` pairs: maps
  recurse with `.`-joined keys, vectors recurse with `.`-joined numeric
  indices, scalars are returned as-is (JSON-encoding happens later, at
  transaction time). `nil` leaves are skipped - Terraform's attribute maps
  use `nil` to mean \"no value\", and Datomic has no null datom to
  transact."
  [key value]
  (cond
    (nil? value)
    []

    (map? value)
    (mapcat (fn [[k v]] (flatten-attribute-value (str key "." (name k)) v)) value)

    (sequential? value)
    (mapcat (fn [i v] (flatten-attribute-value (str key "." i) v)) (range) value)

    :else
    [[key value]]))

(defn- oversized-string-value?
  "Whether a modeled attribute's value needs the oversized fallback. Only
  `:db.type/string`-valued modeled attributes can exceed the byte limit -
  every other Datomic value type (e.g. `:db.type/long`) is always small."
  [value-type value]
  (and (= :db.type/string value-type)
       (not (fits? (json/generate-string value)))))

(defn- decompose-scalar
  [{:keys [ident value-type]} key value]
  (if (oversized-string-value? value-type value)
    {:typed [] :generic [(generic-entry key value)]}
    {:typed [[ident value]] :generic []}))

(defn- decompose-many
  [{:keys [ident value-type]} key values]
  (reduce
   (fn [acc [idx v]]
     (cond
       (nil? v)
       acc

       (oversized-string-value? value-type v)
       (update acc :generic conj (generic-entry (str key "." idx) v))

       :else
       (update acc :typed conj [ident v])))
   {:typed [] :generic []}
   (map-indexed vector values)))

(defn- decompose-unmodeled
  [key value]
  (let [pairs (flatten-attribute-value key value)]
    {:typed []
     :generic (if (seq pairs)
                (mapv (fn [[k v]] (generic-entry k v)) pairs)
                ;; An empty map/vector flattens to zero leaves; preserve the
                ;; container shape itself rather than silently dropping the
                ;; key.
                (if (or (map? value) (sequential? value))
                  [(generic-entry key value)]
                  []))}))

(defn decompose-attributes
  "Given a Terraform resource `type` and its raw (parsed) `attributes` map,
  return `{:typed [[ident value] ...] :generic [sub-entity-tx-map ...]}`:
  typed pairs for attributes covered by `resource-schema` for `type`
  (falling back to `:generic` per-value when oversized), and generic
  key/value sub-entity tx-maps (via flattening) for everything else."
  [type attributes]
  (reduce
   (fn [acc [key value]]
     (if (nil? value)
       acc
       (let [schema-entry (get (get resource-schema type) key)]
         (merge-with into acc
                     (cond
                       (nil? schema-entry)
                       (decompose-unmodeled key value)

                       (= :db.cardinality/many (:cardinality schema-entry))
                       (decompose-many schema-entry key value)

                       :else
                       (decompose-scalar schema-entry key value))))))
   {:typed [] :generic []}
   attributes))

(defn- typed-tx-map
  "Fold `[ident value]` pairs into a tx-map fragment. An ident appearing
  more than once (only possible for a cardinality-many modeled attribute
  with multiple elements) becomes a vector value, transacting all of them
  at once."
  [typed-pairs]
  (reduce (fn [m [ident value]]
            (if (contains? m ident)
              (update m ident (fn [existing] (if (sequential? existing) (conj existing value) [existing value])))
              (assoc m ident value)))
          {}
          typed-pairs))

(defn resource-attr-tx
  "Build the tx-map fragment for one resource's decomposed attributes:
  typed keys for schema-mapped attributes, plus a `:resource/attribute`
  vector of generic key/value sub-entity tx-maps for everything else. Merge
  this into the resource's own upsert tx-map."
  [type attributes]
  (let [{:keys [typed generic]} (decompose-attributes type attributes)]
    (cond-> (typed-tx-map typed)
      (seq generic) (assoc :resource/attribute generic))))

;; ---------------------------------------------------------------------------
;; Attribute reconstruction (GET read path)
;; ---------------------------------------------------------------------------

(defn idents-for-key
  "All modeled `:db/ident`s (across every resource type in
  `resource-schema`) whose Terraform attribute key is `key`. Used by the
  by-attribute-value query to search modeled attributes regardless of which
  resource type they belong to."
  [key]
  (into #{} (keep (fn [attrs] (:ident (get attrs key)))) (vals resource-schema)))

(defn- ident->key
  [type]
  (into {} (map (fn [[k v]] [(:ident v) k])) (get resource-schema type)))

(defn- reconstruct-modeled
  [type pulled]
  (reduce (fn [m [ident key]]
            (if (contains? pulled ident)
              (let [v (get pulled ident)]
                (assoc m key (if (coll? v) (vec v) v)))
              m))
          {}
          (ident->key type)))

(defn- resolve-attribute-value
  [{:resource.attribute/keys [value overflow-chunk]}]
  (json/parse-string
   (if (seq overflow-chunk)
     (->> overflow-chunk (sort-by :overflow-chunk/index) (map :overflow-chunk/value) (apply str))
     value)))

(defn- numeric-key?
  [s]
  (boolean (re-matches #"\d+" s)))

(defn- assoc-path
  [m [k & more] value]
  (if (seq more)
    (update m k (fnil assoc-path {}) more value)
    (assoc m k value)))

(defn- build-nested
  [flat-map]
  (reduce (fn [acc [k v]] (assoc-path acc (str/split k #"\.") v))
          {}
          flat-map))

(defn- vectorize
  "Post-process a nested map built by `build-nested`: any map all of whose
  keys are non-negative-integer strings (i.e. every path segment came from
  a flattened vector index) becomes a vector, ordered by that index."
  [x]
  (if (map? x)
    (let [x' (into {} (map (fn [[k v]] [k (vectorize v)])) x)]
      (if (and (seq x') (every? numeric-key? (keys x')))
        (mapv second (sort-by (comp #(Long/parseLong ^String %) first) x'))
        x'))
    x))

(defn- unflatten
  [flat-map]
  (vectorize (build-nested flat-map)))

(defn- generic-attribute-map
  [pulled-attrs]
  (into {}
        (map (fn [attr] [(:resource.attribute/key attr) (resolve-attribute-value attr)]))
        pulled-attrs))

(defn reconstruct-attributes
  "Given a resource `type` and its pulled entity map (modeled attrs plus
  pulled `:resource/attribute` sub-entities), rebuild the original nested
  attribute map: un-flattening dotted/indexed generic keys, reading modeled
  attributes by their schema-map keys, and resolving oversized-fallback
  (chunked overflow) values."
  [type pulled]
  (let [modeled (reconstruct-modeled type pulled)
        generic (unflatten (generic-attribute-map (:resource/attribute pulled)))]
    (merge generic modeled)))

;; ---------------------------------------------------------------------------
;; Query helpers
;; ---------------------------------------------------------------------------

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
  "All resource entity ids currently in the database, Terraform-managed and
  Discovered alike."
  [db]
  (map first (d/q '[:find ?e :where [?e :resource/id]] db)))

(defn managed-resource-eids
  "Entity ids of every Terraform-managed (`:resource/managed? true`)
  resource currently in the database - excludes Discovered Resources.
  Used by `GET /state` reconstruction, which must never tell Terraform it
  owns a Discovered Resource."
  [db]
  (map first (d/q '[:find ?e :where [?e :resource/managed? true]] db)))

(defn resource-id->eid
  "Map of `:resource/id` -> entity id for every resource entity currently in
  the database, Terraform-managed and Discovered alike. Used to find a
  resource's existing entity (if any) when upserting its attributes."
  [db]
  (into {} (d/q '[:find ?id ?e :where [?e :resource/id ?id]] db)))

(defn managed-resource-id->eid
  "Map of `:resource/id` -> entity id for every Terraform-managed
  (`:resource/managed? true`) resource currently in the database -
  excludes Discovered Resources. Used by `POST` to find resources that are
  in the database but no longer present in the newly posted state (e.g.
  destroyed/removed resources), so their stale entities can be retracted -
  a Discovered Resource is never a candidate for this retraction,
  regardless of whether it's mentioned in the posted body."
  [db]
  (into {} (d/q '[:find ?id ?e :where [?e :resource/id ?id] [?e :resource/managed? true]] db)))

(def ^:private modeled-idents
  (into [] (comp (mapcat vals) (map :ident)) (vals resource-schema)))

(def ^:private resource-pull-pattern
  (into [:resource/type :resource/name :resource/instance-meta
         {:resource/attribute [:resource.attribute/key :resource.attribute/value
                                {:resource.attribute/overflow-chunk [:overflow-chunk/index :overflow-chunk/value]}]}]
        modeled-idents))

(defn all-resources
  "Pull `:resource/type`, `:resource/name`, `:resource/instance-meta`, every
  modeled attribute, and every generic `:resource/attribute` sub-entity for
  every resource entity currently in the database, Terraform-managed and
  Discovered alike."
  [db]
  (map #(d/pull db resource-pull-pattern %) (all-resource-eids db)))

(defn managed-resources
  "Like `all-resources`, but limited to Terraform-managed
  (`:resource/managed? true`) resources - excludes Discovered Resources.
  Used by `GET` to reconstruct a state document's `resources[]`, so
  Terraform is never told it owns a Discovered Resource."
  [db]
  (map #(d/pull db resource-pull-pattern %) (managed-resource-eids db)))

(defn all-state-version-eids
  "All state-version entity ids currently in the database. `DELETE` needs to
  retract every one of these, not just the latest, so that a subsequent
  `GET` correctly reports \"no state\" instead of falling back to an older,
  still-live version."
  [db]
  (map first (d/q '[:find ?e :where [?e :state-version/outputs]] db)))

(def ^:private many-modeled-idents
  (into [] (comp (mapcat vals) (filter #(= :db.cardinality/many (:cardinality %))) (map :ident))
        (vals resource-schema)))

(defn resource-upsert-retractions
  "Tx-data to retract before re-asserting a resource's decomposed
  attributes on `POST`, so an upsert-in-place doesn't accumulate stale
  datoms: every existing `:resource/attribute` sub-entity (whose retraction
  cascades to its overflow chunks, being components), and every existing
  value of every cardinality-many modeled attribute (so a shrunk/changed
  set doesn't just grow). Cardinality-one modeled attributes need no
  explicit retraction - asserting a new value for an existing entity/
  attribute pair already retracts the old one. Returns `[]` if no resource
  entity with `resource-id` currently exists (first-time POST)."
  [db resource-id]
  (if-let [eid (get (resource-id->eid db) resource-id)]
    (let [attr-eids        (map first (d/q '[:find ?a :in $ ?e :where [?e :resource/attribute ?a]] db eid))
          attr-retractions (mapv (fn [aeid] [:db/retractEntity aeid]) attr-eids)
          value-retractions (mapcat
                              (fn [ident]
                                (map (fn [v] [:db/retract eid ident v])
                                     (map first (d/q '[:find ?v :in $ ?e ?a :where [?e ?a ?v]] db eid ident))))
                              many-modeled-idents)]
      (into attr-retractions value-retractions))
    []))
