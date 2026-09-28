# Agent surface — `tap` CLI verbs and `tap-mcp` for coding agents

Date: 2026-09-28. Decision record, approved by the owner. It adds an agent-facing surface:
CLI verbs in the `tap` binary and a Python MCP server over them, and makes the daemon changes that surface needs. The
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

4. **Agent verbs in the `tap` binary** (CLI). They are clients of the daemon over the
   `:contracts:api` stubs, like `start`/`status`/`stop` already are — they never use the SDKs
   (`host/` never depends on `clients/`). Per `cli-parsing.md`, this second tier of commands
   is when the CLI moves to **Clikt (`clikt-core`)**.

5. **`tap-mcp`** (MCP, Python, stdio; owner's call: "use python for the mcp, it would be
   simpler"). A small Python package, `clients/mcp` (`tap-mcp`), on the official `mcp` SDK
   (FastMCP). Each tool runs the matching `tap` CLI verb and returns its output (screenshots as
   image content read from the file the CLI wrote), so the CLI stays the single implementation
   of selector parsing, rendering and diffs, and the two can never disagree. Sessions are the
   CLI's held connections by name (default `default`), so an agent can mix the two and a
   restarted MCP server finds its device again. It needs the `tap` binary on `PATH` (or
   `TAP_BIN`), which the daemon needs anyway. It depends on neither gRPC nor `tap-e2e`.
   A Kotlin MCP inside the daemon binary was rejected: the official Kotlin SDK (0.15.0) pulls
   Ktor server, kotlin-logging, Kotlin stdlib 2.4 and coroutines/serialization 1.11 into the
   native binary, and hand-writing the protocol is more code to own than a Python wrapper.

Deliberately not done: cheaper attach by keeping the driver instrumentation alive (a separate
performance TODO), SDK APIs for held connections and snapshots (follow-up; the proto is
there), recording evidence video, coordinate taps.

## CLI shape

```
tap attach <serial> <package> [--session S] [--idle 15m] [--launch|--cold] [--wait-for-device D]
tap sessions                              held connections and their devices
tap release [--session S]                 Disconnect: detach everything, end the session
tap devices                               ListDevices

# device verbs take [--session S] [--device SERIAL] (needed only with several devices)
tap snapshot [-i | --all] [--json]        compact outline; refs @eN
tap tap <target> [--long] [--settle]
tap fill <target> <text> [--settle]       SetText
tap type <text> [--settle]                TypeText into the focused node
tap clear <target>
tap scroll <target> <up|down|left|right> [--settle]
tap swipe <up|down|left|right> [--settle]
tap key <back|home|enter|...> [--settle]
tap wait <target> [--gone|--one] [--timeout 10s]
tap settle                                WaitScreenStable, then the snapshot diff
tap screenshot [-o FILE]                  prints the path
tap capture [-o DIR]                      screenshot, hierarchy, device info, driver log
tap app <launch|cold-launch|stop|clear|install APK|grant PERM>
tap export --kotlin|--python [-o FILE]
```

`<target>` is a ref (`@e7`) or a selector: `id=login`, `text=Log in`, `desc=Close`,
`class=android.widget.Button`, several joined with commas meaning "all of". Outputs are short
text on stdout; `--json` gives the structured form; files (screenshots, captures) are written
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

1. Contract: this record, proto for phases 2–3 (the event-log proto comes with phase 6), docs.
2. Held connections: daemon core + `ClientConnectionService` + unit tests.
3. Snapshots: parser, selector synthesis, ref alignment, `ScreenSnapshot`/`ResolveRef` + unit tests.
4. CLI on Clikt: existing verbs migrated unchanged, agent verbs, `SKILL.md`; device run on the
   local matrix (fixture app), native-image smoke.
5. `tap-mcp` (Python): tools over the CLI; unit tests with a fake `tap`; smoke with a real MCP client.
6. Event log + `tap export`; the exported Python/Kotlin tests run against the fixture app.

## Verification

- Unit: daemon core (idle expiry, renewal, name uniqueness, observe rejection, grace-reaper
  skip), snapshot (parser on recorded dumps from both devices, synthesis uniqueness, ref
  alignment), `tap-mcp` (tool schemas, argument mapping, error results, against a fake `tap`), CLI parsing.
- Device (emulator-5554 + 85e49002, never rebooted): an agent-style CLI session on the fixture
  app — attach, snapshot, tap by ref, fill, settle diff, wait, capture, release — and an
  MCP session doing the same; every ref action checked against the driver's exact-one answer.
- Native image: `tap` built with `nativeCompile`, the same CLI session on the native binary;
  re-record reachability metadata if the XML parser or Clikt needs it.
