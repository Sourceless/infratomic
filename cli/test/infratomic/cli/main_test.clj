(ns infratomic.cli.main-test
  "Hermetic unit tests for the `drift-check` subcommand's HTTP-calling
  pieces (`trigger-drift-check`/`drift-check!`), exercised against a
  JDK-native `com.sun.net.httpserver.HttpServer` test double standing in
  for the State Backend's `GET /drift` endpoint on an ephemeral local
  port - no external HTTP-client or server dependency needed, matching
  this project's own dependency-free HTTP approach (see `main.clj`'s ns
  docstring)."
  (:require [clojure.test :refer [deftest is]]
            [infratomic.cli.main :as main])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- respond
  [^HttpExchange exchange status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- with-drift-endpoint
  "Start a JDK-native `HttpServer` on an ephemeral local port serving
  `/drift` with `status`/`body`, for the duration of `(f url)` (`url`
  being that server's full `/drift` URL). Stops the server afterward."
  [status body f]
  (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)]
    (.createContext server "/drift"
                     (reify HttpHandler
                       (handle [_ exchange]
                         (respond exchange status body))))
    (.start server)
    (try
      (f (str "http://localhost:" (.getPort (.getAddress server)) "/drift"))
      (finally
        (.stop server 0)))))

;; ---------------------------------------------------------------------------
;; trigger-drift-check
;; ---------------------------------------------------------------------------

(deftest trigger-drift-check-parses-a-well-formed-response-with-no-drift
  (with-drift-endpoint 200 "{\"drifted\": []}"
    (fn [url]
      (is (= {:drifted []} (main/trigger-drift-check url))))))

(deftest trigger-drift-check-parses-a-well-formed-response-with-drift-present
  (with-drift-endpoint 200 "{\"drifted\": [{\"type\": \"aws_security_group\", \"id\": \"sg-1\"}]}"
    (fn [url]
      (is (= {:drifted [{"type" "aws_security_group" "id" "sg-1"}]}
             (main/trigger-drift-check url))))))

(deftest trigger-drift-check-reports-an-error-for-a-non-200-status
  (with-drift-endpoint 500 "boom"
    (fn [url]
      (is (contains? (main/trigger-drift-check url) :error)))))

(deftest trigger-drift-check-reports-an-error-for-malformed-json
  (with-drift-endpoint 200 "not json"
    (fn [url]
      (is (contains? (main/trigger-drift-check url) :error)))))

(deftest trigger-drift-check-reports-an-error-for-a-response-missing-the-drifted-array
  (with-drift-endpoint 200 "{}"
    (fn [url]
      (is (contains? (main/trigger-drift-check url) :error)))))

(deftest trigger-drift-check-reports-an-error-when-the-request-fails
  ;; Port 1 - nothing is listening there, so the request itself fails
  ;; (connection refused), exercising the fail-closed `catch` branch.
  (is (contains? (main/trigger-drift-check "http://localhost:1/drift") :error)))

;; ---------------------------------------------------------------------------
;; drift-check! - the subcommand's exit-code contract
;; ---------------------------------------------------------------------------

(deftest drift-check-with-no-drift-exits-zero
  (with-drift-endpoint 200 "{\"drifted\": []}"
    (fn [url]
      (is (= 0 (main/drift-check! url))))))

(deftest drift-check-with-drift-present-exits-nonzero
  (with-drift-endpoint 200 "{\"drifted\": [{\"type\": \"aws_security_group\", \"id\": \"sg-1\"}]}"
    (fn [url]
      (is (not= 0 (main/drift-check! url))))))

(deftest drift-check-on-a-malformed-response-exits-nonzero
  (with-drift-endpoint 200 "not json"
    (fn [url]
      (is (not= 0 (main/drift-check! url))))))

(deftest drift-check-on-a-failed-request-exits-nonzero
  (is (not= 0 (main/drift-check! "http://localhost:1/drift"))))

;; ---------------------------------------------------------------------------
;; new_children/removed_children (issue #32) - trigger-drift-check/drift-check!
;; pass these through and print them without breaking, whether or not a
;; given entry carries them.
;; ---------------------------------------------------------------------------

(def ^:private drift-response-with-children-body
  (str "{\"drifted\": ["
       "{\"type\": \"aws_security_group\", \"id\": \"aws_security_group.ssh_open\", "
       "\"new_children\": [{\"type\": \"aws_security_group_rule\", \"id\": \"aws_security_group_rule.discovered-sgr-1\"}]},"
       "{\"type\": \"aws_route_table\", \"id\": \"aws_route_table.rt_a\", "
       "\"removed_children\": [{\"type\": \"aws_route\", \"id\": \"aws_route.rt_a_igw\"}]},"
       "{\"type\": \"aws_instance\", \"id\": \"aws_instance.web\"}"
       "]}"))

(deftest trigger-drift-check-parses-a-response-with-new-and-removed-children-present
  (with-drift-endpoint 200 drift-response-with-children-body
    (fn [url]
      (let [{:keys [drifted]} (main/trigger-drift-check url)]
        (is (= 3 (count drifted)))
        (is (= [{"type" "aws_security_group_rule" "id" "aws_security_group_rule.discovered-sgr-1"}]
               (get (first drifted) "new_children")))
        (is (= [{"type" "aws_route" "id" "aws_route.rt_a_igw"}]
               (get (second drifted) "removed_children")))
        (is (not (contains? (nth drifted 2) "new_children")))
        (is (not (contains? (nth drifted 2) "removed_children")))))))

(deftest drift-check-with-new-and-removed-children-present-exits-nonzero-and-does-not-throw
  (with-drift-endpoint 200 drift-response-with-children-body
    (fn [url]
      (is (not= 0 (main/drift-check! url))))))
