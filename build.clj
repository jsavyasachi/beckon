(ns build
  "Build + Clojars deploy for beckon (tools.build + deps-deploy).

   Usage:
     clojure -T:build compile-java   ; javac src/java -> target/classes (needed before tests)
     clojure -T:build jar
     clojure -T:build deploy         ; needs CLOJARS_USERNAME / CLOJARS_PASSWORD"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.savya/beckon)
(def version "0.4.1")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path "pom.xml"}))   ; drop stale lein-generated pom so :pom-data wins

(defn compile-java
  "Compile the ragel-generated Java parsers under src/java into target/classes.
   Run before `clojure -M:test` (the :test alias puts target/classes on the classpath)."
  [_]
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["-target" "8" "-source" "8" "-Xlint:-options" "-XDignore.symbol.file"]}))

(defn jar [_]
  (clean nil)
  (compile-java nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/clojure"]
                :scm {:url "https://github.com/jsavyasachi/beckon"
                      :connection "scm:git:https://github.com/jsavyasachi/beckon.git"
                      :developerConnection "scm:git:ssh://git@github.com/jsavyasachi/beckon.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Manage POSIX signals from Clojure via a small sun.misc.Signal backend."]
                           [:url "https://github.com/jsavyasachi/beckon"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 1.0"]
                             [:url "https://www.eclipse.org/legal/epl-v10.html"]
                             [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs ["src/clojure"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Wrote" jar-file))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
