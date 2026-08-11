(ns infratomic.cli.test-runner
  "Entry point for running the CLI's hermetic test suite (`clojure -X:test`,
  see `deps.edn`) - mirrors `state-backend`'s own `test_runner.clj`."
  (:require [clojure.test :as test]
            [infratomic.cli.main-test]))

(defn run-tests
  "Run the hermetic test suite and exit non-zero on failure/error, so this
  composes with CI."
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.cli.main-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
