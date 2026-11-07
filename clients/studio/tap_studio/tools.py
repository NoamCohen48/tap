"""Closed SDK adapters for explicit, non-recorded Studio tools. Never shell or raw Execute."""
from __future__ import annotations

import json
import re
from dataclasses import asdict, is_dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path

from tap_e2e import DatabaseBundle, Device

from ._gen import studio_pb2 as pb
from .timing import seconds_from_ms

MAX_BYTES = 1 << 20
BUNDLE_FORMAT = "tap-studio-database/1"
MUTATIONS = {pb.PRIVATE_DATA_WRITE, pb.PRIVATE_DATA_DELETE, pb.PRIVATE_DATA_IMPORT_DATABASE}


class ToolInputError(ValueError):
    """Invalid tool input, rejected before an SDK operation."""


class ToolLimitError(ValueError):
    """A verified SDK result exceeds the unary browser download limit."""


def _default(value):
    if is_dataclass(value) and not isinstance(value, type):
        return asdict(value)
    if isinstance(value, Enum):
        return value.name
    if isinstance(value, (Path, datetime)):
        return str(value)
    raise TypeError(f"not JSON metadata: {type(value).__name__}")


def json_text(value) -> str:
    """SDK metadata as finite JSON, without introspecting private client/credential state."""
    return json.dumps(value, default=_default, ensure_ascii=False, allow_nan=False, indent=2)


def _package(value: str) -> None:
    if len(value) > 255 or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*", value):
        raise ToolInputError("a package name is required")


def _timeout(request) -> float | None:
    if not request.HasField("timeout_ms"):
        return None
    if not 1 <= request.timeout_ms <= 120000:
        raise ToolInputError("timeout_ms must be 1..120000")
    return seconds_from_ms(request.timeout_ms)


def query_app(device: Device, request: pb.QueryAppRequest) -> pb.QueryAppResponse:
    """Read one app state without adding a recording step or changing its lifecycle."""
    _package(request.package_name)
    budget = _timeout(request)
    if budget is not None and request.query != pb.APP_QUERY_PROCESS:
        raise ToolInputError("only process queries accept timeout_ms")
    if bool(request.permission) != (request.query == pb.APP_QUERY_PERMISSION):
        raise ToolInputError("permission is required only for the permission query")
    app = device.app(request.package_name)
    if request.query == pb.APP_QUERY_INSTALLED:
        result = app.is_installed()
    elif request.query == pb.APP_QUERY_RUNNING:
        result = app.is_running()
    elif request.query == pb.APP_QUERY_PROCESS:
        result = app.process(budget)
    elif request.query == pb.APP_QUERY_LOCALES:
        result = app.locales()
    elif request.query == pb.APP_QUERY_PERMISSION:
        result = app.is_permission_granted(request.permission)
    else:
        raise ToolInputError("unknown app query")
    return pb.QueryAppResponse(json=json_text(result))


def private_data(device: Device, request: pb.PrivateDataRequest) -> pb.PrivateDataResponse:
    """Use app.data only, preserving host UID-stop/path/sidecar checks; no implicit lifecycle."""
    _package(request.package_name)
    operation = request.operation
    allowed = {
        pb.PRIVATE_DATA_CAPABILITIES: set(),
        pb.PRIVATE_DATA_LIST: {"path"},
        pb.PRIVATE_DATA_READ: {"path", "timeout_ms"},
        pb.PRIVATE_DATA_WRITE: {"path", "data", "overwrite", "confirmed", "timeout_ms"},
        pb.PRIVATE_DATA_DELETE: {"path", "confirmed"},
        pb.PRIVATE_DATA_EXPORT_DATABASE: {"name", "timeout_ms"},
        pb.PRIVATE_DATA_IMPORT_DATABASE: {"name", "bundle", "overwrite", "confirmed", "timeout_ms"},
    }
    if operation not in allowed:
        raise ToolInputError("unknown private data operation")
    present = {field.name for field, _ in request.ListFields()} - {"operation", "package_name"}
    if present - allowed[operation]:
        raise ToolInputError("fields do not apply to the chosen private data operation")
    budget = _timeout(request)
    if operation in MUTATIONS and not request.confirmed:
        raise ToolInputError("explicit mutation confirmation is required")
    if operation in (pb.PRIVATE_DATA_READ, pb.PRIVATE_DATA_WRITE, pb.PRIVATE_DATA_DELETE) and not request.path:
        raise ToolInputError("a relative file path is required")
    if operation == pb.PRIVATE_DATA_EXPORT_DATABASE and not request.name:
        raise ToolInputError("a database basename is required")
    if len(request.data) > MAX_BYTES:
        raise ToolInputError("browser file transfers are limited to 1 MiB; use the SDK for larger data")
    if operation == pb.PRIVATE_DATA_IMPORT_DATABASE:
        bundle = request.bundle
        size = len(bundle.database) + len(bundle.wal) + len(bundle.journal)
        if not request.HasField("bundle") or bundle.format != BUNDLE_FORMAT or not bundle.name or not bundle.database or size > MAX_BYTES:
            raise ToolInputError("a tap-studio-database/1 bundle with a nonempty database and at most 1 MiB total is required")
    data = device.app(request.package_name).data
    response = pb.PrivateDataResponse()
    if operation == pb.PRIVATE_DATA_CAPABILITIES:
        response.json = json_text(data.capabilities())
    elif operation == pb.PRIVATE_DATA_LIST:
        response.json = json_text(data.list(request.path))
    elif operation == pb.PRIVATE_DATA_READ:
        result = data.read(request.path, timeout=budget)
        if len(result) > MAX_BYTES:
            raise ToolLimitError("private file exceeds the 1 MiB browser limit; use the SDK")
        response.data = result
    elif operation == pb.PRIVATE_DATA_WRITE:
        data.write(request.path, request.data, overwrite=request.overwrite, timeout=budget)
    elif operation == pb.PRIVATE_DATA_DELETE:
        data.delete(request.path)
    elif operation == pb.PRIVATE_DATA_EXPORT_DATABASE:
        exported = data.export_database(request.name, timeout=budget)
        if len(exported.database) + len(exported.wal or b"") + len(exported.journal or b"") > MAX_BYTES:
            raise ToolLimitError("database bundle exceeds the 1 MiB browser limit; use the SDK")
        response.bundle.CopyFrom(pb.DatabaseBundle(format=BUNDLE_FORMAT, name=exported.name, database=exported.database))
        if exported.wal is not None:
            response.bundle.wal = exported.wal
        if exported.journal is not None:
            response.bundle.journal = exported.journal
    elif operation == pb.PRIVATE_DATA_IMPORT_DATABASE:
        bundle = request.bundle
        imported = DatabaseBundle(bundle.name, bundle.database, bundle.wal if bundle.HasField("wal") else None, bundle.journal if bundle.HasField("journal") else None)
        data.import_database(imported, name=request.name or None, overwrite=request.overwrite, timeout=budget)
    if operation in MUTATIONS:
        response.json = json_text({"completed": True, "note": "No implicit relaunch or restore. Do not repeat after an uncertain result."})
    return response


def diagnostic(device: Device, request: pb.DiagnosticRequest) -> pb.DiagnosticResponse:
    """One bounded SDK download. History belongs to Studio's connection, not another client's."""
    kind = request.kind
    if request.HasField("after_seq") and (kind != pb.DIAGNOSTIC_EVENTS or request.after_seq < 0):
        raise ToolInputError("after_seq is nonnegative and only applies to raw event history")
    if kind == pb.DIAGNOSTIC_SCREENSHOT:
        content, name, mime = device.screenshot().bytes, "screen.png", "image/png"
    elif kind == pb.DIAGNOSTIC_HIERARCHY:
        content, name, mime = device.dump_hierarchy().bytes, "hierarchy.xml", "application/xml"
    elif kind == pb.DIAGNOSTIC_DRIVER_LOG:
        content, name, mime = device.driver_log().bytes, "driver-log.txt", "text/plain"
    else:
        if kind == pb.DIAGNOSTIC_DEVICE_LOGS:
            result, name = device.device_logs(), "device-logs.json"
        elif kind == pb.DIAGNOSTIC_DEVICE_INFO:
            result, name = device.info(), "device-info.json"
        elif kind == pb.DIAGNOSTIC_SERVER_INFO:
            result, name = device.client.info(), "server-info.json"
        elif kind == pb.DIAGNOSTIC_DEVICES:
            result, name = device.client.devices(), "devices.json"
        elif kind == pb.DIAGNOSTIC_EVENTS:
            log = device.owner_connection.event_log(request.after_seq)
            result, name = {"format": "tap-events/1", "connection": "tap-studio", "dropped": log.dropped, "events": [e.to_dict() for e in log.events]}, "events.json"
        else:
            raise ToolInputError("unknown diagnostic kind")
        content, mime = json_text(result).encode("utf-8"), "application/json"
    if len(content) > MAX_BYTES:
        raise ToolLimitError("diagnostic exceeds the 1 MiB browser limit; use the SDK")
    return pb.DiagnosticResponse(data=content, filename=name, content_type=mime)
