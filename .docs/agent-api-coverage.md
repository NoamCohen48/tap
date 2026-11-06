# Agent SDK coverage

Owner-approved follow-up to the agent API audit: improve practical daemon/Python/Kotlin SDK
coverage in one separate change after the gesture/lifecycle/private-data work. Keep the agent a
thin Python SDK adapter, not a second gRPC client or a raw Execute endpoint.

## Scope and checked registry

`clients/agent/api-coverage.json` classifies the implemented server API and operational SDK
surface. `tests/test_api_coverage.py` compares it with generated protobuf descriptors, public
methods/properties on nine Python SDK classes, CLI parser fields and MCP schemas. The existing
reference check also requires every verb/tool in its generated documentation group.

Current inventory:

- **52 of 56 RPCs** reached through SDK-backed tools/helpers; the other four are deliberately
  excluded below. This is practical reachability, not identical RPC signatures.
- **42 of 42 Execute operations** mapped to tools, without exposing Execute itself.
- **50 CLI verbs / 48 MCP tools**, including two local-only CLI verbs (`mcp`, `skill`). Field-name
  parity accounts explicitly for names/units (`--idle` versus minutes, CLI duration strings
  versus MCP seconds), views, session-wide discovery and optional-string normalization.
- SDK aliases are grouped: element text/flags come from inspection, triple-tap from count=3,
  descendant/child/first/index from selectors, and settle/animation-end from tree/pixels signals.
  Representation helpers, local handle state and arbitrary Python predicates remain client-only.
  Kotlin's protocol-backed operations mirror this scope; coroutine/JUnit/model/artifact utilities
  are not tool endpoints. The reflection test covers the named Python classes, not every Kotlin
  extension or all SDK modules.

Do not add an RPC, operation, SDK method or front-end parameter silently: classify it, implement
an adapter or record an intentional exclusion with a reason, and add an affected-API test.
Registry membership and matching parameter names alone do **not** prove correct value forwarding
or identical semantics; the adapter regression tests exercise those separately.

## Implemented adapters

- Immediate exists/count/SDK state inspection; enabled/disabled/checked/unchecked/focused,
  text/text-contains/count waits. Property/count waits reuse SDK host polling; visibility/gone/one
  and app/tree/pixel waits retain their existing SDK paths.
- Existing comma targets plus boolean/text-mode predicates and bounded logical/relational JSON
  selectors. No XPath, persistent handles or coordinates; mutations still require one match.
- Protocol-budget validation (1ms..120s) before connection/input, matching MCP schemas; standalone
  settle exposes its timeout. Element inputs and supported lifecycle/process/visibility actions
  forward explicit budgets. SDK fixed/default-budget calls retain those budgets; an unsupported
  app timeout is rejected rather than ignored.
- App installed/process/visibility, device inventory properties and full daemon/device metadata,
  per-device detach while preserving the held session and its other devices.
- Bounded SDK scroll-until, gesture distance, targeted typing with configurable focus wait,
  explicit package/quiet-period/tree/pixel stability, selector-candidate snapshots and incremental
  event export. Targeted typing remains tap → focus wait → type, with separate step budgets.
- Explicit recording start/stop through SDK scrcpy capture. SDK checksums/formats are reused;
  stop first saves a canonical 0600 artifact in a 0700 directory. Custom-copy failure identifies
  that retained file, never repeats stop. Existing outputs are refused; failure never restarts
  recording or an app. Standalone logs/hierarchy also go to new 0600 files, never inline.

## Intentionally excluded

- `ClientConnectionService.Observe`: test-client liveness; agents use held sessions.
- `AppService.AwaitIdle` / SDK awaitIdle: synchronization remains work in progress. Ordinary
  element/state/tree/pixel waits are independent and supported.
- `WatchService.Watch` and `WatchVideo`: passive shared observation belongs to tap-watcher.
  Recording an agent-owned device is a different, explicitly requested capability.

No implicit lifecycle, root, shell/SQL endpoint, mutation replay or data restoration was added.
The existing recorder startup-readiness/shutdown timing limitations remain deferred.

## Verification and observed boundaries

- Agent unit suite: **167 passed**, two opt-in device checks skipped without explicit serial.
- Python SDK regression suite: **168 passed**, run separately with absolute PYTHONPATH. Its fake
  daemon now removes successfully detached attachments from inventory, matching server behavior.
- Explicit project Pyright: no errors/warnings on agent sources and new tests.
- Full `scripts/build-docs.sh`: strict website plus generated Kotlin/Python/gRPC/agent references
  and Markdown bundle passed. Generated command reference remains ignored, not committed.
- API 34 `emulator-5554`, existing native daemon built with the merged protocol/driver:
  **2 device checks passed**. Queries, process/visibility, targeted typing, state/count/text waits,
  tree+pixel stability, bounded scrolling, private diagnostics, incremental export, detach and
  checksum-verified private video output were exercised. Only the installed repository fixture
  was changed; no reboot. API 29 Samsung was disconnected and was **not run**.

Reproduce the optional device flow after explicitly starting a daemon with the current driver
and configured scrcpy and installing the fixture:

```sh
TAP_AGENT_DEVICE_SERIAL=emulator-5554 TAP_AGENT_RECORDING=1 \
  PYTHONPATH="$PWD/clients/python:$PWD/clients/agent" \
  .venv/bin/python -m pytest clients/agent/tests/device -vv
```

Earlier device attempts were not uninterrupted passes. One first-focus input produced a duplicate
initial character; a separate probe and the final flow explicitly observed keyboard readiness
before typing. Focus confirmation alone does not prove IME readiness, and input was never replayed
to repair the result. Empty hinted fields can expose their hint as accessibility text. Another
attempt found that normal launch brought the fixture's existing task top (the list) back instead
of resetting the root form: the final tests explicitly cold-launch the fixture and wait for their
screen's node. Package visibility alone is not destination-screen readiness. These existing SDK/
Android boundaries are not hidden by agent retries, sleeps after mutations, or implicit resets.

Evidence logs: `/tmp/tap-agent-coverage-unit-final.log`,
`/tmp/tap-agent-coverage-python-regression.log`, `/tmp/tap-agent-coverage-docs-final.log`,
`/tmp/tap-agent-coverage-device-cold.log`. Local native daemon was stopped after validation.
