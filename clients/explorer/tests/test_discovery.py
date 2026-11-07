"""Unseeded discovery from SDK snapshots; neither AI nor a state/action catalog is used."""

import pytest
from tap_e2e import proto as pb
from google.protobuf.json_format import ParseDict
from tap_e2e import Selector, class_name, res, text

from tap_explorer.discovery import SignaturePolicy, discover

PACKAGE = "unknown.app"


def snapshot():
    response = pb.ScreenSnapshotResponse(snapshot_id=1)
    response.nodes.add(ref="e1", window_package=PACKAGE, class_name="android.widget.TextView",
                       resource_name=f"{PACKAGE}:id/title", text="Library", flags=[pb.FLAG_ENABLED])
    response.nodes.add(ref="e2", window_package=PACKAGE, class_name="android.widget.Button",
                       resource_name=f"{PACKAGE}:id/settings", text="Settings", interactive=True,
                       flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE], selector=res("settings").to_proto())
    return response


def read(fake, pilot_device, response):
    fake.devices.snapshot = response
    return pilot_device.screen_snapshot(selector_candidates=True)


def test_unseeded_capabilities_propose_but_never_approve(fake, pilot_device):
    frame = read(fake, pilot_device, snapshot())
    candidates = discover(frame, PACKAGE, "state-1")
    assert [candidate.verb for candidate in candidates] == ["back", "tap"]  # Sorted by ID.
    assert candidates[1].label == "Settings"
    assert candidates[1].target()["risk"] == "unknown"
    assert candidates[1].target()["provenance"] == "snapshot-capability/1"
    assert "approval" not in candidates[1].target()
    assert candidates[0].target()["provenance"] == "system-key/1" and candidates[0].selector is None


def test_long_press_and_scroll_controls_are_proposed(fake, pilot_device):
    response = snapshot()
    response.nodes[1].flags.append(pb.FLAG_LONG_CLICKABLE)
    response.nodes.add(ref="e3", window_package=PACKAGE, class_name="android.widget.ScrollView",
                       resource_name=f"{PACKAGE}:id/content", interactive=True,
                       flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE], selector=res("content").to_proto())
    response.nodes.add(ref="e4", window_package=PACKAGE, class_name="androidx.viewpager.widget.ViewPager",
                       resource_name=f"{PACKAGE}:id/pages", interactive=True,
                       flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE], selector=res("pages").to_proto())
    candidates = discover(read(fake, pilot_device, response), PACKAGE, "s")
    proposed = {(candidate.verb, candidate.label, candidate.target().get("direction")) for candidate in candidates}
    assert proposed == {("tap", "Settings", None), ("long_tap", "Settings", None), ("back", "Back", None),
                        ("scroll", f"{PACKAGE}:id/content", "DOWN"), ("scroll", f"{PACKAGE}:id/pages", "RIGHT")}
    assert all(candidate.blocked_reason is None for candidate in candidates)


def test_refs_focus_snapshot_ids_do_not_invent_states(fake, pilot_device):
    policy = SignaturePolicy()
    response = snapshot()
    first = read(fake, pilot_device, response)
    response.snapshot_id = 900
    response.nodes[0].ref = "e99"
    response.nodes[1].flags.append(pb.FLAG_FOCUSED)
    second = read(fake, pilot_device, response)
    assert policy.signature(first, PACKAGE) == policy.signature(second, PACKAGE)
    assert discover(first, PACKAGE, "s")[0].id == discover(second, PACKAGE, "s")[0].id


@pytest.mark.parametrize("kind", ["error", "value", "toggle", "modal"])
def test_meaningful_variations_remain_distinct(fake, pilot_device, kind):
    policy = SignaturePolicy()
    response = snapshot()
    first = read(fake, pilot_device, response)
    if kind == "error":
        response.nodes[0].text = "Invalid input"
    elif kind == "value":
        response.nodes[0].text = "Ada"
    elif kind == "toggle":
        response.nodes[1].flags.append(pb.FLAG_CHECKED)
    else:
        response.nodes.add(ref="e10", window_package="android.permissioncontroller",
                           class_name="android.widget.Button", resource_name="android:id/allow")
    second = read(fake, pilot_device, response)
    assert policy.signature(first, PACKAGE) != policy.signature(second, PACKAGE)


def test_status_bar_icon_churn_is_not_a_new_state(fake, pilot_device):
    policy = SignaturePolicy()
    response = snapshot()
    response.nodes.add(ref="e20", depth=0, window_package="com.android.systemui", class_name="android.widget.FrameLayout")
    response.nodes.add(ref="e21", depth=1, window_package="com.android.systemui", class_name="android.widget.FrameLayout",
                       resource_name="com.android.systemui:id/status_bar_container")
    first = read(fake, pilot_device, response)
    response.nodes.add(ref="e22", depth=9, window_package="com.android.systemui", class_name="android.widget.ImageView")
    second = read(fake, pilot_device, response)
    assert policy.signature(first, PACKAGE) == policy.signature(second, PACKAGE)
    response.nodes.add(ref="e23", depth=0, window_package="com.google.android.inputmethod.latin",
                       class_name="android.widget.FrameLayout")
    keyboard = read(fake, pilot_device, response)
    assert policy.signature(second, PACKAGE) != policy.signature(keyboard, PACKAGE)


def test_the_text_cursor_handle_after_typing_is_not_a_new_state(fake, pilot_device):
    policy = SignaturePolicy()
    plain = read(fake, pilot_device, snapshot())
    response = snapshot()
    # A popup window in the app's own package that fades a few seconds after typing (Loop's form).
    response.nodes.add(ref="e30", depth=0, window_package=PACKAGE, class_name="android.widget.PopupWindow$PopupDecorView")
    response.nodes.add(ref="e31", depth=1, window_package=PACKAGE, class_name="android.view.View",
                       resource_name="android:id/insertion_handle", flags=[pb.FLAG_ENABLED])
    response.nodes.add(ref="e32", depth=2, window_package=PACKAGE, class_name="android.view.View")
    handle = read(fake, pilot_device, response)
    features = policy.features(handle, PACKAGE)["nodes"]
    assert not any(node["resource"] == "android:id/insertion_handle" for node in features)
    # Its popup window holds nothing else, so it goes too: the same state as without it.
    assert policy.signature(handle, PACKAGE) == policy.signature(plain, PACKAGE)


def test_only_explicit_volatile_values_are_ignored(fake, pilot_device):
    response = snapshot()
    first = read(fake, pilot_device, response)
    response.nodes[0].text = "00:01"
    second = read(fake, pilot_device, response)
    assert SignaturePolicy().signature(first, PACKAGE) != SignaturePolicy().signature(second, PACKAGE)
    policy = SignaturePolicy((f"{PACKAGE}:id/title",))
    assert policy.signature(first, PACKAGE) == policy.signature(second, PACKAGE)
    assert policy.features(second, PACKAGE)["nodes"][0]["resource"] == f"{PACKAGE}:id/title"


def test_another_app_is_not_a_target_state(fake, pilot_device):
    frame = read(fake, pilot_device, snapshot())
    with pytest.raises(ValueError, match="no target-package"):
        SignaturePolicy().signature(frame, "other.app")
    assert discover(frame, "other.app", "s") == ()


@pytest.mark.parametrize("kind", ["disabled", "password", "index", "missing", "duplicate", "unsupported"])
def test_unsafe_or_unsupported_targets_are_explicit_limitations(fake, pilot_device, kind):
    response = snapshot()
    node = response.nodes[1]
    if kind == "disabled":
        node.flags.remove(pb.FLAG_ENABLED)
    elif kind == "password":
        node.password = True
    elif kind == "index":
        node.by_index = True
        node.selector.CopyFrom(res("settings").at(0).to_proto())
    elif kind == "missing":
        node.ClearField("selector")
    elif kind == "duplicate":
        response.nodes.add().CopyFrom(node)
        response.nodes[-1].ref = "e3"
    else:
        node.flags.remove(pb.FLAG_CLICKABLE)  # Interactive, yet no verb Tap can perform.
    candidates = [candidate for candidate in discover(read(fake, pilot_device, response), PACKAGE, "s")
                  if candidate.verb != "back"]
    assert candidates
    assert all(candidate.blocked_reason for candidate in candidates)


def test_editable_field_has_finite_unapproved_fill_candidate(fake, pilot_device):
    response = snapshot()
    response.nodes[1].class_name = "android.widget.EditText"
    response.nodes[1].text = ""
    candidates = discover(read(fake, pilot_device, response), PACKAGE, "s")
    assert {candidate.verb for candidate in candidates} == {"tap", "fill", "back"}
    assert all("value" not in candidate.target() for candidate in candidates)


def test_a_text_field_offers_its_keyboard_action_only_with_focus_and_never_a_long_press(fake, pilot_device):
    response = snapshot()
    response.nodes[1].class_name = "android.widget.EditText"
    # Android makes every text field long-clickable: that is text selection, not the app's.
    response.nodes[1].flags.append(pb.FLAG_LONG_CLICKABLE)
    verbs = {candidate.verb for candidate in discover(read(fake, pilot_device, response), PACKAGE, "s")}
    assert verbs == {"tap", "fill", "back"}
    response.nodes[1].flags.append(pb.FLAG_FOCUSED)
    verbs = {candidate.verb for candidate in discover(read(fake, pilot_device, response), PACKAGE, "s")}
    assert verbs == {"tap", "fill", "submit", "back"}


def _corpus_snapshot(case_id):
    """A real retained Loop observation, replayed through the fake daemon as protobuf."""
    import json
    from pathlib import Path

    from google.protobuf.json_format import ParseDict

    corpus = json.loads((Path(__file__).parents[1] / "benchmarks" / "corpus-v2.json").read_text())
    case = next(case for case in corpus["cases"] if case["id"] == case_id)
    response = pb.ScreenSnapshotResponse(snapshot_id=1)
    for node in case["snapshot"]["nodes"]:
        added = response.nodes.add(
            ref=node["ref"], depth=node["depth"], window_package=node["package"], password=node["password"],
            interactive=node["interactive"], by_index=node["by_index"],
            flags=[getattr(pb, f"FLAG_{flag}") for flag in node["flags"]],
            bounds=pb.Bounds(left=node["bounds"][0], top=node["bounds"][1], right=node["bounds"][2], bottom=node["bounds"][3]),
            **{key: node[source] for key, source in (("class_name", "class"), ("resource_name", "resource"),
               ("text", "text"), ("content_description", "description"), ("hint", "hint")) if node[source] is not None})
        if node["selector"] is not None:
            added.selector.CopyFrom(ParseDict(node["selector"], pb.Selector()))
    return case["snapshot"]["nodes"][0]["package"], response


def test_loop_habit_rows_without_clickable_ancestor_get_row_text_taps(fake, pilot_device):
    package, response = _corpus_snapshot("loop/obs-000051")
    frame = read(fake, pilot_device, response)
    rows = [c for c in discover(frame, package, "list") if c.provenance == "list-row-text/1"]
    labels = ["Tap benchmark binary", "Tap benchmark limit", "Tap benchmark quantity"]
    # Each row is offered a tap and a long press (Loop selects a habit on a long press).
    assert sorted((c.label, c.verb) for c in rows) == [(label, verb) for label in labels for verb in ("long_tap", "tap")]
    assert all(c.blocked_reason is None for c in rows)
    assert all(c.selector is not None and "at" not in c.selector for c in rows)
    # The index-only check-in boxes hold no label: they stay blocked, nothing is invented.
    days = [c for c in discover(frame, package, "list") if c.label == "android.view.View"]
    assert days and all(c.blocked_reason for c in days)


def _row_list(clickable_row: bool, duplicate: bool = False):
    response = pb.ScreenSnapshotResponse(snapshot_id=1)
    response.nodes.add(ref="e1", depth=0, window_package=PACKAGE, class_name="androidx.recyclerview.widget.RecyclerView",
                       flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE], interactive=True)
    names = ["Alpha", "Alpha" if duplicate else "Beta"]
    for index, name in enumerate(names):
        row_flags = [pb.FLAG_ENABLED] + ([pb.FLAG_CLICKABLE] if clickable_row else [])
        row = response.nodes.add(ref=f"r{index}", depth=1, window_package=PACKAGE, class_name="android.widget.FrameLayout",
                                 flags=row_flags, interactive=clickable_row, by_index=True)
        row.selector.CopyFrom(class_name("android.widget.FrameLayout").at(index).to_proto())
        label = response.nodes.add(ref=f"t{index}", depth=2, window_package=PACKAGE, class_name="android.widget.TextView",
                                   text=name, flags=[pb.FLAG_ENABLED])
        if not duplicate:
            label.selector.CopyFrom(text(name).to_proto())
    return response


def test_clickable_index_only_row_is_addressed_by_its_label(fake, pilot_device):
    frame = read(fake, pilot_device, _row_list(clickable_row=True))
    candidates = discover(frame, PACKAGE, "s")
    derived = [c for c in candidates if c.provenance == "ancestor-text/1"]
    assert sorted(c.label for c in derived) == ["Alpha", "Beta"]
    assert derived[0].selector is not None
    selector = Selector.from_proto(ParseDict(derived[0].selector, pb.Selector()))
    assert "has_descendant" in selector.render() or "descendant" in selector.render().lower()
    assert not [c for c in candidates if c.provenance == "list-row-text/1"]


def test_ambiguous_row_labels_propose_nothing_executable(fake, pilot_device):
    frame = read(fake, pilot_device, _row_list(clickable_row=True, duplicate=True))
    candidates = discover(frame, PACKAGE, "s")
    assert not [c for c in candidates if c.provenance == "ancestor-text/1"]
    frame = read(fake, pilot_device, _row_list(clickable_row=False, duplicate=True))
    rows = [c for c in discover(frame, PACKAGE, "s") if c.provenance == "list-row-text/1"]
    assert rows and all(c.blocked_reason for c in rows)
