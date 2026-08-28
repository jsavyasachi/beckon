(ns beckon-test
  (:require [clojure.test :refer :all]
            [beckon :as beckon])
  (:import (com.hypirion.beckon SignalFolder SignalRegistererHelper)
           (sun.misc Signal SignalHandler)
           (java.io BufferedReader InputStreamReader)
           (java.util.concurrent ArrayBlockingQueue CountDownLatch Executors
                                  ThreadPoolExecutor TimeUnit)))

(def ^:private subprocess-timeout-ms 5000)
(def ^:private posix-host?
  (not (re-find #"(?i)windows" (System/getProperty "os.name"))))

(defn- child-handler
  [label]
  (fn []
    (println label)
    (flush)
    (Thread/sleep 500)
    (System/exit 0)))

(defn- run-child-mode
  [mode]
  (case mode
    "delivery"
    (do
      (reset! (beckon/signal-atom "USR2") [(child-handler "received")])
      (println "ready")
      (flush)
      (Thread/sleep 10000))

    "restore"
    (do
      (reset! (beckon/signal-atom "USR2") [(child-handler "received")])
      (beckon/reinit! "USR2")
      (println "restored")
      (flush)
      (Thread/sleep 10000))

    "reserved"
    (try
      (beckon/signal-atom "KILL")
      (println "reserved-accepted")
      (flush)
      (System/exit 1)
      (catch Throwable _
        (println "reserved-rejected")
        (flush)))

    "repeated-reset"
    (do
      (let [atom (beckon/signal-atom "USR2")]
        (reset! atom [(child-handler "received")])
        (beckon/reinit! "USR2")
        (beckon/reinit! "USR2")
        (beckon/reinit-all!)
        (beckon/reinit-all!)
        (reset! atom [(child-handler "received")]))
      (println "ready")
      (flush)
      (Thread/sleep 10000))

    (throw (ex-info "unknown child mode" {:mode mode}))))

(defn -main
  [& args]
  (when (= "--child" (first args))
    (run-child-mode (second args))))

(defn- bounded-read-line
  [^BufferedReader reader]
  (let [read-line (future (.readLine reader))
        result (deref read-line subprocess-timeout-ms ::read-timeout)]
    (when (= ::read-timeout result)
      (future-cancel read-line))
    result))

(defn- bounded-read-rest
  [^BufferedReader reader]
  (let [read-rest (future (slurp reader))
        result (deref read-rest subprocess-timeout-ms ::read-timeout)]
    (when (= ::read-timeout result)
      (future-cancel read-rest))
    result))

(defn- child-process
  [mode]
  (let [java (str (System/getProperty "java.home") java.io.File/separator
                  "bin" java.io.File/separator "java")
        command [java "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-m" "beckon-test" "--child" mode]
        builder (.redirectErrorStream (ProcessBuilder. ^java.util.List command) true)
        process (.start builder)
        reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
        first-line (bounded-read-line reader)]
    {:process process
     :reader reader
     :first-line first-line}))

(defn- finish-child
  [{:keys [^Process process ^BufferedReader reader first-line]}]
  (let [rest (if (= ::read-timeout first-line)
               ::read-timeout
               (bounded-read-rest reader))
        exited? (.waitFor process subprocess-timeout-ms TimeUnit/MILLISECONDS)]
    (when-not exited?
      (.destroyForcibly process)
      (.waitFor process subprocess-timeout-ms TimeUnit/MILLISECONDS))
    {:exit (when exited? (.exitValue process))
     :output (str first-line "\n" rest)
     :timed-out (or (= ::read-timeout first-line)
                    (= ::read-timeout rest)
                    (not exited?))}))

(defn- send-signal!
  [^Process process signal]
  (let [kill (.start (ProcessBuilder. ^java.util.List
                                      ["kill" (str "-" signal)
                                       (str (.pid process))]))]
    (is (.waitFor kill subprocess-timeout-ms TimeUnit/MILLISECONDS)
        (str "timed out waiting for kill -" signal))
    (is (= 0 (.exitValue kill)) (str "kill -" signal " failed"))))

(defn- signal-child!
  [mode]
  (let [child (child-process mode)]
    (is (= (if (= mode "restore") "restored" "ready") (:first-line child))
        (str "child did not become ready: " child))
    (send-signal! (:process child) (if (= mode "restore") "TERM" "USR2"))
    (finish-child child)))

(defn- reserved-child!
  []
  (let [child (child-process "reserved")]
    (finish-child child)))

(deftest subprocess-delivers-actual-signal
  (testing "an OS-delivered signal reaches a beckon handler"
    (if-not posix-host?
      (is true "skipped: OS does not provide POSIX signals")
      (let [{:keys [exit output timed-out]} (signal-child! "delivery")]
        (is (not timed-out) (str "child timed out; output: " output))
        (is (= 0 exit) (str "child output: " output))
        (is (re-find #"received" output) (str "child output: " output))))))

(deftest subprocess-restores-default-handler
  (testing "reinit restores the default disposition in a fresh process"
    (if-not posix-host?
      (is true "skipped: OS does not provide POSIX signals")
      (let [{:keys [exit output timed-out]} (signal-child! "restore")]
        (is (not timed-out) (str "child timed out; output: " output))
        (is (not= 0 exit) (str "default signal handler did not terminate child: " output))
        (is (not (re-find #"received" output)) (str "child output: " output))))))

(deftest subprocess-rejects-reserved-signal
  (testing "a signal reserved by the OS cannot be claimed"
    (if-not posix-host?
      (is true "skipped: OS does not provide POSIX signals")
      (let [{:keys [exit output timed-out]} (reserved-child!)]
        (is (not timed-out) (str "child timed out; output: " output))
        (is (= 0 exit) (str "child output: " output))
        (is (re-find #"reserved-rejected" output) (str "child output: " output))))))

(deftest subprocess-survives-repeated-resets
  (testing "repeated reset operations preserve later signal registration"
    (if-not posix-host?
      (is true "skipped: OS does not provide POSIX signals")
      (let [{:keys [exit output timed-out]} (signal-child! "repeated-reset")]
        (is (not timed-out) (str "child timed out; output: " output))
        (is (= 0 exit) (str "child output: " output))
        (is (re-find #"received" output) (str "child output: " output))))))

;; Use SIGUSR2: its default disposition is to terminate the JVM. Every test
;; installs a beckon handler before it raises the signal, thus delivery runs our
;; code and does not stop the runner. Reset all beckon-owned handlers after each
;; test.
(use-fixtures :each (fn [run]
                      (try (run)
                           (finally
                             (beckon/set-dispatch-policy! :synchronous)
                             (beckon/set-callback-error-policy! :stop)
                             (beckon/reinit-all!)))))

(deftest synchronous-dispatch-is-the-default
  (is (= :synchronous (beckon/dispatch-policy))))

(deftest serial-dispatch-does-not-overlap-callbacks
  (let [executor (Executors/newFixedThreadPool 2)
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        max-running (atom 0)
        running (atom 0)
        callback (fn []
                   (swap! running inc)
                   (swap! max-running max @running)
                   (.countDown started)
                   (.await release 2 TimeUnit/SECONDS)
                   (swap! running dec))]
    (try
      (beckon/set-dispatch-policy! (beckon/serial-policy executor))
      (let [folder (SignalFolder. "USR2" [callback])]
        (future (.handle folder nil))
        (is (.await started 2 TimeUnit/SECONDS))
        (future (.handle folder nil))
        (Thread/sleep 100)
        (is (= 1 @max-running))
        (.countDown release)
        (Thread/sleep 100)
        (is (= 1 @max-running)))
      (finally
        (.shutdownNow executor)))))

(deftest parallel-dispatch-allows-same-signal-callbacks-to-overlap
  (let [executor (Executors/newFixedThreadPool 2)
        ready (CountDownLatch. 2)
        release (CountDownLatch. 1)
        callback (fn [] (.countDown ready) (.await release 2 TimeUnit/SECONDS))]
    (try
      (beckon/set-dispatch-policy! (beckon/parallel-policy executor))
      (.handle (SignalFolder. "USR2" [callback callback]) nil)
      (is (.await ready 2 TimeUnit/SECONDS))
      (.countDown release)
      (finally
        (.shutdownNow executor)))))

(deftest bounded-dispatch-drops-callbacks-rejected-by-a-full-queue
  (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                     (ArrayBlockingQueue. 1))
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        hits (atom 0)
        callback (fn [] (swap! hits inc)
                   (when (= 1 @hits)
                     (.countDown started)
                     (.await release 2 TimeUnit/SECONDS)))]
    (try
      (beckon/set-dispatch-policy! (beckon/bounded-policy executor))
      (let [folder (SignalFolder. "USR2" [callback])]
        (.handle folder nil)
        (is (.await started 2 TimeUnit/SECONDS))
        (.handle folder nil)
        (.handle folder nil)
        (.countDown release)
        (Thread/sleep 100)
        (is (= 2 @hits)))
      (finally
        (.shutdownNow executor)))))

(deftest callback-error-policies-are-explicit
  (let [policy-var (ns-resolve 'beckon 'callback-error-policy)
        set-policy-var (ns-resolve 'beckon 'set-callback-error-policy!)]
    (is (some? policy-var))
    (is (some? set-policy-var))
    (when (and policy-var set-policy-var)
      (let [errors (atom [])
            ran (atom 0)]
        (@set-policy-var (@policy-var :continue
                                      :on-error #(swap! errors conj %)))
        (let [folder (SignalFolder. "USR2"
                                    [(fn [] (throw (Exception. "boom")))
                                     #(swap! ran inc)])]
          (.handle folder nil))
        (is (= 1 @ran))
        (is (= "boom" (.getMessage (first @errors)))))
      (let [ran (atom 0)]
        (@set-policy-var :rethrow)
        (is (thrown-with-msg? Exception #"boom"
                              (.handle (SignalFolder. "USR2"
                                                       [(fn [] (throw (Exception. "boom")))
                                                        #(swap! ran inc)])
                                       nil)))
        (is (= 0 @ran))))))

(deftest callback-errors-can-be-collected
  (let [policy-var (ns-resolve 'beckon 'callback-error-policy)
        set-policy-var (ns-resolve 'beckon 'set-callback-error-policy!)]
    (is (some? policy-var))
    (is (some? set-policy-var))
    (when (and policy-var set-policy-var)
      (let [errors (atom [])
            ran (atom 0)]
        (@set-policy-var (@policy-var :collect :collector errors))
        (.handle (SignalFolder. "USR2"
                                [(fn [] (throw (Exception. "collected")))
                                 #(swap! ran inc)])
                 nil)
        (is (= 1 @ran))
        (is (= "collected" (.getMessage (first @errors))))))))

(deftest signal-capability-api-normalizes-portable-names
  (let [normalize (ns-resolve 'beckon 'normalize-signal-name)
        supported (ns-resolve 'beckon 'supported-signals)
        signal-supported (ns-resolve 'beckon 'signal-supported?)]
    (is (some? normalize))
    (is (some? supported))
    (is (some? signal-supported))
    (when (and normalize supported signal-supported)
      (is (= "TERM" (@normalize "sigterm")))
      (is (@signal-supported "SIGTERM"))
      (is (contains? (@supported) "TERM"))
      (is (not (@signal-supported "NOT_A_SIGNAL")))
      (is (thrown-with-msg? IllegalArgumentException #"signal name"
                            (@normalize "SIG"))))))

;; This suite is the backend-agnostic behavioral spec. It runs without changes
;; against the backend that `-Dbeckon.signal.backend` selects (default sunmisc;
;; this repository's CI currently exercises sunmisc only).
(deftest backend-selection
  (testing "the backend that loaded matches the one requested"
    (let [active (SignalRegistererHelper/backendName)]
      (case (System/getProperty "beckon.signal.backend" "sunmisc")
        "ffm"     (is (contains? #{"FfmSignalfdBackend" "FfmKqueueBackend"} active))
        "sunmisc" (is (= "SunMiscSignalBackend" active))))))

(deftest signal-atom-identity
  (testing "the same signal name yields the identical atom"
    (is (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR2"))))
  (testing "different signals yield different atoms"
    ;; WINCH (terminal resize), not USR1: the JVM keeps SIGUSR1 for internal use
    ;; on some platforms (JDK 8 on Linux, for example). A handler on SIGUSR1
    ;; throws "Signal already used by VM or OS".
    (is (not (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "WINCH"))))))

(deftest signal-atom-holds-runnable-collection
  (testing "a signal atom dereferences to a Seqable collection"
    (is (seq? (seq @(beckon/signal-atom "USR2"))))))

(deftest handler-runs-on-raise
  (testing "beckon calls a handler in the atom when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR2") [(fn [] (deliver ran true))])
      (beckon/raise! "USR2")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "beckon calls every Runnable in the collection on a single raise"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR2") [bump bump bump])
      (beckon/raise! "USR2")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "a raise with no handler installed throws no exception"
    (reset! (beckon/signal-atom "USR2") [])
    (is (nil? (beckon/raise! "USR2")))))

(deftest nil-handler-set-is-rejected
  (testing "a signal atom cannot be reset to nil"
    (is (thrown? IllegalStateException
                 (reset! (beckon/signal-atom "USR2") nil)))))

(deftest clearing-handlers-removes-them-from-dispatch
  (testing "a handler installed before a clear is not called on later delivery"
    (let [hits (atom 0)
          first-run (promise)
          handler (fn []
                    (swap! hits inc)
                    (deliver first-run true))]
      (reset! (beckon/signal-atom "USR2") [handler])
      (beckon/raise! "USR2")
      (is (true? (deref first-run 2000 :timed-out)))
      (reset! (beckon/signal-atom "USR2") [])
      (beckon/raise! "USR2")
      (Thread/sleep 100)
      (is (= 1 @hits)))))

(deftest add-handler-registers-multiple-handlers
  (testing "handlers added independently all run on a raise"
    (let [hits (atom 0)
          done (java.util.concurrent.CountDownLatch. 3)]
      (beckon/clear-handlers! "USR2")
      (dotimes [_ 3]
        (beckon/add-handler! "USR2"
                             (fn [] (swap! hits inc) (.countDown done))))
      (beckon/raise! "USR2")
      (is (.await done 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest remove-handler-removes-only-the-selected-handler
  (testing "removing one handler leaves the other handlers active"
    (let [removed-hits (atom 0)
          remaining-hits (atom 0)
          done (java.util.concurrent.CountDownLatch. 1)
          removed (fn [] (swap! removed-hits inc))
          remaining (fn [] (swap! remaining-hits inc) (.countDown done))]
      (beckon/clear-handlers! "USR2")
      (beckon/add-handler! "USR2" removed)
      (beckon/add-handler! "USR2" remaining)
      (beckon/remove-handler! "USR2" removed)
      (beckon/raise! "USR2")
      (is (.await done 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 0 @removed-hits))
      (is (= 1 @remaining-hits)))))

(deftest clear-handlers-removes-all-composable-handlers
  (testing "clearing composable handlers leaves nothing to dispatch"
    (let [hits (atom 0)
          handler (fn [] (swap! hits inc))]
      (beckon/clear-handlers! "USR2")
      (beckon/add-handler! "USR2" handler)
      (beckon/clear-handlers! "USR2")
      (beckon/raise! "USR2")
      (Thread/sleep 100)
      (is (= 0 @hits)))))

(deftest concurrent-handler-registration-does-not-lose-updates
  (testing "concurrent additions atomically retain every handler"
    (let [handler-count 100
          _ (beckon/clear-handlers! "USR2")
          jobs (doall (repeatedly handler-count
                                   #(future (beckon/add-handler! "USR2" (fn [])))))]
      (doseq [job jobs] @job)
      (is (= handler-count (count @(beckon/signal-atom "USR2")))))))

(deftest current-handler-returns-the-pre-existing-handler
  (testing "reading a handler does not replace it"
    (let [sig (Signal. "WINCH")
          prior (reify SignalHandler (handle [_ _]))]
      (try
        (Signal/handle sig prior)
        (is (identical? prior (beckon/current-handler "WINCH")))
        (finally
          (Signal/handle sig prior))))))

(deftest dispositions-restore-the-exact-pre-beckon-handler
  (testing "default and ignored dispositions can be restored"
    (let [sig (Signal. "WINCH")
          prior (reify SignalHandler (handle [_ _]))]
      (try
        (Signal/handle sig prior)
        (beckon/default-handler! "WINCH")
        (beckon/restore-handler! "WINCH")
        (is (identical? prior (beckon/current-handler "WINCH")))
        (beckon/ignored-handler! "WINCH")
        (beckon/restore-handler! "WINCH")
        (is (identical? prior (beckon/current-handler "WINCH")))
        (finally
          (Signal/handle sig prior))))))

(deftest chained-handler-preserves-and-invokes-prior-handler
  (testing "a chained handler calls the supplied JVM handler"
    (let [sig (Signal. "WINCH")
          prior-hits (promise)
          prior (reify SignalHandler
                  (handle [_ _] (deliver prior-hits true))) ]
      (Signal/handle sig prior)
      (try
        (reset! (beckon/signal-atom "WINCH") [])
        (beckon/chain-handler! "WINCH" prior)
        (beckon/raise! "WINCH")
        (is (true? (deref prior-hits 2000 :timed-out)))
        (finally
          (beckon/restore-handler! "WINCH")))
      (is (identical? prior (beckon/current-handler "WINCH"))))))

(deftest reserved-hotspot-signal-cannot-use-process-wide-dispositions
  (testing "explicit default and ignored modes reject SIGUSR2"
    (is (thrown-with-msg? IllegalArgumentException #"reserved"
                          (beckon/default-handler! "USR2")))
    (is (thrown-with-msg? IllegalArgumentException #"reserved"
                          (beckon/ignored-handler! "USR2")))))
