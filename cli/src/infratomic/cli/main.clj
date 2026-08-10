(ns infratomic.cli.main
  "Entry point for the CLI (`clojure -M -m infratomic.cli.main -- <terraform
  args...>`): a drop-in replacement for the `terraform` binary that passes
  every subcommand straight through unchanged except `apply`, which it
  intercepts to run a Policy Check - via the State Backend's `POST
  /policy-check` endpoint - before ever invoking real `terraform apply`,
  and `sync`, a State-Backend-only subcommand (not a real Terraform one)
  that triggers Sync - via `POST /sync` - and prints a summary of what it
  discovered/ingested. See openspec/changes/issue-16-block-bad-infra-
  states-before-terraform-apply-runs/design.md's \"CLI structure\"
  decision, and openspec/changes/issue-26-sync-unmanaged-localstack-
  resources-into-datomic/design.md's \"CLI sync subcommand\" decision.

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
  :updated [...] :skipped-already-managed N}` for a positively confirmed
  response, or `{:error <message>}` for anything else - a non-`200`
  status, a response body that doesn't parse as JSON, a parsed body
  missing the expected `discovered`/`updated` arrays, or the HTTP request
  itself failing (e.g. connection refused). Fail-closed like
  `policy-check`: only a `200` response with the expected shape is ever
  treated as a confirmed result, so a malformed or non-2xx response from
  the Sync endpoint is reported as a failure rather than silently treated
  as \"nothing discovered\"."
  [sync-url]
  (try
    (let [{:keys [status body]} (post-json sync-url "")]
      (if (not= 200 status)
        {:error (format "Sync endpoint returned HTTP %d: %s" status body)}
        (let [parsed (try (json/parse-string body) (catch Exception _ ::invalid))]
          (cond
            (= parsed ::invalid)
            {:error (format "Sync endpoint returned a response that isn't valid JSON: %s" body)}

            (not (and (sequential? (get parsed "discovered")) (sequential? (get parsed "updated"))))
            {:error (format "Sync endpoint response has no `discovered`/`updated` arrays: %s" body)}

            :else
            {:discovered              (get parsed "discovered")
             :updated                 (get parsed "updated")
             :skipped-already-managed (get parsed "skipped_already_managed")}))))
    (catch Exception e
      {:error (format "Sync request to %s failed: %s" sync-url (.getMessage e))})))

(defn- sync!
  "The `sync` subcommand's full flow: trigger Sync (`trigger-sync`) and
  print either a human-readable summary of what it discovered/updated, or
  an error - exiting non-zero on any failure (fail closed, matching
  `apply-gated!`'s error handling style) rather than silently reporting
  success. Returns the exit code the CLI process should exit with."
  [sync-url]
  (let [{:keys [discovered updated skipped-already-managed error]} (trigger-sync sync-url)]
    (if error
      (do
        (println "Sync failed:")
        (println (str "  " error))
        1)
      (do
        (println (format "Sync complete: %d discovered, %d updated, %d already managed (skipped)"
                          (count discovered) (count updated) (or skipped-already-managed 0)))
        (when (seq discovered)
          (println "Discovered:")
          (doseq [resource discovered] (print-discovered-resource resource)))
        (when (seq updated)
          (println "Updated:")
          (doseq [resource updated] (print-discovered-resource resource)))
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
        tf-args          (remove #(or (str/starts-with? % "--policy-check-url=")
                                       (str/starts-with? % "--sync-url="))
                                  args)
        exit             (cond
                            (= "apply" (subcommand tf-args))
                            (apply-gated! policy-check-url (rest (drop-while #(not= % "apply") tf-args)))

                            (= "sync" (subcommand tf-args))
                            (sync! sync-url)

                            :else
                            (terraform! tf-args))]
    (System/exit exit)))
