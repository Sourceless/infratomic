(ns infratomic.state-backend.datomic
  "Facade over `datomic.client.api`, re-exporting the same operation names
  (`client`, `create-database`, `connect`, `db`, `with-db`, `q`, `pull`,
  `transact`, `with`, `history`, `as-of`) so every call site in `db.clj`,
  `query.clj`, `policy.clj`, `sync.clj`, and `handler.clj` stays textually
  unchanged (`(:require [infratomic.state-backend.datomic :as d])` instead
  of `(:require [datomic.client.api :as d])`, call sites untouched)
  regardless of `INFRATOMIC_DATOMIC_MODE`.

  Every operation is a multimethod dispatching on the concrete type of the
  client/conn/db value it's given:

  - `client` dispatches on `(:mode config)` (`:embedded` or `:gateway`,
    defaulting to `:embedded`) - the one operation with no pre-existing
    client/conn/db value to dispatch on, since its whole job is to produce
    one.
  - Every other operation dispatches on the type of its client/conn/db
    argument (`q`'s dispatch looks at its first *input*, since `q` is
    variadic: `(q query & inputs)`, and every call site in this codebase
    passes the db as the first input). A `Gateway*` value (see below)
    dispatches to the Dev-Local Gateway HTTP path; anything else - a real
    `datomic.client.api` client/conn/db value, indistinguishable from any
    other unrecognized type - falls through to the `:default` method, which
    delegates straight to the real `datomic.client.api` function with the
    exact same arguments. This is the embedded path's \"zero behavior
    change from today\": there is no `EmbeddedClient`-wrapped value flowing
    through call sites at all after construction - see `client`'s
    `:embedded` method and `infratomic.state-backend.db/client`, which
    unwraps the constructed `EmbeddedClient` back to the raw real client
    immediately, so every value downstream of it (conn, db, historical db,
    query results) is a plain real `datomic.client.api` value, exactly as
    it is today. This matters beyond this facade: `db.clj`/`query.clj`/
    `policy.clj`/`sync.clj`/`handler.clj` are the only namespaces updated to
    require this facade instead of `datomic.client.api` directly - every
    test namespace still requires `datomic.client.api` directly and calls
    it on the value `db/client :mem` returns, which only keeps working
    because that value is the raw real client, never a wrapper.

  `GatewayClient`/`GatewayConn`/`GatewayDb` (the gateway-mode types) wrap
  only the Dev-Local Gateway's base URL plus (for `GatewayConn`/`GatewayDb`)
  an opaque handle string returned by the gateway for a previously
  registered conn/db value - see
  `openspec/changes/issue-35-ship-infratomic-deployable-container-real-
  datomic/design.md`'s \"Dev-Local Gateway: opaque handles over HTTP+EDN\"
  decision for the wire protocol this implements against."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as real])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Client/conn/db value types
;; ---------------------------------------------------------------------------

(defrecord EmbeddedClient [client])
(defrecord GatewayClient [base-url])
(defrecord GatewayConn [base-url handle])
(defrecord GatewayDb [base-url handle])

;; ---------------------------------------------------------------------------
;; Dev-Local Gateway wire protocol (client side)
;; ---------------------------------------------------------------------------

(defn- edn-post!
  "POST `body` (a Clojure value) as EDN to `(str base-url path)`, and
  EDN-decode a successful (2xx) response body. Throws on a non-2xx response
  - there is no other operation-specific error handling here, matching
  every other HTTP client in this codebase (see `cli/main.clj`'s
  `post-json`/`get-json`)."
  [base-url path body]
  (let [http-client (HttpClient/newHttpClient)
        request     (-> (HttpRequest/newBuilder (URI/create (str base-url path)))
                         (.header "Content-Type" "application/edn")
                         (.POST (HttpRequest$BodyPublishers/ofString (pr-str body)))
                         .build)
        response    (.send http-client request (HttpResponse$BodyHandlers/ofString))
        status      (.statusCode response)]
    (if (<= 200 status 299)
      (when (seq (.body response)) (edn/read-string (.body response)))
      (throw (ex-info (str "Dev-Local Gateway request to " path " failed: HTTP " status)
                       {:status status :body (.body response)})))))

(defn- handle-wrapper
  "The wire representation of a `GatewayConn`/`GatewayDb` value: its opaque
  handle, wrapped so the Dev-Local Gateway can distinguish \"a handle
  reference\" from an ordinary EDN value at any argument position (see
  `q`)."
  [x]
  {:gateway/handle (:handle x)})

;; ---------------------------------------------------------------------------
;; client
;; ---------------------------------------------------------------------------

(defmulti client
  "Build a client. `config` is a `datomic.client.api/client` config map,
  plus an optional `:mode` key (`:embedded`, the default, or `:gateway`).
  `:gateway` mode additionally requires a `:base-url` key (the Dev-Local
  Gateway's base URL) instead of the usual `:server-type`/`:storage-dir`/
  `:system` keys."
  (fn [config] (:mode config :embedded)))

(defmethod client :embedded
  [config]
  (->EmbeddedClient (real/client (dissoc config :mode))))

(defmethod client :gateway
  [config]
  (->GatewayClient (:base-url config)))

;; ---------------------------------------------------------------------------
;; create-database / connect
;; ---------------------------------------------------------------------------

(defmulti create-database (fn [client _db-map] (type client)))

(defmethod create-database EmbeddedClient
  [c db-map]
  (real/create-database (:client c) db-map))

(defmethod create-database GatewayClient
  [c db-map]
  (edn-post! (:base-url c) "/create-database" db-map)
  true)

(defmethod create-database :default
  [client db-map]
  (real/create-database client db-map))

(defmulti connect (fn [client _db-map] (type client)))

(defmethod connect EmbeddedClient
  [c db-map]
  (real/connect (:client c) db-map))

(defmethod connect GatewayClient
  [c db-map]
  (let [{:keys [conn]} (edn-post! (:base-url c) "/connect" db-map)]
    (->GatewayConn (:base-url c) (:gateway/handle conn))))

(defmethod connect :default
  [client db-map]
  (real/connect client db-map))

;; ---------------------------------------------------------------------------
;; db / with-db
;; ---------------------------------------------------------------------------

(defmulti db type)

(defmethod db GatewayConn
  [conn]
  (let [{:keys [db]} (edn-post! (:base-url conn) "/db" {:conn (handle-wrapper conn)})]
    (->GatewayDb (:base-url conn) (:gateway/handle db))))

(defmethod db :default
  [conn]
  (real/db conn))

(defmulti with-db type)

(defmethod with-db GatewayConn
  [conn]
  (let [{:keys [db]} (edn-post! (:base-url conn) "/with-db" {:conn (handle-wrapper conn)})]
    (->GatewayDb (:base-url conn) (:gateway/handle db))))

(defmethod with-db :default
  [conn]
  (real/with-db conn))

;; ---------------------------------------------------------------------------
;; pull
;; ---------------------------------------------------------------------------

(defmulti pull (fn [db _pattern _eid] (type db)))

(defmethod pull GatewayDb
  [db pattern eid]
  (:result (edn-post! (:base-url db) "/pull" {:db (handle-wrapper db) :pattern pattern :eid eid})))

(defmethod pull :default
  [db pattern eid]
  (real/pull db pattern eid))

;; ---------------------------------------------------------------------------
;; q
;; ---------------------------------------------------------------------------

(defmulti q
  "`(q query & inputs)`, mirroring `datomic.client.api/q`. Every call site
  in this codebase passes its db (or historical/speculative db, itself a
  `Gateway*` value in gateway mode) as the first input, so dispatch looks
  only at `(first inputs)`."
  (fn [_query & inputs] (type (first inputs))))

(defmethod q GatewayDb
  [query & inputs]
  (let [db         (first inputs)
        wire-args  (into [(handle-wrapper db)] (rest inputs))]
    (:result (edn-post! (:base-url db) "/q" {:query query :args wire-args}))))

(defmethod q :default
  [query & inputs]
  (apply real/q query inputs))

;; ---------------------------------------------------------------------------
;; transact
;; ---------------------------------------------------------------------------

(defmulti transact (fn [conn _tx-map] (type conn)))

(defmethod transact GatewayConn
  [conn tx-map]
  (let [resp (edn-post! (:base-url conn) "/transact" {:conn (handle-wrapper conn) :tx-data (:tx-data tx-map)})]
    (cond-> {}
      (:db-before resp) (assoc :db-before (->GatewayDb (:base-url conn) (:gateway/handle (:db-before resp))))
      (:db-after resp)  (assoc :db-after (->GatewayDb (:base-url conn) (:gateway/handle (:db-after resp)))))))

(defmethod transact :default
  [conn tx-map]
  (real/transact conn tx-map))

;; ---------------------------------------------------------------------------
;; with
;; ---------------------------------------------------------------------------

(defmulti with (fn [db _tx-map] (type db)))

(defmethod with GatewayDb
  [db tx-map]
  (let [resp (edn-post! (:base-url db) "/with" {:db (handle-wrapper db) :tx-data (:tx-data tx-map)})]
    {:db-before (->GatewayDb (:base-url db) (:gateway/handle (:db-before resp)))
     :db-after  (->GatewayDb (:base-url db) (:gateway/handle (:db-after resp)))}))

(defmethod with :default
  [db tx-map]
  (real/with db tx-map))

;; ---------------------------------------------------------------------------
;; history / as-of
;; ---------------------------------------------------------------------------

(defmulti history type)

(defmethod history GatewayDb
  [db]
  (let [{new-db :db} (edn-post! (:base-url db) "/history" {:db (handle-wrapper db)})]
    (->GatewayDb (:base-url db) (:gateway/handle new-db))))

(defmethod history :default
  [db]
  (real/history db))

(defmulti as-of (fn [db _t] (type db)))

(defmethod as-of GatewayDb
  [db t]
  (let [{new-db :db} (edn-post! (:base-url db) "/as-of" {:db (handle-wrapper db) :t t})]
    (->GatewayDb (:base-url db) (:gateway/handle new-db))))

(defmethod as-of :default
  [db t]
  (real/as-of db t))
