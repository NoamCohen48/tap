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
    view = sub.add_parser("view", help="read-only graph/screenshot workbench; acquires no device")
    view.add_argument("--run", required=True, type=Path)
    view.add_argument("--port", type=int, default=0)
    explore = sub.add_parser("explore", help="fresh unseeded discovery workbench; explicit operator review, no auto-crawl")
    explore.add_argument("--serial", required=True)
    explore.add_argument("--package", required=True)
    explore.add_argument("--apk", required=True, type=Path, help="trusted APK for a package not already installed")
    explore.add_argument("--out", required=True, type=Path)
    explore.add_argument("--max-actions", type=int, default=50)
    explore.add_argument("--port", type=int, default=0)
    benchmark = sub.add_parser("benchmark", help="score labeled screen/variant evidence offline; no device or identity changes")
    from .benchmark import POLICIES

    benchmark.add_argument("--corpus", required=True, help="tap-discovery-corpus/2 JSON file with owned snapshots")
    benchmark.add_argument("--policy", choices=("all", *POLICIES), default="all")
    benchmark.add_argument("--require-no-screen-merges", action="store_true",
                           help="return 1 if any selected policy merges labeled screen families")
    robots = sub.add_parser("robots", help="write unverified robot (page-object) drafts from a run; no device")
    robots.add_argument("--run", required=True, type=Path, help="run directory with graph.db and observations/")
    robots.add_argument("--out", required=True, type=Path, help="directory for robots.json, robots.py, REVIEW.md")
    robots.add_argument("--review", type=Path, help="tap-robots-review/1 decisions applied on regeneration")
    robots.add_argument("--verification", type=Path,
                        help="tap-robots-verification/1 replay results (from robots-verify) to mark methods")
    robots.add_argument("--ask", action="store_true",
                        help="ask the review questions on the terminal and save the answers to --review first")
    replay = sub.add_parser("robots-verify",
                            help="replay each draft method from a restored emulator snapshot (requires live extra)")
    replay.add_argument("--run", required=True, type=Path)
    replay.add_argument("--out", required=True, type=Path, help="the robots output directory to verify")
    replay.add_argument("--serial", required=True, help="an emulator serial; physical devices are refused")
    replay.add_argument("--snapshot", required=True, help="emulator snapshot to restore before every method")
    replay.add_argument("--package", required=True)
    replay.add_argument("--adb", default="adb")
    replay.add_argument("--only", nargs="*", help="Robot.method keys to replay (default: all)")
    scored = sub.add_parser("robots-score",
                            help="score a run's robot drafts against a hand-written answer key; no device")
    scored.add_argument("--run", required=True, type=Path)
    scored.add_argument("--key", required=True, type=Path, help="tap-robots-key/1 answer key")
    scored.add_argument("--review", type=Path, help="also score these tap-robots-review/1 decisions")
    scored.add_argument("--verification", type=Path, help="tap-robots-verification/1 replay results")
    scored.add_argument("--json", action="store_true", help="print the full tap-robots-score/1 report")
    labels = sub.add_parser("label-review", help="review a corpus source's provisional labels against screenshots; no device")
    labels.add_argument("--corpus", required=True, type=Path, help="tap-discovery-corpus/2 JSON file")
    labels.add_argument("--decisions", required=True, type=Path, help="tap-label-review/1 file (created or updated)")
    labels.add_argument("--source", help="corpus source to review (serve mode)")
    labels.add_argument("--evidence", type=Path, help="that source's run observations/ directory (serve mode)")
    labels.add_argument("--port", type=int, default=0)
    labels.add_argument("--apply", type=Path, metavar="OUT",
                        help="write a new corpus with the decided cases human-reviewed, instead of serving")
    args = parser.parse_args(argv)
    if args.verb == "robots-verify":
        if args.db is not None:
            parser.error("robots-verify reads --run only; do not pass --db")
        import contextlib

        from tap_e2e import TapClient
        from tap_e2e.client import resolve_endpoint

        from .verify import emulator_snapshot_reset, verify

        stack = contextlib.ExitStack()

        def open_app():
            connection = stack.enter_context(TapClient(resolve_endpoint()).connect("tap-explorer-robots-verify"))
            app = stack.enter_context(connection.attach_device(args.serial)).app(args.package)
            app.launch()
            return app

        try:
            result = verify(args.run, args.out, open_app=open_app, close=stack.close,
                            reset=emulator_snapshot_reset(args.serial, args.snapshot, args.adb),
                            only=set(args.only) if args.only else None)
        except (OSError, ValueError, KeyError, RuntimeError) as error:
            parser.exit(1, f"tap-explorer robots-verify: {error}\n")
        counts: dict[str, int] = {}
        for entry in result["methods"].values():
            counts[entry["status"]] = counts.get(entry["status"], 0) + 1
        print(json.dumps({"out": str(args.out / "verification.json"), **counts}, sort_keys=True))
        return 0 if set(counts) <= {"verified"} else 1
    if args.verb == "label-review":
        if args.db is not None:
            parser.error("label-review reads --corpus only; do not pass --db")
        from . import labels as label_review

        try:
            if args.apply is not None:
                if args.apply.resolve() == args.corpus.resolve():
                    parser.error("--apply writes a new corpus; it never rewrites --corpus")
                result = label_review.apply(_object_file(str(args.corpus)), _object_file(str(args.decisions)))
                args.apply.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
                reviewed = sum(case["labels"]["provenance"] == "human-reviewed" for case in result["cases"])
                print(json.dumps({"out": str(args.apply), "human_reviewed": reviewed}, sort_keys=True))
                return 0
            if args.source is None or args.evidence is None:
                parser.error("serving needs --source and --evidence")
            label_review.serve(label_review.LabelReview(args.corpus, args.source, args.evidence, args.decisions),
                               port=args.port)
            return 0
        except (OSError, ValueError, KeyError) as error:
            parser.exit(1, f"tap-explorer label-review: {error}\n")
        except KeyboardInterrupt:
            return 0
    if args.verb == "robots-score":
        if args.db is not None:
            parser.error("robots-score reads --run only; do not pass --db")
        from .score import evaluate, load_key, summary

        try:
            review, verification = ((json.loads(path.read_text(encoding="utf-8")) if path else None)
                                    for path in (args.review, args.verification))
            report = evaluate(args.run, load_key(args.key), review, verification)
        except (OSError, ValueError, KeyError, sqlite3.Error) as error:
            parser.exit(1, f"tap-explorer robots-score: {error}\n")
        print(json.dumps(report, indent=2, sort_keys=True) if args.json else summary(report))
        return 0
    if args.verb == "robots":
        if args.db is not None:
            parser.error("robots reads --run only; do not pass --db")
        from .robots import ask, build, write

        if args.ask and args.review is None:
            parser.error("--ask saves its answers to --review; pass a review path")
        try:
            if args.ask:
                review = json.loads(args.review.read_text(encoding="utf-8")) if args.review.is_file() else None
                verification = (json.loads(args.verification.read_text(encoding="utf-8"))
                                if args.verification else None)
                answers = ask(build(args.run, review, verification), review)
                args.review.write_text(json.dumps(answers, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print(json.dumps(write(args.run, args.out, args.review, args.verification), indent=2, sort_keys=True))
            return 0
        except (OSError, ValueError, KeyError, sqlite3.Error) as error:
            parser.exit(1, f"tap-explorer robots: {error}\n")
    if args.verb == "benchmark":
        if args.db is not None:
            parser.error("benchmark reads --corpus only; do not pass --db")
        from .benchmark import compare, score

        try:
            if Path(args.corpus).stat().st_size > 10 * 1024 * 1024:
                raise ValueError("corpus exceeds 10 MiB limit")
            corpus = _object_file(args.corpus)
            report = compare(corpus) if args.policy == "all" else score(corpus, args.policy)
            print(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False))
            reports = report.values() if args.policy == "all" else [report]
            if args.require_no_screen_merges and any(not r["screen_merge_gate"]["passed"] for r in reports):
                return 1
            return 0
        except (OSError, ValueError, KeyError, TypeError) as error:
            parser.exit(1, f"tap-explorer benchmark: {error}\n")
    if args.verb in ("view", "explore"):
        if args.db is not None:
            parser.error("workbench uses --run/--out, not --db")
        if not 0 <= args.port <= 65535:
            parser.error("port must be 0..65535")
        if not (Path(__file__).parent / "static" / "index.html").is_file():
            parser.exit(1, "tap-explorer: build clients/explorer/web first (bun install && bun run build)\n")
        from .web import Workbench, serve

        workbench = None
        try:
            workbench = (Workbench(args.run) if args.verb == "view" else
                         Workbench(args.out, serial=args.serial, package=args.package, apk=args.apk,
                                   max_actions=args.max_actions))
            serve(workbench, port=args.port)
            return 0
        except KeyboardInterrupt:
            return 0
        except Exception as error:
            parser.exit(1, f"tap-explorer workbench: {type(error).__name__}: {error}\n")
        finally:
            if workbench is not None:
                workbench.close()
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
