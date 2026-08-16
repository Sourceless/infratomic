(ns build
  "`tools.build` build script for the State Backend's uberjar (used by
  `Dockerfile`'s image build stage: `clojure -T:build uber`). Not part of
  the application's own `deps.edn` `:deps` (only the `:build` alias) - it
  never ships inside the uberjar itself."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/state-backend.jar")
(def ^:private main-ns 'infratomic.state-backend.main)

(defn uber
  "Build `target/state-backend.jar`: an AOT-compiled, dependency-inlined
  uberjar with `main-ns` (`infratomic.state-backend.main`, which
  `:gen-class`es) as its manifest main class, so `java -jar
  target/state-backend.jar [bootstrap]` runs it directly - see
  `main.clj`'s `-main` arg-dispatch."
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
