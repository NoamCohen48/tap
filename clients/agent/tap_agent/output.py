"""Small structured replies and exclusive owner-only artifact outputs."""

from __future__ import annotations

import dataclasses
import enum
import json
import os
from pathlib import Path
from typing import Any

from google.protobuf.json_format import MessageToDict
from google.protobuf.message import Message
from tap_e2e import Selector  # pyright: ignore[reportMissingImports]


def json_value(value: Any) -> Any:
    """Convert SDK values to JSON data, never implicitly decoding artifact bytes."""
    if isinstance(value, Selector):
        return MessageToDict(value.to_proto(), preserving_proto_field_name=True)
    if isinstance(value, Message):
        return MessageToDict(value, preserving_proto_field_name=True)
    if isinstance(value, enum.Enum):
        return value.name
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        result = {field.name: json_value(getattr(value, field.name)) for field in dataclasses.fields(value)}
        if result.get("password") is True:
            for key in ("text", "hint", "error", "content_description", "selector", "selector_candidates"):
                if key in result:
                    result[key] = None
        return result
    if isinstance(value, dict):
        return {str(key): json_value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list, set, frozenset)):
        items = [json_value(item) for item in value]
        return sorted(items) if isinstance(value, (set, frozenset)) else items
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise TypeError(f"Cannot render {type(value).__name__} as agent JSON")


def json_text(value: Any) -> str:
    """An SDK value as readable JSON; large artifact contents belong in files instead."""
    return json.dumps(json_value(value), ensure_ascii=False, indent=2, allow_nan=False)


def save_private(path: Path, data: bytes) -> Path:
    """Create a new mode-0600 file, refusing existing outputs including symlinks."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
    except BaseException:
        # Do not leave an apparently complete artifact after a definite local write failure.
        path.unlink(missing_ok=True)
        raise
    return path
