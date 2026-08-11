(ns infratomic.cli.main
  "Entry point for the CLI (`clojure -M -m infratomic.cli.main -- <terraform
  args...>`): a drop-in replacement for the `terraform` binary that passes
  every subcommand straight through unchanged except `apply`, which it
  intercepts to run a Policy Check - via the State Backend's `POST
  /policy-check` endpoint - before ever invoking real `terraform apply`,
  `sync`, a State-Backend-only subcommand (not a real Terraform one) that
  triggers Sync - via `POST /sync` - and prints a summary of what it
  discovered/ingested/found drifted, and `drift-check`, another State-
  Backend-only subcommand that calls `GET /drift` and reports any managed
  resources whose live values have drifted out-of-band since Terraform
  last asserted them, exiting non-zero when drift is found (so it's usable
  as an automation gate). See openspec/changes/issue-16-block-bad-infra-
  states-before-terraform-apply-runs/design.md's \"CLI structure\"
  decision, openspec/changes/issue-26-sync-unmanaged-localstack-
  resources-into-datomic/design.md's \"CLI sync subcommand\" decision, and
  openspec/changes/issue-27-flag-out-of-band-drift-on-terraform-managed-
  resources/design.md's \"CLI drift-check subcommand\" decision.

  Passthrough subcommands shell out to the real `terraform` binary with
  `ProcessBuilder`, inheriting stdio, so interactive prompts, colored
  output, etc. all behave exactly as running `terraform` directly would.
  The Policy Check endpoint's base URL is a config point (env var or CLI
  flag - see `-main`), not hardcoded, so this isn't coupled to the sample
  app's `localhost:8080`. Uses the JDK's built-in `java.net.http.HttpClient`
  to talk to it, so this project has no HTTP-client library dependency at
  all, beyond `cheshire` for JSON (see `deps.edn`)."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers])
  (:gen-class))

(def ^:private default-policy-check-url
  "The Policy Check endpoint's default base URL - the sample app's local
  State Backend address (see `state-backend/src/infratomic/state_backend
  /main.clj`'s `port`)."
  "http://localhost:8080/policy-check")

(def ^:private default-sync-url
  "The Sync endpoint's default base URL - the sample app's local State
  Backend address, mirroring `default-policy-check-url`."
  "http://localhost:8080/sync")

(def ^:private default-drift-url
  "The Drift Check endpoint's default base URL - the sample app's local
  State Backend address, mirroring `default-sync-url`."
  "http://localhost:8080/drift")

(defn- subcommand
  "The first non-flag (doesn't start with `-`) argument in `args` - the
  Terraform subcommand being invoked."
  [args]
  (first (remove #(str/starts-with? % "-") args)))

(defn- terraform-command
  [args]
  (vec (cons "terraform" args)))

(defn- terraform!
  "Shell out to the real `terraform` binary with `args`, inheriting this
  process's stdio, and return its exit code."
  [args]
  (let [pb (ProcessBuilder. (terraform-command args))]
    (.inheritIO pb)
    (.waitFor (.start pb))))

(defn- capture!
  "Shell out to the real `terraform` binary with `args`, inheriting stderr
  (so errors stay visible) but capturing stdout, returning `{:exit ... :out
  ...}`."
  [args]
  (let [pb (ProcessBuilder. (terraform-command args))]
    (.redirectError pb ProcessBuilder$Redirect/INHERIT)
    (let [proc (.start pb)
          out  (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      {:exit exit :out out})))

(defn- post-json
  "POST `body` to `url` with a JSON content type, returning `{:status ...
  :body ...}` - both the HTTP status code and the response body as a
  string, so callers can distinguish a confirmed-successful response from
  anything else."
  [url body]
  (let [client  (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI/create url))
                     (.header "Content-Type" "application/json")
                     (.POST (HttpRequest$BodyPublishers/ofString body))
                     .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn- get-json
  "GET `url`, returning `{:status ... :body ...}` - mirrors `post-json`'s
  shape, for the no-request-body `GET /drift` endpoint."
  [url]
  (let [client   (HttpClient/newHttpClient)
        request  (-> (HttpRequest/newBuilder (URI/create url)) .GET .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn- print-violation
  [{:strs [rule resource]}]
  (println (format "  - %s: %s (%s)" rule (get resource "id") (get resource "type"))))

(defn- policy-check
  "POST `plan-json` to `policy-check-url` and return either `{:violations
  [...]}` (possibly empty) for a positively confirmed clean/violating
  check, or `{:error <message>}` for anything else - a non-`200` status, a
  response body that doesn't parse as JSON, a parsed body missing a
  `violations` array, or the HTTP request itself failing (e.g. connection
  refused). This is deliberately fail-closed: only a `200` response whose
  body actually contains a `violations` array is ever treated as anything
  but blocked, so a malformed or non-2xx response from the Policy Check
  endpoint can never be silently mistaken for \"no violations\" and let a
  real `apply` through."
  [policy-check-url plan-json]
  (try
    (let [{:keys [status body]} (post-json policy-check-url plan-json)]
      (if (not= 200 status)
        {:error (format "Policy Check endpoint returned HTTP %d: %s" status body)}
        (let [parsed (try (json/parse-string body) (catch Exception _ ::invalid))]
          (cond
            (= parsed ::invalid)
            {:error (format "Policy Check endpoint returned a response that isn't valid JSON: %s" body)}

            (not (sequential? (get parsed "violations")))
            {:error (format "Policy Check endpoint response has no `violations` array: %s" body)}

            :else
            {:violations (get parsed "violations")}))))
    (catch Exception e
      {:error (format "Policy Check request to %s failed: %s" policy-check-url (.getMessage e))})))

(defn- apply-gated!
  "The `apply`-intercepting flow: `terraform plan -out=tfplan` -> (on a
  successful plan) `terraform show -json tfplan` -> POST the resulting plan
  JSON to `policy-check-url` -> branch on the result: print each violation
  and exit non-zero without ever invoking real `terraform apply` if any
  are present *or* if the Policy Check itself couldn't be positively
  confirmed clean (non-2xx/malformed response, request failure - fail
  closed, never open), else invoke real `terraform apply tfplan` and pass
  through its exit code. `extra-args` are the flags following `apply` on
  the original command line (see `-main`) - forwarded to the `plan` step
  only, since Terraform rejects `-var`/`-var-file`/`-target`/etc. on an
  `apply` that names a saved plan file (the plan file already carries
  them); any user-supplied `-out=`/`-auto-approve` is dropped since this
  flow controls the plan file and the applied-plan-file path never
  prompts. Returns the exit code the CLI process should exit with."
  [policy-check-url extra-args]
  (let [plan-flags (remove #(or (str/starts-with? % "-out=") (= % "-auto-approve")) extra-args)
        plan-exit  (terraform! (concat ["plan" "-out=tfplan"] plan-flags))]
    (if-not (zero? plan-exit)
      plan-exit
      (let [{:keys [exit out]} (capture! ["show" "-json" "tfplan"])]
        (if-not (zero? exit)
          exit
          (let [{:keys [violations error]} (policy-check policy-check-url out)]
            (cond
              error
              (do
                (println "Policy Check failed - this apply is blocked:")
                (println (str "  " error))
                1)

              (seq violations)
              (do
                (println "Policy Check failed - this apply is blocked by:")
                (doseq [violation violations] (print-violation violation))
                1)

              :else
              (terraform! ["apply" "tfplan"]))))))))

(defn- print-discovered-resource
  [{:strs [type id]}]
  (println (format "  - %s (%s)" id type)))

(defn- trigger-sync
  "POST an empty body to `sync-url` and return either `{:discovered [...]
  :updated [...] :drifted [...] :skipped-already-managed N}` for a
  positively confirmed response, or `{:error <message>}` for anything
  else - a non-`200` status, a response body that doesn't parse as JSON, a
  parsed body missing the expected `discovered`/`updated`/`drifted`
  arrays, or the HTTP request itself failing (e.g. connection refused).
  Fail-closed like `policy-check`: only a `200` response with the expected
  shape is ever treated as a confirmed result, so a malformed or non-2xx
  response from the Sync endpoint is reported as a failure rather than
  silently treated as \"nothing discovered\"."
  [sync-url]
  (try
    (let [{:keys [status body]} (post-json sync-url "")]
      (if (not= 200 status)
        {:error (format "Sync endpoint returned HTTP %d: %s" status body)}
        (let [parsed (try (json/parse-string body) (catch Exception _ ::invalid))]
          (cond
            (= parsed ::invalid)
            {:error (format "Sync endpoint returned a response that isn't valid JSON: %s" body)}

            (not (and (sequential? (get parsed "discovered"))
                      (sequential? (get parsed "updated"))
                      (sequential? (get parsed "drifted"))))
            {:error (format "Sync endpoint response has no `discovered`/`updated`/`drifted` arrays: %s" body)}

            :else
            {:discovered              (get parsed "discovered")
             :updated                 (get parsed "updated")
             :drifted                 (get parsed "drifted")
             :skipped-already-managed (get parsed "skipped_already_managed")}))))
    (catch Exception e
      {:error (format "Sync request to %s failed: %s" sync-url (.getMessage e))})))

(defn- sync!
  "The `sync` subcommand's full flow: trigger Sync (`trigger-sync`) and
  print either a human-readable summary of what it discovered/updated/
  found drifted, or an error - exiting non-zero on any failure (fail
  closed, matching `apply-gated!`'s error handling style) rather than
  silently reporting success. Returns the exit code the CLI process
  should exit with."
  [sync-url]
  (let [{:keys [discovered updated drifted skipped-already-managed error]} (trigger-sync sync-url)]
    (if error
      (do
        (println "Sync failed:")
        (println (str "  " error))
        1)
      (do
        (println (format "Sync complete: %d discovered, %d updated, %d drifted, %d already managed (skipped)"
                          (count discovered) (count updated) (count drifted) (or skipped-already-managed 0)))
        (when (seq discovered)
          (println "Discovered:")
          (doseq [resource discovered] (print-discovered-resource resource)))
        (when (seq updated)
          (println "Updated:")
          (doseq [resource updated] (print-discovered-resource resource)))
        (when (seq drifted)
          (println "Drifted:")
          (doseq [resource drifted] (print-discovered-resource resource)))
        0))))

(defn trigger-drift-check
  "GET `drift-url` and return either `{:drifted [...]}` for a positively
  confirmed response, or `{:error <message>}` for anything else - a
  non-`200` status, a response body that doesn't parse as JSON, a parsed
  body missing the expected `drifted` array, or the HTTP request itself
  failing (e.g. connection refused). Fail-closed like `trigger-sync`: only
  a `200` response with the expected shape is ever treated as a confirmed
  result, so a malformed or non-2xx response from the Drift Check endpoint
  is reported as a failure rather than silently treated as \"no drift\".
  Public (rather than `defn-`) so it's directly testable, mirroring
  `db.clj`'s `backfill-managed-flag!`."
  [drift-url]
  (try
    (let [{:keys [status body]} (get-json drift-url)]
      (if (not= 200 status)
        {:error (format "Drift Check endpoint returned HTTP %d: %s" status body)}
        (let [parsed (try (json/parse-string body) (catch Exception _ ::invalid))]
          (cond
            (= parsed ::invalid)
            {:error (format "Drift Check endpoint returned a response that isn't valid JSON: %s" body)}

            (not (sequential? (get parsed "drifted")))
            {:error (format "Drift Check endpoint response has no `drifted` array: %s" body)}

            :else
            {:drifted (get parsed "drifted")}))))
    (catch Exception e
      {:error (format "Drift Check request to %s failed: %s" drift-url (.getMessage e))})))

(defn drift-check!
  "The `drift-check` subcommand's full flow: trigger a Drift Check
  (`trigger-drift-check`) and print either a human-readable summary of any
  drifted resources, or an error - exiting non-zero on request/shape
  failure (fail closed, matching `sync!`'s error handling style) *and*
  when drift is found (so it's usable as an automation gate), `0` only
  when the check succeeds and finds nothing. Returns the exit code the
  CLI process should exit with. Public (rather than `defn-`) so it's
  directly testable, mirroring `trigger-drift-check`."
  [drift-url]
  (let [{:keys [drifted error]} (trigger-drift-check drift-url)]
    (cond
      error
      (do
        (println "Drift Check failed:")
        (println (str "  " error))
        1)

      (seq drifted)
      (do
        (println (format "Drift Check found %d drifted resource(s):" (count drifted)))
        (doseq [resource drifted] (print-discovered-resource resource))
        1)

      :else
      (do
        (println "Drift Check complete: no drift found")
        0))))

(defn- flag-value
  [args flag]
  (some (fn [arg] (when (str/starts-with? arg (str flag "=")) (subs arg (inc (count flag)))))
        args))

(defn -main
  "`args` may include a leading literal `\"--\"` (per this CLI's documented
  invocation, `clojure -M -m infratomic.cli.main -- <terraform args...>` -
  `clojure`'s `-M -m` mode, unlike `-e`, passes `--` through to `-main`
  rather than stripping it as an args separator itself, and the real
  `terraform` binary doesn't understand a bare `--` either), so it's
  filtered out here before anything else."
  [& args]
  (let [args             (remove #{"--"} args)
        policy-check-url (or (flag-value args "--policy-check-url")
                              (System/getenv "INFRATOMIC_POLICY_CHECK_URL")
                              default-policy-check-url)
        sync-url         (or (flag-value args "--sync-url")
                              (System/getenv "INFRATOMIC_SYNC_URL")
                              default-sync-url)
        drift-url        (or (flag-value args "--drift-url")
                              (System/getenv "INFRATOMIC_DRIFT_URL")
                              default-drift-url)
        tf-args          (remove #(or (str/starts-with? % "--policy-check-url=")
                                       (str/starts-with? % "--sync-url=")
                                       (str/starts-with? % "--drift-url="))
                                  args)
        exit             (cond
                            (= "apply" (subcommand tf-args))
                            (apply-gated! policy-check-url (rest (drop-while #(not= % "apply") tf-args)))

                            (= "sync" (subcommand tf-args))
                            (sync! sync-url)

                            (= "drift-check" (subcommand tf-args))
                            (drift-check! drift-url)

                            :else
                            (terraform! tf-args))]
    (System/exit exit)))
