# Tap

Kotlin host-driven Android E2E framework in three components, mirrored by the layout:
`device/` (driver, sync-sdk), `host/` (`:host:core` session infrastructure, `:host:daemon` =
`tap serve` over loopback gRPC / native image, `:host:validation`), `clients/` (Kotlin SDK +
JUnit 5, Python + pytest — all gRPC clients of the server; `clients/agent` = `tap-agent`, the agent
CLI + MCP server, built on the Python client), with `contracts/proto` holding
the one protobuf schema (`tap.v1` server API, `tap.wire.v1` device payloads). Phases 0 and 1 (contract and driver) are
complete; Phase 2 (server + clients) has a usable first cut with `:samples:fixture-tests`.
Nothing under `host/` may depend on `clients/`; clients depend only on `:contracts:api`.
Synchronization (`device/sync-sdk`, the sync provider path, `awaitIdle`) is work in progress:
ignore it for now — do not fix, extend or redesign it unless asked (its review items are
Deferred in `.docs/code-review-status.md`).
See `CONTRIBUTING.md` for build/run commands.

## Documents

- `.docs/android-e2e-framework-implementation-plan.md` — the original design. A reference,
  not a source of truth: the code, the contracts and the decision records below win where
  they differ, and any deliberate departure from the plan needs the user's approval and a
  note in the relevant decision record. Never silently "correct" code back to the plan.
- `.docs/pool-and-leases.md` — decision record: the server leases nothing; exclusive device
  use is the per-serial journal lock, roles and device choice are client-side.
- `.docs/protocol-contract.md` — the *implemented* wire contract (protocol 4.0). Update it in
  the same change as any protocol edit.
- `.docs/project-architecture.md` — current module/file layout.
- `.docs/phase-1-progress.md` — Phase 1 checklist. Keep it honest: only tick items that are
  implemented *and* exercised by a test or the device validation flow.
- `.docs/framework-gaps.md` — the remaining delta to the plan, per section. Move an item out
  of it only together with the test or device check that proves it.
- `.docs/server-api.md` — the host daemon contract (`contracts/proto/*.proto`, `tap.v1`): run
  liveness, pool, sessions, status mapping, native build. Update it with any proto change.
- `.docs/multi-language-bindings.md` — analysis behind the server + Python binding, with
  the outcome section recording what was decided.
- `.docs/upstream-reference-audit.md` — adopt/adapt/do-not-copy decisions per upstream tool.
- `.docs/daemon-startup.md` — decision record: the server is started explicitly (`tap start`),
  never by a client; readiness is the `Info` RPC on a port `start` chose, and the alternatives
  (notify socket, ready file, inherited pipe, fixed port) and why they lost.
- `.docs/coroutines.md` — decision record + completed work plan: host core, server and the
  Kotlin client moved to kotlinx.coroutines / grpc-kotlin (`tapTest` per plan §12). Host core
  and server passed the API 29/API 34 no-reboot matrix plus native-image smoke; the Kotlin
  client/`tapTest` conversion (client 0.2.0) passed its JVM suites and the 13-test API 29/API 34
  device matrix. It lists the pre-migration design, decisions, implementation history and
  verification evidence.
- `.docs/agent-surface.md` — decision record + phase plan: the agent surface (held connections,
  screen snapshots with refs, event log, and `tap-agent` — CLI + MCP in Python on `tap-e2e`).
- `.docs/cli-parsing.md` — research note on CLI parsing libraries for `tap` (Clikt / picocli /
  kotlinx-cli); decision: hand parser until the CLI grows a second tier, then Clikt core.
- `docs/` + `mkdocs.yml` — the *public* user documentation (guide pages and generated Kotlin /
  Python / gRPC references; `scripts/build-docs.sh`). Update the guide with any user-visible
  client change; public client API needs KDoc/docstrings because the references are generated
  from them. `.docs/` stays internal.
- `.docs/code-review.md` + `.docs/code-review-status.md` — the harsh review and its per-item fix
  status (open/partial gaps by area, decisions such as "the driver assumes nothing about the
  app"). Update the status row in the same change as the fix.
- `.docs/release-engineering.md` — artifact families, version lines (`gradle.properties`
  `tap.version.*`, `pyproject.toml`), CI jobs and tag-driven releases. Versions are bumped
  there, never in code (`ENGINE_VERSION` is generated).

## Learning from other testing tools

Whenever it is useful and possible, take inspiration from existing, proven testing tools
rather than inventing behavior from scratch. Primary references:

- **Appium UiAutomator2** (server + driver): Android/UiAutomator edge cases, input and
  key-event handling, permission dialogs, screenshots, accessibility-cache/staleness
  workarounds, daemon lifecycle.
- **Maestro**: host ergonomics, condition waits, selector usability, process supervision,
  artifact/report separation (event model → JUnit/HTML adapters), multi-device runner.
- **androidx.test.uiautomator**: the API we build on. Check the current AndroidX source
  (`UiDevice`, `ByMatcher`, `BySelector`, `UiObject2`) before assuming what a selector or
  gesture does, and prefer newer predicate/window-scoped APIs where they fit.
- **openatx/uiautomator2**: the Python UiAutomator wrapper. Python API ergonomics (lazy
  `d(text=...)` objects, `exists`/`wait`, `app_start`/`app_wait`, gesture and key helpers),
  on-device agent lifecycle and Android/OEM quirks it has hit over the years.
- **callstack/agent-device**: CLI + MCP server + Node API for AI coding agents. The shape of an
  agent-facing surface (accessibility snapshots with refs and diffs, `--settle`, evidence
  capture, replay scripts) — a possible future adapter over the Tap daemon, not a driver model.

Rules when doing so:

- Use the pinned commits in `.docs/upstream-reference-audit.md`; never a moving branch.
- Follow the decision matrix there: some areas are ADOPT/ADAPT, others (XPath hot path,
  persistent element handles, WebDriver surface, YAML flows, retry-by-default) are
  deliberately DO NOT COPY.
- Adapt narrowly, behind a regression test for the affected API/device family.
- Any copied or substantially adapted code must be recorded in `THIRD_PARTY_NOTICES.md`
  with repo, commit, source/destination path, license, and modification summary.
  Independently written protocol/session/security code stays separate from borrowed code.

## Invariants that must not regress

- Driver is a separate package from the AUT; it survives AUT force-stop/clear-data.
- No hierarchy dump or XPath on the selector hot path; dumps are diagnostic only.
- No persistent `UiObject2`/node handles across commands; elements are lazy selectors.
- Mutations require exactly one match; `AMBIGUOUS`/`NOT_FOUND` are returned before input.
- Never replay a transmitted mutation; transport loss after acceptance is `INDETERMINATE`.
- Request IDs are strictly increasing per session generation; old generations are rejected.
- Every ADB call is serial-specific (`-s`); never `forward --remove-all`.
- Layering: `clients/*` → `:contracts:api` only; `host/*` never references `clients/`; all
  ADB/journal/lease/driver/app lifecycle lives in `:host:core` and is reached through the server.

## Build notes

- Gradle runs on JDK 17 via `gradle/gradle-daemon-jvm.properties` (discovered in `~/.gradle/jdks`),
  so `JAVA_HOME` no longer matters. Versions live in `gradle/libs.versions.toml`; shared JVM module
  setup (`tap.kotlin-jvm`, `tap.published`, `tap.dokka`) in `build-logic/`.
- Device validation is the JUnit 5 `deviceTest` suite in `:host:validation`:
  `./gradlew :host:validation:deviceTest -Ptap.serials=emulator-5554,85e49002` (one class per
  scenario; skipped without serials). APKs default to the validation-flavor driver + fixture;
  override with `-Ptap.driverApk=… -Ptap.driverTestApk=… -Ptap.fixtureApk=…`. The default run
  never reboots and is safe for routine validation on the local matrix (emulator-5554 API 34,
  85e49002 Samsung SM-J810G API 29). `-Ptap.reboot=true` adds the `@Tag("reboot")`
  `LateMutationQuarantineTest`, which reboots devices: do not run it against shared devices
  without asking. `tap-product-probe` (`:host:validation:installDist`) is the product probe.
- Client/server changes are validated with
  `./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002` (starts the JVM
  daemon dist via `tap.manageDaemon` and stops it afterwards unless one was already running)
  and the Python suite below. Clients never start the server: `tap start` / `tap stop`
  (`.docs/daemon-startup.md`). Unit tests: `:host:core:test :host:daemon:test :contracts:protocol:test
  :device:driver:command-engine:test`.
- Product code must never depend on `:host:validation`; the device tests are fault-injection
  validation, not framework code.
- `~/.tap/sessions` holds machine-wide device leases/journals; `.tap/` in the repo is ignored.
- `contracts/proto/**/*.proto` are the single schema for the server API and the device wire
  (`wire/`). After editing them: run `:contracts:protocol:test` (wire golden bytes) and
  `:host:daemon:test`, regenerate the committed Python stubs
  with `clients/python/scripts/gen_stubs.py` (needs `grpcio-tools` at the version pinned in
  the script), and update `.docs/server-api.md`. CI also runs `buf lint`/`buf breaking`
  (`contracts/proto/buf.yaml`): only add fields/values; never remove, renumber or retype.
- Native image: `GRAALVM_HOME=~/.local/share/graalvm/graalvm-community-openjdk-21.0.2+13.1
  ./gradlew :host:daemon:nativeCompile`. If a new dependency uses
  reflection, re-record `host/daemon/src/main/resources/META-INF/native-image` with the
  tracing agent (`JAVA_OPTS=-agentlib:native-image-agent=config-output-dir=...` on the JVM
  dist while running the smoke flow).
- Python: system Python has no pip; use a venv (`python -m venv .venv && .venv/bin/pip install
  -e clients/python[dev]`). `TAP_BIN=<native tap> TAP_MANAGE_DAEMON=1 TAP_SERIALS=emulator-5554,85e49002
  pytest clients/python/tests` validates the server + client on the local matrix (starts and
  stops the server; without `TAP_MANAGE_DAEMON` a running one is required).
- `tap-agent` (`clients/agent`): install with `uv pip install --python .venv/bin/python -e
  clients/python -e clients/agent` (the venv has no pip); unit tests `pytest clients/agent/tests`
  reuse the Python client's in-process fake daemon.
