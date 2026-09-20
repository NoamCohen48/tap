"""Selector builders. A Selector wraps the protocol's own AST (tap.v1.Selector), a small
expression tree, so what a test builds is validated identically by the service and the driver
and rendered in errors. Building one does no I/O; every action resolves it again on the device."""
from __future__ import annotations

from dataclasses import dataclass

from google.protobuf import text_format

from . import _gen as pb

EXACT = pb.MATCH_EXACT
CONTAINS = pb.MATCH_CONTAINS
STARTS_WITH = pb.MATCH_STARTS_WITH
ENDS_WITH = pb.MATCH_ENDS_WITH
REGEX = pb.MATCH_REGEX


# --- node constructors (the proto tree, normalised) -------------------------------------------


def _match(prop: int, value: str, mode: int) -> pb.Node:
    return pb.Node(match=pb.Match(property=prop, value=value, mode=mode))


def _flag(prop: int, value: bool) -> pb.Node:
    return pb.Node(flag=pb.Flag(property=prop, value=value))


def _resource(name: str, package_name: str | None = None) -> pb.Node:
    resource = pb.ResourceId(name=name)
    if package_name is not None:
        resource.package_name = package_name
    return pb.Node(resource=resource)


def _aut_resource(name: str) -> pb.Node:
    return pb.Node(resource=pb.ResourceId(name=name, aut_package=True))


def _related(relation: int, node: pb.Node) -> pb.Node:
    return pb.Node(related=pb.Related(relation=relation, node=node))


def _all_of(*nodes: pb.Node) -> pb.Node:
    """A conjunction with nested ``all_of`` flattened, so refinements chain into one flat node."""
    flat = [n for node in nodes for n in (node.all_of.nodes if node.HasField("all_of") else [node])]
    return flat[0] if len(flat) == 1 else pb.Node(all_of=pb.AllOf(nodes=flat))


def _any_of(*nodes: pb.Node) -> pb.Node:
    """A disjunction with nested ``any_of`` flattened."""
    flat = [n for node in nodes for n in (node.any_of.nodes if node.HasField("any_of") else [node])]
    return flat[0] if len(flat) == 1 else pb.Node(any_of=pb.AnyOf(nodes=flat))


@dataclass(frozen=True)
class Selector:
    """An immutable description of one accessibility node.

    Build one with the module functions (``text``, ``res``, ``desc``, ...), narrow it with
    the methods below and combine selectors with ``&`` / ``|``; each returns a new Selector.
    Bind it with ``Device.element``. Matching happens on the device, scoped to the app under
    test; see the selectors guide.
    """

    proto: pb.Selector

    def _with_node(self, node: pb.Node) -> "Selector":
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.node.CopyFrom(node)
        return Selector(copy)

    def _also(self, node: pb.Node) -> "Selector":
        """This selector's node, further constrained by ``node``."""
        return self._with_node(_all_of(self.proto.node, node))

    # --- property refinements on this node -----------------------------------------------

    def and_text(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this text (``mode``: ``EXACT``, ``CONTAINS``, ``STARTS_WITH``, ``ENDS_WITH``, ``REGEX``)."""
        return self._also(_match(pb.PROPERTY_TEXT, value, mode))

    def and_desc(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this content description."""
        return self._also(_match(pb.PROPERTY_CONTENT_DESCRIPTION, value, mode))

    def and_class_name(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this widget class name."""
        return self._also(_match(pb.PROPERTY_CLASS_NAME, value, mode))

    def and_hint(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this hint (empty text fields)."""
        return self._also(_match(pb.PROPERTY_HINT, value, mode))

    def and_res(self, package_name: str, name: str) -> "Selector":
        """Also require the resource id ``package_name:id/name``."""
        return self._also(_resource(name, package_name))

    def and_res_aut(self, name: str) -> "Selector":
        """Also require the app-under-test resource id ``name`` (see ``res``)."""
        return self._also(_aut_resource(name))

    def checkable(self, value: bool = True) -> "Selector":
        """Require ``isCheckable == value``."""
        return self._also(_flag(pb.FLAG_CHECKABLE, value))

    def checked(self, value: bool = True) -> "Selector":
        """Require ``isChecked == value``."""
        return self._also(_flag(pb.FLAG_CHECKED, value))

    def clickable(self, value: bool = True) -> "Selector":
        """Require ``isClickable == value``."""
        return self._also(_flag(pb.FLAG_CLICKABLE, value))

    def enabled(self, value: bool = True) -> "Selector":
        """Require ``isEnabled == value``."""
        return self._also(_flag(pb.FLAG_ENABLED, value))

    def focusable(self, value: bool = True) -> "Selector":
        """Require ``isFocusable == value``."""
        return self._also(_flag(pb.FLAG_FOCUSABLE, value))

    def focused(self, value: bool = True) -> "Selector":
        """Require ``isFocused == value``."""
        return self._also(_flag(pb.FLAG_FOCUSED, value))

    def long_clickable(self, value: bool = True) -> "Selector":
        """Require ``isLongClickable == value``."""
        return self._also(_flag(pb.FLAG_LONG_CLICKABLE, value))

    def scrollable(self, value: bool = True) -> "Selector":
        """Require ``isScrollable == value``."""
        return self._also(_flag(pb.FLAG_SCROLLABLE, value))

    def selected(self, value: bool = True) -> "Selector":
        """Require ``isSelected == value``."""
        return self._also(_flag(pb.FLAG_SELECTED, value))

    # --- combinators ----------------------------------------------------------------------

    def __and__(self, other: "Selector") -> "Selector":
        """Both must hold on the same node: ``text("Add") & clickable()``.
        Scope and match choice are taken from the left operand."""
        return self._also(other.proto.node)

    def __or__(self, other: "Selector") -> "Selector":
        """Either may hold: ``text("Allow") | text("Allow only while using the app")``.
        Scope and match choice are taken from the left operand. Disjunctions are evaluated by
        the driver's tree walk rather than a native UiAutomator lookup."""
        return self._with_node(_any_of(self.proto.node, other.proto.node))

    # --- relations ------------------------------------------------------------------------

    def has_descendant(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when some descendant matches ``other``."""
        return self._also(_related(pb.RELATION_DESCENDANT, other.proto.node))

    def has_child(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when a direct child matches ``other``."""
        return self._also(_related(pb.RELATION_CHILD, other.proto.node))

    def has_parent(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when its parent matches ``other``."""
        return self._also(_related(pb.RELATION_PARENT, other.proto.node))

    def has_ancestor(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when an ancestor matches ``other``."""
        return self._also(_related(pb.RELATION_ANCESTOR, other.proto.node))

    def descendant(self, other: "Selector") -> "Selector":
        """An element matching ``other`` somewhere below this one, in this selector's scope."""
        return self._navigate(other, pb.RELATION_ANCESTOR)

    def child(self, other: "Selector") -> "Selector":
        """Match the direct child ``other`` of this node."""
        return self._navigate(other, pb.RELATION_PARENT)

    def _navigate(self, other: "Selector", back: int) -> "Selector":
        copy = pb.Selector()
        copy.CopyFrom(other.proto)
        copy.node.CopyFrom(_all_of(other.proto.node, _related(back, self.proto.node)))
        if self.proto.HasField("system"):
            copy.system.CopyFrom(self.proto.system)
        else:
            copy.ClearField("scope")
        return Selector(copy)

    # --- scope and match choice -----------------------------------------------------------

    def in_system_package(self, package_name: str) -> "Selector":
        """Allow this selector to match inside the allowlisted system package ``package_name``
        (by default only ``com.google.android.permissioncontroller``) instead of the app under test.
        """
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.system.package_name = package_name
        return Selector(copy)

    def first(self) -> "Selector":
        """Accept the first match in accessibility order instead of requiring exactly one."""
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.first.SetInParent()
        return Selector(copy)

    def at(self, index: int) -> "Selector":
        """Accept the ``index``-th match (0-based) in accessibility order instead of requiring exactly one."""
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.at.index = index
        return Selector(copy)

    def render(self) -> str:
        """The selector as protobuf text, exactly as the device will see it."""
        return text_format.MessageToString(self.proto, as_one_line=True)

    def __str__(self) -> str:
        return self.render()


def _selector(node: pb.Node) -> Selector:
    return Selector(pb.Selector(node=node))


def text(value: str, mode: int = EXACT) -> Selector:
    """Visible text; Compose ``Text`` and View ``TextView``/``Button`` both expose it."""
    return _selector(_match(pb.PROPERTY_TEXT, value, mode))


def text_contains(value: str) -> Selector:
    """Text containing ``value``."""
    return text(value, CONTAINS)


def text_starts_with(value: str) -> Selector:
    """Text starting with ``value``."""
    return text(value, STARTS_WITH)


def text_matches(re2: str) -> Selector:
    """Text fully matching the RE2 regular expression ``re2``."""
    return text(re2, REGEX)


def desc(value: str, mode: int = EXACT) -> Selector:
    """``contentDescription`` (View) / ``contentDescription`` semantics (Compose)."""
    return _selector(_match(pb.PROPERTY_CONTENT_DESCRIPTION, value, mode))


def raw_res(name: str) -> Selector:
    """Compose ``testTag`` projected through ``testTagsAsResourceId``; never package-qualified."""
    return _selector(_resource(name))


def res_id(package_name: str, name: str) -> Selector:
    """Android View resource id ``package:id/name``."""
    return _selector(_resource(name, package_name))


def res(name: str) -> Selector:
    """View resource id ``name`` of the **app under test**: ``<aut>:id/name``, with the package
    filled in by the service from the session, so the same selector works on every device and
    role. Use ``res_id`` for another package (a system dialog with ``in_system_package``)."""
    return _selector(_aut_resource(name))


def class_name(value: str, mode: int = EXACT) -> Selector:
    """Widget class name, e.g. ``android.widget.EditText``."""
    return _selector(_match(pb.PROPERTY_CLASS_NAME, value, mode))


def hint(value: str, mode: int = EXACT) -> Selector:
    """Hint of an empty text field (an empty ``EditText`` shows its hint as its text)."""
    return _selector(_match(pb.PROPERTY_HINT, value, mode))


def clickable() -> Selector:
    """Any clickable node; combine with ``and_text`` etc."""
    return _selector(_flag(pb.FLAG_CLICKABLE, True))


def scrollable() -> Selector:
    """Any scrollable node; combine with ``and_res`` etc."""
    return _selector(_flag(pb.FLAG_SCROLLABLE, True))


def any_of(first: Selector, *rest: Selector) -> Selector:
    """Any of the selectors may match: ``any_of(text("OK"), text("Allow"), desc("Accept"))``.
    Scope and match choice come from the first."""
    result = first
    for other in rest:
        result = result | other
    return result


def all_of(first: Selector, *rest: Selector) -> Selector:
    """All of the selectors must hold on one node. Scope and match choice come from the first."""
    result = first
    for other in rest:
        result = result & other
    return result
