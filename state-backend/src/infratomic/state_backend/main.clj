(ns infratomic.state-backend.main
  "Entry point for the State Backend. Opens the dev-local client, ensures the
  database and schema exist, and starts the Jetty server."
  (:require [infratomic.state-backend.db :as db]
            [infratomic.state-backend.handler :as handler]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(def port 8080)

(defn -main
  [& _args]
  (let [conn (db/ensure-db! (db/client))]
    (println (str "State Backend listening on port " port))
    (jetty/run-jetty (handler/handler conn) {:port port :join? true})))
