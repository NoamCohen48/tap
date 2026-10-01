"""Selector composition never silently drops a package predicate or a match choice (PY-7)."""
# pyright: reportMissingImports=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e import Selector, all_of, any_of, clickable, desc, proto, res, res_id, text

SYSTEM = "com.google.android.permissioncontroller"


def test_and_flattens_plain_predicates_and_keeps_the_left_pick():
    combined = text("Add").at(1) & clickable()
    assert len(combined._proto.node.all_of.nodes) == 2
    assert combined._proto.WhichOneof("pick") == "at"
    assert combined._proto.at.index == 1


def test_an_app_binds_its_package_as_one_more_predicate_and_keeps_the_pick():
    bound = (text("Add") | desc("Add")).at(1)._in_package(SYSTEM)
    either, owner = bound._proto.node.all_of.nodes
    assert len(either.any_of.nodes) == 2
    assert (owner.match.property, owner.match.value) == (pb.PROPERTY_PACKAGE_NAME, SYSTEM)
    assert bound._proto.at.index == 1


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
            getattr(res("card"), relation)(operand)


def test_descendant_and_child_reject_a_picked_receiver():
    with pytest.raises(ValueError, match="receiver"):
        res("list").at(2).descendant(text("row"))
    with pytest.raises(ValueError, match="receiver"):
        res("list").first().child(text("row"))


def test_descendant_keeps_the_target_pick():
    target = res("list").descendant(text("row").at(3))
    assert target._proto.WhichOneof("pick") == "at"
    assert target._proto.at.index == 3
    related = [n for n in target._proto.node.all_of.nodes if n.HasField("related")]
    assert related[0].related.relation == pb.RELATION_ANCESTOR


def test_selectors_are_hashable_values():
    a = text("a") & clickable()
    b = text("a") & clickable()
    assert a == b and hash(a) == hash(b)
    assert len({a, b, text("b")}) == 2
    assert {a: 1}[b] == 1
    assert a != text("a")


def test_res_names_an_id_in_any_package_and_res_id_pins_the_package():
    assert not res("login")._proto.node.resource.HasField("package_name")
    assert res_id("android", "button1")._proto.node.resource.package_name == "android"
    assert text("a").and_res("login") == text("a") & res("login")
    qualified = text("a").and_res("com.pkg", "login")
    node = qualified._proto.node.all_of.nodes[1].resource
    assert (node.package_name, node.name) == ("com.pkg", "login")


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
