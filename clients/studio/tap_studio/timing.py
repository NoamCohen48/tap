"""Preserve recorded integer milliseconds through SDK float-seconds conversion."""
from __future__ import annotations

import math


def seconds_from_ms(milliseconds: int) -> float:
    """Avoid a lost millisecond when SDKs encode seconds with ``int(seconds * 1000)``.

    Only nudge a rounded-down representation by one float ULP. This does not introduce a
    retry, extra timeout slack or a different phase budget (e.g. 1001 ms stays 1001 ms).
    """
    seconds = milliseconds / 1000
    return math.nextafter(seconds, math.inf) if int(seconds * 1000) < milliseconds else seconds
