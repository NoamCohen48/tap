"""Target, duration and key parsing; snapshot and diff rendering."""

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_agent import render
from tap_agent.targets import UsageError, format_duration, parse_duration, parse_key, parse_target
from tap_e2e import Bounds, NodeChange, NodeFlag, ScreenNode, ScreenSnapshot, all_of, class_name, desc, res, res_id, text
from tap_e2e import CONTAINS, ENDS_WITH


def test_refs_with_or_without_the_at_sign():
    assert parse_target("@e12").ref == "e12"
    assert parse_target("e3").ref == "e3"
    assert parse_target("@e3").describe() == "@e3"


@pytest.mark.parametrize(
    ("spec", "expected"),
    [
        ("id=login", res("login")),
        ("id=com.android.systemui:id/clock", res_id("com.android.systemui", "clock")),
        ("text=Log in", text("Log in")),
        ("text~=Log", text("Log", CONTAINS)),
        ("desc~=Clo", desc("Clo", CONTAINS)),
        ("class=android.widget.Button", class_name("android.widget.Button")),
        ("class=Button", class_name(".Button", ENDS_WITH)),
        ("class=Button,text=OK", all_of(class_name(".Button", ENDS_WITH), text("OK"))),
        ("text=a\\,b", text("a,b")),
        ("text=x=y", text("x=y")),
    ],
)
def test_selector_terms(spec, expected):
    target = parse_target(spec)
    assert target.ref is None and target.selector is not None
    assert target.selector.render() == all_of(expected).render()
    assert target.describe() == spec


def test_package_and_index_modifiers():
    target = parse_target("text=Allow,pkg=com.android.permissioncontroller,index=1")
    assert target.selector is not None
    assert target.selector.render() == text("Allow").in_package("com.android.permissioncontroller").at(1).render()


@pytest.mark.parametrize("spec", ["login", "colour=red", "pkg=com.x", "index=a,text=b", "=x"])
def test_bad_targets_are_usage_errors(spec):
    with pytest.raises(UsageError):
        parse_target(spec)


def test_durations():
    assert parse_duration("2500ms") == 2.5
    assert parse_duration("10s") == parse_duration("10") == 10
    assert parse_duration("15m") == 900
    assert parse_duration("1h") == 3600
    with pytest.raises(UsageError):
        parse_duration("soon")
    assert [format_duration(s) for s in (900, 3600, 12, 0.5)] == ["15m", "1h", "12s", "500ms"]


def test_keys():
    assert parse_key("back") == 4
    assert parse_key("ENTER") == 66
    assert parse_key("KEYCODE_TAB") == 61
    assert parse_key("187") == 187
    with pytest.raises(UsageError):
        parse_key("hyper")


def _node(ref: str, change: NodeChange = NodeChange.NONE, **fields) -> ScreenNode:
    values = {
        "depth": 1,
        "window_package": "com.example",
        "class_name": None,
        "resource_name": None,
        "text": None,
        "content_description": None,
        "hint": None,
        "bounds": Bounds(0, 0, 10, 10),
        "flags": frozenset({NodeFlag.ENABLED}),
        "password": False,
        "interactive": False,
        "selector": res("x"),
        "by_index": False,
    }
    values.update(fields)
    return ScreenNode(ref=ref, change=change, **values)  # type: ignore[arg-type]


def test_node_line():
    node = _node(
        "e3",
        class_name="android.widget.EditText",
        resource_name="com.example:id/email",
        hint="Email",
        interactive=True,
        flags=frozenset({NodeFlag.ENABLED, NodeFlag.FOCUSED}),
    )
    assert render.node_line(node) == '@e3  [EditText]  hint="Email"  id=email  focused'
    other = _node("e4", window_package="com.android.systemui", resource_name="com.android.systemui:id/clock", text="12:00")
    assert render.node_line(other) == '@e4  "12:00"  id=clock'
    foreign = _node("e5", resource_name="android:id/content", text="x", selector=None)
    assert render.node_line(foreign) == '@e5  "x"  id=android:id/content  (no selector)'
    secret = _node("e6", text="hunter2", password=True, by_index=True, interactive=True, flags=frozenset())
    assert render.node_line(secret) == "@e6  password  disabled  (by index)"


def test_levels_and_windows():
    snapshot = ScreenSnapshot(
        1,
        (
            _node("e1", class_name="android.widget.FrameLayout"),
            _node("e2", text="Title"),
            _node("e3", class_name="android.widget.Button", interactive=True),
            _node("e4", window_package="com.android.systemui", text="12:00"),
        ),
        (),
        0,
    )
    assert render.snapshot_text(snapshot).splitlines() == [
        "# com.example",
        '@e2  "Title"',
        "@e3  [Button]",
        "# com.android.systemui",
        '@e4  "12:00"',
    ]
    assert render.snapshot_text(snapshot, render.INTERACTIVE).splitlines() == ["# com.example", "@e3  [Button]"]
    assert "@e1  [FrameLayout]" in render.snapshot_text(snapshot, render.ALL)


def test_diff_marks_added_and_removed():
    snapshot = ScreenSnapshot(
        2,
        (_node("e2", NodeChange.UNCHANGED, text="Title"), _node("e7", NodeChange.ADDED, text="Welcome")),
        (_node("e5", NodeChange.REMOVED, text="Log in"),),
        0,
    )
    assert render.diff_text(snapshot).splitlines() == [
        "# com.example",
        '+ @e7  "Welcome"',
        "# removed",
        '- @e5  "Log in"',
    ]
    assert '= @e2  "Title"' in render.diff_text(snapshot, full=True)
    unchanged = ScreenSnapshot(3, (_node("e2", NodeChange.UNCHANGED, text="Title"),), (), 0)
    assert render.diff_text(unchanged) == "(no change)"
    first = ScreenSnapshot(1, (_node("e2", text="Title"),), (), 0)
    assert render.diff_text(first) == render.snapshot_text(first)
