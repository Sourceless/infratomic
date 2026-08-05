(ns infratomic.state-backend.test-runner
  "Entry point for running the State Backend's test suite via `clojure
  -X:test` (see `deps.edn`)."
  (:require [clojure.test :as test]
            [infratomic.state-backend.handler-test]))

(defn run-tests
  "Run all tests and exit non-zero on failure/error, so this composes with
  CI."
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.state-backend.handler-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
