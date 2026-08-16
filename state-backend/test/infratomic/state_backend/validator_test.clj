(ns infratomic.state-backend.validator-test
  "Dedicated unit tests for the shared query/rule validator
  (`infratomic.state-backend.validator`), covering the allowlist boundary
  and the recursion/rule-invocation distinction directly against
  `validate-query` - not just indirectly through the ad-hoc query
  (`/query`) or Rule-registration (`/rules`) HTTP endpoints (design.md's
  \"Two new untrusted-Datalog-accepting HTTP surfaces... share one
  validator\" risk callout)."
  (:require [clojure.test :refer [deftest is testing]]
            [infratomic.state-backend.validator :as validator]))

(deftest a-query-with-only-allowlisted-predicates-is-valid
  (is (= {:valid? true}
         (validator/validate-query
          '{:find  [?e]
            :where [[?e :aws-security-group-rule/from-port ?from]
                    [(<= ?from 22)]
                    [(>= ?from 0)]
                    [(< ?from 100)]
                    [(> ?from -1)]
                    [(= ?from ?from)]
                    [(not= ?from 9999)]
                    [(== ?from ?from)]]}))))

(deftest a-query-with-a-disallowed-function-invocation-clause-is-rejected
  (let [result (validator/validate-query
                '{:find  [?e]
                  :where [[?e :aws-security-group-rule/from-port ?from]
                          [(str ?from) ?str]]})]
    (is (false? (:valid? result)))
    (is (string? (:reason result)))))

(deftest every-allowlisted-predicate-individually-is-valid
  (doseq [sym '[< > <= >= = not= ==]]
    (is (true? (:valid? (validator/validate-query
                          {:find  '[?e]
                           :where [['?e :resource/id '?id]
                                   [(list sym '?id '?id)]]})))
        (str sym " should be allowlisted"))))

(deftest arbitrary-application-functions-are-rejected
  (doseq [sym '[clojure.core/eval read-string slurp println system-exit]]
    (is (false? (:valid? (validator/validate-query
                           {:find  '[?e]
                            :where [['?e :resource/id '?id]
                                    [(list sym '?id)]]})))
        (str sym " should be rejected"))))

(deftest a-bare-list-rule-invocation-clause-is-never-rejected
  (testing "a rule-invocation clause like (reaches ?src ?dst) is a bare list, not a
    vector-wrapped function-invocation clause, and is unaffected by the allowlist check"
    (is (= {:valid? true}
           (validator/validate-query
            '{:find  [?dst]
              :in    [$ % ?src ?dst]
              :where [(reaches ?src ?dst)]}
            '[[(reaches ?src ?dst)
               [(= ?src ?dst)]]])))))

(deftest validation-recurses-into-not-clauses
  (is (false? (:valid? (validator/validate-query
                         '{:find  [?e]
                           :where [(not [(str ?e) ?x])]})))))

(deftest validation-recurses-into-not-join-clauses
  (is (false? (:valid? (validator/validate-query
                         '{:find  [?e]
                           :where [(not-join [?e] [(str ?e) ?x])]})))))

(deftest validation-recurses-into-or-clauses
  (is (false? (:valid? (validator/validate-query
                         '{:find  [?e]
                           :where [(or [(str ?e) ?x]
                                       [?e :resource/id ?x])]})))))

(deftest validation-recurses-into-or-join-clauses
  (is (false? (:valid? (validator/validate-query
                         '{:find  [?e]
                           :where [(or-join [?e]
                                             [(str ?e) ?x]
                                             [?e :resource/id ?x])]})))))

(deftest validation-recurses-into-every-rule-body-in-rule-defs
  (testing "a disallowed function-invocation clause inside a rule body is rejected, even
    though the query's own :where only invokes the rule by name"
    (let [result (validator/validate-query
                  '{:find  [?dst]
                    :in    [$ % ?src ?dst]
                    :where [(reaches ?src ?dst)]}
                  '[[(reaches ?src ?dst)
                     [(str ?src) ?bad]]])]
      (is (false? (:valid? result))))))

(deftest a-recursive-rule-set-using-only-allowlisted-predicates-and-rule-invocations-is-valid
  (testing "a genuinely self-referential rule (base case + recursive case invoking itself),
    using only the allowlisted `=` predicate and rule invocations - no arithmetic (`-`), which
    is deliberately outside the validator's fixed allowlist"
    (is (= {:valid? true}
           (validator/validate-query
            '{:find  [?dst]
              :in    [$ % ?src ?dst]
              :where [(reaches ?src ?dst)]}
            '[[(reaches ?src ?dst)
               [(= ?src ?dst)]]
              [(reaches ?src ?dst)
               [?mid :resource/id ?src]
               (reaches ?mid ?dst)]])))))

(deftest a-stored-rule-map-using-rule-where-key-validates-the-same-as-a-query-map
  (is (= {:valid? true}
         (validator/validate-query
          '{:rule/id    :security-groups-with-port-22-open
            :rule/find  [?sg]
            :rule/in    [$]
            :rule/where [[?sg :aws-security-group/id ?sg-id]
                         [(<= 1 22)]]}))))
