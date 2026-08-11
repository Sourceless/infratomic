(ns infratomic.state-backend.main
  "Entry point for the State Backend. Opens the dev-local client, ensures the
  database and schema exist, and starts the Jetty server."
  (:require [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.policy :as policy]
            [infratomic.state-backend.query :as query]
            [infratomic.state-backend.sync :as sync]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(def port 8080)

(defn app-handler
  "The full Ring handler: `handler.clj`'s own `/state` dispatch (untouched),
  plus the `POST /policy-check` (`policy.clj`), `POST /sync` (`sync.clj`),
  and `GET /drift` (`query.clj`) routes layered in front of it, all
  closing over the one dev-local `conn` this process holds (`/sync` also
  closes over `ec2-client`, an EC2 client built once at process start and
  reused across every Sync invocation - an implementation choice, not a
  correctness one, see design.md). Any other method on
  `/policy-check`/`/sync`/`/drift` gets an explicit `405` rather than
  falling through to `state-handler` (which knows nothing about these
  routes and would 404 them). Public (rather than `defn-`) so it's
  directly testable, mirroring `handler/handler`."
  [conn ec2-client]
  (let [state-handler (handler/handler conn)]
    (fn [{:keys [request-method uri body] :as request}]
      (cond
        (and (= uri "/policy-check") (= request-method :post))
        (policy/policy-check conn (slurp body))

        (= uri "/policy-check")
        {:status 405 :headers {"Allow" "POST"} :body ""}

        (and (= uri "/sync") (= request-method :post))
        (sync/sync-endpoint conn ec2-client)

        (= uri "/sync")
        {:status 405 :headers {"Allow" "POST"} :body ""}

        (and (= uri "/drift") (= request-method :get))
        (query/drift-endpoint conn)

        (= uri "/drift")
        {:status 405 :headers {"Allow" "GET"} :body ""}

        :else
        (state-handler request)))))

(defn -main
  [& _args]
  (let [conn (db/ensure-db! (db/client))
        ec2-client (sync/ec2-client)]
    (println (str "State Backend listening on port " port))
    (jetty/run-jetty (app-handler conn ec2-client) {:port port :join? true})))
