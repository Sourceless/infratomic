(ns infratomic.state-backend.test-runner
  "Entry point for running the State Backend's hermetic test suite via
  `clojure -X:test` (see `deps.edn`). The `query-integration-test`
  namespace is deliberately excluded - it shells out to real
  `terraform`/`docker` against already-running LocalStack + state-backend
  services, unlike every namespace here, which is fully hermetic and
  in-memory. Run it separately via `clojure -X:integration-test`."
  (:require [clojure.test :as test]
            [infratomic.state-backend.handler-test]
            [infratomic.state-backend.query-test]))

(defn run-tests
  "Run all tests and exit non-zero on failure/error, so this composes with
  CI."
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.state-backend.handler-test
                                              'infratomic.state-backend.query-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
