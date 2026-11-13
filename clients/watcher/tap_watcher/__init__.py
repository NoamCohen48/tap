"""Tap Watcher: read-only watching with explicit on-phone touch-indicator controls."""

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _dist_version

try:
    __version__ = _dist_version("tap-watcher")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
