# Coding agents: tap-agent

!!! warning "Experimental"
    `tap-agent`, the server features it is built on (held connections, screen snapshots with
    refs, the event log) and the `tap-events/1` export format are experimental: they may change
    in any release, including after 1.0, until this notice goes away.

`tap-agent` lets a coding agent (Claude Code, Cursor, …) drive a device through the same `tap`
server your tests use. The agent reads the screen as text, acts on it, checks the result, and
hands you a log of what it did. That covers checking a change on a real device, reproducing a
bug, or exploring an app before writing a test for it.

It comes two ways, with the same steps and the same sessions: a **CLI** (one step per call,
for agents that run shell commands) and an **MCP server** (the steps as tools). Both are built
on the Python client. Every command is listed in the
[command reference](commands.md).

## Install

It needs the `tap` server ([The tap server](../guide/server.md)) and Python 3.10+.
The [Download](../download.md) bundle installs it with everything else; on its own, install the
Python client first, then the agent tools, from the GitHub releases:

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-agent/v0.0.2/tap_agent-0.0.2-py3-none-any.whl
tap start
```

## Connect your agent

=== "Claude Code (MCP)"

    ```bash
    claude mcp add tap -- tap-agent mcp
    ```

=== "Other MCP clients"

    Any client that starts a stdio MCP server, such as Cursor or Claude Desktop:

    ```json
    {
      "mcpServers": {
        "tap": {"command": "tap-agent", "args": ["mcp"]}
      }
    }
    ```

=== "CLI and a skill"

    An agent that runs shell commands can use the CLI directly. `tap-agent skill` prints a
    `SKILL.md` that teaches it the flow below; save it where your agent reads skills or
    instructions (for Claude Code, `.claude/skills/tap-android/SKILL.md`; the skill is named `tap-android`).

    ```bash
    mkdir -p .claude/skills/tap-android && tap-agent skill > .claude/skills/tap-android/SKILL.md
    ```

Then ask for something concrete: *"On the emulator, open the Settings app, search for dark
mode and tell me which settings come up."*

## Next

- [A session, step by step](session.md): attach, read the screen, act, check, and what to do
  when a step fails, from a real session.
- [Sessions to tests](export.md): export what the agent did and turn it into a test.
- [Commands](commands.md): every command and MCP tool, with its options.
