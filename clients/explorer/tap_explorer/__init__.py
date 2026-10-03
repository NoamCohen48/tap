"""Experimental offline app-exploration graphs. No device or AI execution yet."""

from importlib.metadata import version

from .store import FORMAT, GraphStore

__version__ = version("tap-explorer")
__all__ = ["FORMAT", "GraphStore"]
