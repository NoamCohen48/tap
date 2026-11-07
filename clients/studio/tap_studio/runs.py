"""What each replay did: its identity and every step's result, bounded and with secrets scrubbed.

Held in memory by the page's server and written only where a person asks (a download, or
``tap-studio replay --results``). A result covers the steps that finished. It is not an execution
journal: if the process dies mid-step, nothing here says whether that step's input was sent. It holds
no screenshots or logs; those are captured only when someone asks for them.
"""

from __future__ import annotations

import json

import secrets as random
from collections import deque
from collections.abc import Iterable, Mapping

from google.protobuf import json_format, text_encoding
from google.protobuf.timestamp_pb2 import Timestamp

from ._gen import studio_pb2 as studio

FORMAT = "tap-studio-run/1"
MAX_RUNS = 20
MAX_STEPS = 1000
MAX_TEXT = 4096
REDACTED = "***"


def scrub(text: str, secret_values: Iterable[str]) -> str:
    """The text with every known secret value replaced, longest first (one may contain another),
    and cut to a length a result can hold."""
    for value in sorted(_forms(secret_values), key=len, reverse=True):
        text = text.replace(value, REDACTED)
    return text if len(text) <= MAX_TEXT else f"{text[:MAX_TEXT]}… (cut)"


def _forms(secret_values: Iterable[str]) -> set[str]:
    """Each value as it is, and as a failure message may quote it: Python's repr, JSON and the
    protobuf text format escape a quote, a backslash or a newline differently."""
    forms: set[str] = set()
    for value in secret_values:
        if not value:
            continue
        forms.add(value)
        forms.add(repr(value)[1:-1])
        forms.add(repr(value + "'")[1:-3])  # the other quote style: repr changes it when both appear
        forms.add(json.dumps(value)[1:-1])
        forms.add(json.dumps(value, ensure_ascii=False)[1:-1])
        raw = value.encode()
        forms.add(text_encoding.CEscape(raw, as_utf8=True))  # protobuf text, as printed with as_utf8
        forms.add(text_encoding.CEscape(raw, as_utf8=False))  # and as printed by default: octal bytes
    forms.discard("")
    return forms


def scrub_message(message, secret_values: Iterable[str]) -> None:
    """Scrubs every string in a message, in place."""
    values = list(secret_values)
    pending = [message]
    while pending:
        current = pending.pop()
        for field, value in current.ListFields():
            if field.message_type is not None:
                pending.extend(value if field.is_repeated else [value])
            elif field.type == field.TYPE_STRING and not field.is_repeated:
                setattr(current, field.name, scrub(value, values))


def _now() -> Timestamp:
    stamp = Timestamp()
    stamp.GetCurrentTime()
    return stamp


class Run:
    """One replay as it happens."""

    def __init__(self, serial: str, planned: int, parameters: Mapping[str, str]) -> None:
        self.result = studio.RunResult(
            format=FORMAT,
            id=random.token_hex(8),
            started_at=_now(),
            status=studio.RUN_STATUS_RUNNING,
            serial=serial,
            planned=planned,
            parameters=dict(parameters),
        )

    def add(self, step_id: str, outcome: studio.Outcome, message: str = "") -> None:
        result = self.result
        result.ran += 1
        failed = outcome.HasField("error") or outcome.HasField("failure") or bool(outcome.mismatch)
        if failed:
            result.failed_step_id = step_id
        if len(result.steps) >= MAX_STEPS:
            result.truncated = True
            return
        result.steps.add(step_id=step_id, outcome=outcome, message=message)

    def finish(self) -> None:
        """Passed needs every planned step to have run and passed; anything less is not a pass."""
        result = self.result
        if result.failed_step_id:
            result.status = studio.RUN_STATUS_FAILED
        elif result.ran < result.planned:
            result.status = studio.RUN_STATUS_STOPPED
        else:
            result.status = studio.RUN_STATUS_PASSED
        result.finished_at.CopyFrom(_now())


class RunLog:
    """The last few runs of this process."""

    def __init__(self) -> None:
        self._runs: deque[Run] = deque(maxlen=MAX_RUNS)

    def begin(self, serial: str, planned: int, parameters: Mapping[str, str]) -> Run:
        run = Run(serial, planned, parameters)
        self._runs.appendleft(run)
        return run

    def summaries(self) -> list[studio.RunResult]:
        """Newest first, without the steps."""
        summaries = []
        for run in self._runs:
            summary = studio.RunResult()
            summary.CopyFrom(run.result)
            summary.ClearField("steps")
            summaries.append(summary)
        return summaries

    def get(self, run_id: str) -> studio.RunResult | None:
        return next((run.result for run in self._runs if run.result.id == run_id), None)


def dumps(result: studio.RunResult) -> str:
    return json_format.MessageToJson(result, preserving_proto_field_name=True, indent=2)
