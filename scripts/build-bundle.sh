#!/usr/bin/env bash
# Assemble one unified Tap release from this run's staged artifacts; no registry downloads.
set -euo pipefail
exec python3 "$(dirname "$0")/build_bundle.py" "$@"
