(ns infratomic.state-backend.terraform
  "Unattended Terraform execution (issue #33): `apply!`/`import!`/`destroy!`
  each shell out to the real `terraform` binary, non-interactively, against
  a caller-supplied working directory - the primitive the reconciliation
  engine (#34) will call to actually remediate drift, once it exists. This
  namespace only executes; deciding *when* to call `apply!`/`import!`/
  `destroy!` is #34's job entirely (see proposal.md's Non-Goals).

  Every call is wrapped, uniformly, by `with-lock-and-invocation`:
  1. Acquire a per-resource-address lock (`acquire-lock!`, blocking/
     polling `try-acquire-lock!` until it succeeds - see that fn's
     docstring for why acquisition is CAS-safe against real concurrency).
  2. Run the actual `terraform` subprocess (`run-terraform!`).
  3. Persist an Invocation entity unconditionally - success or failure,
     never conditional on the caller doing anything with the result.
  4. Release the lock (`release-lock!`), in a `finally` so it happens even
     if step 2/3 throws.
  5. Return `{:success true/false :out ... :err ...}`.

  `apply!` targets a caller-supplied `address` purely for locking/audit
  purposes - unlike `import!`/`destroy!`, the underlying `terraform apply`
  it runs is untargeted (design.md: `apply -auto-approve`, no `-target`),
  since a plain `apply` reconciles the whole working directory's plan, not
  one resource. `address` still identifies which resource the caller is
  invoking this apply on behalf of, so it locks/logs consistently with
  `import!`/`destroy!` (see the terraform-execution spec's \"Every
  invocation is recorded\" requirement, generic across all three
  commands)."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [infratomic.state-backend.datomic :as d]))

;; ---------------------------------------------------------------------------
;; Locking (design.md's "Datomic-backed, per-resource-address, CAS-safe
;; acquire, TTL-based staleness")
;; ---------------------------------------------------------------------------

(def lock-ttl-ms
  "A lock held longer than this is treated as stale (its holder presumed
  crashed) and becomes reacquirable without manual intervention. 10
  minutes - comfortably longer than any single-resource-targeted apply/
  import/destroy should take (design.md's Risks/Trade-offs)."
  (* 10 60 1000))

(def ^:private lock-poll-interval-ms
  "How long `acquire-lock!` sleeps between `try-acquire-lock!` retries
  while blocked behind another invocation's live lock on the same
  address."
  50)

(defn- ensure-lock-entity!
  "Idempotently ensure a Lock entity exists for `address`, touching
  *only* `:lock/resource-address` - never `:lock/acquired-at`. Safe under
  real concurrency: two concurrent callers both asserting the same
  `:db.unique/identity` value upsert to the same entity (Datomic
  serializes the two transactions; the second is a no-op re-assertion of
  an unchanged value), so this step alone never grants or denies the
  lock itself - it only guarantees the entity exists so the CAS step
  below has a real (non-tempid) entity to CAS against.

  This two-transaction shape (ensure-entity, then a separate `:db/cas`
  transaction) is deliberate, not incidental: combining entity creation
  and the CAS guard into a *single* transaction via a tempid (`[:db/add
  \"t\" :lock/resource-address address] [:db/cas \"t\" :lock/acquired-at
  nil now]`) looks appealing but is unsound - Datomic's upsert
  resolution of the tempid and the `:db/cas` old-value comparison do not
  reliably order against each other within one transaction, so a `:db/cas`
  against a same-transaction tempid can silently succeed even when a
  live lock already exists (confirmed empirically against dev-local
  while implementing this). CAS-ing a lookup ref to an *already-existing*
  entity, in its own transaction, doesn't have this problem - the
  lookup ref resolves before the CAS runs, and the CAS is exactly Datomic's
  intended `compare-and-swap` use. See
  docs/adr/0011-datomic-cas-plus-ttl-lock-for-unattended-terraform-invocations.md."
  [conn address]
  (d/transact conn {:tx-data [{:lock/resource-address address}]}))

(defn- lock-acquired-at
  [db address]
  (ffirst (d/q '[:find ?t :in $ ?a :where [?e :lock/resource-address ?a] [?e :lock/acquired-at ?t]]
                db address)))

(defn- lock-eid
  [db address]
  (ffirst (d/q '[:find ?e :in $ ?a :where [?e :lock/resource-address ?a]] db address)))

(defn- stale?
  [^java.util.Date acquired-at]
  (> (- (System/currentTimeMillis) (.getTime acquired-at)) lock-ttl-ms))

(defn try-acquire-lock!
  "Attempt to acquire the lock for `address` exactly once. Returns `true`
  if acquired (a fresh lock, or a stale one successfully stolen from its
  presumed-crashed holder), `false` if a live (non-stale) lock is
  currently held by someone else.

  `ensure-lock-entity!` first guarantees the Lock entity exists (see its
  docstring), then this reads the entity's current `:lock/acquired-at`
  (`nil` if free) and, only when free-or-stale, attempts a `:db/cas`
  transaction asserting that *exact* just-read value as the CAS old-value
  and a fresh timestamp as the new value. Two concurrent callers racing
  from the same free-or-stale read will both attempt this CAS; Datomic
  serializes the two transactions, the first to commit changes the live
  value out from under the second, so the second's CAS old-value no
  longer matches and it fails cleanly - exactly one caller acquires,
  with no window in which both can observe \"free\" and both proceed."
  [conn address]
  (ensure-lock-entity! conn address)
  (let [current (lock-acquired-at (d/db conn) address)]
    (if (and (some? current) (not (stale? current)))
      false
      (try
        (d/transact conn {:tx-data [[:db/cas [:lock/resource-address address]
                                      :lock/acquired-at current (java.util.Date.)]]})
        true
        (catch Exception _
          false)))))

(defn acquire-lock!
  "Block until the lock for `address` is acquired: `try-acquire-lock!`,
  retrying every `lock-poll-interval-ms` while another invocation holds a
  live lock on the same address - the terraform-execution spec's \"the
  second invocation does not begin running terraform against that
  address until the first has released its lock\" requirement. A holder
  that crashed rather than released eventually stops blocking new
  acquisitions too, once its lock passes `lock-ttl-ms` and
  `try-acquire-lock!` starts treating it as stale."
  [conn address]
  (loop []
    (if (try-acquire-lock! conn address)
      true
      (do (Thread/sleep ^long lock-poll-interval-ms)
          (recur)))))

(defn release-lock!
  "Release the lock for `address` by retracting its Lock entity entirely
  (design.md: \"Release is a retraction of the lock entity\") - a no-op
  if no Lock entity currently exists for `address`. A subsequent
  `try-acquire-lock!`/`acquire-lock!` recreates the entity fresh via
  `ensure-lock-entity!`."
  [conn address]
  (when-let [eid (lock-eid (d/db conn) address)]
    (d/transact conn {:tx-data [[:db/retractEntity eid]]})))

;; ---------------------------------------------------------------------------
;; Invocation logging
;; ---------------------------------------------------------------------------

(defn- record-invocation!
  "Persist an Invocation entity unconditionally - `command` (`:apply`/
  `:import`/`:destroy`), `address`, and `success?` - regardless of
  whether the caller does anything with the returned result. Deliberately
  excludes captured stdout/stderr (design.md: Datomic dev-local's
  4096-byte-per-string limit makes storing arbitrary-length captured
  output risky; the immediate caller still receives it in the returned
  map, just not durably)."
  [conn command address success?]
  (d/transact conn {:tx-data [{:invocation/command          command
                                :invocation/resource-address address
                                :invocation/success?         success?
                                :invocation/at               (java.util.Date.)}]}))

;; ---------------------------------------------------------------------------
;; Process invocation
;; ---------------------------------------------------------------------------

(defn- run-terraform!
  "Shell out to `terraform` with `args` against `working-dir`
  (`clojure.java.shell/sh`, following `sync_integration_test.clj`'s
  existing non-interactive pattern rather than the CLI's `inheritIO`
  one), normalizing `sh`'s `{:exit :out :err}` into `{:success
  (zero? exit) :out ... :err ...}`. Never throws: a failure to even start
  the `terraform` process (e.g. binary not found) is caught and reported
  as `{:success false :out \"\" :err <message>}` too, so a caller (and
  `with-lock-and-invocation`, which always wants a result map to record
  and return) never has to handle an unhandled exception from this step -
  matching the terraform-execution spec's \"reports failure rather than
  raising an unhandled exception\" scenario."
  [working-dir args]
  (try
    (let [{:keys [exit out err]} (apply shell/sh "terraform" (concat args [:dir working-dir]))]
      {:success (zero? exit) :out out :err err})
    (catch Exception e
      {:success false :out "" :err (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Lock + Invocation wrapper (design.md: "acquire lock -> run terraform ->
;; persist Invocation entity -> release lock -> return result")
;; ---------------------------------------------------------------------------

(defn- with-lock-and-invocation
  "The shared wrapper every one of `apply!`/`import!`/`destroy!` goes
  through: acquire the lock for `address` (blocking until it's free),
  run `f` (a no-arg thunk performing the actual `terraform` invocation via
  `run-terraform!`), persist an Invocation entity reflecting `f`'s result
  unconditionally, release the lock (in a `finally`, so it releases even
  if `f` or invocation-recording itself throws), and return `f`'s result
  unchanged."
  [conn command address f]
  (acquire-lock! conn address)
  (try
    (let [result (f)]
      (record-invocation! conn command address (:success result))
      result)
    (finally
      (release-lock! conn address))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn apply!
  "Run `terraform apply -auto-approve` non-interactively against
  `working-dir`, locking/logging under `address` (see this namespace's
  docstring for why `apply!` still takes a resource address despite
  running an untargeted apply). Returns `{:success true/false :out ...
  :err ...}`."
  [conn working-dir address]
  (with-lock-and-invocation conn :apply address
    #(run-terraform! working-dir ["apply" "-auto-approve"])))

(defn import!
  "Run `terraform import <address> <aws-id>` non-interactively against
  `working-dir`, for a resource address that already has a corresponding
  resource block declared in that directory's configuration - this is a
  pure executor, it never writes or modifies Terraform configuration to
  synthesize a missing block (that's a separate concern, #34's own
  \"synthesized import\" AC - see proposal.md's Non-Goals). Returns
  `{:success true/false :out ... :err ...}`."
  [conn working-dir address aws-id]
  (with-lock-and-invocation conn :import address
    #(run-terraform! working-dir ["import" address aws-id])))

(defn destroy!
  "Run `terraform destroy -auto-approve -target=<address>` non-interactively
  against `working-dir`. Returns `{:success true/false :out ... :err
  ...}`."
  [conn working-dir address]
  (with-lock-and-invocation conn :destroy address
    #(run-terraform! working-dir ["destroy" "-auto-approve" (str "-target=" address)])))

;; ---------------------------------------------------------------------------
;; HTTP request validation (issue #33 code review, finding #1/#2): the HTTP
;; endpoints below are unauthenticated, so `working_directory` cannot be
;; trusted to name an arbitrary path the OS user can read/write - unlike
;; `apply!`/`import!`/`destroy!` themselves (still generically parameterized
;; by working directory, per the terraform-execution spec's "caller-supplied
;; working directory (not a fixed, hardcoded path)" requirement - this
;; restriction is an HTTP-layer allowlist, not a change to that primitive),
;; a request naming a path outside the configured base directory - or using
;; `..` to escape it - is rejected before `apply!`/`import!`/`destroy!` ever
;; runs. `resource_address`/`aws_id` are also rejected if they could be
;; parsed by `terraform` as a flag rather than a positional argument.
;; ---------------------------------------------------------------------------

(defn terraform-base-dir
  "The single directory `working_directory` must resolve inside of (itself
  or any subdirectory), read from `INFRATOMIC_TERRAFORM_BASE_DIR` (following
  `db.clj`'s `INFRATOMIC_GATEWAY_HOST`/`INFRATOMIC_GATEWAY_PORT` naming
  convention) - defaulting, when unset, to `../terraform` relative to the
  process's working directory, matching where the sample Terraform app
  already lives (`terraform_integration_test.clj`'s own `terraform-dir`) and
  where `state-backend/` is documented (`db.clj`'s `storage-dir`) to be
  started from."
  []
  (or (System/getenv "INFRATOMIC_TERRAFORM_BASE_DIR")
      (str (System/getProperty "user.dir") "/../terraform")))

(defn- within-base-dir?
  "Whether canonicalizing `working-dir` (resolving any `..`/symlinks)
  lands it at, or inside, `base-dir`'s own canonical form - the actual
  check, rather than a raw string prefix comparison, so `../terraform-evil`
  (shares the string prefix `../terraform` but is a sibling, not a
  descendant) and `working_directory` values containing `..` segments that
  walk back out of `base-dir` are both correctly rejected. Returns `false`
  (rather than throwing) if either path can't be resolved at all."
  [base-dir working-dir]
  (try
    (let [base-canonical (.getCanonicalFile (java.io.File. ^String base-dir))
          dir-canonical  (.getCanonicalFile (java.io.File. ^String working-dir))
          base-path      (.getCanonicalPath base-canonical)
          dir-path       (.getCanonicalPath dir-canonical)]
      (or (= base-path dir-path)
          (.startsWith dir-path (str base-path java.io.File/separator))))
    (catch Exception _
      false)))

(defn- safe-working-dir?
  [working-dir]
  (within-base-dir? (terraform-base-dir) working-dir))

(defn- safe-argv-token?
  "Whether `s` is safe to pass as a positional `terraform` argument
  (`resource_address`, or `import!`'s `aws_id`) - specifically, that it
  can't be parsed by `terraform` itself as a flag instead. `sh` execs
  `terraform` directly with no shell interpolation, so this is not a
  shell-injection concern - only an argv-parsing one."
  [s]
  (not (str/starts-with? s "-")))

(defn- invalid-request-response
  [message]
  {:status  400
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:error message})})

;; ---------------------------------------------------------------------------
;; HTTP handlers (mirrors policy.clj/sync.clj's convention of embedding the
;; endpoint's own request-parsing alongside the capability it wraps)
;; ---------------------------------------------------------------------------

(defn- parse-json
  "Parse `s` as JSON, returning `::invalid` instead of throwing on failure -
  mirrors `policy.clj`/`sync.clj`'s own JSON-parsing error handling."
  [s]
  (try
    (json/parse-string s)
    (catch Exception _
      ::invalid)))

(defn- result->response
  "`result` (`apply!`/`import!`/`destroy!`'s `{:success ... :out ... :err
  ...}`) as a `200` JSON response - `200` regardless of `:success`'s
  value, since a failed `terraform` invocation is a confirmed, well-formed
  result, not a malformed request (mirrors `policy-check`'s `{\"violations\"
  [...]}` always-`200`-on-a-parseable-request convention)."
  [result]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:success (:success result)
                                    :out     (:out result)
                                    :err     (:err result)})})

(defn- missing-fields-response
  [missing]
  {:status  400
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:error (str "missing required field(s): " (pr-str missing))})})

(defn- invalid-json-response
  []
  {:status  400
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:error "invalid JSON"})})

(defn- require-fields
  "`nil` if every one of `fields` is present (a non-blank string) in parsed
  request map `m`, else the seq of missing field names - used by each
  endpoint below to validate its own required fields before calling
  `apply!`/`import!`/`destroy!`."
  [m fields]
  (seq (remove #(some-> (get m %) str seq) fields)))

(defn- request-validation-error
  "`nil` if `parsed`'s `working_directory` resolves inside
  `terraform-base-dir` and every one of `argv-fields` (`resource_address`,
  and, for `import`, `aws_id`) is safe to pass as a positional `terraform`
  argument, else a human-readable error message describing the first
  problem found - used by each endpoint below, after `require-fields`
  confirms the fields are all present, to reject a request before it ever
  reaches `apply!`/`import!`/`destroy!` (see this section's own docstring
  for why: both checks defend the unauthenticated HTTP surface, not the
  `apply!`/`import!`/`destroy!` primitives themselves)."
  [parsed argv-fields]
  (cond
    (not (safe-working-dir? (get parsed "working_directory")))
    (str "working_directory must resolve inside the configured Terraform base directory (" (terraform-base-dir) ")")

    (not-every? #(safe-argv-token? (get parsed %)) argv-fields)
    (str "field(s) must not start with '-': " (pr-str argv-fields))

    :else nil))

(defn apply-endpoint
  "Handle a `POST /apply` request body: `{\"working_directory\": \"...\"
  \"resource_address\": \"...\"}`. Responds `400` on invalid JSON, a
  missing required field, a `working_directory` outside the configured
  base directory, or a `resource_address` unsafe to pass to `terraform`;
  else runs `apply!` and responds `200` with its result."
  [conn raw-body]
  (let [parsed  (parse-json raw-body)
        missing (when-not (= parsed ::invalid) (require-fields parsed ["working_directory" "resource_address"]))
        invalid (when-not (or (= parsed ::invalid) missing) (request-validation-error parsed ["resource_address"]))]
    (cond
      (= parsed ::invalid)  (invalid-json-response)
      missing               (missing-fields-response missing)
      invalid               (invalid-request-response invalid)
      :else                 (result->response (apply! conn (get parsed "working_directory") (get parsed "resource_address"))))))

(defn import-endpoint
  "Handle a `POST /import` request body: `{\"working_directory\": \"...\"
  \"resource_address\": \"...\" \"aws_id\": \"...\"}`. Responds `400` on
  invalid JSON, a missing required field, a `working_directory` outside
  the configured base directory, or a `resource_address`/`aws_id` unsafe
  to pass to `terraform`; else runs `import!` and responds `200` with its
  result."
  [conn raw-body]
  (let [parsed  (parse-json raw-body)
        missing (when-not (= parsed ::invalid) (require-fields parsed ["working_directory" "resource_address" "aws_id"]))
        invalid (when-not (or (= parsed ::invalid) missing) (request-validation-error parsed ["resource_address" "aws_id"]))]
    (cond
      (= parsed ::invalid)  (invalid-json-response)
      missing               (missing-fields-response missing)
      invalid               (invalid-request-response invalid)
      :else                 (result->response (import! conn (get parsed "working_directory") (get parsed "resource_address") (get parsed "aws_id"))))))

(defn destroy-endpoint
  "Handle a `POST /destroy` request body: `{\"working_directory\": \"...\"
  \"resource_address\": \"...\"}`. Responds `400` on invalid JSON, a
  missing required field, a `working_directory` outside the configured
  base directory, or a `resource_address` unsafe to pass to `terraform`;
  else runs `destroy!` and responds `200` with its result."
  [conn raw-body]
  (let [parsed  (parse-json raw-body)
        missing (when-not (= parsed ::invalid) (require-fields parsed ["working_directory" "resource_address"]))
        invalid (when-not (or (= parsed ::invalid) missing) (request-validation-error parsed ["resource_address"]))]
    (cond
      (= parsed ::invalid)  (invalid-json-response)
      missing               (missing-fields-response missing)
      invalid               (invalid-request-response invalid)
      :else                 (result->response (destroy! conn (get parsed "working_directory") (get parsed "resource_address"))))))
