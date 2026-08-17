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

(defn- sync-interval-seconds
  "`INFRATOMIC_SYNC_INTERVAL_SECONDS` (seconds, default `300`) - how often
  the scheduler (`sync/schedule!`) automatically triggers Sync in the
  normal-startup path, read once at startup, following the existing
  `INFRATOMIC_<NAME>` convention (`db.clj`'s `gateway-mode?`/`gateway-url`)."
  []
  (Long/parseLong (or (System/getenv "INFRATOMIC_SYNC_INTERVAL_SECONDS") "300")))

(defn app-handler
  "The full Ring handler: `handler.clj`'s own `/state` dispatch (untouched),
  plus the `POST /policy-check` (`policy.clj`), `POST /sync` (`sync.clj`),
  `GET /drift` (`query.clj`), `POST /query` (`query.clj`'s `ad-hoc-query`),
  and `POST /rules` (`policy.clj`'s `register-rule-endpoint`) routes
  layered in front of it, all closing over the one dev-local `conn` this
  process holds (`/sync` also closes over `ec2-client`, an EC2 client
  built once at process start and reused across every Sync invocation -
  an implementation choice, not a correctness one, see design.md). Any
  other method on `/policy-check`/`/sync`/`/drift`/`/query`/`/rules` gets
  an explicit `405` rather than falling through to `state-handler` (which
  knows nothing about these routes and would 404 them). Public (rather
  than `defn-`) so it's directly testable, mirroring `handler/handler`."
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

        (and (= uri "/query") (= request-method :post))
        (query/ad-hoc-query conn (slurp body))

        (= uri "/query")
        {:status 405 :headers {"Allow" "POST"} :body ""}

        (and (= uri "/rules") (= request-method :post))
        (policy/register-rule-endpoint (slurp body))

        (= uri "/rules")
        {:status 405 :headers {"Allow" "POST"} :body ""}

        :else
        (state-handler request)))))

(defn -main
  "With no args, the normal startup path: connect (implicitly bootstrapping
  schema via `db/ensure-db!`, as always), start the automatic Sync
  scheduler (`sync/schedule!`, issue #31 - runs `sync/sync!` on a fixed
  `INFRATOMIC_SYNC_INTERVAL_SECONDS`-configured delay, in addition to the
  existing on-demand `POST /sync` trigger), and start Jetty. With
  `bootstrap` as the sole arg, run the same `db/ensure-db!` call and exit
  `0` without starting Jetty or the scheduler - useful for scripted/CI
  setup against a fresh database (e.g. a Dev-Local Gateway db in `gateway`
  mode) before the server itself needs to be up. Both paths call the exact
  same `db/ensure-db!` - there is only ever one bootstrap implementation."
  [& args]
  (if (= "bootstrap" (first args))
    (do
      (db/ensure-db! (db/client))
      (println "Bootstrap complete.")
      (System/exit 0))
    (let [conn (db/ensure-db! (db/client))
          ec2-client (sync/ec2-client)]
      (println (str "State Backend listening on port " port))
      (sync/schedule! (sync-interval-seconds)
                       (sync/wrap-failure-isolated (fn [] (sync/sync! conn ec2-client))))
      (jetty/run-jetty (app-handler conn ec2-client) {:port port :join? true}))))
