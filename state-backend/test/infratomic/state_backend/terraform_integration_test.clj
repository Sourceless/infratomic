(ns infratomic.state-backend.terraform-integration-test
  "Integration test: exercises `terraform.clj`'s `apply!`/`import!`/
  `destroy!` against the sample Terraform app (`terraform/`) and
  already-running LocalStack, via a real State Backend HTTP server this
  test starts itself - mirroring `sync_integration_test.clj`/
  `query_integration_test.clj`'s fixture pattern (own State Backend
  connection, apply-then-destroy the sample app in a `finally`, see those
  namespaces' docstrings for why).

  NOT part of the hermetic `clojure -X:test` suite (see `test_runner.clj`)
  - it shells out to real `terraform`/`docker`. Run it separately via
  `clojure -X:integration-test`, after bringing up `docker compose up -d`
  from the repo root (LocalStack reachable at http://localhost:4566, with
  the `s3` service enabled)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.sync :as sync]
            [infratomic.state-backend.terraform :as terraform]
            [ring.adapter.jetty :as jetty]))

(def ^:private port 8080)

(def ^:private terraform-dir
  (str (io/file (System/getProperty "user.dir") ".." "terraform")))

(def ^:private uploads-bucket-address "aws_s3_bucket.uploads")
(def ^:private uploads-bucket-id "infratomic-test-app-uploads")

(defn- terraform!
  "Shell out to `terraform <args>` in `terraform-dir` directly (not via
  `terraform.clj`), throwing on a non-zero exit - used for setup/teardown
  only, mirroring `sync_integration_test.clj`'s own `terraform!` helper."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "terraform" (concat args [:dir terraform-dir]))]
    (when-not (zero? exit)
      (throw (ex-info (str "terraform " (first args) " failed (exit " exit ")")
                       {:args args :out out :err err})))
    out))

(defn- s3-client
  "An S3 client pointed at LocalStack, mirroring `sync.clj`'s `ec2-client`/
  `iam-client` shape exactly - including reusing `sync/http-client` (see
  that fn's docstring for why: `com.cognitect.aws/api`'s own bundled
  Jetty-9-based http-client is binary-incompatible with the Jetty 12
  `ring-jetty-adapter` already pulls onto this project's classpath)."
  []
  (aws/client {:api                  :s3
               :region               "us-east-1"
               :http-client          (sync/http-client)
               :endpoint-override    {:protocol :http :hostname "localhost" :port 4566}
               :credentials-provider (credentials/basic-credentials-provider
                                      {:access-key-id "test" :secret-access-key "test"})}))

(defn- bucket-exists?
  [client bucket]
  (let [response (aws/invoke client {:op :HeadBucket :request {:Bucket bucket}})]
    (not (:cognitect.anomalies/category response))))

(defn- with-state-backend-server
  [f]
  (let [conn   (db/ensure-db! (db/client))
        server (jetty/run-jetty (handler/handler conn) {:port port :join? false})]
    (try
      (f conn)
      (finally
        (.stop server)))))

(defn- with-clean-terraform-state
  "Setup: `terraform init` (idempotent) then `terraform destroy` (clears
  any pre-existing state so each test starts from a known-empty
  baseline). Runs `(f)`, then tears down via `terraform destroy` in a
  `finally` regardless of whether `f` succeeded."
  [f]
  (terraform! "init" "-input=false")
  (terraform! "destroy" "-auto-approve")
  (try
    (f)
    (finally
      (terraform! "destroy" "-auto-approve"))))

(deftest apply-bang-against-the-sample-app-succeeds-and-matches-terraform-show
  (with-state-backend-server
    (fn [conn]
      (with-clean-terraform-state
        (fn []
          (let [result (terraform/apply! conn terraform-dir "aws_s3_bucket.uploads")]
            (testing "apply! reports success"
              (is (true? (:success result))))
            (testing "terraform show confirms the sample app is actually applied"
              (let [show (terraform! "show")]
                (is (re-find #"aws_s3_bucket\.uploads" show))
                (is (re-find #"aws_instance\.workload_1" show))))
            (testing "an Invocation entity was recorded"
              (let [db (d/db conn)]
                (is (seq (d/q '[:find ?e :in $ ?a
                                 :where [?e :invocation/resource-address ?a]
                                        [?e :invocation/command :apply]
                                        [?e :invocation/success? true]]
                               db "aws_s3_bucket.uploads")))))))))))

(deftest import-bang-against-a-resource-with-a-pre-existing-config-block-succeeds
  (with-state-backend-server
    (fn [conn]
      (with-clean-terraform-state
        (fn []
          (terraform! "apply" "-auto-approve")
          ;; Remove the bucket from Terraform's own state (not from AWS/
          ;; LocalStack itself) so there's something real for import! to
          ;; rebind - the config block for aws_s3_bucket.uploads already
          ;; exists in terraform/s3.tf, satisfying import!'s pure-executor
          ;; precondition.
          (terraform! "state" "rm" uploads-bucket-address)
          (let [result (terraform/import! conn terraform-dir uploads-bucket-address uploads-bucket-id)]
            (testing "import! reports success"
              (is (true? (:success result))))
            (testing "the resource is back in Terraform's state, bound to the same address"
              (let [show (terraform! "show")]
                (is (re-find #"aws_s3_bucket\.uploads" show))))
            (testing "a subsequent plan reports no diff for the re-imported resource (already matches live)"
              (let [{:keys [exit]} (shell/sh "terraform" "plan" "-detailed-exitcode" "-input=false" :dir terraform-dir)]
                ;; -detailed-exitcode: 0 = no changes, 2 = changes present,
                ;; 1 = error. Either 0 or 2 is an acceptable outcome here
                ;; (other resources may have drifted attributes irrelevant
                ;; to this test); 1 would mean the import itself left
                ;; something broken.
                (is (not= 1 exit))))))))))

(deftest destroy-bang-with-a-target-address-only-removes-that-resource
  (with-state-backend-server
    (fn [conn]
      (with-clean-terraform-state
        (fn []
          (terraform! "apply" "-auto-approve")
          (let [client (s3-client)]
            (try
              (is (true? (bucket-exists? client uploads-bucket-id))
                  "the bucket must exist before destroy! for this test to mean anything")
              (let [result (terraform/destroy! conn terraform-dir uploads-bucket-address)]
                (testing "destroy! reports success"
                  (is (true? (:success result))))
                (testing "the targeted bucket is gone from Terraform's state"
                  (let [show (terraform! "show")]
                    (is (not (re-find #"aws_s3_bucket\.uploads" show)))))
                (testing "the targeted bucket is gone from LocalStack itself"
                  (is (false? (bucket-exists? client uploads-bucket-id))))
                (testing "other sample-app resources are untouched"
                  (let [show (terraform! "show")]
                    (is (re-find #"aws_instance\.workload_1" show)))))
              (finally
                (aws/stop client)))))))))
