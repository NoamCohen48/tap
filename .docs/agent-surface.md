# Agent surface — `tap-agent` CLI and MCP server for coding agents

Date: 2026-09-28. Decision record, approved by the owner. It adds an agent-facing surface:
`tap-agent`, a Python CLI and MCP server built on the `tap-e2e` client library, and makes the
daemon changes that surface needs. The
upstream reference is callstack/agent-device (`upstream-reference-audit.md`, rows "Agent-facing
surface" and "Ref-addressed elements").

## Research summary

| Tool | Surface | Taken |
|---|---|---|
| Playwright CLI (Microsoft) | CLI + background session per `-s=name`, idle shutdown after 1 h, installable skills | Big outputs go to files; stdout stays short. Microsoft reports ~4× fewer tokens than their MCP server for coding agents. |
| callstack/agent-device | CLI, `agent-device mcp` and a Node API over one runtime | `@eN [role] "label"` snapshots, `-i` interactive-only, `--settle` returning a diff, device claims, evidence on disk, replay scripts exportable to Maestro. |
| mobile-mcp | MCP only (stdio / SSE + bearer) | Accessibility tree first, screenshots + coordinates as the fallback. |
| Maestro MCP | Coarse tools: `list_devices`, `inspect_screen`, `run(yaml)`, `take_screenshot` | — |

None of these can drive a device Tap holds: they bring their own UiAutomation client (one per
device at a time) and ignore the per-serial lock.

## Options considered

- **A. Skill file over the existing Python client.** Nothing to build, but every agent step
  pays a full attach (1.2 s emulator, 3.8 s Samsung) and there is no live session.
- **B. MCP only.** One long-lived process holds the connection. Tool schemas and payloads sit
  in the agent's context, and each agent needs configuring.
- **C. CLI + skill, with a client-side background broker** holding the connection.
- **D. One client-side core (Python) behind both a CLI (via a broker) and MCP.**
- **E (chosen). The daemon is the core.** The daemon already is the long-lived process that
  owns devices; a client-side broker would only exist to keep a connection alive inside it.
  Three daemon changes remove the need for one, and the CLI and MCP become thin, stateless
  shells over RPCs.

## Decision

1. **Held connections** (daemon). A connection is a lease with two possible renewal rules:
   - *observed* (today, the default): renewed by the open `Observe` stream, expires the moment
     it ends. Tests keep this: a crashed or killed run frees its devices at once.
   - *held* (`ConnectRequest.hold`): renewed by every call that names the connection; expires
     after `hold.idle_timeout_ms` without one, or on `Disconnect` / daemon shutdown. It needs no
     `Observe` stream (opening one is `FAILED_PRECONDITION`) and is never reaped by the 30 s
     observe grace. Its name is unique among held connections, so a later process finds it by
     name (`ListConnections`). The cost, accepted: a crashed agent keeps its devices until the
     idle timeout or `tap release`.
   Held connections live in daemon memory: a daemon restart ends them like any other.
   Anyone with the daemon token can resume one by name — the same trust boundary as today
   (the token is the machine owner's).

2. **Screen snapshots with refs** (daemon, `DeviceService.ScreenSnapshot` / `ResolveRef`).
   - The daemon runs the existing diagnostic `DumpHierarchy`, parses the UiAutomator XML
     (every window root, visible nodes only), and returns compact `ScreenNode`s: ref, depth,
     window package, class, resource name, text, description, hint, bounds, flags, whether
     the node is interactive, and a **selector the daemon synthesised** for it.
   - Selector synthesis prefers what a person would write: resource id, text, description,
     their combinations with each other and the class, then the same with an `ancestor`
     relation to the nearest ancestor that has its own unique selector. Uniqueness is checked
     against the dump in the same scope (the AUT's windows → `aut`; another package's → `system`).
     The last resort is an `At(index)` pick, marked `by_index`. A node with none of these has no
     selector and cannot be acted on by ref.
   - A ref is a *name for that selector*, not a node handle. Acting on a ref resolves it with
     `ResolveRef` and sends the ordinary command; the driver still demands exactly one match and
     returns `AMBIGUOUS`/`NOT_FOUND` before input when the screen differs from the dump. So the
     invariants hold: no dump on the action path, no persistent node handles, exact-one mutations.
   - Refs stay stable across snapshots: each snapshot is aligned with the previous one (longest
     common subsequence over node signatures), unchanged nodes keep their ref, new nodes get new
     numbers from a per-device counter. An old ref is therefore either still the same node or
     unknown (`FAILURE_REASON_UNKNOWN_REF`) — it never silently names another node.
   - The response marks each node `ADDED`/`UNCHANGED` against the previous snapshot and lists
     the removed ones, which is the `--settle` diff.

3. **Per-connection event log** (daemon, plan §19). Every `Execute` and app lifecycle call is
   recorded with its command, resolved selector, result code and timing, in a bounded
   in-memory log per connection, read by `ClientConnectionService.Events`. `tap export`
   renders it as a Kotlin or Python test. This also starts the §19 event model the
   JUnit/HTML reports need.

4. **`tap-agent`: the CLI and the MCP server, in Python on `tap-e2e`** (owner's call: both
   agent front ends use the Python library). One package, `clients/agent` (`tap-agent`), with
   one core — sessions, targets, rendering, diffs, evidence — and two thin front ends over it:
   `tap-agent <verb>` (argparse, stdlib) and `tap-agent mcp` (the official `mcp` SDK, stdio),
   whose tools call the core in-process. The core is a client of the daemon through `tap-e2e`
   only, which gains what the agent needs and any Python user can use: held connections
   (`TapClient.connect(name, hold=…)`, `connections()`, `resume(name)`), devices already attached
   to a resumed connection, and `Device.screen_snapshot()` / `resolve_ref()`.
   A Kotlin CLI inside the native `tap` binary was considered first (one binary, ~ms startup)
   and dropped: it could not use the Kotlin SDK (`host/` never depends on `clients/`), so it
   would have re-implemented selectors, error mapping, streamed installs and capture on raw
   stubs, and the MCP server would have had to shell out to it. The cost accepted: agent tools
   need Python (`pipx`/`uvx`), and each CLI call pays Python start-up (a few hundred ms, small
   next to a device action). A Kotlin MCP inside the daemon was also rejected: the official
   Kotlin SDK (0.15.0) pulls Ktor server, kotlin-logging, Kotlin stdlib 2.4 and
   coroutines/serialization 1.11 into the native binary.

Deliberately not done: cheaper attach by keeping the driver instrumentation alive (a separate
performance TODO), Kotlin SDK APIs for held connections and snapshots (follow-up; the proto is
there), recording evidence video, coordinate taps.

## CLI shape

```
tap-agent attach <serial> <package> [--session S] [--idle 15m] [--launch|--cold] [--wait-for-device D]
tap-agent sessions                              held connections and their devices
tap-agent release [--session S]                 Disconnect: detach everything, end the session
tap-agent devices                               ListDevices

# device verbs take [--session S] [--device SERIAL] (needed only with several devices)
tap-agent snapshot [-i | --all]                compact outline; refs @eN
tap-agent tap <target> [--long] [--settle]
tap-agent fill <target> <text> [--settle]       SetText
tap-agent type <text> [--settle]                TypeText into the focused node
tap-agent clear <target>
tap-agent scroll <target> <up|down|left|right> [--settle]
tap-agent swipe <target> <up|down|left|right> [--settle]
tap-agent key <back|home|enter|...> [--settle]
tap-agent wait <target> [--gone|--one] [--timeout 10s]
tap-agent settle                                WaitScreenStable, then the snapshot diff
tap-agent screenshot [-o FILE]                  prints the path
tap-agent capture [-o DIR]                      screenshot, hierarchy, device info, driver log
tap-agent app <launch|cold-launch|stop|clear|install APK|grant PERM>
tap-agent export --kotlin|--python [-o FILE]
tap-agent mcp                            the MCP server (stdio)
```

`<target>` is a ref (`@e7`) or a selector: `id=login`, `text=Log in`, `desc=Close`,
`class=android.widget.Button`, several joined with commas meaning "all of". Outputs are short
text on stdout, errors on stderr; exit 0 ok, 1 the operation failed, 2 usage, 3 no daemon
(`tap start`). Files (screenshots, captures) are written
under `.tap/agent/` in the working directory by default and their paths printed.

Snapshot line format:
```
@e3  [Button] "Log in"  id=login_button
@e5  [EditText] hint="Email"  id=email  focused
@e9  [TextView] "Welcome"
```
`--settle` and `tap settle` print the diff: `+` added, `-` removed, unchanged nodes omitted
(`--full` shows them with `=`).

## Phases (one commit each)

Status: 1–6 done; 7 (event log + export) open.

1. Contract: this record, proto for phases 2–3 (the event-log proto comes with phase 6), docs.
2. Held connections: daemon core + `ClientConnectionService` + unit tests.
3. Snapshots: parser, selector synthesis, ref alignment, `ScreenSnapshot`/`ResolveRef` + unit tests.
4. `tap-e2e`: held connections, resume, screen snapshots, resolve ref; unit tests on the fake server.
5. `tap-agent` core + CLI + `SKILL.md`; device run on the local matrix (fixture app).
6. `tap-agent mcp` over the same core; unit tests; smoke with a real MCP client.
7. Event log + `tap-agent export`; the exported Python/Kotlin tests run against the fixture app.

## Status

- Phase 3 (snapshots) is implemented in `host/daemon/.../daemon/snapshot/` and wired into
  `DeviceService`; unit-tested on six recorded fixture dumps (both local devices × three
  activities), with the device check pending in phase 4. Details the record did not fix:
  - Scope: a node in an AUT window gets `aut`; a node in any other package's window gets
    `any_window` (not `system{package}`, which searches only that package's *focused* window,
    so the status bar, navigation bar and overlays would resolve to `NOT_FOUND`).
  - Uniqueness emulates the driver's *native* plan, which every synthesised selector compiles
    to. In `aut` the `pkg` filter applies to the node itself (an AUT-window node of another
    package gets no selector), and because the dump does not say which window is focused,
    matches are counted over every AUT window (never fewer than the driver's); an `At` index is
    counted within the node's own window. In `any_window` matches and `At` indexes are counted
    over the whole dump.
  - In `aut` scope a resource id of another package (`android:id/content`) is not used: the
    driver denies it (`SCOPE_DENIED`).
  - The ancestor round also tries the class alone with the ancestor ("a Button under X"); the
    ancestor must be at most 32 levels up (the driver's traversal walk bound).
  - The dump is parsed by a small hand parser (DTDs rejected, only predefined entities and
    character references), so the native image needs no extra metadata for it.

- Phases 4–6 (2026-09-28): `tap-e2e` held connections/resume/snapshots, `clients/agent`
  (`tap-agent` CLI + `tap-agent mcp`), 39 unit tests over the fake daemon. Device run on the
  native daemon: a CLI session on emulator-5554 (attach --cold, snapshot, tap by ref with
  `--settle`, an ambiguous text selector refused before input, fill, wait, a status-bar ref
  tapped through `any_window`, an unknown ref, key back, screenshot, capture, release) and an
  MCP stdio session on 85e49002 (attach, snapshot, tap/fill with settle, wait, screenshot as
  image content) at the same time, each in its own session; exit 3 without a daemon. The
  Python device suite (130) and `:samples:fixture-tests` (17) passed on both devices after it.
  A changed text shows as `+` new ref / `-` old ref (the ref signature includes the text); a
  diff that removes a whole screen lists 10 removed nodes and counts the rest.

## Verification

- Unit: daemon core (idle expiry, renewal, name uniqueness, observe rejection, grace-reaper
  skip), snapshot (parser on recorded dumps from both devices, synthesis uniqueness, ref
  alignment), `tap-agent` (target parsing, rendering, diff, CLI argument mapping, MCP tool schemas and error results, against the fake server).
- Device (emulator-5554 + 85e49002, never rebooted): an agent-style CLI session on the fixture
  app — attach, snapshot, tap by ref, fill, settle diff, wait, capture, release — and an
  MCP session doing the same; every ref action checked against the driver's exact-one answer.
- Native image: the daemon built with `nativeCompile` serves the same session; re-record
  reachability metadata if the XML parser needs it.
