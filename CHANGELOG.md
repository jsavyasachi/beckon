# Changelog

## [Unreleased]

### Added

- Add explicit default, ignored, chained, current-handler, and restoration APIs.
- Reject process-wide default/ignored changes for HotSpot's reserved SIGUSR2.
- Add configurable synchronous, serial, parallel, and bounded asynchronous
  signal callback dispatch policies.

## [0.5.0] - 2026-08-27

### Added

- Add `add-handler!`, `remove-handler!`, and `clear-handlers!` for atomic,
  composable updates to a signal's handler collection.

## [0.4.3] - 2026-08-17

### Fixed

- A signal atom rejects nil and non-`Runnable` handler sets via a validator, so
  a bad handler can no longer NPE on delivery.
- Clearing a signal's handler set removes the handlers from dispatch.

## [0.4.2] - 2026-07-12

### Changed
- Migrate the build to deps.edn and tools.build (Java compiled via `clojure -T:build compile-java`), with Leiningen supported via lein-tools-deps.
