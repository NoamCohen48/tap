"""Context is presentation, never a replacement target selector."""

from dataclasses import replace

import pytest  # type: ignore[import-not-found]
from tap_e2e import Bounds, NodeChange, NodeFlag, ScreenSnapshot
from tap_e2e import _gen as pb

from tap_agent import cli, render
from tap_agent.core import AgentError

from .test_targets_render import _node


def screen(*nodes, removed=(), display=(0, 0, 0)):
    return ScreenSnapshot(1, tuple(nodes), tuple(removed), 0, *display)


PHONE = (1080, 2400, 420)  # the API 34 emulator: its status bar is 63 px = 24 dp


def settings():
    return (
        _node("e1", depth=0, window_index=1, window_kind=1, window_package="com.android.settings"),
        _node("e2", depth=1, window_index=1, window_kind=1, window_package="com.android.settings", interactive=True),
        _node("e3", depth=2, window_index=1, window_kind=1, window_package="com.android.settings", text="Network & internet"),
        _node("e4", depth=2, window_index=1, window_kind=1, window_package="com.android.settings", text="Wi-Fi, mobile, data"),
    )


def test_settings_row_is_understandable_and_target_is_unchanged():
    nodes = settings()
    result = render.snapshot_text(screen(*nodes), render.INTERACTIVE)
    assert result.splitlines() == [
        "# com.android.settings · window 1",
        '@e2  context="Network & internet · Wi-Fi, mobile, data"',
    ]
    assert nodes[1].text is None
    assert nodes[1].selector == _node("original").selector
    assert render.snapshot_text(screen(*nodes), render.DEFAULT).splitlines()[2].startswith('  @e3')


def test_nested_controls_dont_lend_their_labels_to_the_row():
    nodes = settings() + (
        _node("e5", depth=2, window_index=1, window_package="com.android.settings", interactive=True,
              text="Separate control", flags=frozenset({NodeFlag.ENABLED, NodeFlag.CHECKED})),
        _node("e6", depth=3, window_index=1, window_package="com.android.settings", text="Separate detail"),
        _node("e7", depth=2, window_index=1, window_package="com.android.settings", interactive=True),
    )
    result = render.snapshot_text(screen(*nodes), render.INTERACTIVE)
    lines = result.splitlines()
    assert "Separate" not in lines[1]
    assert '  @e5  "Separate control"  context="Separate detail"  checked' in lines
    assert '  @e7  context="Network & internet · Wi-Fi, mobile, data"' in lines


def test_dedup_passwords_hints_limits_and_scroll_containers():
    root = _node("e1", depth=0, interactive=True)
    children = (
        _node("e2", depth=1, text="Wi-Fi", content_description="Wi-Fi"),
        _node("e3", depth=1, text="Wi-Fi"),
        _node("e4", depth=1, text="secret", content_description="secret", password=True),
        _node("e5", depth=1, text="Enter password", showing_hint=True),
    )
    result = render.snapshot_text(screen(root, *children), render.INTERACTIVE)
    assert 'context="Wi-Fi"' in result
    assert "secret" not in result and "Enter password" not in result
    scroll = replace(root, flags=frozenset({NodeFlag.SCROLLABLE}))
    assert "context=" not in render.snapshot_text(screen(scroll, *children), render.INTERACTIVE)
    long = _node("long", depth=1, text="x" * 500)
    assert 'context="' + "x" * 79 + '…"' in render.snapshot_text(screen(root, long), render.INTERACTIVE)


def test_labeled_groups_survive_collapsed_wrappers():
    nodes = (
        _node("e1", depth=0, text="Connectivity"),
        _node("e2", depth=1),
        _node("e3", depth=2, interactive=True, text="Wi-Fi"),
    )
    result = render.snapshot_text(screen(*nodes), render.INTERACTIVE)
    assert '@e1  "Connectivity"  (group)' in result
    assert '  @e3  "Wi-Fi"' in result
    assert "@e2" not in result
    changed = tuple(replace(n, change=NodeChange.UNCHANGED) for n in nodes[:2]) + (
        replace(nodes[2], change=NodeChange.ADDED),
    )
    assert render.diff_text(screen(*changed), render.INTERACTIVE).splitlines() == [
        "# com.example", '= @e1  "Connectivity"  (group)', '+   @e3  "Wi-Fi"',
    ]


def test_same_package_windows_and_filters_do_not_cross_roots():
    nodes = (
        _node("e1", depth=0, window_index=1, window_kind=1, interactive=True),
        _node("e2", depth=1, window_index=1, window_kind=1, text="Settings"),
        _node("e3", depth=0, window_index=2, window_kind=2, interactive=True, text="Keyboard"),
        _node("e4", depth=0, window_index=3, window_kind=7, interactive=True, text="Status bar",
              window_package="com.android.systemui", bounds=Bounds(0, 0, 1080, 63)),
        _node("e5", depth=0, window_index=4, window_kind=0, interactive=True, text="Unknown"),
        _node("e6", depth=0, window_index=5, window_kind=1, interactive=True,
              window_package="com.android.permissioncontroller", text="Allow"),
        _node("e7", depth=0, window_index=6, window_kind=3, interactive=True, text="Shade",
              window_package="com.android.systemui", bounds=Bounds(0, 0, 1080, 2400)),
    )
    result = render.snapshot_text(screen(*nodes, display=PHONE), render.INTERACTIVE,
                                  filters=render.SnapshotFilter(keyboard="omit", system_bars="omit"))
    assert "Keyboard" not in result and "Status bar" not in result and "window 3" not in result
    assert "Settings" in result and "Unknown" in result and "Allow" in result and "Shade" in result
    assert 'context="Settings"' in result.splitlines()[1]
    one = render.snapshot_text(screen(*nodes), filters=render.SnapshotFilter(window=2))
    assert one.splitlines() == ['# com.example · window 2', '@e3  "Keyboard"']
    assert render.snapshot_text(screen(*nodes), filters=render.SnapshotFilter(package="missing")) == "(no visible nodes)"
    assert "Allow" not in render.snapshot_text(screen(*nodes), filters=render.SnapshotFilter(package="com.example"))


def test_diff_surfaces_unchanged_container_when_descendant_changes_or_disappears():
    nodes = tuple(replace(n, change=NodeChange.UNCHANGED) for n in settings())
    changed = nodes[:3] + (replace(nodes[3], change=NodeChange.ADDED, text="Off"),)
    result = render.diff_text(screen(*changed), render.INTERACTIVE)
    assert '= @e2  context="Network & internet · Off"' in result
    assert render.diff_text(screen(*nodes), render.INTERACTIVE) == "(no change)"
    # Last borrowed label disappears: the now-anonymous control must still surface.
    result = render.diff_text(screen(*nodes[:2], removed=nodes[2:]), render.INTERACTIVE)
    assert "= @e2" in result
    assert "context=" not in result
    assert render.diff_text(screen(*changed), render.INTERACTIVE,
                            filters=render.SnapshotFilter(package="missing")) == "(no change)"


@pytest.mark.parametrize("window", [0, -1, True, 1.5])
def test_invalid_window_is_rejected_before_rpc(agent, window):
    with pytest.raises(AgentError, match="window must"):
        agent.snapshot(window=window)


@pytest.mark.parametrize("kwargs", [{"package": "p"}, {"window": 1}, {"keyboard": "omit"}, {"system_bars": "show"}])
def test_candidates_cannot_silently_ignore_filters(agent, kwargs):
    with pytest.raises(AgentError, match="no view level or filter"):
        agent.snapshot(as_json=True, **kwargs)


def _keyboard_screen():
    from tap_e2e import _gen as pb
    from tap_e2e._proto import screen_snapshot
    app = pb.ScreenNode(ref="e1", window_package="com.shop", window_index=1, window_kind=1, text="Search",
                        interactive=True, flags=[pb.FLAG_ENABLED, pb.FLAG_EDITABLE], change=pb.NODE_UNCHANGED)
    keys = [pb.ScreenNode(ref=f"e{i}", window_package="com.android.inputmethod", window_index=2, window_kind=2, depth=1 if i > 2 else 0,
                          text=key, interactive=True, flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE], change=pb.NODE_ADDED)
            for i, key in enumerate(["", "q", "w", "e"], start=2)]
    return screen_snapshot(pb.ScreenSnapshotResponse(nodes=[app, *keys], removed=[]))


def test_the_keyboard_window_collapses_to_its_header_unless_asked():
    from tap_agent import render
    shot = _keyboard_screen()
    collapsed = render.snapshot_text(shot, render.ALL)
    assert "# com.android.inputmethod · window 2 · keyboard (4 nodes, collapsed)" in collapsed
    assert '"q"' not in collapsed and '"Search"' in collapsed
    shown = render.snapshot_text(shot, render.ALL, filters=render.SnapshotFilter(keyboard="show"))
    assert '"q"' in shown and "collapsed" not in shown
    hidden = render.snapshot_text(shot, render.ALL, filters=render.SnapshotFilter(keyboard="omit"))
    assert "inputmethod" not in hidden
    named = render.snapshot_text(shot, render.ALL, filters=render.SnapshotFilter(window=2))
    assert '"q"' in named
    diff = render.diff_text(shot, render.ALL)
    assert diff.splitlines() == ["# com.android.inputmethod · window 2 · keyboard (4 nodes, collapsed)"]


def test_keyboard_and_system_bars_take_collapse_show_or_omit(agent):
    with pytest.raises(AgentError, match="--keyboard takes collapse, show, omit"):
        agent.snapshot(keyboard="hide")
    with pytest.raises(AgentError, match="--system-bars takes"):
        agent.snapshot(system_bars=True)  # type: ignore[arg-type]
    for gone in ("--hide-keyboard", "--hide-system-chrome"):
        with pytest.raises(SystemExit):
            cli.parser().parse_args(["snapshot", gone])


SYSTEM_UI = "com.android.systemui"


def _window(ref, index, bounds, *, package=SYSTEM_UI, kind=3, text=None, change=NodeChange.NONE):
    return _node(ref, change, depth=0, window_index=index, window_kind=kind, window_package=package,
                 bounds=Bounds(*bounds), interactive=True, text=text or ref)


def test_system_bars_are_the_windows_the_server_calls_bars():
    # The server decides (WindowKinds in the daemon, with the measured geometries); the agent reads it.
    shot = screen(_window("app", 1, (0, 0, 1080, 2400), package="p", kind=pb.WINDOW_KIND_APPLICATION),
                  _window("status", 2, (0, 0, 1080, 63), kind=pb.WINDOW_KIND_STATUS_BAR),
                  _window("nav", 3, (0, 2274, 1080, 2400), kind=pb.WINDOW_KIND_NAVIGATION_BAR),
                  _window("shade", 4, (0, 0, 1080, 2400), kind=pb.WINDOW_KIND_SYSTEM), display=PHONE)
    assert render.system_bars(shot) == {2: "status bar", 3: "navigation bar"}


def test_bars_collapse_to_a_header_and_stay_out_of_diffs():
    nodes = (
        _window("e1", 1, (0, 0, 1080, 2400), package="com.example", kind=1, text="Pay"),
        _window("e2", 2, (0, 0, 1080, 63), kind=pb.WINDOW_KIND_STATUS_BAR, text="12:01"),
        _node("e3", depth=1, window_index=2, window_kind=pb.WINDOW_KIND_STATUS_BAR, window_package=SYSTEM_UI, text="Wi-Fi",
              bounds=Bounds(900, 0, 960, 63)),
    )
    shot = screen(*nodes, display=PHONE)
    text = render.snapshot_text(shot, render.ALL)
    assert "# com.android.systemui · window 2 · status bar (2 nodes, collapsed)" in text
    assert "12:01" not in text and '"Pay"' in text
    assert "12:01" in render.snapshot_text(shot, render.ALL, filters=render.SnapshotFilter(system_bars="show"))
    assert "12:01" in render.snapshot_text(shot, render.ALL, filters=render.SnapshotFilter(window=2))
    # The clock ticked and an icon went: neither is worth a diff line.
    ticked = screen(replace(nodes[0], change=NodeChange.UNCHANGED), replace(nodes[1], change=NodeChange.ADDED, text="12:02"),
                    removed=(replace(nodes[2], change=NodeChange.REMOVED),), display=PHONE)
    assert render.diff_text(ticked, render.ALL) == "(no change)"
    assert "12:02" in render.diff_text(ticked, render.ALL, filters=render.SnapshotFilter(system_bars="show"))

