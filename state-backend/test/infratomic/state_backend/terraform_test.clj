(ns infratomic.state-backend.terraform-test
  "Unit tests for `terraform.clj`'s locking (`try-acquire-lock!`/
  `acquire-lock!`/`release-lock!`), invocation logging, and process-failure
  handling - exercised against an in-memory (non-persistent) Datomic
  dev-local database, mirroring `policy_test.clj`/`sync_test.clj`'s
  fixture pattern. Deliberately hermetic: never assumes a real `terraform`
  binary is on `PATH` (the CI `test` job that runs this suite doesn't
  install one) - `run-terraform!`/`with-lock-and-invocation` are exercised
  either against a guaranteed-to-fail invocation (a nonexistent working
  directory, which fails identically whether `terraform` is present or
  not) or via `#'terraform/with-lock-and-invocation`'s injectable thunk,
  standing in for the real subprocess call. Real `terraform apply`/
  `import`/`destroy` against a live LocalStack + the sample app is covered
  separately by `terraform-integration-test` (not part of this hermetic
  suite, see `test_runner.clj`)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.terraform :as terraform]))

(defn- fresh-conn
  "A connection to a freshly-created, schema-loaded, in-memory dev-local
  database, isolated from any other test by a random db name."
  []
  (let [client  (db/client :mem)
        db-name (str "test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data db/schema})
      conn)))

;; ---------------------------------------------------------------------------
;; Locking (tasks.md 2.4)
;; ---------------------------------------------------------------------------

(deftest acquiring-a-free-lock-succeeds
  (let [conn (fresh-conn)]
    (is (true? (terraform/try-acquire-lock! conn "aws_s3_bucket.uploads")))))

(deftest acquiring-an-already-held-non-stale-lock-fails
  (let [conn    (fresh-conn)
        address "aws_s3_bucket.uploads"]
    (is (true? (terraform/try-acquire-lock! conn address)))
    (is (false? (terraform/try-acquire-lock! conn address)))))

(deftest acquiring-a-stale-lock-succeeds
  (let [conn      (fresh-conn)
        address   "aws_s3_bucket.uploads"
        stale-at  (java.util.Date. (- (System/currentTimeMillis) terraform/lock-ttl-ms 1000))]
    (is (true? (terraform/try-acquire-lock! conn address)))
    ;; Directly backdate the lock's :lock/acquired-at past the TTL
    ;; threshold, standing in for "this lock's holder crashed a while
    ;; ago" without needing to actually wait out the real TTL.
    (let [eid (ffirst (d/q '[:find ?e :in $ ?a :where [?e :lock/resource-address ?a]]
                            (d/db conn) address))]
      (d/transact conn {:tx-data [[:db/add eid :lock/acquired-at stale-at]]}))
    (is (true? (terraform/try-acquire-lock! conn address)))))

(deftest releasing-a-lock-makes-the-address-acquirable-again
  (let [conn    (fresh-conn)
        address "aws_s3_bucket.uploads"]
    (is (true? (terraform/try-acquire-lock! conn address)))
    (is (false? (terraform/try-acquire-lock! conn address)))
    (terraform/release-lock! conn address)
    (is (true? (terraform/try-acquire-lock! conn address)))))

(deftest releasing-a-never-acquired-lock-is-a-no-op
  (let [conn (fresh-conn)]
    (is (nil? (terraform/release-lock! conn "aws_s3_bucket.never-locked")))))

(deftest locks-on-different-addresses-are-independent
  (let [conn (fresh-conn)]
    (is (true? (terraform/try-acquire-lock! conn "addr-a")))
    (is (true? (terraform/try-acquire-lock! conn "addr-b")))))

;; ---------------------------------------------------------------------------
;; run-terraform! never throws (tasks.md 6.4)
;; ---------------------------------------------------------------------------

(deftest a-failing-invocation-is-reported-as-a-failure-map-not-an-exception
  ;; A nonexistent working directory fails identically whether `terraform`
  ;; is on PATH or not: `sh` either can't even start the process (binary
  ;; missing) or `terraform` itself errors on the bad `:dir` - either way
  ;; `run-terraform!` must catch it and report `{:success false ...}`
  ;; rather than letting an exception escape.
  (let [result (#'terraform/run-terraform! "/definitely/does/not/exist-4f9a2b" ["apply" "-auto-approve"])]
    (is (map? result))
    (is (false? (:success result)))
    (is (string? (:err result)))))

;; ---------------------------------------------------------------------------
;; Invocation logging (tasks.md 6.5) - exercised via the private
;; with-lock-and-invocation wrapper apply!/import!/destroy! all share, with
;; an injected thunk standing in for the real terraform subprocess so this
;; stays hermetic.
;; ---------------------------------------------------------------------------

(defn- invocations
  [db address]
  (map first
       (d/q '[:find (pull ?e [:invocation/command :invocation/resource-address :invocation/success?])
              :in $ ?a
              :where [?e :invocation/resource-address ?a]]
            db address)))

(deftest a-successful-invocation-is-recorded
  (let [conn    (fresh-conn)
        address "aws_s3_bucket.uploads"]
    (#'terraform/with-lock-and-invocation conn :apply address (constantly {:success true :out "ok" :err ""}))
    (let [entries (invocations (d/db conn) address)]
      (is (= 1 (count entries)))
      (is (= {:invocation/command          :apply
              :invocation/resource-address address
              :invocation/success?         true}
             (first entries))))))

(deftest a-failed-invocation-is-recorded
  (let [conn    (fresh-conn)
        address "aws_s3_bucket.uploads"]
    (#'terraform/with-lock-and-invocation conn :destroy address (constantly {:success false :out "" :err "boom"}))
    (let [entries (invocations (d/db conn) address)]
      (is (= 1 (count entries)))
      (is (= {:invocation/command          :destroy
              :invocation/resource-address address
              :invocation/success?         false}
             (first entries))))))

(deftest with-lock-and-invocation-releases-the-lock-even-if-the-thunk-throws
  (let [conn    (fresh-conn)
        address "aws_s3_bucket.uploads"]
    (is (thrown? Exception
                 (#'terraform/with-lock-and-invocation conn :apply address
                   (fn [] (throw (ex-info "boom" {}))))))
    ;; Lock was released despite the thunk throwing - re-acquirable.
    (is (true? (terraform/try-acquire-lock! conn address)))))

(deftest with-lock-and-invocation-returns-the-thunks-result-unchanged
  (let [conn    (fresh-conn)
        result  {:success true :out "applied" :err ""}
        address "aws_s3_bucket.uploads"]
    (is (= result (#'terraform/with-lock-and-invocation conn :import address (constantly result))))))

;; ---------------------------------------------------------------------------
;; Concurrency (tasks.md 6.6/6.7)
;; ---------------------------------------------------------------------------

(deftest concurrent-invocations-on-the-same-address-serialize
  (let [conn      (fresh-conn)
        address   "aws_s3_bucket.uploads"
        events    (atom [])
        record!   (fn [event] (swap! events conj [event (System/nanoTime)]))
        slow-fn   (fn [] (record! :first-start) (Thread/sleep 100) (record! :first-end)
                     {:success true :out "" :err ""})
        fast-fn   (fn [] (record! :second-start) {:success true :out "" :err ""})
        first-th  (Thread. #(#'terraform/with-lock-and-invocation conn :apply address slow-fn))
        second-th (Thread. (fn []
                              ;; give the first thread a head start so it
                              ;; reliably wins the lock first
                              (Thread/sleep 20)
                              (#'terraform/with-lock-and-invocation conn :apply address fast-fn)))]
    (.start first-th)
    (.start second-th)
    (.join first-th)
    (.join second-th)
    (let [by-event (into {} @events)]
      (testing "the second invocation's own terraform call never starts before the first's ends"
        (is (< (get by-event :first-end) (get by-event :second-start)))))))

(deftest concurrent-invocations-on-different-addresses-proceed-independently
  (let [conn       (fresh-conn)
        started    (java.util.concurrent.CountDownLatch. 2)
        both-ran   (atom false)
        overlap-fn (fn []
                     (.countDown started)
                     ;; Wait for both threads to have started before
                     ;; either returns - only possible if neither is
                     ;; blocked behind the other's lock.
                     (is (true? (.await started 2 java.util.concurrent.TimeUnit/SECONDS)))
                     (reset! both-ran true)
                     {:success true :out "" :err ""})
        th-a       (Thread. #(#'terraform/with-lock-and-invocation conn :apply "addr-a" overlap-fn))
        th-b       (Thread. #(#'terraform/with-lock-and-invocation conn :apply "addr-b" overlap-fn))]
    (.start th-a)
    (.start th-b)
    (.join th-a 2000)
    (.join th-b 2000)
    (is (true? @both-ran))))

(deftest a-stale-lock-can-be-reacquired-by-a-new-invocation
  (let [conn      (fresh-conn)
        address   "aws_s3_bucket.uploads"
        stale-at  (java.util.Date. (- (System/currentTimeMillis) terraform/lock-ttl-ms 1000))]
    (is (true? (terraform/try-acquire-lock! conn address)))
    (let [eid (ffirst (d/q '[:find ?e :in $ ?a :where [?e :lock/resource-address ?a]]
                            (d/db conn) address))]
      (d/transact conn {:tx-data [[:db/add eid :lock/acquired-at stale-at]]}))
    ;; A new invocation (via the public wrapper this time) proceeds
    ;; without blocking on the stale lock.
    (let [result (#'terraform/with-lock-and-invocation conn :apply address (constantly {:success true :out "" :err ""}))]
      (is (= true (:success result))))))

;; ---------------------------------------------------------------------------
;; HTTP endpoint request validation
;; ---------------------------------------------------------------------------

(deftest apply-endpoint-rejects-invalid-json
  (let [conn     (fresh-conn)
        response (terraform/apply-endpoint conn "not json")]
    (is (= 400 (:status response)))))

(deftest apply-endpoint-rejects-a-missing-required-field
  (let [conn     (fresh-conn)
        response (terraform/apply-endpoint conn (json/generate-string {"working_directory" "/tmp"}))]
    (is (= 400 (:status response)))))

(deftest import-endpoint-rejects-a-missing-aws-id
  (let [conn     (fresh-conn)
        response (terraform/import-endpoint conn (json/generate-string {"working_directory" "/tmp"
                                                                          "resource_address" "aws_s3_bucket.uploads"}))]
    (is (= 400 (:status response)))))

(deftest destroy-endpoint-rejects-a-missing-required-field
  (let [conn     (fresh-conn)
        response (terraform/destroy-endpoint conn (json/generate-string {"resource_address" "aws_s3_bucket.uploads"}))]
    (is (= 400 (:status response)))))

(deftest apply-endpoint-with-valid-fields-runs-and-responds-200-even-on-terraform-failure
  ;; Hermetic: a nonexistent working directory guarantees run-terraform!
  ;; reports {:success false ...} (see above), but the *endpoint* itself
  ;; still confirms-clean-request-shape -> 200, mirroring policy-check's
  ;; "200 regardless of :success" convention.
  (let [conn     (fresh-conn)
        response (terraform/apply-endpoint conn (json/generate-string
                                                   {"working_directory" "/definitely/does/not/exist-4f9a2b"
                                                    "resource_address"  "aws_s3_bucket.uploads"}))]
    (is (= 200 (:status response)))
    (is (false? (get (json/parse-string (:body response)) "success")))))
