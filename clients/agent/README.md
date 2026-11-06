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
- `record start NAME`, `record stop`, `record list`, `record clean NAME` manage private named
  recordings of high-level actions and explicit waits. `export [NAME] [-o FILE]` writes
  `tap-agent-recording/1` to stdout or a new 0600 file, even after release. It retains resolved
  selectors, device identity and outcomes, never replays input, and redacts sensitive text.
  Storage is under `TAP_AGENT_DIR/records` (default `.tap/agent/records`); share that directory
  between CLI/MCP processes. `clean --all` explicitly clears stopped archives.
- `history [-o FILE] [--after-seq N]` is the old export: the bounded raw SDK event log,
  `tap-events/1`, which ends with its session. `media-record start|stop` is video/audio capture.
  Converting either JSON document into a test remains the user's job.
- `tap-agent skill` prints `SKILL.md`, the instructions to give an agent that uses the CLI.
- `tap-agent mcp` serves the same steps as MCP tools over stdio, e.g. for Claude Code:
  `claude mcp add tap -- tap-agent mcp`.

Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon. Design: `.docs/agent-surface.md`.
