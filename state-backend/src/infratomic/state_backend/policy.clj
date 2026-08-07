(ns infratomic.state-backend.policy
  "Policy Check: plan-decomposition glue, Address Stand-in resolution, the
  Rule registry, and the `POST /policy-check` handler.

  Given a parsed `terraform show -json` plan document, builds the same
  tx-shape `db.clj`'s `resource-attr-tx` already produces for posted state
  (see `handler.clj`), speculatively transacts it via `d/with` (never
  `d/transact` - the result is never persisted, see `evaluate`), and
  evaluates every registered Rule against the resulting db, returning
  structured Violations. A plan resource's identifying attributes are
  frequently `null` at plan time (the resource doesn't exist yet), which
  would otherwise leave an identity-based Rule join with nothing to match
  against; Address Stand-in resolution substitutes the resource's own (or a
  directly-referenced resource's) Terraform address instead - see
  docs/adr/0004-resolve-plan-time-references-to-address-stand-ins.md."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.query :as query]))

;; ---------------------------------------------------------------------------
;; Plan-decomposition glue
;; ---------------------------------------------------------------------------

(defn- plan-resources
  "The `planned_values.root_module.resources[]` entries of a parsed plan
  document - only the root module is read (see design.md's Non-Goals)."
  [parsed]
  (get-in parsed ["planned_values" "root_module" "resources"] []))

(defn- config-resources
  [parsed]
  (get-in parsed ["configuration" "root_module" "resources"] []))

(defn- resource-address
  [resource]
  (str (get resource "type") "." (get resource "name")))

(defn- known-addresses
  "Every resource address present in this plan - the universe of addresses
  an unresolved reference can possibly be resolved against."
  [parsed]
  (into #{} (map resource-address) (plan-resources parsed)))

(defn- expressions-by-address
  "Map of resource address -> that resource's `configuration.root_module
  .resources[].expressions` map, for cross-resource reference resolution."
  [parsed]
  (into {} (map (fn [r] [(resource-address r) (get r "expressions" {})])) (config-resources parsed)))

(defn- referenced-address
  "The single known resource address that `refs` (a `references` list from
  plan JSON's `expressions.<key>.references`) directly, singly points at,
  or `nil` if `refs` is absent/empty or names zero or more than one
  distinct known resource (a `var.*`/`local.*` reference, or a
  conditional/interpolated expression touching more than one resource -
  neither is resolved, per ADR-0004)."
  [known refs]
  (let [addresses (into #{}
                         (keep (fn [ref]
                                 (some (fn [addr]
                                         (when (or (= addr ref) (str/starts-with? ref (str addr ".")))
                                           addr))
                                       known)))
                         refs)]
    (when (= 1 (count addresses))
      (first addresses))))

(def ^:private identifying-attribute-keys
  "The subset of each type's modeled `db/resource-schema` keys that are
  identity attributes - AWS-assigned ids/foreign keys the
  `security-groups-with-port-22-open` rule's join actually matches on (see
  `db/resource-schema`'s docstring: `aws_security_group`'s `id` and
  `aws_security_group_rule`'s `security_group_id` are modeled specifically
  for that join). These are the only attributes eligible for Address
  Stand-in substitution (ADR-0004, design.md's \"For each modeled
  *identifying* attribute\"). Every other modeled attribute (e.g.
  `from_port`/`to_port`/`protocol`/`cidr_blocks`) is an ordinary value -
  frequently `null` at plan time for reasons unrelated to resource
  identity - and must decompose normally, `nil` and all, via
  `resource-attr-tx`/`decompose-attributes`; substituting an address
  string into one of those would violate its Datomic type (a
  `:db.type/long` or a `:db.cardinality/many :db.type/string` iterated
  character-by-character) and crash the endpoint."
  {"aws_security_group"      #{"id"}
   "aws_security_group_rule" #{"security_group_id"}})

(defn- identifying-keys
  "The identifying attribute keys (`identifying-attribute-keys`) for
  `type` - the attributes eligible for Address Stand-in resolution when
  unknown."
  [type]
  (get identifying-attribute-keys type #{}))

(defn- resolve-address-stand-ins
  "Address Stand-in resolution (ADR-0004): for each of `type`'s modeled
  attribute keys whose value in `values` is missing/`null`, substitute a
  directly, singly referenced other known resource's address (per
  `expressions`) if one exists, else this resource's own `own-address`."
  [type own-address values known expressions]
  (reduce
   (fn [vs key]
     (if (some? (get vs key))
       vs
       (let [refs     (get-in expressions [key "references"])
             stand-in (or (referenced-address known refs) own-address)]
         (assoc vs key stand-in))))
   values
   (identifying-keys type)))

(defn- resource-tx-map
  "One resource's speculative upsert tx-map: `:resource/id` (`type` + `.` +
  `name`), `:resource/type`, `:resource/name`, plus `resolved-values`
  decomposed via `db/resource-attr-tx` exactly as posted state's attributes
  are (see `handler.clj`'s `resource->tx`) - no `:resource/instance-meta`
  and no `:resource/state-version`, since this speculative db is only ever
  evaluated by Rules, never reconstructed back into a state document."
  [type own-address name resolved-values]
  (merge {:resource/id   own-address
          :resource/type type
          :resource/name name}
         (db/resource-attr-tx type resolved-values)))

(defn plan->tx-data
  "The full speculative tx-data for a parsed plan document: one upsert
  tx-map per `planned_values.root_module.resources[]` entry, each built
  from its own Address-Stand-in-resolved `values`."
  [parsed]
  (let [resources   (plan-resources parsed)
        known       (known-addresses parsed)
        expr-lookup (expressions-by-address parsed)]
    (mapv (fn [resource]
            (let [type        (get resource "type")
                  name         (get resource "name")
                  own-address  (resource-address resource)
                  values       (get resource "values" {})
                  expressions  (get expr-lookup own-address {})
                  resolved     (resolve-address-stand-ins type own-address values known expressions)]
              (resource-tx-map type own-address name resolved)))
          resources)))

;; ---------------------------------------------------------------------------
;; Rule registry and evaluation
;; ---------------------------------------------------------------------------

(def ^:private rules
  "The static vector of registered Rules: each a map of `:rule/id` (a
  keyword identifying the Rule in a Violation) and `:rule/query`, a `(fn
  [db] -> seq-of-maps)` matching `query.clj`'s existing function shape.
  References `query/security-groups-with-port-22-open` directly - not a
  copy - so this can never drift out of sync with the live-state version."
  [{:rule/id    :security-groups-with-port-22-open
    :rule/query query/security-groups-with-port-22-open}])

(defn evaluate
  "Given `conn` and a parsed plan document, builds the plan's speculative
  tx-data and transacts it into a *speculative* db value only - `(d/with
  (d/with-db conn) {:tx-data ...})`, never `d/transact` - runs every
  registered Rule against the resulting `:db-after`, and returns a seq of
  Violation maps (`{:rule <keyword> :resource/id ... :resource/type
  ...}`), one per resource returned by a Rule. `d/with` is a pure,
  non-mutating operation on a db value: `conn`'s live db, and any
  concurrent real `/state` traffic against it, are never affected."
  [conn parsed]
  (let [tx-data  (plan->tx-data parsed)
        db-after (:db-after (d/with (d/with-db conn) {:tx-data tx-data}))]
    (mapcat (fn [{rule-id :rule/id rule-query :rule/query}]
              (map (fn [{:resource/keys [id type]}]
                     {:rule rule-id :resource/id id :resource/type type})
                   (rule-query db-after)))
            rules)))

;; ---------------------------------------------------------------------------
;; HTTP handler
;; ---------------------------------------------------------------------------

(defn- parse-json
  "Parse `s` as JSON, returning ::invalid instead of throwing on failure -
  mirrors `handler.clj`'s own `/state` JSON-parsing error handling."
  [s]
  (try
    (json/parse-string s)
    (catch Exception _
      ::invalid)))

(defn- violation->json
  [{:keys [rule] :resource/keys [id type]}]
  {"rule"     (name rule)
   "resource" {"id" id "type" type}})

(defn policy-check
  "Handle a `POST /policy-check` request body: parse `raw-body` as a
  Terraform plan JSON document (the output of `terraform show -json` on a
  plan file), evaluate it (`evaluate`), and respond `200` with
  `{\"violations\": [...]}` (`[]` when clean), or `400` on invalid JSON."
  [conn raw-body]
  (let [parsed (parse-json raw-body)]
    (if (= parsed ::invalid)
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "invalid JSON"})}
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:violations (mapv violation->json (evaluate conn parsed))})})))
