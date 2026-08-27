(ns beckon-test
  (:require [clojure.test :refer :all]
            [beckon :as beckon])
  (:import (com.hypirion.beckon SignalFolder SignalRegistererHelper)
           (sun.misc Signal SignalHandler)
           (java.util.concurrent ArrayBlockingQueue CountDownLatch Executors
                                  ThreadPoolExecutor TimeUnit)))

;; Use SIGUSR2: its default disposition is to terminate the JVM. Every test
;; installs a beckon handler before it raises the signal, thus delivery runs our
;; code and does not stop the runner. Reset all beckon-owned handlers after each
;; test.
(use-fixtures :each (fn [run]
                      (try (run)
                           (finally
                             (beckon/set-dispatch-policy! :synchronous)
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

;; This suite is the backend-agnostic behavioral spec. It runs without changes
;; against the backend that `-Dbeckon.signal.backend` selects (default sunmisc;
;; CI also runs it with ffm on Linux/JDK 22+).
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
