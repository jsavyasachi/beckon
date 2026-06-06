(defproject net.clojars.savya/beckon "0.3.0"
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
  :javac-options ["-target" "8" "-source" "8" "-Xlint:-options" "-XDignore.symbol.file"]
  ;; Experimental FFM signalfd backend (JDK 22+, Linux only). Kept OUT of the
  ;; default build and the published jar: src/java-ffm is added only by the :ffm
  ;; profile, which compiles at target 22, selects the backend at runtime, and
  ;; enables native access. Exercised on CI (Linux/JDK 25) only.
  :profiles {:ffm {:java-source-paths ^:replace ["src/java" "src/java-ffm"]
                   :javac-options ^:replace ["-source" "22" "-target" "22"
                                             "-XDignore.symbol.file"]
                   :jvm-opts ["-Dbeckon.signal.backend=ffm"
                              "--enable-native-access=ALL-UNNAMED"]}})
