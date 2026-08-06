(ns infratomic.state-backend.query-integration-test
  "Integration test: applies the sample Terraform app (`terraform/`) against
  already-running LocalStack plus a State Backend HTTP server started by
  this test itself, then runs all 4 `infratomic.state-backend.query`
  functions against the live state-backend db and asserts on their
  results.

  NOT part of the hermetic `clojure -X:test` suite (see `test_runner.clj`)
  - it shells out to real `terraform`/`docker`, unlike every other test
  namespace here. Run it separately via `clojure -X:integration-test` (see
  `deps.edn`), after bringing up `docker compose up -d` from the repo root
  (LocalStack reachable at http://localhost:4566, with the `ec2` service
  enabled).

  This test starts its own State Backend server (same `main.clj`
  connect-then-serve logic, in-process) rather than assuming one is
  already running separately, and stops it in a `finally`: Datomic
  dev-local's storage only allows a single process to hold an open
  connection to a given database at a time (`d/connect` takes an exclusive
  OS-level file lock - confirmed empirically, a separately-launched
  `clojure -M -m infratomic.state-backend.main` process and this test
  process cannot both connect concurrently). Owning the connection here
  lets the test query the exact same live db Terraform's HTTP requests
  land in, with no second `d/connect` needed. If a separate dev server
  happens to already be running on port 8080 against the same `.datomic/`
  storage, stop it first - this test's own server start will fail
  otherwise.

  Setup destroys any pre-existing `terraform/` state before applying, and
  teardown destroys again in a `finally`, so a run doesn't pollute (or get
  polluted by) shared local dev state - matching the sample app itself,
  not a separate fixture app, since there's no ephemeral fixture
  infrastructure in scope (see design.md's Test architecture decision)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.query :as query]
            [ring.adapter.jetty :as jetty]))

(def ^:private port 8080)

(def ^:private terraform-dir
  (str (io/file (System/getProperty "user.dir") ".." "terraform")))

(defn- terraform!
  "Shell out to `terraform <args>` in `terraform-dir`, throwing on a
  non-zero exit so a setup/apply/teardown failure fails the test loudly
  instead of silently continuing against a half-applied stack."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "terraform" (concat args [:dir terraform-dir]))]
    (when-not (zero? exit)
      (throw (ex-info (str "terraform " (first args) " failed (exit " exit ")")
                       {:args args :out out :err err})))
    out))

(defn- with-state-backend-server
  "Start a real State Backend HTTP server on `port`, bound to a connection
  this test also uses directly to query afterward, for the duration of
  `(f conn)`. Stops the server in a `finally`."
  [f]
  (let [conn   (db/ensure-db! (db/client))
        server (jetty/run-jetty (handler/handler conn) {:port port :join? false})]
    (try
      (f conn)
      (finally
        (.stop server)))))

(defn- with-applied-sample-app
  "Setup: `terraform init` (idempotent, so a fresh checkout's provider cache
  is populated) then `terraform destroy` (clears any pre-existing state so
  the test starts from a known-empty baseline) then `terraform apply`. Runs
  `(f conn)` against the applied stack, then tears down via `terraform
  destroy` in a `finally` regardless of whether `f` succeeded."
  [conn f]
  (terraform! "init" "-input=false")
  (terraform! "destroy" "-auto-approve")
  (terraform! "apply" "-auto-approve")
  (try
    (f conn)
    (finally
      (terraform! "destroy" "-auto-approve"))))

(deftest query-functions-answer-real-infrastructure-questions
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        conn
        (fn [conn]
          (let [db (d/db conn)]
            (testing "all deployed resources"
              (let [ids (into #{} (map :resource/id) (query/all-deployed-resources db))]
                (is (contains? ids "aws_s3_bucket.uploads"))
                (is (contains? ids "aws_iam_role.lambda_exec"))
                (is (contains? ids "aws_security_group.ssh_open"))
                (is (contains? ids "aws_security_group.https_only"))))

            (testing "resources by type"
              (testing "a type with matches"
                (let [ids (into #{} (map :resource/id) (query/resources-by-type db "aws_security_group"))]
                  (is (= #{"aws_security_group.ssh_open" "aws_security_group.https_only"} ids))))
              (testing "a type with no matches"
                (is (empty? (query/resources-by-type db "not_a_real_resource_type")))))

            (testing "resources by attribute value"
              (testing "a match"
                (let [ids (into #{} (map :resource/id)
                                 (query/resources-by-attribute-value db "bucket" "infratomic-test-app-uploads"))]
                  (is (contains? ids "aws_s3_bucket.uploads"))))
              (testing "no match"
                (is (empty? (query/resources-by-attribute-value db "bucket" "does-not-exist")))))

            (testing "security groups with port 22 open to the internet"
              (let [ids (into #{} (map :resource/id) (query/security-groups-with-port-22-open db))]
                (is (contains? ids "aws_security_group.ssh_open"))
                (is (not (contains? ids "aws_security_group.https_only")))))))))))
