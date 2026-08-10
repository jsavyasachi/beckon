# Contributing to beckon

Thanks for your interest in improving `beckon`. Bug reports, fixes, and
focused feature contributions are all welcome.

## Before you start

- For a change that is more than a trivial fix, **open an issue first**. Then we
  can agree on the approach before you do the work.
- Read the open issues and pull requests to prevent duplicate work.

## Development

This is a Clojure library. You need a JDK and [Leiningen](https://leiningen.org/).
A project that uses `deps.edn` uses the Clojure CLI instead: see the README.

```bash
lein test     # run the test suite
lein check    # AOT-compile; must be free of reflection warnings
```

Requirements for a change that we can merge:

- **Tests first.** Add or update the tests for the behavior you change. For a
  bug fix, include a regression test that fails before your fix and passes after
  it.
- **Green build.** `lein test` passes and `lein check` reports **zero**
  reflection warnings.
- **No scope creep.** Keep each pull request to one logical change.

## Commits and pull requests

- Follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` …).
- Keep the subject in the imperative mood and under ~72 characters.
- Update `CHANGES.md` / `CHANGELOG.md` when your change is user-visible.
- Rebase on the latest `main` before opening the pull request.

## License

If you contribute, you agree to license your contribution under the same license
as this project (see `LICENSE` or the README).
