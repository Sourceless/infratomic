(ns infratomic.datomic
  "Local, file-backed Datomic database setup for development and tests.

  Storage is repo-local (not `~/.datomic/local.edn`) so that `clone + run`
  works with no manual per-machine setup. The storage directory
  (`.datomic/storage`, resolved relative to the process's working
  directory - the repo root when run via `clj -M:test`) and system name
  (`dev`) are defined below and are gitignored so contributors can freely
  delete them to reset local state."
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]))

(def storage-dir
  "Repo-local directory where dev-local persists database files, as an
  absolute path (dev-local requires an absolute path or `:mem`).
  Gitignored - safe to delete to reset all local Datomic state."
  (.getAbsolutePath (io/file ".datomic/storage")))

(def system
  "Fixed dev-local system name used for this repo's local database(s)."
  "dev")

(defn client-config
  "Builds the `datomic.client.api/client` config for the repo-local
  dev-local storage directory and system name."
  []
  {:server-type :datomic-local
   :storage-dir storage-dir
   :system system})

(defn client
  "Returns a Datomic client for the repo-local dev-local system."
  []
  (d/client (client-config)))

(defn connect
  "Ensures a local database named `db-name` exists (idempotent) and
  returns a connection to it."
  [db-name]
  (let [c (client)]
    (d/create-database c {:db-name db-name})
    (d/connect c {:db-name db-name})))
