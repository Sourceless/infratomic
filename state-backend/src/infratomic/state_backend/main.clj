(ns infratomic.state-backend.main
  "Entry point for the State Backend. Opens the dev-local client, ensures the
  database and schema exist, and starts the Jetty server."
  (:require [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [infratomic.state-backend.policy :as policy]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(def port 8080)

(defn- app-handler
  "The full Ring handler: `handler.clj`'s own `/state` dispatch (untouched),
  plus the new `POST /policy-check` route (`policy.clj`) layered in front of
  it, both closing over the one dev-local `conn` this process holds."
  [conn]
  (let [state-handler (handler/handler conn)]
    (fn [{:keys [request-method uri body] :as request}]
      (if (and (= uri "/policy-check") (= request-method :post))
        (policy/policy-check conn (slurp body))
        (state-handler request)))))

(defn -main
  [& _args]
  (let [conn (db/ensure-db! (db/client))]
    (println (str "State Backend listening on port " port))
    (jetty/run-jetty (app-handler conn) {:port port :join? true})))
