(ns infratomic.state-backend.test-runner
  "Entry points for running the State Backend's test suites: `run-tests`
  (hermetic, in-memory - `clojure -X:test`) and `run-integration-tests`
  (shells out to real `terraform`/`docker` against already-running
  LocalStack + a running state-backend dev server - `clojure
  -X:integration-test`), both defined in `deps.edn`. The integration test
  namespace is deliberately excluded from `run-tests`'s default run, since
  it isn't hermetic like every other namespace here."
  (:require [clojure.test :as test]
            [infratomic.state-backend.db-test]
            [infratomic.state-backend.gateway-client-test]
            [infratomic.state-backend.handler-test]
            [infratomic.state-backend.iam-test]
            [infratomic.state-backend.main-test]
            [infratomic.state-backend.policy-test]
            [infratomic.state-backend.query-test]
            [infratomic.state-backend.query-integration-test]
            [infratomic.state-backend.sync-test]
            [infratomic.state-backend.sync-integration-test]
            [infratomic.state-backend.validator-test]))

(defn run-tests
  "Run the hermetic test suite and exit non-zero on failure/error, so this
  composes with CI."
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.state-backend.db-test
                                              'infratomic.state-backend.gateway-client-test
                                              'infratomic.state-backend.handler-test
                                              'infratomic.state-backend.iam-test
                                              'infratomic.state-backend.main-test
                                              'infratomic.state-backend.policy-test
                                              'infratomic.state-backend.query-test
                                              'infratomic.state-backend.sync-test
                                              'infratomic.state-backend.validator-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(defn run-integration-tests
  "Run `query-integration-test` and `sync-integration-test` and exit
  non-zero on failure/error. Assumes LocalStack + the State Backend dev
  server are already running (see each namespace's docstring)."
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.state-backend.query-integration-test
                                              'infratomic.state-backend.sync-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
