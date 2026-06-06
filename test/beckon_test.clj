(ns beckon-test
  (:require [clojure.test :refer :all]
            [beckon :as beckon]))

;; Use SIGUSR2: its default disposition is to terminate the JVM, but every test
;; installs a beckon handler before raising it, so delivery runs our code rather
;; than killing the runner. Reset all beckon-owned handlers after each test.
(use-fixtures :each (fn [run] (try (run) (finally (beckon/reinit-all!)))))

(deftest signal-atom-identity
  (testing "the same signal name yields the identical atom"
    (is (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR2"))))
  (testing "different signals yield different atoms"
    (is (not (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR1"))))))

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
