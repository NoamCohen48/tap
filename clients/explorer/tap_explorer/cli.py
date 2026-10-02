"""Local graph management only: this CLI does not connect to a daemon or an AI provider."""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

from .store import GraphStore


def _object_file(path: str) -> dict:
    try:
        with Path(path).open(encoding="utf-8") as source:
            value = json.load(source)
    except (OSError, ValueError) as error:
        raise ValueError(f"cannot read JSON file {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("JSON file must contain an object")
    return value


def main(argv: list[str] | None = None) -> int:
    """Manage an offline graph. Return 0 on success; exit 1 on data/storage errors."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, help="SQLite file for one exploration run")
    sub = parser.add_subparsers(dest="verb", required=True)
    init = sub.add_parser("init", help="initialize an empty run")
    init.add_argument("--context", required=True, help="JSON context file; do not include secrets")
    init.add_argument("--max-actions", type=int, default=100)
    init.add_argument("--max-depth", type=int, default=10)
    load = sub.add_parser("import", help="import graph metadata into an empty database")
    load.add_argument("file")
    sub.add_parser("export", help="write graph JSON to stdout (not automatically redacted)")
    sub.add_parser("status", help="report counts and the next scheduler decision")
    recover = sub.add_parser("recover", help="mark orphaned intents uncertain, without replay")
    recover.add_argument("--executor-stopped", action="store_true", help="confirm prior executor is stopped")
    args = parser.parse_args(argv)
    try:
        if args.verb not in ("init", "import") and not Path(args.db).is_file():
            raise ValueError("database does not exist")
        if args.verb == "recover" and not args.executor_stopped:
            raise ValueError("recovery requires --executor-stopped")
        # Parse input before creating a database so malformed input leaves no new file.
        incoming = _object_file(args.context) if args.verb == "init" else _object_file(args.file) if args.verb == "import" else None
        with GraphStore(args.db) as store:
            if args.verb == "init":
                if incoming is None:
                    raise ValueError("missing context")
                store.initialize(incoming, max_actions=args.max_actions, max_depth=args.max_depth)
            elif args.verb == "import":
                if incoming is None:
                    raise ValueError("missing graph")
                store.import_document(incoming)
            elif args.verb == "export":
                print(json.dumps(store.document(), indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False))
            elif args.verb == "status":
                doc = store.document()
                counts = {status: sum(a["status"] == status for a in doc["actions"].values())
                          for status in ("pending", "blocked", "attempted", "uncertain")}
                print(json.dumps({"observations": len(doc["observations"]), "states": len(doc["states"]),
                    "attempts": len(doc["attempts"]), "actions": counts, "next": store.next_action(),
                    "coverage": "configured candidates only; not whole-app coverage"}, sort_keys=True))
            elif args.verb == "recover":
                print(json.dumps({"interrupted": store.recover_interrupted(), "next": store.next_action()}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        parser.exit(1, f"tap-explorer: {error}\n")
