"""Steps: a caller's own unit of work (an agent's command, a line of a test), named so that
the device log's actions and the event log can be matched to it afterwards."""
from __future__ import annotations

import contextlib
import contextvars
import re
import uuid
from collections.abc import Iterator

_STEP: contextvars.ContextVar[str | None] = contextvars.ContextVar("tap_step", default=None)
_VALID = re.compile(r"[A-Za-z0-9._-]{1,64}")


def current_step() -> str | None:
    """The id of the step this thread (or task) is in, or None outside ``step()``."""
    return _STEP.get()


@contextlib.contextmanager
def step(step_id: str | None = None) -> Iterator[str]:
    """Runs the block as one step and yields the step's id.

    Every call made to the server inside the block, on this thread, carries the id. The server
    copies it to the actions the calls cause in the device log (``LogAction.step``) and to their
    events (``LoggedEvent.step``), so a caller that keeps its own record of steps can find what
    each one did on the device::

        with tap_e2e.step() as checkout:
            cart.tap()
            pay.tap()
        log = device.logs.read(step=checkout)   # from its first action to its last

    ``step_id`` is 1 to 64 of ``A-Z a-z 0-9 . _ -``; without one a new id is made. A step inside
    a step replaces it until the inner block ends."""
    if step_id is None:
        step_id = uuid.uuid4().hex[:16]
    elif not isinstance(step_id, str) or not _VALID.fullmatch(step_id):
        raise ValueError("a step id is 1 to 64 of A-Z a-z 0-9 . _ -")
    token = _STEP.set(step_id)
    try:
        yield step_id
    finally:
        _STEP.reset(token)
