"""Graph management and an explicitly opted-in sample-device pilot. No AI provider."""

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
    """Manage graphs or run the bounded sample pilot. Return 0 on success; exit 1 on failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", help="SQLite file (required for offline graph commands)")
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
    sample = sub.add_parser("sample", help="install/explore only Tap's disposable sample APK (requires live extra)")
    sample.add_argument("--serial", required=True, help="explicit device serial; no implicit device selection")
    sample.add_argument("--apk", required=True, type=Path, help="built samples/explorer-app debug APK")
    sample.add_argument("--out", required=True, type=Path, help="new evidence directory (must not exist)")
    sample.add_argument("--max-actions", type=int, default=100)
    args = parser.parse_args(argv)
    if args.verb == "sample":
        if args.db is not None:
            parser.error("sample writes its own graph under --out; do not pass --db")
        try:
            from .sample import run_sample
        except ImportError as error:
            parser.exit(1, f"tap-explorer: install tap-explorer[live]: {error}\n")
        try:
            report = run_sample(args.serial, args.apk, args.out, max_actions=args.max_actions)
            print(json.dumps(report, indent=2, sort_keys=True))
            return 0
        except Exception as error:
            parser.exit(1, f"tap-explorer sample: {type(error).__name__}: {error}\n")
    if args.db is None:
        parser.error("offline graph commands require --db")
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
