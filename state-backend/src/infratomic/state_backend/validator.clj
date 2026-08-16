(ns infratomic.state-backend.validator
  "The shared query/rule validator (design.md's \"Shared query/rule
  validator\" decision): the one enforcement point deciding what Datalog is
  safe to run on untrusted input, used by both the ad-hoc query endpoint
  (`POST /query`) and Policy Check Rule registration (`POST /rules`).

  Walks every `:where` clause of a query map - recursing into `not`/
  `not-join`/`or`/`or-join` sub-clauses, and into every rule body in an
  accompanying `%` rule-set (`rule-defs`) - and rejects the query if any
  clause is a function-invocation clause (Datalog's `[(sym args...)
  binding?]` shape: a vector whose first element is a list) whose `sym` is
  not in `allowed-predicates`. A bare-list clause like `(reaches ?src
  ?dst)` is a *rule invocation*, not a function-invocation clause, and is
  never rejected by this check - only vector-wrapped-list predicate clauses
  are. A rule invocation naming a rule absent from the supplied `%` set
  simply fails at `d/q` time with Datomic's own error - no extra validator
  logic needed for that case."
  )

(def allowed-predicates
  "The fixed, explicit allowlist of built-in Datalog predicate symbols a
  function-invocation clause may call. Anything else - including any
  application function - is rejected."
  '#{< > <= >= = not= ==})

(defn- function-invocation-clause?
  "Whether `clause` is Datalog's `[(sym args...) binding?]` shape: a vector
  whose first element is a list (the function/predicate call itself)."
  [clause]
  (and (vector? clause) (seq? (first clause))))

(defn- clause-symbol
  [clause]
  (first (first clause)))

(declare validate-where)

(defn- validate-clause
  [clause]
  (cond
    (function-invocation-clause? clause)
    (let [sym (clause-symbol clause)]
      (when-not (contains? allowed-predicates sym)
        {:valid? false :reason (str "disallowed function-invocation clause: " (pr-str sym))}))

    ;; `not`/`or`: `(not clause...)` / `(or clause...)` - a plain list whose
    ;; first element is the operator symbol, the rest sub-clauses to
    ;; recurse into.
    (and (seq? clause) (contains? #{'not 'or} (first clause)))
    (validate-where (rest clause))

    ;; `not-join`/`or-join`: `(not-join [vars...] clause...)` /
    ;; `(or-join [vars...] clause...)` - same, but skip the leading
    ;; variables vector.
    (and (seq? clause) (contains? #{'not-join 'or-join} (first clause)))
    (validate-where (rest (rest clause)))

    ;; A plain data pattern (`[?e :attr ?v]`) or a rule invocation
    ;; (`(reaches ?src ?dst)`, a bare list whose first element isn't
    ;; `not`/`or`/`not-join`/`or-join`) - never rejected by this check.
    :else
    nil))

(defn- validate-where
  [where-clauses]
  (some validate-clause where-clauses))

(defn- rule-def-bodies
  "Every clause-seq in a rule-defs vector: each rule-defs entry is `[(rule-
  head args...) clause...]` - the head is skipped, every remaining clause
  validated exactly like a `:where` clause."
  [rule-defs]
  (mapcat rest rule-defs))

(defn validate-query
  "Validate a `{:find ... :in ... :where ...}` query map (or a stored Rule
  map using `:rule/where` in place of `:where`) plus an optional `rule-defs`
  (the `%` rule-set argument, or `nil`). Returns `{:valid? true}` or
  `{:valid? false :reason \"...\"}`."
  ([query] (validate-query query nil))
  ([query rule-defs]
   (let [where  (or (:where query) (:rule/where query))
         result (or (validate-where where)
                    (when (seq rule-defs)
                      (validate-where (rule-def-bodies rule-defs))))]
     (or result {:valid? true}))))
