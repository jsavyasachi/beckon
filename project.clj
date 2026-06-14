(defproject net.clojars.savya/beckon "0.4.1"
  :description "Handle POSIX signals in Clojure."
  :url "https://github.com/jsavyasachi/beckon"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.5"]]
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  ;; -XDignore.symbol.file silences javac's "internal proprietary API" warnings
  ;; for sun.misc.Signal/SignalHandler, which beckon deliberately wraps and which
  ;; have no public replacement. -Xlint:-options silences the source/target 8
  ;; "obsolete" notice. Both are intentional; do not remove.
  :javac-options ["-target" "8" "-source" "8" "-Xlint:-options" "-XDignore.symbol.file"])
