"""Selector composition never silently drops a scope or a match choice (PY-7)."""
# pyright: reportMissingImports=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e import Selector, all_of, any_of, clickable, proto, raw_res, res, text

SYSTEM = "com.google.android.permissioncontroller"


def test_and_keeps_the_left_pick_and_scope():
    combined = text("Add").at(1).in_package(SYSTEM) & clickable()
    assert len(combined._proto.node.all_of.nodes) == 2
    assert combined._proto.WhichOneof("pick") == "at"
    assert combined._proto.system.package_name == SYSTEM


@pytest.mark.parametrize("operand", [text("x").first(), text("x").at(2)])
def test_operands_with_a_pick_are_rejected(operand):
    with pytest.raises(ValueError, match="match choice"):
        text("a") & operand
    with pytest.raises(ValueError, match="match choice"):
        text("a") | operand
    with pytest.raises(ValueError):
        all_of(text("a"), operand)
    with pytest.raises(ValueError):
        any_of(text("a"), operand)
    for relation in ("has_descendant", "has_child", "has_parent", "has_ancestor"):
        with pytest.raises(ValueError, match=relation):
            getattr(raw_res("card"), relation)(operand)


def test_operands_with_a_different_scope_are_rejected_but_the_same_is_fine():
    other = text("x").in_package(SYSTEM)
    with pytest.raises(ValueError, match="scope"):
        text("a") & other
    with pytest.raises(ValueError, match="scope"):
        raw_res("card").has_child(other)
    with pytest.raises(ValueError, match="scope"):
        text("a").in_package("other.pkg") | other
    same = text("a").in_package(SYSTEM) | other
    assert same._proto.system.package_name == SYSTEM
    assert len(same._proto.node.any_of.nodes) == 2


def test_any_window_scope_composes_like_a_package_scope():
    anywhere = text("OK").in_any_window()
    assert anywhere._proto.WhichOneof("scope") == "any_window"
    with pytest.raises(ValueError, match="scope"):
        text("a") & anywhere
    with pytest.raises(ValueError, match="scope"):
        text("a").in_package(SYSTEM) | anywhere
    combined = text("a").in_any_window() & anywhere
    assert combined._proto.WhichOneof("scope") == "any_window"
    row = raw_res("list").in_any_window().descendant(text("row"))
    assert row._proto.WhichOneof("scope") == "any_window"
    assert row.render().endswith("any_window { }")


def test_descendant_and_child_reject_a_picked_receiver():
    with pytest.raises(ValueError, match="receiver"):
        raw_res("list").at(2).descendant(text("row"))
    with pytest.raises(ValueError, match="receiver"):
        raw_res("list").first().child(text("row"))


def test_descendant_keeps_the_target_pick_and_the_receiver_scope():
    target = raw_res("list").in_package(SYSTEM).descendant(text("row").at(3))
    assert target._proto.WhichOneof("pick") == "at"
    assert target._proto.at.index == 3
    assert target._proto.system.package_name == SYSTEM
    related = [n for n in target._proto.node.all_of.nodes if n.HasField("related")]
    assert related[0].related.relation == pb.RELATION_ANCESTOR
    with pytest.raises(ValueError, match="scope"):
        raw_res("list").descendant(text("row").in_package(SYSTEM))


def test_selectors_are_hashable_values():
    a = text("a") & clickable()
    b = text("a") & clickable()
    assert a == b and hash(a) == hash(b)
    assert len({a, b, text("b")}) == 2
    assert {a: 1}[b] == 1
    assert a != text("a")


def test_and_res_matches_kotlin_and_res():
    assert text("a").and_res("login") == text("a").and_res_aut("login")
    assert text("a").and_res("login") == text("a") & res("login")
    qualified = text("a").and_res("com.pkg", "login")
    node = qualified._proto.node.all_of.nodes[1].resource
    assert (node.package_name, node.name, node.aut_package) == ("com.pkg", "login", False)


def test_selectors_convert_to_and_from_their_proto_as_copies():
    selector = res("login").at(1)
    message = selector.to_proto()
    assert isinstance(message, proto.Selector) and message == selector._proto
    message.at.index = 5  # a copy: the selector is unchanged
    assert selector == res("login").at(1)
    again = Selector.from_proto(message)
    message.at.index = 6
    assert again == res("login").at(5)
    assert proto.selector_pb2.Selector is proto.Selector
