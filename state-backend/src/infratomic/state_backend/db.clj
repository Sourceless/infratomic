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
   {"id"                        {:ident :aws-security-group-rule/id :value-type :db.type/string}
    "from_port"                 {:ident :aws-security-group-rule/from-port :value-type :db.type/long}
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
    "vpc_security_group_ids" {:ident :aws-instance/vpc-security-group-id :value-type :db.type/string :cardinality :db.cardinality/many}}

   "aws_iam_role"
   {"id"   {:ident :aws-iam-role/id :value-type :db.type/string}
    "name" {:ident :aws-iam-role/name :value-type :db.type/string}
    "arn"  {:ident :aws-iam-role/arn :value-type :db.type/string}}

   "aws_iam_policy"
   {"arn" {:ident :aws-iam-policy/arn :value-type :db.type/string}}

   "aws_s3_bucket"
   {"arn" {:ident :aws-s3-bucket/arn :value-type :db.type/string}}

   "aws_iam_role_policy_attachment"
   {"role"       {:ident :aws-iam-role-policy-attachment/role :value-type :db.type/string}
    "policy_arn" {:ident :aws-iam-role-policy-attachment/policy-arn :value-type :db.type/string}}})

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
    {:db/ident       :resource/last-write-source
     :db/valueType   :db.type/keyword
     :db/cardinality :db.cardinality/one
     :db/doc         ":terraform for a write made via POST /state (resource->tx); :sync for a write made by Sync (resource-tx), whether creating a newly Discovered Resource or updating a resource (discovered or Terraform-managed) whose observed live value changed. Set on every resource-entity write, by both write paths. Read by the drift Rule (query.clj) to find managed resources whose most recent write source is :sync, and via d/history/d/as-of to locate the most recent :terraform-sourced write to compare against."}
    {:db/ident       :resource/sync-present?
     :db/valueType   :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc         "Whether Sync's most recent full pass, for a Terraform-managed resource of one of the four FK-bearing child types this drift mechanism covers (aws_security_group_rule, aws_route, aws_route_table_association, aws_iam_role_policy_attachment), found this resource still present in AWS. true when found; false when Sync ran and did not find it (removed-child drift); absent when no Sync run covering this type has happened since the resource was created/last matched. Never read by GET /state reconstruction (absent from resource-pull-pattern) - purely a query-time signal for the removed-child Rule (query.clj)."}
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
     :db/doc         "One chunk (<=4096 bytes) of the parent attribute's JSON-encoded value."}

    ;; Scratch-only schema for `infratomic.state-backend.iam`'s query-time
    ;; policy-statement fact derivation. These idents are transacted here
    ;; (alongside the fixed schema, at `ensure-db!` time) so `d/with` has
    ;; somewhere to assert derived statement facts, but no `:iam-statement/*`
    ;; datom is ever written by `d/transact` - every value is asserted only
    ;; into a scratch db built via `(d/with db {:tx-data ...})` inside
    ;; `iam-reachable?`, never committed, never visible outside that one
    ;; call. See docs/adr/0005-derive-iam-policy-facts-at-query-time-via-speculative-db.md.
    {:db/ident       :iam-statement/effect
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc         "\"Allow\" or \"Deny\", as parsed from an IAM policy statement's `Effect` key. Scratch-only, see this schema section's preamble."}
    {:db/ident       :iam-statement/action
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/many
     :db/doc         "The statement's `Action` values (normalized from either a bare string or an array), each an IAM action glob pattern (e.g. \"s3:GetObject\", \"s3:Get*\"). Scratch-only, see this schema section's preamble."}
    {:db/ident       :iam-statement/resource
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/many
     :db/doc         "The statement's `Resource` values (normalized from either a bare string or an array), each an ARN glob pattern. Absent for a trust-policy statement, whose target is implicitly the role it's attached to. Scratch-only, see this schema section's preamble."}
    {:db/ident       :iam-statement/principal
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/many
     :db/doc         "The statement's `Principal` values (flattened from a resource-based or trust policy's `Principal` map, e.g. `{\"AWS\": \"arn:...\"}`), each an ARN glob pattern (or a non-ARN identifier, e.g. a service principal, which simply never matches a resource's ARN). Absent for an identity-based statement, whose principal is implicitly the identity it's attached to. Scratch-only, see this schema section's preamble."}
    {:db/ident       :iam-statement/source
     :db/valueType   :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/doc         "The already-persisted resource entity this statement's owning policy is attached to: the role for an identity-based or trust statement (including one reached via a managed policy attachment), or the target resource (e.g. a bucket) for a resource-based statement. Scratch-only, see this schema section's preamble."}
    {:db/ident       :iam-statement/kind
     :db/valueType   :db.type/keyword
     :db/cardinality :db.cardinality/one
     :db/doc         "`:identity`, `:resource`, or `:trust` - which side of an access grant this statement evaluates, so the `grants` rule can distinguish them without re-parsing. Scratch-only, see this schema section's preamble."}]
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

(defn- untagged-write-source-eids
  "Entity ids of every resource entity with no `:resource/last-write-source`
  value yet - i.e. every resource that predates that attribute. Every one
  of these predates `:resource/last-write-source` entirely, so by
  definition it was written via `POST /state` (see issue #27's design.md
  Migration Plan)."
  [db]
  (map first (d/q '[:find ?e
                     :where
                     [?e :resource/id]
                     (not [?e :resource/last-write-source _])]
                   db)))

(defn backfill-last-write-source!
  "One-time, idempotent migration step: tag every resource entity that
  predates `:resource/last-write-source` as `:terraform`-sourced. Runs on
  every `ensure-db!` call (i.e. every process start), but after the first
  run post-deploy the query above finds nothing, making every subsequent
  call a no-op - mirrors `backfill-managed-flag!` exactly. Public (rather
  than `defn-`) so it's directly testable against an isolated test db."
  [conn]
  (let [eids (untagged-write-source-eids (d/db conn))]
    (when (seq eids)
      (d/transact conn {:tx-data (mapv (fn [eid] [:db/add eid :resource/last-write-source :terraform]) eids)}))))

(defn ensure-db!
  "Create the database if it doesn't already exist, connect, ensure the
  schema is transacted, and backfill `:resource/managed? true` /
  `:resource/last-write-source :terraform` onto any resource entity
  written before those attributes existed. Idempotent - safe to call on
  every startup."
  [client]
  (d/create-database client {:db-name db-name})
  (let [conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    (backfill-managed-flag! conn)
    (backfill-last-write-source! conn)
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

(defn managed-resource-ids-by-type
  "Map of AWS-identifying value -> entity id, for every Terraform-managed
  (`:resource/managed? true`) resource of `type` currently in the
  database - keyed by that type's modeled `\"id\"` attribute's value
  (e.g. `:aws-security-group-rule/id`) for an id-based type, or by the
  `[role policy-arn]` pair for `\"aws_iam_role_policy_attachment\"`, the
  one FK-bearing child type with no modeled id of its own (mirroring
  `sync.clj`'s `existing-match`/`discovered-resource-id` composite-key
  handling for that type). `nil` for a type with no modeled `\"id\"`
  attribute other than the composite-keyed one. Used by Sync's
  removed-child presence-marker step (`sync.clj`'s `sync!`) to diff a full
  pass's observed AWS ids against what's currently stored, for each of
  the four FK-bearing child types the removed-child drift mechanism
  covers."
  [db type]
  (if (= type "aws_iam_role_policy_attachment")
    (into {}
          (map (fn [[role arn e]] [[role arn] e]))
          (d/q '[:find ?role ?arn ?e
                 :where
                 [?e :resource/type "aws_iam_role_policy_attachment"]
                 [?e :resource/managed? true]
                 [?e :aws-iam-role-policy-attachment/role ?role]
                 [?e :aws-iam-role-policy-attachment/policy-arn ?arn]]
               db))
    (when-let [ident (get-in resource-schema [type "id" :ident])]
      (into {}
            (d/q '[:find ?v ?e
                   :in $ ?type ?ident
                   :where
                   [?e :resource/type ?type]
                   [?e :resource/managed? true]
                   [?e ?ident ?v]]
                 db type ident)))))

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

(defn stored-attributes
  "Reconstruct one resource's currently-stored attribute map (the same
  Terraform-shaped map `GET /state` reconstructs), given its entity id
  `eid` and Terraform `type` - pulls `resource-pull-pattern` against `db`
  and hands it to `reconstruct-attributes`. `db` may be a live db value or
  a historical one (e.g. from `d/as-of`), so this doubles as the
  comparison primitive for both Sync's diff-gated update-on-drift
  (`sync.clj`'s `resource-tx`) and the drift Rule's history-based
  comparison (`query.clj`)."
  [db eid type]
  (reconstruct-attributes type (d/pull db resource-pull-pattern eid)))

(defn- many-keys
  "Set of `type`'s Terraform attribute keys (`resource-schema` keys, not
  idents) that are modeled cardinality-many - e.g. `vpc_security_group_ids`
  for `aws_instance`. Used by `comparable-attributes` to know which values
  need order-normalizing before comparison."
  [type]
  (into #{} (keep (fn [[k v]] (when (= :db.cardinality/many (:cardinality v)) k)))
        (get resource-schema type)))

(defn comparable-attributes
  "`attrs` (a reconstructed or freshly Sync-observed Terraform-shaped
  attribute map for `type`) restricted to `type`'s modeled keys
  (`resource-schema`'s keys), with any key mapped to `nil` or an empty
  collection dropped, and every cardinality-many modeled attribute's value
  normalized to a canonical (sorted) order.

  Used to scope both Sync's diff-gated update-on-drift and the drift
  Rule's history-based comparison to attributes Sync can actually
  observe: every `sync.clj` `*->attrs` translation function only ever
  produces keys covered by `resource-schema` for its type, so a
  Terraform-managed resource's *other* attributes - e.g. `tags`,
  `description`, an ARN, anything only ever set via `POST /state` and
  stored generically (see `decompose-attributes`) - are never attributes
  Sync could observe drifting in the first place; comparing the full
  reconstructed attribute set (typed *and* generic) would spuriously
  flag every such resource as drifted (or, symmetrically, make the drift
  Rule see permanent phantom drift once Sync's own upsert-in-place
  retracts those generic sub-entities on a real drift update - see
  `resource-upsert-retractions`).

  Dropping a `nil`-valued key rather than comparing it against `nil`
  matters too: a `*->attrs` function sometimes maps a modeled key to
  `nil` when AWS didn't return it (e.g. `gateway_id` on a peering-only
  `aws_route`), but `decompose-attributes` never writes a datom for a
  nil value, so the *reconstructed* side of the comparison never has
  that key at all - without dropping it here, a present-but-nil key
  would never structurally equal an absent one, again a false drift
  positive independent of any real observed change. An empty-collection
  value is dropped for the same reason: `decompose-attributes` never
  writes a datom for an empty cardinality-many value either, so the
  reconstructed side never has that key, while a freshly Sync-observed
  empty collection (e.g. an instance with no security groups) would
  otherwise keep it.

  Normalizing cardinality-many values matters because Datomic stores each
  value of a cardinality-many modeled attribute as an independent datom
  with no preserved ordinal: `d/pull`'s return order for the reconstructed
  side (`stored-attributes`) is whatever the index scan yields, unrelated
  to the order the freshly-observed side is built in (e.g.
  `instance->attrs`'s `(mapv :GroupId (:SecurityGroups instance))`). Without
  sorting both sides into the same order first, plain `=`/`not=` would
  falsely report drift on every sync run for any resource with 2+ values in
  such an attribute, purely because of pull-vs-observed ordering - not any
  real change."
  [type attrs]
  (let [keys (set (keys (get resource-schema type)))
        many (many-keys type)]
    (into {}
          (comp
           (filter (fn [[k v]] (and (contains? keys k)
                                     (some? v)
                                     (not (and (coll? v) (empty? v))))))
           (map (fn [[k v]]
                  (if (and (contains? many k) (coll? v))
                    [k (vec (sort-by str v))]
                    [k v]))))
          attrs)))

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

(defn- new-many-values
  "Map of cardinality-many modeled ident -> the set of values `attributes`
  will assert for `type` (via `resource-attr-tx`, decomposed the same
  way). Used by `resource-upsert-retractions` to avoid retracting a value
  that's about to be re-asserted unchanged - Datomic rejects a
  transaction that both retracts and asserts the exact same [entity
  attribute value] datom."
  [type attributes]
  (let [{:keys [typed]} (decompose-attributes type attributes)]
    (reduce (fn [m [ident value]] (update m ident (fnil conj #{}) value))
            {}
            typed)))

(defn resource-upsert-retractions
  "Tx-data to retract before re-asserting a resource's decomposed
  attributes on `POST` (or Sync), so an upsert-in-place doesn't
  accumulate stale datoms: every existing `:resource/attribute`
  sub-entity (whose retraction cascades to its overflow chunks, being
  components), and every existing value of every cardinality-many
  modeled attribute that `attributes` (this upsert's new value, decomposed
  via `type`) won't itself re-assert (so a shrunk/changed set doesn't
  just grow, while a value that's unchanged between upserts is neither
  retracted nor re-asserted, avoiding a same-transaction retract+assert
  conflict on it). Cardinality-one modeled attributes need no explicit
  retraction - asserting a new value for an existing entity/attribute
  pair already retracts the old one. Returns `[]` if no resource entity
  with `resource-id` currently exists (first-time POST)."
  [db resource-id type attributes]
  (if-let [eid (get (resource-id->eid db) resource-id)]
    (let [attr-eids        (map first (d/q '[:find ?a :in $ ?e :where [?e :resource/attribute ?a]] db eid))
          attr-retractions (mapv (fn [aeid] [:db/retractEntity aeid]) attr-eids)
          new-values       (new-many-values type attributes)
          value-retractions (mapcat
                              (fn [ident]
                                (let [keep (get new-values ident #{})
                                      existing (map first (d/q '[:find ?v :in $ ?e ?a :where [?e ?a ?v]] db eid ident))]
                                  (map (fn [v] [:db/retract eid ident v]) (remove keep existing))))
                              many-modeled-idents)]
      (into attr-retractions value-retractions))
    []))
