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
[net.clojars.savya/beckon "0.4.2"]
```

Clojure CLI (`deps.edn`):

```clj
net.clojars.savya/beckon {:mvn/version "0.4.2"}
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
  (reset! (beckon/signal-atom "INT") #{print-function}))
```

That is all. To check that this works, use the `raise!` function, which raises a
POSIX signal to the VM:

```clj
(beckon/raise! "INT")
; prints nothing
```

Why did `raise!` print nothing? When the JVM receives a signal, it starts a new
thread with maximum priority and handles the signal asynchronously. Thus the
output does not show in the nREPL window. Look in the `*nrepl-server*` buffer to
see the message.

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

beckon has 4 core functions: `signal-atom`, `raise!`, `reinit!` and
`reinit-all!`. Usually you need only `signal-atom` in a production system. The
other functions help you to debug, and to reset the signal handling to the
initial setup of signal handlers when the JVM starts.

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

### `raise!`

`raise!` sends a signal of the type given as input. For example, `(beckon/raise!
"INT")` has the same effect as a SIGINT signal sent to the JVM process. Use it
to check that your signal handlers work as intended.

### `reinit!` and `reinit-all!`

`reinit!` and `reinit-all!` reset the signal handlers to their state when the
JVM started. `reinit!` takes one argument, the signal to reset. `reinit-all!`
takes no argument and resets every signal.

## How is a signal handled?

When the JVM receives a signal, it starts a new thread at
`Thread.MAX_PRIORITY` and runs it asynchronously. This is why nREPL shows no
output, although output works in a command-line program. It is better to send a
message from the signal handler to a logger or a printer than to print in the
signal handler.

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
[net.clojars.savya/beckon "0.4.2"]
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
