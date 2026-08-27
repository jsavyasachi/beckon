(ns beckon
  (:import (clojure.lang Seqable)
           (com.hypirion.beckon SignalAtoms SignalFolder SignalRegisterer)
           (sun.misc SignalHandler)
           (java.util.concurrent ExecutorService)))

(def ^:private current-dispatch-policy (atom :synchronous))

(defn dispatch-policy
  "Returns the currently configured dispatch policy, initially :synchronous."
  [] @current-dispatch-policy)

(defn serial-policy
  "Returns a policy that serializes deliveries for each signal on executor."
  [executor]
  {:mode :serial :executor executor})

(defn parallel-policy
  "Returns a policy that may overlap callbacks for the same signal on executor."
  [executor]
  {:mode :parallel :executor executor})

(defn bounded-policy
  "Returns a non-blocking policy that drops callbacks rejected by executor."
  [executor]
  {:mode :bounded :executor executor})

(defn set-dispatch-policy!
  "Configures callback dispatch. The executor remains owned by the caller.

  Policy is :synchronous or a policy map returned by serial-policy,
  parallel-policy, or bounded-policy."
  [policy]
  (let [[mode executor] (if (keyword? policy)
                          [(name policy) nil]
                          [(some-> (:mode policy) name) (:executor policy)])]
    (when-not (contains? #{"synchronous" "serial" "parallel" "bounded"} mode)
      (throw (IllegalArgumentException. (str "Unknown dispatch policy: " policy))))
    (when (and (not= mode "synchronous")
               (not (instance? ExecutorService executor)))
      (throw (IllegalArgumentException. "An ExecutorService is required for asynchronous dispatch")))
    (SignalFolder/configureDispatch mode executor)
    (reset! current-dispatch-policy policy)
    policy))

(defn- handler-collection?
  [handlers]
  (and (instance? Seqable handlers)
       (every? #(instance? Runnable %) handlers)))

(defn signal-atom
  "Returns the beckon atom of the signal with the name signal-name. A change in
  the atom changes the signal handling, but a change in the signal handling does
  NOT change the atom. Two calls for the same signal atom return the same
  (identical) atom.

  A beckon atom is an atom that contains a Seqable Clojure collection. All the
  elements in the Seqable collection must be Runnable. A Clojure function is
  Runnable if it can take zero arguments. By default a beckon atom is a Clojure
  Set that contains the default signal handler in a Runnable. In a set the order
  of the Runnable is arbitrary. If you need a known order, first change the set
  to a vector or a list.

  To handle a signal, beckon calls all the functions of the beckon atom in
  order. If a Runnable throws an Exception, beckon stops the handling and calls
  no more elements. If a signal handler throws an Error, beckon does not catch
  that Error.

  signal-name must be a legal POSIX signal, where SIG is omitted from the first
  part of the name."
  [signal-name]
  (let [handler-atom (SignalAtoms/getSignalAtom signal-name)]
    (.setValidator handler-atom handler-collection?)
    handler-atom))

(defn raise!
  "Raises a signal of the type specified. The signal handling procedure then
  handles that signal.

  signal-name must be a legal POSIX signal, where SIG is omitted from the first
  part of the name."
  [signal-name]
  (SignalRegisterer/raiseSignal signal-name))

(defn current-handler
  "Returns the process-wide JVM SignalHandler currently installed for signal-name."
  [signal-name]
  (SignalRegisterer/currentHandler signal-name))

(defn default-handler!
  "Installs SIG_DFL. SIGUSR2 is rejected because HotSpot reserves it."
  [signal-name]
  (SignalRegisterer/setDefaultHandler signal-name))

(defn ignored-handler!
  "Installs SIG_IGN. SIGUSR2 is rejected because HotSpot reserves it."
  [signal-name]
  (SignalRegisterer/setIgnoredHandler signal-name))

(defn chain-handler!
  "Installs a beckon folder that invokes handler after beckon's handlers."
  [signal-name ^SignalHandler handler]
  (SignalRegisterer/chainHandler signal-name handler))

(defn restore-handler!
  "Restores the exact disposition saved before beckon's first change."
  [signal-name]
  (SignalRegisterer/restoreHandler signal-name))

(defn reinit!
  "Resets the signal handler of signal-name to its state at the start of this
  JVM process."
  [signal-name]
  (SignalRegisterer/resetDefaultHandler signal-name))

(defn reinit-all!
  "Resets all signal handlers of beckon to their default values."
  []
  (SignalRegisterer/resetAllHandlers))

(defn add-handler!
  "Adds handler to the signal's handler collection atomically.

  This is safer than reading the collection and using reset!, because
  concurrent registrations cannot overwrite one another."
  [signal-name handler]
  (swap! (signal-atom signal-name) conj handler))

(defn remove-handler!
  "Removes handler from the signal's handler collection atomically.

  Handlers are matched by reference identity."
  [signal-name handler]
  (swap! (signal-atom signal-name)
         #(remove (fn [candidate] (identical? candidate handler)) %)))

(defn clear-handlers!
  "Atomically removes all handlers from signal-name's handler collection."
  [signal-name]
  (swap! (signal-atom signal-name) empty))
