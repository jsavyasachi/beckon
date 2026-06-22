# beckon changelog

## 0.4.1

* Docs-only release: standardize the README to the canonical skeleton, add the
  cljdoc badge, unify the status badges and CI workflow name, and cut
  self-promotional phrasing.

## 0.4.0

* **Move the experimental FFM signal backends to a separate `beckon-ffm`
  artifact.** The FFM backends need JDK 22+, so keeping them out of core lets
  the core jar stay on its JDK 8 floor. Core's pluggable backend seam is
  unchanged; consumers wanting `signalfd`/`kqueue` add `net.clojars.savya/beckon-ffm`.
* Document `beckon-ffm` as a separate package and add its version badge.

## 0.3.0

* **Pluggable signal backend.** Isolate the `sun.misc.Signal` usage behind a
  `SignalBackend` seam so alternative backends can be selected at runtime.
* Add an experimental, opt-in FFM `signalfd` backend (Linux, JDK 22+). Make FFM
  `raise!` synchronous so signals don't bleed across handler sets. (These FFM
  backends move to the `beckon-ffm` artifact in 0.4.0.)

## 0.2.0

* First self-published maintenance fork as `net.clojars.savya/beckon`.
* Compile cleanly on modern JDKs (javac 8 target, Clojure 1.12.5; `sun.misc`
  deprecation warnings silenced).
* Replace the generated stub test with real signal-handling coverage; add a
  GitHub Actions JDK matrix (8, 11, 17, 21).

## 0.1.1 [`docs`][0.1.0-docs] [`tag`][0.1.1-tag]

* Beckon will now always compile to 1.6-compliant bytecode.

## 0.1.0 [`docs`][0.1.0-docs] [`tag`][0.1.0-tag]

* **New:** The function `signal-atom` returns an atom containing a collection of
  functions. The functions will be invoked sequentially whenever a signal is
  trapped.
* **New:** The function `raise!` raises a signal.
* **New:** `reinit!` and `reinit-all!` reinitializes signal handlers, and return
  them to their "factory settings".

[0.1.1-tag]: https://github.com/hyPiRion/beckon/tree/0.1.1
[0.1.0-tag]: https://github.com/hyPiRion/beckon/tree/0.1.0
[0.1.0-docs]: http://hypirion.github.com/beckon/0.1.0/
