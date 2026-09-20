# Tap

Kotlin host-driven Android E2E framework in three components, mirrored by the layout:
`device/` (driver, sync-sdk), `host/` (`:host:core` session infrastructure, `:host:service` =
`tap serve` over loopback gRPC / native image, `:host:validation`), `clients/` (Kotlin SDK +
JUnit 5, Python + pytest — all gRPC clients of the service), with `contracts/` holding the
TAP1 device protocol and the `tap.v1` service API. Phases 0 and 1 (contract and driver) are
complete; Phase 2 (service + clients) has a usable first cut with `:samples:fixture-tests`.
Nothing under `host/` may depend on `clients/`; clients depend only on `:contracts:api`.
See `README.md` for build/run commands.

## Documents

- `.docs/android-e2e-framework-implementation-plan.md` — the original design. A reference,
  not a source of truth: the code, the contracts and the decision records below win where
  they differ, and any deliberate departure from the plan needs the user's approval and a
  note in the relevant decision record. Never silently "correct" code back to the plan.
- `.docs/pool-and-leases.md` — decision record: the service leases nothing; exclusive device
  use is the per-serial journal lock, roles and device choice are client-side.
- `.docs/protocol-contract.md` — the *implemented* wire contract (protocol 1.0). Update it in
  the same change as any protocol edit.
- `.docs/project-architecture.md` — current module/file layout.
- `.docs/phase-1-progress.md` — Phase 1 checklist. Keep it honest: only tick items that are
  implemented *and* exercised by a test or the device validation flow.
- `.docs/framework-gaps.md` — the remaining delta to the plan, per section. Move an item out
  of it only together with the test or device check that proves it.
- `.docs/service-api.md` — the host service contract (`contracts/api/proto/tap.proto`, `tap.v1`): run
  liveness, pool, sessions, status mapping, native build. Update it with any proto change.
- `.docs/multi-language-bindings.md` — analysis behind the service + Python binding, with
  the outcome section recording what was decided.
- `.docs/upstream-reference-audit.md` — adopt/adapt/do-not-copy decisions per upstream tool.
- `.docs/service-startup.md` — decision record: the service is started explicitly (`tap start`),
  never by a client; readiness is the `Info` RPC on a port `start` chose, and the alternatives
  (notify socket, ready file, inherited pipe, fixed port) and why they lost.
- `.docs/coroutines.md` — decision record + work plan: host core, service and the Kotlin
  client move to kotlinx.coroutines / grpc-kotlin (`tapTest` per plan §12). Host core and host
  service have landed (Kotlin client + `tapTest` pending); device-matrix and native-image
  verification of the coroutine implementation remain pending. It lists the pre-migration
  thread/future design, the per-layer arguments, target design, step order and risks. Update
  its status with each step.
- `.docs/cli-parsing.md` — research note on CLI parsing libraries for `tap` (Clikt / picocli /
  kotlinx-cli); decision: hand parser until the CLI grows a second tier, then Clikt core.
- `docs/` + `mkdocs.yml` — the *public* user documentation (guide pages and generated Kotlin /
  Python / gRPC references; `scripts/build-docs.sh`). Update the guide with any user-visible
  client change; public client API needs KDoc/docstrings because the references are generated
  from them. `.docs/` stays internal.
- `.docs/release-engineering.md` — artifact families, version lines (`gradle.properties`
  `tap.version.*`, `pyproject.toml`), CI jobs and tag-driven releases. Versions are bumped
  there, never in code (`ENGINE_VERSION` is generated).

## Learning from other testing tools

Whenever it is useful and possible, take inspiration from existing, proven testing tools
rather than inventing behavior from scratch. Primary references:

- **Appium UiAutomator2** (server + driver): Android/UiAutomator edge cases, input and
  key-event handling, permission dialogs, screenshots, accessibility-cache/staleness
  workarounds, server lifecycle.
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
  capture, replay scripts) — a possible future adapter over the Tap service, not a driver model.

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
  ADB/journal/lease/driver/app lifecycle lives in `:host:core` and is reached through the service.

## Build notes

- JDK 17 is required: `export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`
  on this workstation; the default JDK is not compatible with AGP.
- The validation executable is `host/validation/build/install/host/bin/host`
  (`:host:validation:installDist`). The full flow (`host <serials> <apks>`) is destructive
  and reboots devices. Do not run it against shared devices without asking.
  `host --no-reboot ...` skips only the reboot scenario and is safe for routine validation on
  the local matrix (emulator-5554 API 34, 85e49002 Samsung SM-J810G API 29).
- Client/service changes are validated with
  `./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002` (starts the JVM
  service dist via `tap.manageService` and stops it afterwards unless one was already running)
  and the Python suite below. Clients never start the service: `tap start` / `tap stop`
  (`.docs/service-startup.md`). Unit tests: `:host:core:test :host:service:test :contracts:protocol:test
  :device:driver:command-engine:test`.
- Product code must never depend on `:host:validation`; `PhaseZeroMain` is fault-injection
  validation, not framework code.
- `~/.tap/sessions` holds machine-wide device leases/journals; `.tap/` in the repo is ignored.
- `contracts/api/proto/tap.proto` is the single source for the service API. After editing it: run
  `:host:service:test` (enum mirror + golden round trip), regenerate the committed Python stubs
  with `clients/python/scripts/gen_stubs.py` (needs `grpcio-tools` at the version pinned in
  the script), and update `.docs/service-api.md`. CI also runs `buf lint`/`buf breaking`
  (`contracts/api/buf.yaml`): only add fields/values; never remove, renumber or retype.
- Native image: `GRAALVM_HOME=~/.local/share/graalvm/graalvm-community-openjdk-21.0.2+13.1
  ./gradlew :host:service:nativeCompile` (JAVA_HOME stays JDK 17). If a new dependency uses
  reflection, re-record `host/service/src/main/resources/META-INF/native-image` with the
  tracing agent (`JAVA_OPTS=-agentlib:native-image-agent=config-output-dir=...` on the JVM
  dist while running the smoke flow).
- Python: system Python has no pip; use a venv (`python -m venv .venv && .venv/bin/pip install
  -e clients/python[dev]`). `TAP_BIN=<native tap> TAP_MANAGE_SERVICE=1 TAP_SERIALS=emulator-5554,85e49002
  pytest clients/python/tests` validates the service + client on the local matrix (starts and
  stops the service; without `TAP_MANAGE_SERVICE` a running one is required).
