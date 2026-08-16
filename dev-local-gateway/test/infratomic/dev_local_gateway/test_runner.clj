(ns infratomic.dev-local-gateway.test-runner
  "Entry point for running the Dev-Local Gateway's hermetic test suite
  (`clojure -X:test`), mirroring `state-backend`'s
  `infratomic.state-backend.test-runner`."
  (:require [clojure.test :as test]
            [infratomic.dev-local-gateway.main-test]))

(defn run-tests
  [_]
  (let [{:keys [fail error]} (test/run-tests 'infratomic.dev-local-gateway.main-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
