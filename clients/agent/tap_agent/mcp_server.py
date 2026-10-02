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
Flow: devices -> attach(serial) -> app(cold-launch, package) -> snapshot -> act (tap, fill,
scroll, key, ...) -> release. Selectors search the whole screen unless `pkg=` names a package.
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
        idle_minutes: Annotated[float, Field(gt=0, description="End the session after this long unused.")] = 15,
        wait_for_device_seconds: Annotated[float, Field(ge=0, description="Wait this long for a device another session holds.")] = 0,
        session: Session = "",
    ) -> CallToolResult:
        """Attach a device to the session, creating the session if needed. Call this first."""
        return await call(
            session,
            lambda a: a.attach(
                serial,
                idle=idle_minutes * 60,
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
        double: Annotated[bool, Field(description="Double-tap instead.")] = False,
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Tap the one node matching target."""
        return await call(session, lambda a: a.tap(target, device or None, long=long, settle=settle, double=double))

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

    @mcp.tool(name="fling")
    async def fling(
        target: Target, direction: Direction, settle: Settle = False, session: Session = "", device: Device = ""
    ) -> CallToolResult:
        """One fast swipe on a scrollable node towards a content edge, as for scroll; the content
        may keep moving afterwards (use settle)."""
        return await call(session, lambda a: a.fling(target, direction, device or None, settle=settle))

    @mcp.tool(name="drag")
    async def drag(
        target: Target,
        destination: Annotated[str, Field(description="Where to drop it: a ref (@e7) or selector terms, as for target.")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Long-press the node matching target, move it onto the node matching destination and drop it."""
        return await call(session, lambda a: a.drag(target, destination, device or None, settle=settle))

    @mcp.tool(name="pinch")
    async def pinch(
        target: Target,
        how: Annotated[Literal["open", "close"], Field(description="open: fingers apart (zoom in); close: together.")],
        percent: Annotated[int, Field(ge=1, le=100, description="How far across the node the fingers move.")] = 80,
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Two-finger pinch on a node (a map, an image)."""
        return await call(session, lambda a: a.pinch(target, how, percent, device or None, settle=settle))

    @mcp.tool(name="submit")
    async def submit(target: Target, settle: Settle = False, session: Session = "", device: Device = "") -> CallToolResult:
        """Run a text field's keyboard action (Search, Go, Send, Done, ... as the app set it up),
        exactly as the keyboard's action key does; press_key enter is not the same. The field must
        have input focus (tap it first). Android 11 (API 30)+."""
        return await call(session, lambda a: a.submit(target, device or None, settle=settle))

    @mcp.tool(name="accessibility_action")
    async def accessibility_action(
        target: Target,
        name: Annotated[
            str,
            Field(
                description="A standard action (expand, collapse, dismiss, scroll-forward, scroll-backward, page-down, "
                "show-on-screen, context-click, select, copy, paste, ...) or, with custom, the custom action's label. "
                "Omit to list what the node offers."
            ),
        ] = "",
        custom: Annotated[bool, Field(description="name is a custom action's label (e.g. 'Archive').")] = False,
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Perform an accessibility action on a node as a screen reader does (no touch), or list
        the actions it offers and its range. Use it for what a node offers instead of a gesture:
        expand/collapse a section, a list item's custom "Archive"/"Delete". An action the node does
        not offer fails before anything happens."""
        return await call(session, lambda a: a.action(target, name or None, device or None, custom=custom, settle=settle))

    @mcp.tool(name="set_progress")
    async def set_progress(
        target: Target,
        value: Annotated[float, Field(description="The value in the node's own units (see accessibility_action's range).")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Set a slider (SeekBar, Slider, RatingBar) to an exact value through accessibility
        ACTION_SET_PROGRESS. A value outside the node's range fails before anything happens."""
        return await call(session, lambda a: a.progress(target, value, device or None, settle=settle))

    @mcp.tool(name="keyboard")
    async def keyboard(
        action: Annotated[
            Literal["state", "hide"],
            Field(description="state: report whether a soft keyboard shows; hide: hide it (Back, only when one shows)."),
        ] = "state",
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """The soft keyboard: is it showing, or hide it."""
        return await call(session, lambda a: a.keyboard(action, device or None, settle=settle))

    @mcp.tool(name="set_location")
    async def set_location(
        latitude: Annotated[float, Field(ge=-90, le=90)],
        longitude: Annotated[float, Field(ge=-180, le=180)],
        accuracy_m: Annotated[float | None, Field(gt=0, description="Accuracy in meters; omit for 5.")] = None,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Mock the device location until release (the device's location providers report this
        fix, and location is turned on if it was off). Call again to move it; release restores
        the device."""
        return await call(session, lambda a: a.location(latitude, longitude, accuracy_m, device or None))

    @mcp.tool(name="push_file")
    async def push_file(
        local_path: Annotated[str, Field(description="The file on this machine.")],
        device_path: Annotated[str, Field(description="Absolute path on the device; its directory must exist (e.g. /sdcard/Download/a.pdf).")],
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Copy a local file to the device until release. A device file Tap did not push is never
        overwritten."""
        return await call(session, lambda a: a.push(local_path, device_path, device or None))

    @mcp.tool(name="pull_file", annotations=READ_ONLY)
    async def pull_file(
        device_path: Annotated[str, Field(description="Absolute path of a regular file on the device.")],
        out: Annotated[str | None, Field(description="Local file to write; omit for one under .tap/agent.")] = None,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Copy a device file to this machine; returns the local path."""
        return await call(session, lambda a: a.pull(device_path, out, device or None))

    @mcp.tool(name="add_media")
    async def add_media(
        local_path: Annotated[str, Field(description="A photo (jpg, png, gif, webp, heic, bmp) or video (mp4, 3gp, webm, mkv, mov) on this machine.")],
        name: Annotated[str | None, Field(description="File name on the device; omit for the local name.")] = None,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Add a photo or video to the device gallery (Pictures/Tap or Movies/Tap, indexed so
        gallery apps and photo pickers list it) until release."""
        return await call(session, lambda a: a.media(local_path, name, device or None))

    @mcp.tool(name="clipboard")
    async def clipboard(
        text: Annotated[str | None, Field(description="Put this on the clipboard; omit to read it.")] = None,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Read the device clipboard as text, or set it."""
        return await call(session, lambda a: a.clipboard(text, device or None))

    @mcp.tool(name="await_toast", annotations=READ_ONLY)
    async def await_toast(
        text: Annotated[str, Field(description="The toast's text; omit for any toast.")] = "",
        contains: Annotated[bool, Field(description="text is only part of the toast.")] = False,
        package: Annotated[str, Field(description="Only this package's toasts; omit for any app's.")] = "",
        timeout_seconds: Annotated[float, Field(gt=0, le=600)] = 10,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Wait for a toast (one shown in the last 3.5 s counts) and return its text and app.
        Toasts never appear in snapshot: use this to check one."""
        return await call(
            session, lambda a: a.toast(text or None, device or None, contains=contains, package=package or None, timeout=timeout_seconds)
        )

    @mcp.tool(name="press_key")
    async def press_key(
        key: Annotated[str, Field(description="back, home, recents, enter, tab, delete, escape, ... or an Android key code.")],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Press a key."""
        return await call(session, lambda a: a.key(key, device or None, settle=settle))

    @mcp.tool(name="open_panel")
    async def open_panel(
        panel: Annotated[
            Literal["notifications", "quick_settings"],
            Field(description="The notification shade, or the quick settings panel."),
        ],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Open a system panel, as a swipe down from the status bar would. Its nodes belong to
        com.android.systemui (target them with pkg=com.android.systemui); press_key back closes it."""
        return await call(session, lambda a: a.panel(panel, device or None, settle=settle))

    @mcp.tool(name="rotate")
    async def rotate(
        how: Annotated[
            Literal["portrait", "landscape", "natural", "left", "upside-down", "right", "auto"],
            Field(description="A geometry, an exact rotation from the natural one, or auto (back to the sensor)."),
        ],
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Rotate the display and keep it there. Returns where the display is afterwards: the app
        may pin its own orientation. The device's rotation settings are restored on release."""
        return await call(session, lambda a: a.rotate(how, device or None, settle=settle))

    @mcp.tool(name="screen")
    async def screen(
        action: Annotated[
            Literal["state", "on", "off", "unlock"],
            Field(description="state: report; on/off: wake or sleep; unlock: wake and dismiss a keyguard without a PIN."),
        ] = "state",
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Screen power and lock screen. A keyguard with a PIN, pattern or password is never unlocked."""
        return await call(session, lambda a: a.screen(action, device or None, settle=settle))

    @mcp.tool(name="permission")
    async def permission(
        choice: Annotated[
            str,
            Field(description="The button to press: allow, allow-foreground-only, allow-one-time, deny, ...; omit to list them."),
        ] = "",
        timeout_seconds: Annotated[float, Field(gt=0, le=600)] = 10,
        accuracy: Annotated[
            Literal["precise", "approximate"] | None,
            Field(description="With a choice: pick Precise or Approximate first (location dialog, Android 12+)."),
        ] = None,
        settle: Settle = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Android's runtime-permission dialog: without a choice, wait for it and list the buttons
        (and location accuracies) it offers; with one, press that button."""
        return await call(
            session, lambda a: a.permission(choice or None, device or None, timeout_seconds, settle=settle, accuracy=accuracy)
        )

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

    @mcp.tool(name="condition")
    async def condition(
        name: Annotated[
            Literal["animations", "dark-mode", "font-scale", "density", "airplane-mode", "wifi", "mobile-data", "locale"] | None,
            Field(description="The condition to read or change; omit to read them all."),
        ] = None,
        value: Annotated[
            str,
            Field(description="animations/dark-mode/airplane-mode/wifi/mobile-data: on or off; font-scale: 0.5..2.0; density: dpi (100..1000) or reset; locale: the device languages as comma-separated BCP-47 tags (fr-FR,en). Omit to read."),
        ] = "",
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """Read the device conditions (animations, dark mode, font scale, display density, airplane
        mode, Wi-Fi, mobile data, the device languages) or change one. A change lasts until release, which restores what
        the device had, and the result is the value read back. Dark mode and the network switches
        need API 29+; the network switches are real (nothing is mocked)."""
        return await call(session, lambda a: a.condition(name, value or None, device or None))

    @mcp.tool(name="capture", annotations=READ_ONLY)
    async def capture(session: Session = "", device: Device = "") -> CallToolResult:
        """Save a screenshot, the hierarchy, device info and the driver log; returns the paths."""
        return await call(session, lambda a: a.capture(device or None))

    @mcp.tool(name="app")
    async def app(
        action: Literal[
            "launch", "cold-launch", "foreground", "background", "open-link",
            "stop", "clear", "install", "uninstall", "grant", "revoke", "granted", "running", "locale",
        ],
        package: Annotated[str, Field(description="The app's package name.")],
        argument: Annotated[
            str,
            Field(
                description="launch/cold-launch: activity (optional); open-link: URI; install: APK path; "
                "grant/revoke/granted: permission; locale: comma-separated BCP-47 tags, or system (omit to read)."
            ),
        ] = "",
        any_app: Annotated[bool, Field(description="open-link: let any app handle the link, not only this one.")] = False,
        session: Session = "",
        device: Device = "",
    ) -> CallToolResult:
        """App lifecycle: launch, cold-launch (force-stop first), foreground (as the launcher
        would: back where it was), background (home key), open-link (a deep link, restricted to
        the app unless any_app), stop, clear data, install, uninstall, grant or revoke a runtime
        permission (revoking stops the app's process), ask whether a permission is granted,
        whether the app is running, or read or set the app's own languages (locale, API 33+,
        restored on release)."""
        return await call(
            session, lambda a: a.app(action, package, argument or None, device or None, any_app=any_app)
        )

    @mcp.tool(name="export", annotations=READ_ONLY)
    async def export(
        path: Annotated[str, Field(description="Write the JSON to this file and return a summary instead.")] = "",
        session: Session = "",
    ) -> CallToolResult:
        """The session's event log as JSON: every device call it made (commands and app changes),
        in order, with outcomes and the selectors refs stood for. Export before `release`: the
        log ends with the session. Converting it into a test is up to the user."""
        return await call(session, lambda a: a.export(path or None))

    return mcp


def serve(default_session: str | None = None) -> None:
    create_server(default_session=default_session).run("stdio")
