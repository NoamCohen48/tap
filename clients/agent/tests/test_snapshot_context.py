"""Context is presentation, never a replacement target selector."""

from dataclasses import replace

import pytest  # type: ignore[import-not-found]
from tap_e2e import NodeChange, NodeFlag, ScreenSnapshot

from tap_agent import render
from tap_agent.core import AgentError

from .test_targets_render import _node


def screen(*nodes, removed=()):
    return ScreenSnapshot(1, tuple(nodes), tuple(removed), 0)


def settings():
    return (
        _node("e1", depth=0, window_index=1, window_type=1, window_package="com.android.settings"),
        _node("e2", depth=1, window_index=1, window_type=1, window_package="com.android.settings", interactive=True),
        _node("e3", depth=2, window_index=1, window_type=1, window_package="com.android.settings", text="Network & internet"),
        _node("e4", depth=2, window_index=1, window_type=1, window_package="com.android.settings", text="Wi-Fi, mobile, data"),
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
        _node("e1", depth=0, window_index=1, window_type=1, interactive=True),
        _node("e2", depth=1, window_index=1, window_type=1, text="Settings"),
        _node("e3", depth=0, window_index=2, window_type=2, interactive=True, text="Keyboard"),
        _node("e4", depth=0, window_index=3, window_type=3, interactive=True, text="Status bar"),
        _node("e5", depth=0, window_index=4, window_type=0, interactive=True, text="Unknown"),
        _node("e6", depth=0, window_index=5, window_type=1, interactive=True,
              window_package="com.android.permissioncontroller", text="Allow"),
    )
    result = render.snapshot_text(screen(*nodes), render.INTERACTIVE,
                                  filters=render.SnapshotFilter(hide_keyboard=True, hide_system_chrome=True))
    assert "Keyboard" not in result and "Status bar" not in result
    assert "Settings" in result and "Unknown" in result and "Allow" in result
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


@pytest.mark.parametrize("kwargs", [{"package": "p"}, {"window": 1}, {"hide_keyboard": True}, {"hide_system_chrome": True}])
def test_candidates_cannot_silently_ignore_filters(agent, kwargs):
    with pytest.raises(AgentError, match="without filters"):
        agent.snapshot(candidates=True, **kwargs)
