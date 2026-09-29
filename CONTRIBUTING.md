# Contributing to Tap

Thanks for your interest in Tap. Bug reports, ideas, documentation fixes and code are all
welcome.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security
issues privately as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Ways to contribute

- **Report a bug.** Open an [issue](https://github.com/NoamCohen48/tap/issues/new/choose) with
  the Tap versions, the device (model, API level, emulator or physical), what you ran and what
  happened. The failure artifacts (`build/tap-artifacts/…` or `tap-artifacts/…`) and
  `tap status` output help a lot.
- **Suggest a feature.** Describe the test you are trying to write and what gets in the way.
  If another tool (Maestro, Appium, uiautomator2) already handles it, say how.
- **Improve the docs.** Guide pages live in `docs/guide/`.
- **Send a pull request.** For anything beyond a small fix, open an issue first so we can agree
  on the approach.

## Getting set up

- [Architecture](docs/development/architecture.md) explains the driver, the server and the
  clients, and where each lives in the repository.
- [Building and testing](docs/development/building-and-testing.md) covers the toolchain, the
  builds and every test suite.

## Rules for changes

- **Invariants.** The driver is a separate package from the app. Selectors never use XPath or
  a hierarchy dump on the hot path. No element handles are kept between commands. Mutations
  need exactly one match. A transmitted mutation is never replayed. Every ADB call names its
  serial (`-s`). A change that weakens any of these will not be merged.
- **Layering.** Clients depend only on `:contracts:api`; nothing under `host/` depends on
  `clients/`; product code never depends on `:host:validation`.
- **The driver assumes nothing about the app.** It performs the action and reports what
  happened; tests assert the effect. Discuss driver behaviour changes in an issue first.
- **Protocol changes** only add fields and values; see
  [Changing the protocol](docs/development/building-and-testing.md#changing-the-protocol).
- **Public API.** Every public Kotlin and Python symbol needs KDoc or a docstring; the API
  reference is generated from them. Update the guide in `docs/` with any user-visible change,
  and add a line under `## Unreleased` in `CHANGELOG.md`.
- **Borrowed code.** Tap learns from Appium UiAutomator2, Maestro, uiautomator2 and AndroidX.
  Anything copied or closely adapted must be recorded in `THIRD_PARTY_NOTICES.md` with the
  repository, commit, paths, license and what was changed.

## Pull requests

- Keep a pull request to one topic, with tests for the behaviour it adds or fixes.
- Write commit messages in the imperative, with a short subject line and a body that explains
  why.
- CI must pass: proto lint and breaking-change check, stub drift, JVM and Python unit tests,
  the native image, and both client suites on an API 34 emulator.
- By contributing, you agree that your contribution is licensed under the
  [Apache License 2.0](LICENSE).
