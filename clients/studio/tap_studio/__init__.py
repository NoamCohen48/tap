"""Tap Studio: a browser inspector and action recorder on the Tap daemon (``tap-studio``)."""

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _dist_version

try:
    __version__ = _dist_version("tap-studio")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
