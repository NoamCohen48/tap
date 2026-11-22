"""`capture`: take an evidence bundle, and list, show or clean the ones taken."""
from __future__ import annotations

import datetime
import pathlib
import re
import time
from typing import TYPE_CHECKING, Any

from .output import json_text, save_private
from .records import RecordError
from .targets import UsageError, parse_duration

if TYPE_CHECKING:
    from .core import Agent

ACTIONS = ("list", "show", "clean")
_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")


def _usage(message: str) -> None:
    from .core import EXIT_USAGE, AgentError
    raise AgentError(message, EXIT_USAGE)


def _since(last: str | None, start: str | None, flags: tuple[str, str] = ("--last", "--from")) -> float | None:
    from .history import _time
    if last is not None and start is not None:
        _usage(f"capture takes {flags[0]} or {flags[1]}, not both")
    try:
        if start is not None:
            return _time(flags[1], start)
        return time.time() - parse_duration(last) if last is not None else None
    except UsageError as error:
        _usage(str(error))
    return None


def _iso(seconds: float | None) -> str | None:
    if seconds is None:
        return None
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def capture(agent: Agent, action: str | None = None, name: str | None = None, device: str | None = None,
            out: str | None = None, *, last: str | None = None, start: str | None = None, long: bool = False,
            older_than: str | None = None, all_captures: bool = False, as_json: bool = False,
            newer_than: str | None = None, since: str | None = None) -> str:
    """Without ``action``: take a bundle, labelled ``name`` when given, with the history of the
    ``last`` duration or from ``start``. ``list`` (``newer_than`` a duration or ``since`` a time),
    ``show NAME|ID`` and ``clean NAME|ID`` (or ``older_than`` / ``all_captures``) manage the
    bundles taken."""
    if action is not None and action not in ACTIONS:
        _usage(f"capture takes {', '.join(ACTIONS)}, or nothing to take a bundle (--name NAME labels it), not {action!r}")
    if action is None:
        if long or older_than is not None or all_captures or as_json or newer_than is not None or since is not None:
            _usage("-L, --newer-than, --since, --older-than, --all and --json go with capture list, show or clean")
        return _take(agent, name, device, out, _since(last, start))
    if out is not None:
        _usage(f"-o is for taking a capture, not capture {action}")
    if last is not None or start is not None:
        _usage(f"--last and --from choose the history a new bundle holds; capture list filters with --newer-than or --since")
    if action == "list":
        if name is not None or older_than is not None or all_captures:
            _usage("capture list takes -L, --device, --newer-than/--since and --json")
        return _list(agent, device, _since(newer_than, since, ("--newer-than", "--since")), long=long, as_json=as_json)
    if newer_than is not None or since is not None:
        _usage(f"--newer-than and --since go with capture list, not {action}")
    if long:
        _usage(f"capture {action} takes NAME or ID" + (", --older-than or --all" if action == "clean" else ""))
    if action == "show":
        if name is None or older_than is not None or all_captures:
            _usage("capture show takes one NAME or ID")
        assert name is not None
        return _show(agent, name, as_json=as_json)
    if as_json:
        _usage("capture clean prints text only")
    chosen = [given for given in (name is not None, older_than is not None, all_captures) if given]
    if len(chosen) != 1:
        _usage("capture clean takes one of NAME|ID, --older-than DURATION or --all")
    return _clean(agent, name, older_than, all_captures)


def _take(agent: Agent, name: str | None, device: str | None, out: str | None, since: float | None) -> str:
    from .history import complete
    if name is not None:
        if not _NAME.fullmatch(name) or name in ACTIONS:
            _usage("a capture name is 1..80 ASCII letters/digits/dots/dashes/underscores, not list/show/clean")
        try:
            taken = agent.records.capture_name_taken(name)
        except RecordError:
            taken = False  # indexing reports the storage problem after the files are saved
        if taken:
            _usage(f"a capture named {name!r} exists; clean it or choose another name")

    def step() -> str:
        d = agent._device(device)
        label = f"{name}-" if name else ""
        directory = pathlib.Path(out) if out else agent.out_dir / f"capture-{d.serial}-{label}{_stamp()}"
        directory.mkdir(mode=0o700, parents=True)
        started = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")
        captured = d.capture()
        stem = captured.serial.replace("/", "_").replace("\\", "_")
        saved = [str(save_private(directory / f"{stem}.{part}.{artifact.extension}", artifact.bytes))
            for part, artifact in captured.artifacts.items()]
        failures: dict[str, BaseException] = dict(captured.failures)
        try:  # the hierarchy as the outline an agent reads, with its refs; never fails the capture
            from . import render
            outline = render.snapshot_text(d.screen_snapshot(), render.ALL, filters=render.SnapshotFilter(keyboard="show", system_bars="show"))
        except Exception as error:  # noqa: BLE001 - reported as a failed part, like the others
            failures["snapshot"] = error
        else:
            saved.append(str(save_private(directory / f"{stem}.snapshot.txt", (outline + "\n").encode())))
        try:  # what led up to now; reading it never fails the capture
            text, document = complete(agent, d.owner_connection.id, d.serial, since)
        except Exception as error:  # noqa: BLE001 - reported as a failed part, like the others
            failures["history"] = error
        else:
            saved.append(str(save_private(directory / f"{stem}.history.txt", text.encode())))
            saved.append(str(save_private(directory / f"{stem}.history.json", (json_text(document) + "\n").encode())))
        lines = saved + [f"{part} failed: {error}" for part, error in failures.items()]
        try:
            record = agent.records.ensure(agent.session, d.owner_connection.id)
            with agent.records.lease(record["id"]):
                identifier = agent.records.artifact(record, serial=d.serial, attached_device_id=d.attached_device_id,
                    started_at=started, directory=str(directory.absolute()), parts=[pathlib.Path(p).name for p in saved],
                    failures={part: type(error).__name__ for part, error in failures.items()}, name=name)
            lines.insert(0, f"capture {name or identifier[:8]} saved to {directory.absolute()}")
        except RecordError:
            lines.append("WARNING: capture files were saved, but their history reference could not be indexed; do not repeat capture to recover them")
        return "\n".join(lines)

    return agent._run(step)


def _stamp() -> str:
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]


def _size(row: dict[str, Any]) -> int | None:
    """The bytes of the bundle's indexed files still on disk; None when none is left."""
    sizes = []
    for part in row["parts"]:
        try:
            sizes.append((pathlib.Path(row["directory"]) / part).stat().st_size)
        except OSError:
            continue
    return sum(sizes) if sizes else None


def _bytes(size: int | None) -> str:
    if size is None:
        return "-"
    value = float(size)
    for unit in ("B", "KiB", "MiB"):
        if value < 1024:
            return f"{value:.0f}{unit}" if unit == "B" else f"{value:.1f}{unit}"
        value /= 1024
    return f"{value:.1f}GiB"


def _label(row: dict[str, Any]) -> str:
    return row.get("name") or row["id"][:8]


def _entry(row: dict[str, Any]) -> dict[str, Any]:
    return {"name": row.get("name"), "id": row["id"], "at": row["at"], "serial": row["serial"],
        "directory": row["directory"], "parts": row["parts"], "failures": row["failures"],
        "bytes": _size(row) if not row.get("cleaned_at") else None, "cleaned_at": row.get("cleaned_at")}


def _list(agent: Agent, device: str | None, since: float | None, *, long: bool, as_json: bool) -> str:
    def run() -> str:
        rows = agent.records.captures(start=_iso(since), devices=(device,) if device else ())
        if as_json:
            return json_text([_entry(row) for row in rows])
        lines = []
        for row in rows:
            state = f"cleaned {row['cleaned_at']}" if row.get("cleaned_at") else _bytes(_size(row))
            line = f"{row['at']}  {_label(row)}  {row['serial']}  {state}"
            if long:
                line += f"  id={row['id']}  {row['directory']}  parts={','.join(row['parts'])}"
                if row["failures"]:
                    line += f"  failed={','.join(row['failures'])}"
            lines.append(line)
        return "\n".join(lines) or "no captures"
    return agent._run(run)


def _show(agent: Agent, key: str, *, as_json: bool) -> str:
    def run() -> str:
        row = agent.records.find_capture(key)
        if as_json:
            return json_text(_entry(row))
        directory = pathlib.Path(row["directory"])
        lines = [f"capture {_label(row)}  {row['at']}  {row['serial']}  id={row['id']}", f"directory {directory}"]
        if row.get("cleaned_at"):
            lines.append(f"cleaned {row['cleaned_at']}: its files were deleted")
            return "\n".join(lines)
        for part in row["parts"]:
            path = directory / part
            lines.append(f"{path}  {_bytes(path.stat().st_size) if path.exists() else 'missing'}")
        lines += [f"{part} failed: {error}" for part, error in row["failures"].items()]
        return "\n".join(lines)
    return agent._run(run)


def _clean(agent: Agent, key: str | None, older_than: str | None, everything: bool) -> str:
    def run() -> str:
        if key is not None:
            rows = [agent.records.find_capture(key)]
        else:
            cutoff = None
            if older_than is not None:
                try:
                    cutoff = _iso(time.time() - parse_duration(older_than))
                except UsageError as error:
                    _usage(str(error))
            rows = [row for row in agent.records.captures() if not row.get("cleaned_at") and (cutoff is None or row["at"] < cutoff)]
        lines = []
        for row in rows:
            if row.get("cleaned_at"):
                lines.append(f"capture {_label(row)} is already cleaned")
                continue
            lines.append(_delete(agent, row))
        return "\n".join(lines) or "no captures to clean"
    return agent._run(run)


def _delete(agent: Agent, row: dict[str, Any]) -> str:
    """Deletes the files the agent saved for one capture, then its folder when that is empty. A
    folder holding anything else is left alone: those files are not the agent's to delete."""
    directory = pathlib.Path(row["directory"])
    indexed = set(row["parts"])
    if directory.is_dir():
        others = sorted(p.name for p in directory.iterdir() if p.name not in indexed)
        if others:
            return (f"capture {_label(row)} not cleaned: {directory} also holds {', '.join(others[:5])}"
                f"{' …' if len(others) > 5 else ''}, which the agent did not save; move them, then clean again")
        for part in row["parts"]:
            (directory / part).unlink(missing_ok=True)
        directory.rmdir()
    agent.records.capture_cleaned(row["id"])
    return f"cleaned capture {_label(row)} ({directory})"

