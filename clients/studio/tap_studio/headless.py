"""``tap-studio replay``: a recording on a device, with no browser.

The same steps and validation as the page (``steps.run``), through the public SDK. It never starts a
Tap server, retries nothing, and asks for every value before it touches the device. Exit codes: 0 all
passed, 1 a step failed, 2 the recording or the options are wrong (nothing ran), 3 no server or the
device could not be attached (nothing ran), 130 interrupted.
"""

from __future__ import annotations

import os
import pathlib
import sys
import time
from collections.abc import Callable, Mapping

from tap_e2e import TapClient, TapError

from . import runs, steps, tools
from ._gen import studio_pb2 as studio
from .recording import RecordingError, load, parameter_name, secret_name

PASSED, FAILED, INVALID, UNAVAILABLE, INTERRUPTED = 0, 1, 2, 3, 130
_EVIDENCE = {studio.DIAGNOSTIC_SCREENSHOT, studio.DIAGNOSTIC_HIERARCHY, studio.DIAGNOSTIC_DEVICE_LOGS}


def _say(*text: object) -> None:
    print(*text, flush=True)


def _complain(text: str) -> None:
    print(f"tap-studio: {text}", file=sys.stderr, flush=True)


def _writable_dir(path: pathlib.Path) -> bool:
    return path.is_dir() and os.access(path, os.W_OK | os.X_OK)


def _write_private(path: pathlib.Path, data: bytes) -> None:
    """Results and captures can show private data: only their owner reads them. Refuses to follow a
    link or replace a file, so it cannot be pointed at something else."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as file:
        file.write(data)


def replay(
    document: str,
    serial: str,
    *,
    parameters: Mapping[str, str] | None = None,
    secret_env: Mapping[str, str] | None = None,
    from_step: str | None = None,
    results: str | None = None,
    evidence_dir: str | None = None,
    client_factory: Callable[[], TapClient] = TapClient.create,
    environ: Mapping[str, str] = os.environ,
) -> int:
    try:
        recording = load(document)
    except RecordingError as error:
        _complain("not a valid recording:\n  " + "\n  ".join(error.problems))
        return INVALID
    except OSError as error:
        _complain(f"cannot read {document}: {error.strerror}")
        return INVALID

    chosen = list(recording.steps)
    if from_step:
        at = next((index for index, step in enumerate(chosen) if step.id == from_step), None)
        if at is None:
            _complain(f"no step {from_step!r} in the recording")
            return INVALID
        chosen = chosen[at:]
    if not chosen:
        _complain("the recording has no steps")
        return INVALID

    values = {p.name: p.value for p in recording.parameters}
    for name, value in (parameters or {}).items():
        if name not in values:
            _complain(f"the recording declares no parameter {name!r}")
            return INVALID
        values[name] = value
    known = secret_env or {}
    unused = set(known) - set(recording.secrets)
    if unused:
        _complain(f"the recording uses no secret {', '.join(sorted(unused))}")
        return INVALID
    needed = {name for step in chosen if (name := secret_name(step)) is not None}
    secrets: dict[str, str] = {}
    problems = []
    for name in sorted(needed):
        variable = known.get(name)
        if variable is None:
            problems.append(f"the secret {name!r} needs --secret {name}=ENV_VAR")
        elif variable not in environ:
            problems.append(f"the secret {name!r}: {variable} is not set")
        else:
            secrets[name] = environ[variable]
    if problems:
        _complain("; ".join(problems))
        return INVALID
    results_path = pathlib.Path(results) if results else None
    if results_path is not None:
        if os.path.lexists(results_path):
            _complain(f"{results_path} already exists: results never replace a file")
            return INVALID
        if not _writable_dir(results_path.parent):
            _complain(f"cannot write {results_path}: {results_path.parent} is not a directory you can write to")
            return INVALID
    if evidence_dir:
        evidence = pathlib.Path(evidence_dir)
        exists = os.path.lexists(evidence)
        if (exists and not evidence.is_dir()) or not _writable_dir(evidence if exists else evidence.parent):
            _complain(f"cannot keep evidence in {evidence}: it must be a directory you can write to, or a new one in one")
            return INVALID
    resolved = []
    try:
        for step in chosen:
            resolved.append(steps.resolve(step, values))
    except RecordingError as error:
        _complain("; ".join(error.problems))
        return INVALID
    assert all(parameter_name(step) is None for step in resolved)

    try:
        client = client_factory()
    except TapError as error:
        _complain(f"no Tap server: start one with `tap start` ({error})")
        return UNAVAILABLE
    known_values = list(secrets.values())
    run = runs.Run(serial, len(chosen), values)
    code = PASSED
    device = None
    try:
        with client, client.connect("tap-studio-replay") as connection:
            try:
                device = connection.attach_device(serial)
            except TapError as error:
                _complain(f"cannot attach {serial}: {error}")
                return UNAVAILABLE
            try:
                for original, step in zip(chosen, resolved):
                    name = secret_name(step)
                    started = time.monotonic()
                    failure: TapError | steps.CheckFailed | None = None
                    try:
                        steps.run(device, step, secrets[name] if name is not None else None)
                    except (TapError, steps.CheckFailed) as error:
                        failure = error
                    outcome = steps.outcome(round((time.monotonic() - started) * 1000), failure, serial)
                    runs.scrub_message(outcome, known_values)
                    message = runs.scrub(str(failure) or type(failure).__name__, known_values) if failure is not None else ""
                    run.add(original.id, outcome, message)
                    if failure is not None:
                        _say(f"FAIL {original.id} {outcome.duration_ms} ms: {message}")
                        code = FAILED
                        break
                    _say(f"ok   {original.id} {outcome.duration_ms} ms")
                if code == FAILED and evidence_dir:
                    _capture(device, pathlib.Path(evidence_dir), run.result.id)
            except KeyboardInterrupt:
                code = INTERRUPTED
                _complain("interrupted: the step that was running may have sent its input, and it is not retried")
            finally:
                try:
                    device.detach()
                except TapError as error:
                    # The steps' result stands; the release is reported, not counted as a step.
                    _complain(f"could not release {serial}: {error}")
    except KeyboardInterrupt:
        code = INTERRUPTED
    except TapError as error:
        _complain(f"the Tap server went away: {error}")
        code = UNAVAILABLE if code == PASSED and run.result.ran == 0 else FAILED
    run.finish()
    if results_path is not None:
        try:
            _write_private(results_path, (runs.dumps(run.result) + "\n").encode())
        except OSError as error:
            _complain(f"could not write the results to {results_path}: {error.strerror or error}")
            if code == PASSED:
                code = FAILED  # asked for and not written: the run cannot be shown to have passed
    if code == PASSED:
        _say(f"All {run.result.ran} steps passed.")
    return code


def _capture(device, directory: pathlib.Path, run_id: str) -> None:
    """What was asked for with --evidence-dir, after a failure. A capture that fails is reported and
    changes nothing: the step's failure is still the result."""
    try:
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as error:
        _complain(f"could not keep evidence in {directory}: {error.strerror or error}")
        return
    for kind in sorted(_EVIDENCE):
        try:
            captured = tools.diagnostic(device, studio.DiagnosticRequest(kind=kind))
            _write_private(directory / f"{run_id}-{os.path.basename(captured.filename)}", captured.data)
        except (TapError, OSError, ValueError) as error:
            _complain(f"could not capture {studio.DiagnosticKind.Name(kind)}: {error}")
