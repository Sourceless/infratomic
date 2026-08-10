(ns infratomic.state-backend.sync-integration-test
  "Integration test: applies the sample Terraform app (`terraform/`) against
  already-running LocalStack, directly creates an extra security group (and
  an ingress rule on it) via the EC2 API - bypassing Terraform entirely, the
  same way a real out-of-band/drifted resource would exist - then runs the
  real `sync!` pass (real `cognitect.aws/api` calls against LocalStack, real
  Datomic dev-local transactions) and asserts on its result and on the
  resulting db.

  NOT part of the hermetic `clojure -X:test` suite (see `test_runner.clj`)
  - it shells out to real `terraform`/`docker` and makes real LocalStack API
  calls, unlike every other test namespace besides `query_integration_test`.
  Run it separately via `clojure -X:integration-test`, after bringing up
  `docker compose up -d` from the repo root (LocalStack reachable at
  http://localhost:4566, with the `ec2` service enabled). Mirrors
  `query_integration_test.clj`'s fixture pattern (own State Backend
  connection, apply-then-destroy the sample app in a `finally`) - see that
  namespace's docstring for why (Datomic dev-local's exclusive connection
  lock)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.main :as main]
            [infratomic.state-backend.sync :as sync]
            [ring.adapter.jetty :as jetty]))

(def ^:private port 8080)

(def ^:private terraform-dir
  (str (io/file (System/getProperty "user.dir") ".." "terraform")))

(defn- terraform!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "terraform" (concat args [:dir terraform-dir]))]
    (when-not (zero? exit)
      (throw (ex-info (str "terraform " (first args) " failed (exit " exit ")")
                       {:args args :out out :err err})))
    out))

(defn- with-state-backend-server
  "Start a real State Backend HTTP server on `port`, bound to a connection
  this test also uses directly for `sync!` afterward, for the duration of
  `(f conn)` - `terraform init`/`apply`/`destroy` need a live `/state`
  endpoint to talk to, exactly as `query_integration_test.clj`'s fixture
  of the same name does. Stops the server in a `finally`."
  [f]
  (let [conn   (db/ensure-db! (db/client))
        server (jetty/run-jetty (handler/handler conn) {:port port :join? false})]
    (try
      (f conn)
      (finally
        (.stop server)))))

(defn- with-applied-sample-app
  [f]
  (terraform! "init" "-input=false")
  (terraform! "destroy" "-auto-approve")
  (terraform! "apply" "-auto-approve")
  (try
    (f)
    (finally
      (terraform! "destroy" "-auto-approve"))))

(defn- eid-by-aws-id
  "A modeled id attribute (e.g. `:aws-security-group/id`) isn't
  `:db.unique/*`, so it can't be looked up via a `[:ident value]` lookup
  ref - find its entity id via a direct query instead."
  [db ident aws-id]
  (ffirst (d/q '[:find ?e :in $ ?ident ?v :where [?e ?ident ?v]] db ident aws-id)))

(defn- create-out-of-band-security-group!
  "Directly creates a security group (with an ingress rule opening port 22
  to `0.0.0.0/0`, matching the `security-groups-with-port-22-open` Rule's
  shape) against LocalStack via the EC2 API - bypassing Terraform entirely,
  standing in for a real out-of-band/drifted resource. Returns the new
  security group's id."
  [client]
  (let [vpc-id (-> (aws/invoke client {:op :DescribeVpcs}) :Vpcs first :VpcId)
        sg-id  (-> (aws/invoke client {:op :CreateSecurityGroup
                                        :request {:GroupName (str "sync-test-" (random-uuid))
                                                   :Description "Sync integration test fixture"
                                                   :VpcId vpc-id}})
                    :GroupId)]
    (aws/invoke client {:op :AuthorizeSecurityGroupIngress
                         :request {:GroupId sg-id
                                   :IpPermissions [{:IpProtocol "tcp" :FromPort 22 :ToPort 22
                                                     :IpRanges [{:CidrIp "0.0.0.0/0"}]}]}})
    sg-id))

(deftest sync-discovers-out-of-band-resources-without-duplicating-or-touching-managed-ones
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [client (sync/ec2-client)
                sg-id  (create-out-of-band-security-group! client)]
            (try
              (let [summary1 (sync/sync! conn client)]

                (testing "the out-of-band security group is discovered"
                  (is (contains? (into #{} (map :id) (:discovered summary1)) sg-id)))

                (testing "its ingress rule is discovered too"
                  (is (some #(and (= "aws_security_group_rule" (:type %))
                                   (let [db  (d/db conn)
                                         eid (eid-by-aws-id db :aws-security-group-rule/id (:id %))]
                                     (= sg-id (:aws-security-group-rule/security-group-id
                                               (d/pull db [:aws-security-group-rule/security-group-id] eid)))))
                            (:discovered summary1))))

                (testing "the discovered security group is tagged unmanaged"
                  (let [db  (d/db conn)
                        eid (eid-by-aws-id db :aws-security-group/id sg-id)]
                    (is (= false (:resource/managed? (d/pull db [:resource/managed?] eid))))))

                (testing "the Terraform-managed sample-app security groups are skipped, not re-discovered"
                  (is (not (contains? (into #{} (map :id) (:discovered summary1)) "aws_security_group.ssh_open")))
                  (is (pos? (:skipped-already-managed summary1))))

                (let [summary2 (sync/sync! conn client)]
                  (testing "running sync again with no LocalStack changes creates no duplicates"
                    (is (empty? (:discovered summary2)))
                    (let [db (d/db conn)]
                      (is (= 1 (count (d/q '[:find ?e :in $ ?id :where [?e :aws-security-group/id ?id]] db sg-id))))))

                  (testing "the previously-discovered security group is reported as updated, not re-discovered"
                    (is (contains? (into #{} (map :id) (:updated summary2)) sg-id)))))
              (finally
                ;; Clean up the out-of-band security group so repeated
                ;; test runs don't accumulate orphan LocalStack resources
                ;; (mirrors query_integration_test.clj's "safe to run
                ;; repeatedly" property).
                (aws/invoke client {:op :DeleteSecurityGroup :request {:GroupId sg-id}})
                (aws/stop client)))))))))

(deftest post-sync-endpoint-returns-a-summary-reflecting-real-localstack-resources
  (with-state-backend-server
    (fn [conn]
      (with-applied-sample-app
        (fn []
          (let [client       (sync/ec2-client)
                sync-handler (main/app-handler conn client)]
            (try
              (let [response (sync-handler {:request-method :post :uri "/sync"})
                    body     (json/parse-string (:body response))]
                (is (= 200 (:status response)))
                (testing "the response summary reflects real LocalStack resources"
                  (is (seq (get body "discovered")))
                  (is (every? #(and (contains? % "type") (contains? % "id")) (get body "discovered"))))
                (testing "the sample app's Terraform-managed security groups are reflected as skipped, not re-discovered"
                  (is (not (contains? (into #{} (map #(get % "id")) (get body "discovered"))
                                       "aws_security_group.ssh_open")))
                  (is (pos? (get body "skipped_already_managed")))))
              (finally
                (aws/stop client)))))))))
