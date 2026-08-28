# beckon

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/beckon.svg)](https://clojars.org/net.clojars.savya/beckon)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/beckon)](https://cljdoc.org/d/net.clojars.savya/beckon/CURRENT)
[![test](https://github.com/jsavyasachi/beckon/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/beckon/actions/workflows/test.yml)

A Clojure library to handle POSIX signals in JVM applications. It does the
low-level work and gives you a simple interface to the signal handlers.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=fff" alt="Clojure" /></a>
<a href="https://clojure.org/guides/deps_and_cli"><img src="https://img.shields.io/badge/deps.edn-5881D8?style=flat&logo=clojure&logoColor=fff" alt="deps.edn" /></a>
<a href="https://clojure.github.io/tools.build/"><img src="https://img.shields.io/badge/tools.build-5881D8?style=flat&logo=clojure&logoColor=fff" alt="tools.build" /></a>

## Installation

Use `beckon` by default. It runs on JDK 8+ with no extra JVM flags. Use
[`beckon-ffm`](https://github.com/jsavyasachi/beckon-ffm) only if you want the
experimental Foreign Function & Memory backend on JDK 22+.

Leiningen (`project.clj`):

```clj
[net.clojars.savya/beckon "0.6.0"]
```

Clojure CLI (`deps.edn`):

```clj
net.clojars.savya/beckon {:mvn/version "0.6.0"}
```

beckon runs on JDK 8 or later with **no extra JVM flags**. It wraps
`sun.misc.Signal`, which stays available through the `jdk.unsupported` module on
current JDKs. Thus you do not need `--add-exports` or `--add-opens`.

## Quick-start

To catch `SIGINT` and print a message when someone interrupts the process, start
your Emacs nREPL with `nrepl-jack-in` or a similar command. Then write the
following:

```clj
(require 'beckon)

(let [print-function (fn [] (println "Hahah, nothing can stop me!"))]
  (beckon/add-handler! "INT" print-function))
```

That is all. To check that this works, use the `raise!` function, which raises a
POSIX signal to the VM:

```clj
(beckon/raise! "INT")
; prints nothing
```

Why did `raise!` print nothing? By default the JVM receives a signal on a
maximum-priority signal thread, and beckon runs callbacks there synchronously.
Thus the output does not show in the nREPL window. Look in the `*nrepl-server*`
buffer to see the message. See [Dispatch policies](#dispatch-policies) if
callbacks must not run on that thread.

By default, signals such as SIGTERM and SIGINT terminate the running VM. Be
careful. You can experiment with them in a REPL:

```clj
(beckon/raise! "TERM")
; NB: This will terminate nREPL.
```

If the signal handling is in a bad state, you can go back to the default:

```clj
(beckon/reinit! "INT")
; Reinitializes the SIGINT signal handler.
(beckon/raise! "INT")
; NB: This will terminate your JVM process.
```

That is all you need to know to work with beckon.

## Usage

beckon has 24 core functions: `signal-atom`, `add-handler!`,
`remove-handler!`, `clear-handlers!`, `raise!`, `reinit!`, `reinit-all!`,
`current-handler`, `default-handler!`, `ignored-handler!`,
`chain-handler!`, `restore-handler!`, `dispatch-policy`,
`set-dispatch-policy!`, `serial-policy`, `parallel-policy`, and
`bounded-policy`, `callback-error-policy`, `callback-error-policy-setting`,
`set-callback-error-policy!`, `normalize-signal-name`, `signal-supported?`,
`supported-signals`, and `shutdown!`.
Usually you need only `add-handler!` and `remove-handler!` in a production
system. The other functions help you to inspect or reset signal handling.

### `signal-atom`

`signal-atom` is the core of this library. It uses atoms to set up signal
handlers. It returns an atom. The atom has a validator function, so the only
legal values are Seqable collections in which every element is Runnable. All
Clojure functions implement Runnable, but only a function that takes zero
arguments works as a Runnable.

beckon requires a Seqable of Runnable in the atom, because this lets you add
more than one independent signal handler to a single signal. beckon runs the
signal handlers in sequence. If a function throws an exception, beckon stops the
signal handling and throws no exception. If a function throws an error, the
whole signal handling crashes. You can use this behavior to get conditional
dispatch of functions. For example:

```clj
(reset! (beckon/signal-atom "INT")
        [(fn [] (println "foo"))
         (fn [] (println "bar") (throw (Exception.)))
         (fn [] (println "We'll never see this"))])
```

Will only print `foo` and `bar`.

This is not a good way to do dispatch. Put this logic in the functions when
possible.

beckon updates the signal handler when the atom changes, but a change to the
signal handler does not update the atom. If you use beckon, do not also set
signal handling through another library or through the native Java interface.

### `add-handler!`, `remove-handler!` and `clear-handlers!`

Use these functions to compose handlers from independent components without
replacing the entire collection:

```clj
(def cleanup (fn [] (println "cleaning up")))
(beckon/add-handler! "INT" cleanup)
(beckon/remove-handler! "INT" cleanup)
(beckon/clear-handlers! "INT")
```

Each operation updates the signal atom with `swap!`, so concurrent additions
and removals are atomic. This avoids the lost updates possible with the
read-then-`reset!` pattern, where two components can each read the same old
collection and the later `reset!` can overwrite the earlier registration.
`remove-handler!` matches the handler by reference identity.

### Signal dispositions

For interoperability with other JVM signal users, beckon also exposes the
process-wide disposition directly. `current-handler` returns the exact
pre-existing `sun.misc.SignalHandler`; `default-handler!` and
`ignored-handler!` install `SIG_DFL` and `SIG_IGN`; and `chain-handler!` invokes
the supplied JVM handler after beckon's handlers. `restore-handler!` returns the
signal to the exact disposition saved before beckon's first disposition change.

These operations are process-wide, not thread-local. They reject `SIGUSR2`,
which HotSpot uses internally for thread suspension, to avoid destabilizing the
JVM. Use `chain-handler!` with a preserved JVM handler when integrating with a
signal already owned by another JVM component.

### `raise!`

`raise!` sends a signal of the type given as input. For example, `(beckon/raise!
"INT")` has the same effect as a SIGINT signal sent to the JVM process. Use it
to check that your signal handlers work as intended.

### `reinit!` and `reinit-all!`

`reinit!` and `reinit-all!` reset the signal handlers to their state when the
JVM started. `reinit!` takes one argument, the signal to reset. `reinit-all!`
takes no argument and resets every signal.

### Dispatch policies

Callbacks run synchronously on the JVM signal thread by default. Configure an
executor-backed policy when callbacks can block or take meaningful time:

```clj
(def executor (java.util.concurrent.Executors/newFixedThreadPool 4))
(beckon/set-dispatch-policy! (beckon/serial-policy executor))
```

The policy applies to future deliveries; callbacks already submitted continue
under their original policy. beckon does not shut down a caller-supplied
executor.

| Policy | Ordering and overlap | Overload behavior |
| --- | --- | --- |
| `:synchronous` | Runs callbacks in collection order on the signal thread; callbacks for a signal cannot overlap. | No queue; the signal thread runs the callback directly. This is the default. |
| `(serial-policy executor)` | Queues one delivery at a time per signal. Deliveries for the same signal are ordered and never overlap. Different signals may run in parallel. | Uses the executor's normal `execute` behavior; a rejected submission is propagated to the signal thread. |
| `(parallel-policy executor)` | Submits each callback independently. Callbacks for the same signal may overlap; no callback ordering is guaranteed. | Uses the executor's normal `execute` behavior; a rejection is propagated to the signal thread. |
| `(bounded-policy executor)` | Same overlap and ordering behavior as parallel dispatch. | Non-blocking. If `execute` rejects because the caller's bounded queue is full (or the executor is shut down), that callback is dropped. Other callbacks and later deliveries continue. |

For bounded dispatch, configure the supplied executor with the capacity and
thread count you want, for example a `ThreadPoolExecutor` with an
`ArrayBlockingQueue`. A bounded policy never waits for queue space and therefore
does not put application backpressure on the JVM signal thread.

In asynchronous policies, an `Exception` from one callback does not cancel
other callbacks that were submitted for the same delivery. As with the
historical synchronous behavior, `Error` is not caught by beckon.

### Coordinated process shutdown

For a service, install one asynchronous callback for both termination signals
and let a normal application thread coordinate cleanup. Keep `System/exit` out
of the signal callback so resources can close in a predictable order:

```clj
(import '(java.util.logging Logger)
        '(java.util.concurrent Executors TimeUnit))

(let [logger (Logger/getLogger "my-service")
      stopping (promise)
      executor (Executors/newFixedThreadPool 2)
      request-stop (fn [] (deliver stopping true))]
  (beckon/set-dispatch-policy! (beckon/parallel-policy executor))
  (beckon/add-handler! "TERM" request-stop)
  (beckon/add-handler! "INT" request-stop)
  (future
    @stopping
    (.info logger "shutdown requested")
    ;; Stop accepting new work and close application-owned resources here.
    (beckon/shutdown!)
    (.shutdown executor)
    (.awaitTermination executor 5 TimeUnit/SECONDS)
    (.info logger "shutdown complete")
    (System/exit 0)))
```

The same coordination pattern works with `beckon-ffm`; select it with
`-Dbeckon.signal.backend=ffm --enable-native-access=ALL-UNNAMED`. The FFM
package uses the same Clojure API, but signal availability remains dependent
on the host operating system and backend. `shutdown!` cancels queued beckon
callbacks that have not started; it does not interrupt callbacks already
running or shut down an executor supplied by the application.

## How is a signal handled?

When the JVM receives a signal, it starts a new thread at
`Thread.MAX_PRIORITY`. With the default policy, beckon runs the callbacks on
that thread. Executor-backed policies move callback work to the supplied
executor, while the signal thread only performs the dispatch operation.

## "FAQ"

This list gives the common problems with this library. If it does not help you,
add a [new issue][new-issue].

**Q:** My infinite sequence does not work with this library. Why?  
**A:** For speed, beckon puts the collection of functions into a Java array.
  An infinite sequence does not fit in a Java array.

**Q:** Why does the collection of functions accept keywords, symbols, and other
  values that are clearly not functions?  
**A:** Keywords, symbols and some persistent collections implement the `IFn`
  interface in Clojure, and thus they also implement Runnable. They implement
  Runnable, but they cannot return a value of use. A later version will correct
  this.

[new-issue]: https://github.com/jsavyasachi/beckon/issues/new "Add a new issue to Beckon"

## Signal backends

By default beckon uses `sun.misc.Signal`, which works on JDK 8+ with no extra
JVM flags. The JDK marks that API "internal proprietary", so beckon keeps all
use of it behind a small internal `SignalBackend` seam. An alternative backend
can replace it if necessary.

An experimental alternative uses the Foreign Function & Memory API (JDK 22+). It
ships as a **separate package**,
[`beckon-ffm`](https://github.com/jsavyasachi/beckon-ffm) (Linux `signalfd`,
macOS/BSD `kqueue`). Add it with beckon:

```clj
[net.clojars.savya/beckon "0.6.0"]
[net.clojars.savya/beckon-ffm "0.1.7"]
```

Then start the JVM with `-Dbeckon.signal.backend=ffm
--enable-native-access=ALL-UNNAMED` to select it. It is a separate package
because it needs JDK 22+, but this core jar targets JDK 8. `sun.misc` stays the
default.

## License

Copyright © 2013 Jean Niklas L'orange.

Maintenance fork (2026) by Savyasachi, original: https://github.com/hyPiRion/beckon.
Distributed under the [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html), preserving the original license.

Distributed under the Eclipse Public License, the same as Clojure.
