# tap-agent

A CLI and an MCP server for coding agents that drive Android devices through a running Tap
daemon (`tap start`). Both are thin front ends over one core (`tap_agent.core.Agent`), which
talks to the daemon only through the `tap-e2e` client library.

```sh
pip install -e clients/python -e clients/agent
tap-agent attach emulator-5554 com.example.app --cold
tap-agent snapshot
tap-agent tap @e3 --settle
tap-agent release
```

- Sessions are the daemon's *held connections*: they survive between calls and end after an
  idle timeout (15 min by default) or `release`. The CLI and the MCP server share them.
- `tap-agent skill` prints `SKILL.md`, the instructions to give an agent that uses the CLI.
- `tap-agent mcp` serves the same steps as MCP tools over stdio, e.g. for Claude Code:
  `claude mcp add tap -- tap-agent mcp`.

Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon. Design: `.docs/agent-surface.md`.
