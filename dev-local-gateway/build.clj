(ns build
  "`tools.build` build script for the Dev-Local Gateway's uberjar (used by
  `Dockerfile`'s image build stage: `clojure -T:build uber`) - mirrors
  `state-backend/build.clj`."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/dev-local-gateway.jar")
(def ^:private main-ns 'infratomic.dev-local-gateway.main)

(defn uber
  [_]
  (b/delete {:path "target"})
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis     basis
                     :src-dirs  ["src"]
                     :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns})))
