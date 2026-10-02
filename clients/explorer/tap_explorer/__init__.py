"""Experimental exploration graphs, with an optional bounded sample-device pilot. No AI."""

from importlib.metadata import version

from .store import FORMAT, GraphStore

__version__ = version("tap-explorer")
__all__ = ["FORMAT", "GraphStore"]
