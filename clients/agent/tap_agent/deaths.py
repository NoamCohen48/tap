"""App deaths in agent output: the per-session cursor of what was already reported, the block a
step prints for each new death, and the trace files that keep full stack traces out of context."""

from __future__ import annotations

import json
import os
import pathlib
import re
from typing import Any

from tap_e2e import AppDeath, AppDeathKind  # pyright: ignore[reportMissingImports]

from .output import save_private

# Stack frames shown inline; the rest is in the trace file.
FRAMES = 5
_FRAME = re.compile(r"^\s*(at |#\d+ pc )")


class Cursors:
    """The latest death sequence already reported, per attached device, for one session. Kept
    under the agent directory so CLI calls (one process each) and the MCP server share it."""

    def __init__(self, out_dir: pathlib.Path, session: str):
        self.path = out_dir / "deaths" / (re.sub(r"[^A-Za-z0-9._-]", "_", session) + ".json")

    def load(self) -> dict[str, int]:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        return {str(k): int(v) for k, v in data.items()} if isinstance(data, dict) else {}

    def save(self, cursors: dict[str, int]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.path.with_name(f".{self.path.name}.{os.getpid()}.partial")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(cursors, stream)
        os.replace(temporary, self.path)


def frames(stack_trace: str) -> list[str]:
    """The stack trace's frame lines (Java `at …`, native `#00 pc …`), trimmed."""
    return [line.strip() for line in stack_trace.splitlines() if _FRAME.match(line)]


def trace_file(out_dir: pathlib.Path, serial: str, death: AppDeath) -> pathlib.Path | None:
    """The death's full stack trace as a new owner-only file (the same file on a second call);
    None when there is no trace."""
    if not death.stack_trace:
        return None
    name = re.sub(r"[^A-Za-z0-9._-]", "_", f"{serial}-{death.sequence:04d}-{death.package_name}") + ".txt"
    path = out_dir / "deaths" / name
    if not path.exists():
        try:
            save_private(path, (death.summary + "\n\n" + death.stack_trace + "\n").encode("utf-8"))
        except FileExistsError:
            pass
    return path


def block(death: AppDeath, *, expected: bool, serial: str | None, trace: pathlib.Path | None) -> str:
    """The lines a step prints for one death: ``!`` unexpected, ``✓`` expected."""
    where = f"[{serial}] " if serial else ""
    lines = [f"{'✓' if expected else '!'} {where}{death.summary}" + (" (expected)" if expected else "")]
    shown = frames(death.stack_trace)
    lines += [f"    {frame}" for frame in shown[:FRAMES]]
    if trace is not None:
        more = len(shown) - FRAMES
        lines.append(f"  {'… ' + str(more) + ' more frames; ' if more > 0 else ''}full trace: {trace}")
    if not expected:
        if death.kind is AppDeathKind.ANR:
            lines.append(f"  it may still be stuck: `app stop {death.package_name}`, then launch it again")
        else:
            lines.append(f"  its screen is gone; `app launch {death.package_name}` to continue")
    return "\n".join(lines)


def listing(death: AppDeath, serial: str | None) -> str:
    """One line of the `deaths` listing."""
    where = f"[{serial}] " if serial else ""
    marks = [] if death.watched else ["not watched"]
    if death.reported:
        marks.append("reported")
    note = f"  ({', '.join(marks)})" if marks else ""
    return f"#{death.sequence} {where}{death.summary}{note}"


def as_json(death: AppDeath, serial: str) -> dict[str, Any]:
    return {
        "serial": serial,
        "sequence": death.sequence,
        "kind": death.kind.value,
        "package_name": death.package_name,
        "process_name": death.process_name,
        "pid": death.pid,
        "at": death.at,
        "exception_class": death.exception_class,
        "message": death.message,
        "top_frames": frames(death.stack_trace)[:FRAMES],
        "watched": death.watched,
        "reported": death.reported,
    }


def record_entry(death: AppDeath, serial: str, expected: bool) -> dict[str, Any]:
    """What a recording keeps of a death: identity only, never its message or trace (app output)."""
    return {
        "serial": serial,
        "sequence": death.sequence,
        "kind": death.kind.value,
        "package_name": death.package_name,
        "process_name": death.process_name,
        "exception_class": death.exception_class,
        "expected": expected,
    }
