"""Agent-facing Tap: ``tap-agent <verb>`` and ``tap-agent mcp``, both over :class:`Agent`."""

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _dist_version

from .core import Agent, AgentError

__all__ = ["Agent", "AgentError"]

try:
    __version__ = _dist_version("tap-agent")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
