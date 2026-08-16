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
            [clojure.edn :as edn]
            [clojure.string :as str]
            [infratomic.state-backend.datomic :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.validator :as validator]))

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

(def ^:private resource-summary-pattern
  [:resource/id :resource/type])

(defonce ^{:doc "The atom-backed Rule registry (design.md's \"Unified
  stored Rule format\"), keyed by `:rule/id`. `defonce` (not `def`) so
  reloading this namespace at a REPL doesn't clobber a Rule registered at
  runtime via `POST /rules`. Seeded below, at namespace load, with the
  existing `security-groups-with-port-22-open` Rule."}
  rule-registry
  (atom {}))

(def security-groups-with-port-22-open-rule
  "The stored-data-shape equivalent of `query.clj`'s
  `security-groups-with-port-22-open` function: the same join (a security
  group rule's `security_group_id` back to its owning security group's
  `id`) and the same predicates (`<=`/`>=`, both allowlisted by the shared
  validator), expressed as a stored Rule map instead of a Clojure function
  (design.md's \"Unified stored Rule format\" - no `:rule/rule-defs`, this
  Rule needs no recursive rule set). `:rule/find` binds exactly `?sg`, the
  security group entity the Rule flags - `evaluate` (not this Rule's own
  query) pulls it into a `:resource/id`/`:resource/type` pair."
  {:rule/id    :security-groups-with-port-22-open
   :rule/find  '[?sg]
   :rule/in    '[$]
   :rule/where '[[?sg :aws-security-group/id ?sg-id]
                 [?rule :aws-security-group-rule/security-group-id ?sg-id]
                 [?rule :aws-security-group-rule/from-port ?from]
                 [?rule :aws-security-group-rule/to-port ?to]
                 [?rule :aws-security-group-rule/cidr-block "0.0.0.0/0"]
                 [(<= ?from 22)]
                 [(>= ?to 22)]]})

(swap! rule-registry assoc
       (:rule/id security-groups-with-port-22-open-rule)
       security-groups-with-port-22-open-rule)

(defn register-rule!
  "Validate `rule` (a stored Rule map: `:rule/id`, `:rule/find`,
  `:rule/in`, `:rule/where`, optional `:rule/rule-defs`) via the shared
  validator (`validator/validate-query`, which reads `:rule/where` off the
  map directly) and, on success, `swap!` it into `rule-registry` - upsert
  by `:rule/id`, replacing any Rule already registered under that id.
  Returns the validator's `{:valid? true}`/`{:valid? false :reason ...}`
  result either way; the Rule is registered only when `:valid?` is
  `true`."
  [rule]
  (let [result (validator/validate-query rule (:rule/rule-defs rule))]
    (when (:valid? result)
      (swap! rule-registry assoc (:rule/id rule) rule))
    result))

(defn- rule->query-map
  [{:rule/keys [find in where]}]
  (cond-> {:find find :where where}
    in (assoc :in in)))

(defn- run-rule
  "Run one stored Rule's query against `db`, returning the seq of values
  bound to its single `:rule/find` variable - `d/q`'s raw `[[v] [v] ...]`
  result set flattened to `[v v ...]`. Passes `:rule/rule-defs` as the `%`
  argument when present, matching `:rule/in`'s `[$ %]` shape."
  [db {:rule/keys [rule-defs] :as rule}]
  (let [query (rule->query-map rule)
        args  (cond-> [db] (seq rule-defs) (conj rule-defs))]
    (map first (apply d/q query args))))

(defn evaluate
  "Given `conn` and a parsed plan document, builds the plan's speculative
  tx-data and transacts it into a *speculative* db value only - `(d/with
  (d/with-db conn) {:tx-data ...})`, never `d/transact` - runs every
  registered Rule (read fresh from `rule-registry` on every call - no
  closed-over snapshot, so a Rule registered at runtime is visible on the
  very next call) against the resulting `:db-after`, and returns a seq of
  Violation maps (`{:rule <keyword> :resource/id ... :resource/type
  ...}`), one per value a Rule's query binds - each bound entity is pulled
  into its `:resource/id`/`:resource/type` pair here, in this shared
  evaluation code, not inside the Rule's own query (design.md). `d/with`
  is a pure, non-mutating operation on a db value: `conn`'s live db, and
  any concurrent real `/state` traffic against it, are never affected."
  [conn parsed]
  (let [tx-data  (plan->tx-data parsed)
        db-after (:db-after (d/with (d/with-db conn) {:tx-data tx-data}))]
    (mapcat (fn [[rule-id rule]]
              (map (fn [eid]
                     (let [{:resource/keys [id type]} (d/pull db-after resource-summary-pattern eid)]
                       {:rule rule-id :resource/id id :resource/type type}))
                   (run-rule db-after rule)))
            @rule-registry)))

;; ---------------------------------------------------------------------------
;; HTTP handlers
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

(defn- parse-edn
  "Parse `s` as EDN, returning ::invalid instead of throwing on failure -
  mirrors `parse-json`'s error handling, for the EDN-bodied `/rules`
  endpoint."
  [s]
  (try
    (edn/read-string s)
    (catch Exception _
      ::invalid)))

(defn register-rule-endpoint
  "Handle a `POST /rules` request body: parse `raw-body` as EDN (a stored
  Rule map), validate and register it via `register-rule!`, and respond
  `200` on success or `400` with the reason on invalid EDN or a validator
  rejection."
  [raw-body]
  (let [parsed (parse-edn raw-body)]
    (if (or (= parsed ::invalid) (not (map? parsed)))
      {:status  400
       :headers {"Content-Type" "application/edn"}
       :body    (pr-str {:reason "invalid EDN: expected a Rule map"})}
      (let [result (register-rule! parsed)]
        (if (:valid? result)
          {:status  200
           :headers {"Content-Type" "application/edn"}
           :body    (pr-str {:registered (:rule/id parsed)})}
          {:status  400
           :headers {"Content-Type" "application/edn"}
           :body    (pr-str result)})))))
