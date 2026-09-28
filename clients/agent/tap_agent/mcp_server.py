"""``tap-agent mcp``: the same steps as the CLI, as MCP tools over stdio.

Each tool calls :class:`~tap_agent.core.Agent` in-process (on a worker thread: the client
blocks). Sessions are the daemon's held connections, so a session started here is visible to
``tap-agent`` on the command line and the other way round.
"""

from __future__ import annotations

import base64
import functools
from collections.abc import Callable
from typing import Annotated, Literal

import anyio
from mcp.server.mcpserver import MCPServer
from mcp_types import CallToolResult, ImageContent, TextContent, ToolAnnotations
from pydantic import Field

from . import render
from .core import EXIT_NO_DAEMON, EXIT_USAGE, Agent, AgentError
from .targets import SELECTOR_KEYS

INSTRUCTIONS = """\
Drive an Android device through the Tap daemon (it must be running: `tap start`).
Flow: devices -> attach(serial, package) -> snapshot -> act (tap, fill, scroll, key, ...) -> release.
snapshot prints one line per node: `@e3  [Button] "Log in"  id=login_button`. Pass `@e3` as
`target`, or write a selector. A ref keeps naming the same node while it stays on screen; an
action with settle=true waits for the screen to settle and returns what changed (+ added,
- removed), so a fresh snapshot is rarely needed. An action needs exactly one matching node.
The session lives in the daemon and ends after 15 idle minutes or on release."""

Target = Annotated[
    str,
    Field(
        description=(
            "A ref from snapshot (`@e7`) or selector terms joined by commas, all of which must "
            f"match: `id=login`, `text=Log in`, `desc=Close`, `class=Button` (keys: {SELECTOR_KEYS})."
        )
    ),
]
Device = Annotated[str, Field(description="Serial; needed only when the session has several devices.")]
Settle = Annotated[bool, Field(description="Wait for the screen to settle, then return what changed.")]
Direction = Literal["up", "down", "left", "right"]
Session = Annotated[str, Field(description="Session name; omit for the server's default session.")]

READ_ONLY = ToolAnnotations(read_only_hint=True)


def _text(text: str) -> CallToolResult:
    return CallToolResult(content=[TextContent(type="text", text=text)])


def _error(error: AgentError) -> CallToolResult:
    text = str(error)
    if error.exit_code == EXIT_NO_DAEMON:
        text += "\nThe tap daemon must be running: ask the user to run `tap start`, then retry."
    elif error.exit_code == EXIT_USAGE:
        text += "\n(invalid arguments)"
    return CallToolResult(content=[TextContent(type="text", text=text)], is_error=True)


def create_server(agent_for: Callable[[str], Agent] | None = None, default_session: str | None = None) -> MCPServer:
    """The MCP server. ``agent_for(session)`` builds the agent for a session name (tests pass
    one over a fake daemon); agents are kept per session for the server's lifetime."""
    agents: dict[str, Agent] = {}
    build = agent_for or (lambda session: Agent(session))
    fallback = Agent(default_session).session

    def agent(session: str) -> Agent:
        name = session or fallback
        if name not in agents:
            agents[name] = build(name)
        return agents[name]

    async def call(session: str, step: Callable[[Agent], str]) -> CallToolResult:
        try:
            return _text(await anyio.to_thread.run_sync(functools.partial(step, agent(session))))
        except AgentError as error:
            return _error(error)

    mcp: MCPServer = MCPServer("tap", instructions=INSTRUCTIONS)

    @mcp.tool(name="devices", annotations=READ_ONLY)
    async def devices() -> CallToolResult:
        """List the devices ADB sees, their state and the session holding each."""
        return await call("", lambda a: a.devices())

    @mcp.tool(name="attach")
    async def attach(
        serial: Annotated[str, Field(description="Device serial from `devices`.")],
        package: Annotated[str, Field(description="Package of the app under test.")],
        launch: Annotated[
            Literal["none", "launch", "cold"],
            Field(description="launch: start the app; cold: force-stop it first, then start it."),
        ] = "none",
        idle_minutes: Annotated[float, Field(gt=0, description="End the session after this long unused.")] = 15,
        wait_for_device_seconds: Annotated[float, Field(ge=0, description="Wait this long for a device another session holds.")] = 0,
        session: Session = "",
    ) -> CallToolResult:
        """Attach a device to the session, creating the session if needed. Call this first."""
        return await call(
            session,
            lambda a: a.attach(
                serial,
                package,
                idle=idle_minutes * 60,
                launch=None if launch == "none" else launch,
                wait_for_device=wait_for_device_seconds,
            ),
        )

    @mcp.tool(name="sessions", annotations=READ_ONLY)
    async def sessions() -> CallToolResult:
        """List sessions, how long each has been idle, and their devices."""
        return await call("", lambda a: a.sessions())

    @mcp.tool(name="release")
    async def release(session: Session = "") -> CallToolResult:
        """End the session: detach its devices so others can use them."""
        return await call(session, lambda a: a.release())

    @mcp.tool(name="snapshot", annotations=READ_ONLY)
    async def snapshot(
        view: Annotated[
            Literal["default", "interactive", "all"],
            Field(description="interactive: only nodes an action can target; all: layout containers too."),
        ] = "default",
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """The current screen, one node per line with its ref (`@eN`)."""
        level = {"default": render.DEFAULT, "interactive": render.INTERACTIVE, "all": render.ALL}[view]
        return await call(session, lambda a: a.snapshot(device or None, level))

    @mcp.tool(name="tap")
    async def tap(
        target: Target,
        long: Annotated[bool, Field(description="Long-press instead.")] = False,
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Tap the one node matching target."""
        return await call(session, lambda a: a.tap(target, device or None, long=long, settle=settle))

    @mcp.tool(name="fill")
    async def fill(
        target: Target,
        text: Annotated[str, Field(description="The new text.")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Replace a field's text (accessibility set-text; no key events)."""
        return await call(session, lambda a: a.fill(target, text, device or None, settle=settle))

    @mcp.tool(name="type_text")
    async def type_text(
        text: Annotated[str, Field(description="Characters to type.")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Type key events into whatever has input focus (tap a field first)."""
        return await call(session, lambda a: a.type(text, device or None, settle=settle))

    @mcp.tool(name="clear")
    async def clear(target: Target, settle: Settle = False, session: Session = "", device: Device = "") -> CallToolResult:
        """Clear a field's text."""
        return await call(session, lambda a: a.clear(target, device or None, settle=settle))

    @mcp.tool(name="scroll")
    async def scroll(
        target: Target, direction: Direction, settle: Settle = False, session: Session = "", device: Device = ""
    ) -> CallToolResult:
        """One scroll gesture on a scrollable node; `down` reveals content below."""
        return await call(session, lambda a: a.scroll(target, direction, device or None, settle=settle))

    @mcp.tool(name="swipe")
    async def swipe(
        target: Target, direction: Direction, settle: Settle = False, session: Session = "", device: Device = ""
    ) -> CallToolResult:
        """One swipe across a node, the finger moving in `direction`."""
        return await call(session, lambda a: a.swipe(target, direction, device or None, settle=settle))

    @mcp.tool(name="press_key")
    async def press_key(
        key: Annotated[str, Field(description="back, home, enter, tab, delete, escape, ... or an Android key code.")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Press a key."""
        return await call(session, lambda a: a.key(key, device or None, settle=settle))

    @mcp.tool(name="wait_for", annotations=READ_ONLY)
    async def wait_for(
        target: Target,
        state: Annotated[
            Literal["visible", "gone", "one"], Field(description="one: exactly one node matches.")
        ] = "visible",
        timeout_seconds: Annotated[float, Field(gt=0, le=600)] = 10,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Wait, polling on the device, until target is visible, gone, or exactly one node."""
        return await call(session, lambda a: a.wait(target, device or None, state, timeout_seconds))

    @mcp.tool(name="settle", annotations=READ_ONLY)
    async def settle(
        full: Annotated[bool, Field(description="Also list unchanged nodes (=).")] = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Wait for the screen to stop changing, then list what changed since the last snapshot."""
        return await call(session, lambda a: a.settle(device or None, full=full))

    @mcp.tool(name="screenshot", annotations=READ_ONLY)
    async def screenshot(session: Session = "", device: Device = "") -> CallToolResult:
        """A PNG of the screen, also saved to disk. Prefer snapshot: it is text and has refs."""
        try:
            path = await anyio.to_thread.run_sync(lambda: agent(session).screenshot(device or None))
        except AgentError as error:
            return _error(error)
        data = base64.b64encode(path.read_bytes()).decode("ascii")
        return CallToolResult(
            content=[
                ImageContent(type="image", data=data, mime_type="image/png"),
                TextContent(type="text", text=str(path)),
            ]
        )

    @mcp.tool(name="capture", annotations=READ_ONLY)
    async def capture(session: Session = "", device: Device = "") -> CallToolResult:
        """Save a screenshot, the hierarchy, device info and the driver log; returns the paths."""
        return await call(session, lambda a: a.capture(device or None))

    @mcp.tool(name="app")
    async def app(
        action: Literal["launch", "cold-launch", "stop", "clear", "install", "uninstall", "grant", "running"],
        argument: Annotated[
            str, Field(description="launch/cold-launch: activity (optional); install: APK path; grant: permission.")
        ] = "",
        package: Annotated[str, Field(description="Another package than the session's app.")] = "",
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """App lifecycle: launch, cold-launch (force-stop first), stop, clear data, install,
        uninstall, grant a runtime permission, or ask whether it is running."""
        return await call(session, lambda a: a.app(action, argument or None, device or None, package or None))

    return mcp


def serve(default_session: str | None = None) -> None:
    create_server(default_session=default_session).run("stdio")
