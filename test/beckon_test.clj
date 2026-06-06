(ns beckon-test
  (:require [clojure.test :refer :all]
            [beckon :as beckon])
  (:import (com.hypirion.beckon SignalRegistererHelper)))

;; Use SIGUSR2: its default disposition is to terminate the JVM, but every test
;; installs a beckon handler before raising it, so delivery runs our code rather
;; than killing the runner. Reset all beckon-owned handlers after each test.
(use-fixtures :each (fn [run] (try (run) (finally (beckon/reinit-all!)))))

;; This whole suite is the backend-agnostic behavioral spec: it runs unchanged
;; against whichever backend `-Dbeckon.signal.backend` selects (default sunmisc;
;; CI also runs it under ffm on Linux/JDK 22+).
(deftest backend-selection
  (testing "the backend that loaded matches the one requested"
    (is (= (case (System/getProperty "beckon.signal.backend" "sunmisc")
             "ffm"     "FfmSignalfdBackend"
             "sunmisc" "SunMiscSignalBackend")
           (SignalRegistererHelper/backendName)))))

(deftest signal-atom-identity
  (testing "the same signal name yields the identical atom"
    (is (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR2"))))
  (testing "different signals yield different atoms"
    ;; WINCH (terminal resize), not USR1: the JVM reserves SIGUSR1 internally on
    ;; some platforms (notably JDK 8 on Linux), so installing a handler there
    ;; throws "Signal already used by VM or OS".
    (is (not (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "WINCH"))))))

(deftest signal-atom-holds-runnable-collection
  (testing "a signal atom dereferences to a Seqable collection"
    (is (seq? (seq @(beckon/signal-atom "USR2"))))))

(deftest handler-runs-on-raise
  (testing "a handler set in the atom is invoked when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR2") [(fn [] (deliver ran true))])
      (beckon/raise! "USR2")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "every Runnable in the collection is invoked on a single raise"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR2") [bump bump bump])
      (beckon/raise! "USR2")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "raising with no handlers installed does not throw"
    (reset! (beckon/signal-atom "USR2") [])
    (is (nil? (beckon/raise! "USR2")))))
