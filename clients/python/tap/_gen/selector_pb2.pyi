from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class MatchMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MATCH_UNSPECIFIED: _ClassVar[MatchMode]
    MATCH_EXACT: _ClassVar[MatchMode]
    MATCH_CONTAINS: _ClassVar[MatchMode]
    MATCH_STARTS_WITH: _ClassVar[MatchMode]
    MATCH_ENDS_WITH: _ClassVar[MatchMode]
    MATCH_REGEX: _ClassVar[MatchMode]

class MatchLimit(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LIMIT_UNSPECIFIED: _ClassVar[MatchLimit]
    LIMIT_EXACTLY_ONE: _ClassVar[MatchLimit]
    LIMIT_FIRST: _ClassVar[MatchLimit]
    LIMIT_AT: _ClassVar[MatchLimit]

class TargetScope(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SCOPE_UNSPECIFIED: _ClassVar[TargetScope]
    SCOPE_AUT: _ClassVar[TargetScope]
    SCOPE_SYSTEM: _ClassVar[TargetScope]
MATCH_UNSPECIFIED: MatchMode
MATCH_EXACT: MatchMode
MATCH_CONTAINS: MatchMode
MATCH_STARTS_WITH: MatchMode
MATCH_ENDS_WITH: MatchMode
MATCH_REGEX: MatchMode
LIMIT_UNSPECIFIED: MatchLimit
LIMIT_EXACTLY_ONE: MatchLimit
LIMIT_FIRST: MatchLimit
LIMIT_AT: MatchLimit
SCOPE_UNSPECIFIED: TargetScope
SCOPE_AUT: TargetScope
SCOPE_SYSTEM: TargetScope

class StringMatch(_message.Message):
    __slots__ = ("value", "mode")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    value: str
    mode: MatchMode
    def __init__(self, value: _Optional[str] = ..., mode: _Optional[_Union[MatchMode, str]] = ...) -> None: ...

class ResourceId(_message.Message):
    __slots__ = ("name", "package_name", "aut_package")
    NAME_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    name: str
    package_name: str
    aut_package: bool
    def __init__(self, name: _Optional[str] = ..., package_name: _Optional[str] = ..., aut_package: _Optional[bool] = ...) -> None: ...

class NodeSelector(_message.Message):
    __slots__ = ("text", "content_description", "hint", "class_name", "resource", "enabled", "checked", "checkable", "clickable", "focused", "focusable", "long_clickable", "scrollable", "selected", "parent", "ancestor", "child", "descendant")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    HINT_FIELD_NUMBER: _ClassVar[int]
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    CHECKED_FIELD_NUMBER: _ClassVar[int]
    CHECKABLE_FIELD_NUMBER: _ClassVar[int]
    CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    FOCUSED_FIELD_NUMBER: _ClassVar[int]
    FOCUSABLE_FIELD_NUMBER: _ClassVar[int]
    LONG_CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    SCROLLABLE_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    PARENT_FIELD_NUMBER: _ClassVar[int]
    ANCESTOR_FIELD_NUMBER: _ClassVar[int]
    CHILD_FIELD_NUMBER: _ClassVar[int]
    DESCENDANT_FIELD_NUMBER: _ClassVar[int]
    text: StringMatch
    content_description: StringMatch
    hint: StringMatch
    class_name: StringMatch
    resource: ResourceId
    enabled: bool
    checked: bool
    checkable: bool
    clickable: bool
    focused: bool
    focusable: bool
    long_clickable: bool
    scrollable: bool
    selected: bool
    parent: NodeSelector
    ancestor: NodeSelector
    child: NodeSelector
    descendant: NodeSelector
    def __init__(self, text: _Optional[_Union[StringMatch, _Mapping]] = ..., content_description: _Optional[_Union[StringMatch, _Mapping]] = ..., hint: _Optional[_Union[StringMatch, _Mapping]] = ..., class_name: _Optional[_Union[StringMatch, _Mapping]] = ..., resource: _Optional[_Union[ResourceId, _Mapping]] = ..., enabled: _Optional[bool] = ..., checked: _Optional[bool] = ..., checkable: _Optional[bool] = ..., clickable: _Optional[bool] = ..., focused: _Optional[bool] = ..., focusable: _Optional[bool] = ..., long_clickable: _Optional[bool] = ..., scrollable: _Optional[bool] = ..., selected: _Optional[bool] = ..., parent: _Optional[_Union[NodeSelector, _Mapping]] = ..., ancestor: _Optional[_Union[NodeSelector, _Mapping]] = ..., child: _Optional[_Union[NodeSelector, _Mapping]] = ..., descendant: _Optional[_Union[NodeSelector, _Mapping]] = ...) -> None: ...

class Selector(_message.Message):
    __slots__ = ("node", "scope", "scope_package", "limit", "index", "accept_accessibility_order")
    NODE_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SCOPE_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    INDEX_FIELD_NUMBER: _ClassVar[int]
    ACCEPT_ACCESSIBILITY_ORDER_FIELD_NUMBER: _ClassVar[int]
    node: NodeSelector
    scope: TargetScope
    scope_package: str
    limit: MatchLimit
    index: int
    accept_accessibility_order: bool
    def __init__(self, node: _Optional[_Union[NodeSelector, _Mapping]] = ..., scope: _Optional[_Union[TargetScope, str]] = ..., scope_package: _Optional[str] = ..., limit: _Optional[_Union[MatchLimit, str]] = ..., index: _Optional[int] = ..., accept_accessibility_order: _Optional[bool] = ...) -> None: ...
