(ns infratomic.dev-local-gateway.main
  "The Dev-Local Gateway: a separately-run process wrapping a real
  `com.datomic/local` (dev-local) database behind an HTTP+EDN wire protocol
  mirroring `datomic.client.api`'s conceptual shape (opaque client/conn/db
  handles, the same request/response cycle), so the State Backend can be
  genuinely network-separated from its database without the licensed,
  credential-gated `com.datomic/client-pro` (see
  `openspec/changes/issue-35-ship-infratomic-deployable-container-real-
  datomic/design.md`'s \"Dev-Local Gateway: opaque handles over HTTP+EDN\"
  decision, which this namespace implements the server side of - the
  client side is `infratomic.state-backend.datomic`'s `Gateway*` types).

  Depends only on `com.datomic/local` - no `com.datomic/client-pro`, no
  my.datomic.com credential, anywhere in this namespace's build or
  runtime.

  Holds every client/conn/db value it hands out in an in-memory,
  atom-backed session registry (`registry`), keyed by opaque random-UUID
  string handles - there is no expiry or persistence of the registry
  itself (only the underlying dev-local storage persists); acceptable for
  this issue's demo/try-it scope (see design.md's Risks/Trade-offs).

  One HTTP endpoint per client-api operation the State Backend's four
  db-touching namespaces (`db.clj`/`query.clj`/`policy.clj`/`sync.clj`)
  actually call: `create-database`, `connect`, `db`, `with-db`, `pull`,
  `q`, `transact`, `with`, `history`, `as-of`. Every request/response body
  is EDN (`Content-Type: application/edn`). A request argument standing in
  for a client/conn/db value is `{:gateway/handle \"...\"}`; a response
  substitutes the same shape for any client-api return value that is
  itself a client/conn/db value."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn- storage-dir
  "The dev-local storage directory this Dev-Local Gateway instance persists
  to - configurable via `INFRATOMIC_GATEWAY_STORAGE_DIR`, defaulting to
  `.datomic-gateway/` at the repo root. Always independent of the State
  Backend's own embedded-mode storage directory (`db/storage-dir`,
  `.datomic/`) - a Dev-Local Gateway and an embedded-mode State Backend
  never share a storage directory, even when run from the same checkout."
  []
  (or (System/getenv "INFRATOMIC_GATEWAY_STORAGE_DIR")
      (str (System/getProperty "user.dir") "/../.datomic-gateway")))

(defn- port
  []
  (parse-long (or (System/getenv "INFRATOMIC_GATEWAY_PORT") "8081")))

(defn- host
  []
  (or (System/getenv "INFRATOMIC_GATEWAY_HOST") "0.0.0.0"))

(defn- build-client
  []
  (d/client {:server-type :datomic-local
             :system      "infratomic-gateway"
             :storage-dir (storage-dir)}))

;; ---------------------------------------------------------------------------
;; Opaque-handle session registry
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Atom-backed session registry: opaque random-UUID string
  handle -> the real client/conn/db value it names. In-memory only, no
  expiry - see this namespace's docstring."}
  registry
  (atom {}))

(defn- register!
  [obj]
  (let [handle (str (random-uuid))]
    (swap! registry assoc handle obj)
    handle))

(defn- handle-ref
  "Wrap `obj` (a client-api conn/db return value) as `{:gateway/handle
  ...}`, registering it under a fresh handle first."
  [obj]
  {:gateway/handle (register! obj)})

(defn- resolve-ref
  "Resolve a `{:gateway/handle ...}` wire value back to the real,
  previously-registered client/conn/db object it names. Throws
  `ex-info` on an unknown handle, caught by `app-handler` and turned into
  a `400`."
  [{:gateway/keys [handle] :as wrapper}]
  (if-let [obj (find @registry handle)]
    (val obj)
    (throw (ex-info (str "Unknown Dev-Local Gateway handle") {:wrapper wrapper}))))

(defn- resolve-arg
  "Resolve one `/q` input argument: a `{:gateway/handle ...}` map becomes
  the real object it names; any other EDN value (a rule-defs vector, a
  scalar) passes through unchanged."
  [arg]
  (if (and (map? arg) (contains? arg :gateway/handle))
    (resolve-ref arg)
    arg))

;; ---------------------------------------------------------------------------
;; Operation handlers
;; ---------------------------------------------------------------------------

(defn- edn-response
  ([body] (edn-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "application/edn"}
    :body    (pr-str body)}))

(defn- handle-create-database
  [client body]
  (edn-response (d/create-database client body)))

(defn- handle-connect
  [client body]
  (edn-response {:conn (handle-ref (d/connect client body))}))

(defn- handle-db
  [{:keys [conn]}]
  (edn-response {:db (handle-ref (d/db (resolve-ref conn)))}))

(defn- handle-with-db
  [{:keys [conn]}]
  (edn-response {:db (handle-ref (d/with-db (resolve-ref conn)))}))

(defn- handle-pull
  [{:keys [db pattern eid]}]
  (edn-response {:result (d/pull (resolve-ref db) pattern eid)}))

(defn- handle-q
  [{:keys [query args]}]
  (edn-response {:result (apply d/q query (mapv resolve-arg args))}))

(defn- handle-transact
  [{:keys [conn tx-data]}]
  (let [{:keys [db-before db-after]} (d/transact (resolve-ref conn) {:tx-data tx-data})]
    (edn-response (cond-> {}
                    db-before (assoc :db-before (handle-ref db-before))
                    db-after  (assoc :db-after (handle-ref db-after))))))

(defn- handle-with
  [{:keys [db tx-data]}]
  (let [{:keys [db-before db-after]} (d/with (resolve-ref db) {:tx-data tx-data})]
    (edn-response {:db-before (handle-ref db-before)
                   :db-after  (handle-ref db-after)})))

(defn- handle-history
  [{:keys [db]}]
  (edn-response {:db (handle-ref (d/history (resolve-ref db)))}))

(defn- handle-as-of
  [{:keys [db t]}]
  (edn-response {:db (handle-ref (d/as-of (resolve-ref db) t))}))

;; ---------------------------------------------------------------------------
;; HTTP routing
;; ---------------------------------------------------------------------------

(defn- parse-body
  [body]
  (when body
    (let [s (slurp body)]
      (when (seq s)
        (edn/read-string s)))))

(defn app-handler
  "Build the Dev-Local Gateway's Ring handler, closing over `client` (the
  one real dev-local client this process holds - see `build-client`).
  Every route is `POST`-only; any other method (or an unknown route) gets
  an explicit `405`/`404`. A malformed request body, an unknown handle, or
  any other exception from the underlying `datomic.client.api` call is
  caught here and turned into a `400` with the exception message, rather
  than crashing the process or leaking a bare `500`. Public (rather than
  `defn-`) so it's directly testable."
  [client]
  (fn [{:keys [request-method uri body]}]
    (if (not= request-method :post)
      {:status 405 :headers {"Allow" "POST"} :body ""}
      (try
        (let [parsed (parse-body body)]
          (case uri
            "/create-database" (handle-create-database client parsed)
            "/connect"         (handle-connect client parsed)
            "/db"              (handle-db parsed)
            "/with-db"         (handle-with-db parsed)
            "/pull"            (handle-pull parsed)
            "/q"               (handle-q parsed)
            "/transact"        (handle-transact parsed)
            "/with"            (handle-with parsed)
            "/history"         (handle-history parsed)
            "/as-of"           (handle-as-of parsed)
            {:status 404 :headers {} :body ""}))
        (catch Exception e
          (edn-response 400 {:error (or (ex-message e) (str e))}))))))

(defn -main
  [& _args]
  (let [client (build-client)]
    (println (str "Dev-Local Gateway listening on port " (port)
                   " (storage-dir " (storage-dir) ")"))
    (jetty/run-jetty (app-handler client) {:port (port) :host (host) :join? true})))
