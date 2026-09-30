# Changelog

Each artifact family is versioned on its own (`docs/reference/releases.md`); entries name the
families they cover. Tap is in alpha: any 0.x release may change the API, and parts marked
experimental may change in any release.

## Unreleased

- Licensed under Apache-2.0 (`LICENSE`; the wheels and POMs carry it).
- Every GitHub Release carries the complete documentation as Markdown
  (`tap-docs-<version>.zip` / `.tar.gz`).
- **Tap Studio** (experimental, new family `client-studio`: `tap-studio`): a browser inspector
  and action recorder on a running server. It shows the live screen with its elements. Act,
  Assert and Inspect modes record element steps (taps, text and secrets, scrolls, swipes, keys,
  app calls, checks), never coordinates. Steps can be edited (another selector candidate, or
  one typed in the Kotlin DSL with a live match count), reordered and replayed. Recordings are
  exported and reopened as `tap-recording/1` JSON. Guide: `docs/guide/studio.md`.
- **Notifications and quick settings** (engine, Kotlin and Python clients, Tap Studio): the
  `open_system_panel` command (`tap.v1.OpenSystemPanel`) opens the notification shade or quick
  settings with the system's accessibility action. The clients expose it as
  `device.openNotifications()` / `openQuickSettings()` (`open_notifications()` /
  `open_quick_settings()`). Tap Studio records it from the device rail beside the screen, which
  also has Back, Home and Recent apps.

## 0.0.1 — 2026-09-29 (alpha)

First release of the engine (`daemon/v0.0.1`: the `tap` server with the bundled driver, plus
`tap-schema` / `tap-api`), the Kotlin client (`client-kotlin/v0.0.1`: `tap-client`,
`tap-junit5`), the Python client (`client-python/v0.0.1`: `tap-e2e`) and the agent tools
(`client-agent/v0.0.1`: `tap-agent`). `tap-sync-sdk` is not released yet.

- **Server** (`tap start` / `stop` / `status`, JVM or native binary for Linux x86-64 and macOS
  arm64): one per machine, loopback gRPC with a bearer token, per-device locks shared across
  processes, driver install and session lifecycle, app lifecycle (install, launch, cold launch,
  force-stop, clear data, grant permission, process identity), failure capture.
- **Driver**: a separate package from the app under test; selectors re-resolved on every
  command with exactly one match required before any input; explicit waits (visible, gone,
  exactly one, app visible, screen stable, animation end); taps, text input, keys, swipes,
  scrolls, screenshots, hierarchy dumps; transport loss after acceptance reported as
  `INDETERMINATE`, never replayed.
- **Kotlin client**: coroutine SDK (`Device`, `App`, `Element`, selector DSL, typed errors
  and models) and the JUnit 5 extension (`@TapTest`, `tapTest { }`, named roles for several
  devices, `DeviceBarrier`, failure artifacts, opt-in per-class device reuse).
- **Python client**: the same API, synchronous, with a pytest plugin (`tap_device`,
  `tap_devices`, failure artifacts, device reuse by scope).
- **Agent tools** (experimental): `tap-agent`, a CLI and MCP server for coding agents — held
  sessions, screen snapshots with refs, `--settle` diffs, and `export` of the session's device
  calls as JSON (`tap-events/1`).
- **Experimental**: app synchronization (`awaitIdle` / `await_idle`, `syncAuthority`; Kotlin
  `@ExperimentalTapApi`) and the agent surface (held connections, `resume`, screen snapshots,
  `resolve_ref`, `event_log`, the `Events` RPC, `tap-events/1`).

Known limits: see `.docs/framework-gaps.md` — no crash/ANR error codes yet, no helper for
runtime permission dialogs, no WebView support, app synchronization only works with apps the
driver can see (the fixture app on Android 11+), and typed text (passwords included) is
recorded verbatim in the event log.
