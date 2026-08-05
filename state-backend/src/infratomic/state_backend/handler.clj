(ns infratomic.state-backend.handler
  "Ring handler implementing Terraform's `http` state backend protocol
  (GET/POST/DELETE on /state). LOCK/UNLOCK are out of scope."
  (:require [cheshire.core :as json]
            [datomic.client.api :as d]
            [infratomic.state-backend.db :as db]))

(defn- resource->tx
  "Build an upsert tx-map for one entry in the posted state's `resources[]`,
  referencing the new state-version via its tempid."
  [state-version-tempid resource]
  (let [type       (get resource "type")
        name       (get resource "name")
        attributes (-> resource (get "instances") first (get "attributes" {}))]
    {:resource/id            (str type "." name)
     :resource/type          type
     :resource/name          name
     :resource/attributes    (json/generate-string attributes)
     :resource/state-version state-version-tempid}))

(defn- post-tx-data
  "Build the single transaction for a POST: a new state-version entity
  holding the raw body verbatim, plus one upserted resource entity per
  `resources[]` entry. Missing `resources`/`serial`/`lineage` are handled
  permissively per the state backend's protocol."
  [raw-body parsed]
  (let [sv-tempid "new-state-version"
        resources (get parsed "resources" [])
        sv-tx     (cond-> {:db/id             sv-tempid
                            :state-version/raw raw-body}
                    (contains? parsed "serial")  (assoc :state-version/serial (get parsed "serial"))
                    (contains? parsed "lineage") (assoc :state-version/lineage (get parsed "lineage")))]
    (into [sv-tx] (map (partial resource->tx sv-tempid) resources))))

(defn- parse-json
  "Parse `s` as JSON, returning ::invalid instead of throwing on failure."
  [s]
  (try
    (json/parse-string s)
    (catch Exception _
      ::invalid)))

(defn get-state
  [conn]
  (let [db  (d/db conn)
        eid (db/latest-state-version-eid db)]
    (if eid
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (:state-version/raw (d/pull db [:state-version/raw] eid))}
      {:status 204 :headers {} :body ""})))

(defn post-state
  [conn raw-body]
  (let [parsed (parse-json raw-body)]
    (if (= parsed ::invalid)
      {:status  400
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error "invalid JSON"})}
      (do
        (d/transact conn {:tx-data (post-tx-data raw-body parsed)})
        {:status 200 :headers {} :body ""}))))

(defn delete-state
  [conn]
  (let [db               (d/db conn)
        state-version-eids (db/all-state-version-eids db)
        resource-eids    (db/all-resource-eids db)
        eids             (concat state-version-eids resource-eids)
        retractions      (mapv (fn [eid] [:db/retractEntity eid]) eids)]
    (when (seq retractions)
      (d/transact conn {:tx-data retractions}))
    {:status 200 :headers {} :body ""}))

(defn handler
  "Build the Ring handler for the State Backend, closing over `conn`."
  [conn]
  (fn [{:keys [request-method uri body]}]
    (if (= uri "/state")
      (case request-method
        :get    (get-state conn)
        :post   (post-state conn (slurp body))
        :delete (delete-state conn)
        {:status 405 :headers {} :body ""})
      {:status 404 :headers {} :body ""})))
