# ruff: noqa
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
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

class TextProperty(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PROPERTY_UNSPECIFIED: _ClassVar[TextProperty]
    PROPERTY_TEXT: _ClassVar[TextProperty]
    PROPERTY_CONTENT_DESCRIPTION: _ClassVar[TextProperty]
    PROPERTY_HINT: _ClassVar[TextProperty]
    PROPERTY_CLASS_NAME: _ClassVar[TextProperty]

class NodeFlag(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    FLAG_UNSPECIFIED: _ClassVar[NodeFlag]
    FLAG_ENABLED: _ClassVar[NodeFlag]
    FLAG_CHECKED: _ClassVar[NodeFlag]
    FLAG_CHECKABLE: _ClassVar[NodeFlag]
    FLAG_CLICKABLE: _ClassVar[NodeFlag]
    FLAG_FOCUSED: _ClassVar[NodeFlag]
    FLAG_FOCUSABLE: _ClassVar[NodeFlag]
    FLAG_LONG_CLICKABLE: _ClassVar[NodeFlag]
    FLAG_SCROLLABLE: _ClassVar[NodeFlag]
    FLAG_SELECTED: _ClassVar[NodeFlag]

class Relation(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RELATION_UNSPECIFIED: _ClassVar[Relation]
    RELATION_PARENT: _ClassVar[Relation]
    RELATION_ANCESTOR: _ClassVar[Relation]
    RELATION_CHILD: _ClassVar[Relation]
    RELATION_DESCENDANT: _ClassVar[Relation]
MATCH_UNSPECIFIED: MatchMode
MATCH_EXACT: MatchMode
MATCH_CONTAINS: MatchMode
MATCH_STARTS_WITH: MatchMode
MATCH_ENDS_WITH: MatchMode
MATCH_REGEX: MatchMode
PROPERTY_UNSPECIFIED: TextProperty
PROPERTY_TEXT: TextProperty
PROPERTY_CONTENT_DESCRIPTION: TextProperty
PROPERTY_HINT: TextProperty
PROPERTY_CLASS_NAME: TextProperty
FLAG_UNSPECIFIED: NodeFlag
FLAG_ENABLED: NodeFlag
FLAG_CHECKED: NodeFlag
FLAG_CHECKABLE: NodeFlag
FLAG_CLICKABLE: NodeFlag
FLAG_FOCUSED: NodeFlag
FLAG_FOCUSABLE: NodeFlag
FLAG_LONG_CLICKABLE: NodeFlag
FLAG_SCROLLABLE: NodeFlag
FLAG_SELECTED: NodeFlag
RELATION_UNSPECIFIED: Relation
RELATION_PARENT: Relation
RELATION_ANCESTOR: Relation
RELATION_CHILD: Relation
RELATION_DESCENDANT: Relation

class Match(_message.Message):
    __slots__ = ("property", "value", "mode")
    PROPERTY_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    property: TextProperty
    value: str
    mode: MatchMode
    def __init__(self, property: _Optional[_Union[TextProperty, str]] = ..., value: _Optional[str] = ..., mode: _Optional[_Union[MatchMode, str]] = ...) -> None: ...

class Flag(_message.Message):
    __slots__ = ("property", "value")
    PROPERTY_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    property: NodeFlag
    value: bool
    def __init__(self, property: _Optional[_Union[NodeFlag, str]] = ..., value: _Optional[bool] = ...) -> None: ...

class ResourceId(_message.Message):
    __slots__ = ("name", "package_name", "aut_package")
    NAME_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    name: str
    package_name: str
    aut_package: bool
    def __init__(self, name: _Optional[str] = ..., package_name: _Optional[str] = ..., aut_package: _Optional[bool] = ...) -> None: ...

class Related(_message.Message):
    __slots__ = ("relation", "node")
    RELATION_FIELD_NUMBER: _ClassVar[int]
    NODE_FIELD_NUMBER: _ClassVar[int]
    relation: Relation
    node: Node
    def __init__(self, relation: _Optional[_Union[Relation, str]] = ..., node: _Optional[_Union[Node, _Mapping]] = ...) -> None: ...

class AllOf(_message.Message):
    __slots__ = ("nodes",)
    NODES_FIELD_NUMBER: _ClassVar[int]
    nodes: _containers.RepeatedCompositeFieldContainer[Node]
    def __init__(self, nodes: _Optional[_Iterable[_Union[Node, _Mapping]]] = ...) -> None: ...

class AnyOf(_message.Message):
    __slots__ = ("nodes",)
    NODES_FIELD_NUMBER: _ClassVar[int]
    nodes: _containers.RepeatedCompositeFieldContainer[Node]
    def __init__(self, nodes: _Optional[_Iterable[_Union[Node, _Mapping]]] = ...) -> None: ...

class Node(_message.Message):
    __slots__ = ("match", "flag", "resource", "related", "all_of", "any_of")
    MATCH_FIELD_NUMBER: _ClassVar[int]
    FLAG_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_FIELD_NUMBER: _ClassVar[int]
    RELATED_FIELD_NUMBER: _ClassVar[int]
    ALL_OF_FIELD_NUMBER: _ClassVar[int]
    ANY_OF_FIELD_NUMBER: _ClassVar[int]
    match: Match
    flag: Flag
    resource: ResourceId
    related: Related
    all_of: AllOf
    any_of: AnyOf
    def __init__(self, match: _Optional[_Union[Match, _Mapping]] = ..., flag: _Optional[_Union[Flag, _Mapping]] = ..., resource: _Optional[_Union[ResourceId, _Mapping]] = ..., related: _Optional[_Union[Related, _Mapping]] = ..., all_of: _Optional[_Union[AllOf, _Mapping]] = ..., any_of: _Optional[_Union[AnyOf, _Mapping]] = ...) -> None: ...

class AutScope(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SystemScope(_message.Message):
    __slots__ = ("package_name",)
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    def __init__(self, package_name: _Optional[str] = ...) -> None: ...

class ExactlyOne(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class First(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class At(_message.Message):
    __slots__ = ("index",)
    INDEX_FIELD_NUMBER: _ClassVar[int]
    index: int
    def __init__(self, index: _Optional[int] = ...) -> None: ...

class Selector(_message.Message):
    __slots__ = ("node", "aut", "system", "exactly_one", "first", "at")
    NODE_FIELD_NUMBER: _ClassVar[int]
    AUT_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_FIELD_NUMBER: _ClassVar[int]
    EXACTLY_ONE_FIELD_NUMBER: _ClassVar[int]
    FIRST_FIELD_NUMBER: _ClassVar[int]
    AT_FIELD_NUMBER: _ClassVar[int]
    node: Node
    aut: AutScope
    system: SystemScope
    exactly_one: ExactlyOne
    first: First
    at: At
    def __init__(self, node: _Optional[_Union[Node, _Mapping]] = ..., aut: _Optional[_Union[AutScope, _Mapping]] = ..., system: _Optional[_Union[SystemScope, _Mapping]] = ..., exactly_one: _Optional[_Union[ExactlyOne, _Mapping]] = ..., first: _Optional[_Union[First, _Mapping]] = ..., at: _Optional[_Union[At, _Mapping]] = ...) -> None: ...
