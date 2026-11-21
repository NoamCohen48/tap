# tap-agent

A CLI and an MCP server for coding agents that drive Android devices through a running Tap
daemon (`tap start`). Both are thin front ends over one core (`tap_agent.core.Agent`), which
talks to the daemon only through the `tap-e2e` client library.

```sh
pip install -e clients/python -e clients/agent
tap-agent attach emulator-5554
tap-agent app cold-launch com.example.app
tap-agent snapshot
tap-agent tap @e3 --settle
tap-agent release
```

- Sessions are the daemon's *held connections*: they survive between calls and end after an
  idle timeout (15 min by default) or `release`. The CLI and the MCP server share them.
- Activity is kept automatically: the server keeps every call, crash, ANR, capture gap and
  device-log line, and the agent its own steps. `history [--logs] [--json] [--from TIME]
  [--until TIME] [--limit N]` reads one page of them merged, the newest first for a window that
  ends now (no start, `--last`, `--last-action`); follow the printed `next:` / `older:` position
  with `--after`. `--limit` counts timeline entries. Output
  is readable stdout text or `tap-history/2` JSON; redirect it yourself to save a file.
- For an unexpected incident, `history --last 30s --logs --details` reads everything kept in
  the window, including idle periods and sessions with no local steps. Crashes/ANRs are
  explicit in text and JSON regardless of `--logs`; redacted stacks with `--details` (always in
  JSON). Missing evidence is a `GAP:` entry or a `WARNING:` issue, never silently dropped.
- `record start NAME` keeps a named range of this session's history (every device attached to
  it, also later ones) past rolling eviction; `record stop NAME` ends it, as does `release`.
  `history --recording NAME` reads it, also after release/restart. `record list` prints names,
  `-L` adds state, times, id and bytes. `record clean NAME` deletes a stopped recording.
- Local steps are private under `TAP_AGENT_DIR/records` (default `.tap/agent/records`); share
  it between CLI/MCP processes. SDKs read history directly through `client.history`, never
  through the CLI. Defaults: one hour / 256 MiB rolling, 1 GiB hard budget, 256 MiB free-space
  reserve; configure with `tap config logging-policy`. Recordings cannot override hard limits.
- `history` is the only reader: repeated `--device` filters select devices; log filters
  (`--package`, `--priority`, `--tag`, `--grep`) imply logs, never filter calls, crashes or gaps.
  `--step CURSOR|ID`, `--last-action` and marker bounds (`--since-marker`, `--until-marker`)
  select a range; the server resolves markers among this session's connections.
- `history mark NAME` and `history redact REGEX` control this session's held attachments only
  (all by default, or repeated `--device`). Bookmarks do not protect data. Partial acknowledgement
  is explicit. No standalone `log` command/tool remains.
- `capture -o NEW_DIRECTORY` collects current state into private files and indexes timestamped
  external references in history; reads never capture fresh state. Cleaning
  history does not delete the separately saved files. Driver diagnostics are daemon administration:
  `tap logs driver --serial SERIAL`; `tap logs` still reads daemon.log.
  `media-record start|stop` is separate video/audio capture. Converting history into tests remains the user's job.
- Every step ends with the crashes and ANRs of watched apps that no earlier step reported (top
  frames inline, the full trace in a private file under `deaths/`); such a step exits 4.
  `--expect-death` makes a step require one, `deaths` lists the server's record, and
  `deaths --wait` blocks until the next one, for an agent to run in the background (it keeps no
  idle session alive). An exported step lists the deaths it reported. Design: `.docs/app-deaths.md`.
- `tap-agent skill` prints `SKILL.md`, the instructions to give an agent that uses the CLI.
- `tap-agent mcp` serves the same steps as MCP tools over stdio, e.g. for Claude Code:
  `claude mcp add tap -- tap-agent mcp`.

## Collection reads

`elements TARGET` returns a JSON array of current SDK element snapshots, including checkbox
state (`checkable`, `checked`), `enabled` and `selected`. `--texts` returns just raw text:

```sh
tap-agent elements id=title,pkg=com.shop --texts
tap-agent elements '{"id":"price","ancestor":{"id":"products"},"pkg":"com.shop"}' --texts
tap-agent elements checkable=true,pkg=com.shop --timeout 3s
```

The read-only MCP `elements` tool takes the same target, `texts` (default false),
`timeout_seconds`, session and device. Each call uses one fresh bulk read, without a diagnostic
snapshot or persistent handles. Use written selectors, not snapshot refs, `first` or `index`.
Results follow accessibility window order, then post-order inside each window—not necessarily
visual order. Only exposed nodes are included; no auto-scroll, implicit wait or atomic-UI
transaction. No matches returns `[]`; texts preserve duplicates, empty strings and nulls.
Android may report an empty field's hint as text (`showing_hint` in snapshots). A stale read or
overflow (more than 1000 matches / a snapshot list over 1 MiB) fails the whole operation, never
returns a partial/truncated list. Like `inspect`, full state can include sensitive text.

Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon, 4 the step ran but a watched app
crashed or stopped responding. Design: `.docs/agent-surface.md`.
