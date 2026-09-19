"""Selector builders. A Selector wraps the protocol's own AST (tap.v1.Selector), so what a test
builds is validated identically by the service and the driver and rendered in errors. Building
one does no I/O; every action resolves it again on the device."""
from __future__ import annotations

from dataclasses import dataclass

from google.protobuf import text_format

from ._gen import tap_pb2 as pb

EXACT = pb.MATCH_EXACT
CONTAINS = pb.MATCH_CONTAINS
STARTS_WITH = pb.MATCH_STARTS_WITH
REGEX = pb.MATCH_REGEX


def _match(value: str, mode: int) -> pb.StringMatch:
    return pb.StringMatch(value=value, mode=mode)


@dataclass(frozen=True)
class Selector:
    """An immutable description of one accessibility node.

    Build one with the module functions (``text``, ``res_id``, ``desc``, ...) and narrow it
    with the methods below; each returns a new Selector. Bind it with ``Device.element``.
    Matching happens on the device, scoped to the app under test; see the selectors guide.
    """

    proto: pb.Selector

    # --- property refinements on this node -----------------------------------------------

    def _node(self, **fields) -> "Selector":
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        for name, value in fields.items():
            if isinstance(value, bool):
                setattr(copy.node, name, value)
            else:
                getattr(copy.node, name).CopyFrom(value)
        return Selector(copy)

    def and_text(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this text (``mode``: ``EXACT``, ``CONTAINS``, ``STARTS_WITH``, ``REGEX``)."""
        return self._node(text=_match(value, mode))

    def and_desc(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this content description."""
        return self._node(content_description=_match(value, mode))

    def and_class_name(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this widget class name."""
        return self._node(class_name=_match(value, mode))

    def and_hint(self, value: str, mode: int = EXACT) -> "Selector":
        """Also require this hint (empty text fields)."""
        return self._node(hint=_match(value, mode))

    def and_res(self, package_name: str, name: str) -> "Selector":
        """Also require the resource id ``package_name:id/name``."""
        return self._node(resource=pb.ResourceId(name=name, package_name=package_name))

    def checkable(self, value: bool = True) -> "Selector":
        """Require ``isCheckable == value``."""
        return self._node(checkable=value)

    def checked(self, value: bool = True) -> "Selector":
        """Require ``isChecked == value``."""
        return self._node(checked=value)

    def clickable(self, value: bool = True) -> "Selector":
        """Require ``isClickable == value``."""
        return self._node(clickable=value)

    def enabled(self, value: bool = True) -> "Selector":
        """Require ``isEnabled == value``."""
        return self._node(enabled=value)

    def focusable(self, value: bool = True) -> "Selector":
        """Require ``isFocusable == value``."""
        return self._node(focusable=value)

    def focused(self, value: bool = True) -> "Selector":
        """Require ``isFocused == value``."""
        return self._node(focused=value)

    def long_clickable(self, value: bool = True) -> "Selector":
        """Require ``isLongClickable == value``."""
        return self._node(long_clickable=value)

    def scrollable(self, value: bool = True) -> "Selector":
        """Require ``isScrollable == value``."""
        return self._node(scrollable=value)

    def selected(self, value: bool = True) -> "Selector":
        """Require ``isSelected == value``."""
        return self._node(selected=value)

    # --- relations ------------------------------------------------------------------------

    def has_descendant(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when some descendant matches ``other``."""
        return self._node(descendant=other.proto.node)

    def has_child(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when a direct child matches ``other``."""
        return self._node(child=other.proto.node)

    def has_parent(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when its parent matches ``other``."""
        return self._node(parent=other.proto.node)

    def has_ancestor(self, other: "Selector") -> "Selector":
        """Keep matching this node, but only when an ancestor matches ``other``."""
        return self._node(ancestor=other.proto.node)

    def descendant(self, other: "Selector") -> "Selector":
        """An element matching ``other`` somewhere below this one, in this selector's scope."""
        copy = pb.Selector()
        copy.CopyFrom(other.proto)
        copy.node.ancestor.CopyFrom(self.proto.node)
        self._copy_scope(copy)
        return Selector(copy)

    def child(self, other: "Selector") -> "Selector":
        """Match the direct child ``other`` of this node."""
        copy = pb.Selector()
        copy.CopyFrom(other.proto)
        copy.node.parent.CopyFrom(self.proto.node)
        self._copy_scope(copy)
        return Selector(copy)

    def _copy_scope(self, target: pb.Selector) -> None:
        target.scope = self.proto.scope
        if self.proto.HasField("scope_package"):
            target.scope_package = self.proto.scope_package
        else:
            target.ClearField("scope_package")

    # --- scope and match limit --------------------------------------------------------------

    def in_system_package(self, package_name: str) -> "Selector":
        """Allow this selector to match inside the allowlisted system package ``package_name``
        (by default only ``com.google.android.permissioncontroller``) instead of the app under test.
        """
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.scope = pb.SCOPE_SYSTEM
        copy.scope_package = package_name
        return Selector(copy)

    def first(self) -> "Selector":
        """Accept the first match in accessibility order instead of requiring exactly one."""
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.limit = pb.LIMIT_FIRST
        copy.ClearField("index")
        copy.accept_accessibility_order = True
        return Selector(copy)

    def at(self, index: int) -> "Selector":
        """Accept the ``index``-th match (0-based) in accessibility order instead of requiring exactly one."""
        copy = pb.Selector()
        copy.CopyFrom(self.proto)
        copy.limit = pb.LIMIT_AT
        copy.index = index
        copy.accept_accessibility_order = True
        return Selector(copy)

    def render(self) -> str:
        """The selector as protobuf text, exactly as the device will see it."""
        return text_format.MessageToString(self.proto, as_one_line=True)

    def __str__(self) -> str:
        return self.render()


def _selector(**node_fields) -> Selector:
    return Selector(pb.Selector(node=pb.NodeSelector(**node_fields)))


def text(value: str, mode: int = EXACT) -> Selector:
    """Visible text; Compose ``Text`` and View ``TextView``/``Button`` both expose it."""
    return _selector(text=_match(value, mode))


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
    return _selector(content_description=_match(value, mode))


def raw_res(name: str) -> Selector:
    """Compose ``testTag`` projected through ``testTagsAsResourceId``; never package-qualified."""
    return _selector(resource=pb.ResourceId(name=name))


def res_id(package_name: str, name: str) -> Selector:
    """Android View resource id ``package:id/name``."""
    return _selector(resource=pb.ResourceId(name=name, package_name=package_name))


def class_name(value: str, mode: int = EXACT) -> Selector:
    """Widget class name, e.g. ``android.widget.EditText``."""
    return _selector(class_name=_match(value, mode))


def hint(value: str, mode: int = EXACT) -> Selector:
    """Hint of an empty text field (an empty ``EditText`` shows its hint as its text)."""
    return _selector(hint=_match(value, mode))


def clickable() -> Selector:
    """Any clickable node; combine with ``and_text`` etc."""
    return _selector(clickable=True)


def scrollable() -> Selector:
    """Any scrollable node; combine with ``and_res`` etc."""
    return _selector(scrollable=True)
