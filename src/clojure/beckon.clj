(ns beckon
  (:import (com.hypirion.beckon SignalAtoms SignalRegisterer)))

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
  (SignalAtoms/getSignalAtom signal-name))

(defn raise!
  "Raises a signal of the type specified. The signal handling procedure then
  handles that signal.

  signal-name must be a legal POSIX signal, where SIG is omitted from the first
  part of the name."
  [signal-name]
  (SignalRegisterer/raiseSignal signal-name))

(defn reinit!
  "Resets the signal handler of signal-name to its state at the start of this
  JVM process."
  [signal-name]
  (SignalRegisterer/resetDefaultHandler signal-name))

(defn reinit-all!
  "Resets all signal handlers of beckon to their default values."
  []
  (SignalRegisterer/resetAllHandlers))
