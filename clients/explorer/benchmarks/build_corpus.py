"""Build the reviewed evidence corpus from local runs; no APK, SDK, image upload or input.

Wizard labels come from its source-defined finite oracle, not stored signature IDs. Loop labels
are provisional analyst annotations, NOT human approvals. Name-error annotations reference
inspected screenshots 5/7/11/15. List data is collapsed only in presentation labels; execution
bindings are not changed. Finite fixture values justify hint handling in this script only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from tap_explorer.benchmark import CORPUS_FORMAT, validate_corpus

WIZARD = "io.github.noamcohen48.tap.explorer.machine"
LOOP = "org.isoron.uhabits"
# Observation-specific annotations must never attach to a changed/reordered run silently.
PINS = {
    "wizard": ("78c81243a0c1b389bb92ba783c432d85a8965ebe9b7d8e70983957185b2c6999",
               "01b3484a9aed3d5431a0a3096b6d5c18ec71e40890bffa6bf2c43e4b528b2283"),
    "loop": ("f9bb8895a4a5810af7858ed9b8121e43e71678fd8c1912fa9b725d587a7b06a9",
             "3903d3be3db0f1622ad61cf5bb267515c95a4069e8dd46f73249721b1bb8173e"),
}
SIGNATURE_FLAGS = {"ENABLED", "CHECKED", "CHECKABLE", "CLICKABLE", "LONG_CLICKABLE", "SCROLLABLE", "SELECTED"}
TITLES = {"Local requests": "home", "About requests": "about", "Your name": "identity",
          "Choose a plan": "options", "Plan details": "details", "Review request": "review",
          "Request saved": "receipt"}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def fields(features):
    return {n["resource"].split(":id/")[-1]: n for n in features["nodes"] if n["resource"]}


def finite_value(node, allowed):
    text = node["text"]
    if text in allowed:
        return text
    if not text or text == node["hint"]:
        return ""
    raise ValueError("unexpected value outside the recorded fictional scenarios")


def wizard_label(features):
    f = fields(features)
    screen = TITLES[f["title"]["text"]]
    variant = {}
    if screen == "identity":
        plan = re.fullmatch(r"Plan: (Pro|Basic); terms: (true|false)", f["summary"]["text"])
        if plan is None:
            raise ValueError("unrecognized wizard plan summary")
        variant = {"name": finite_value(f["name"], {"Ada"}), "plan": plan[1], "terms": plan[2],
                   "error": f["validation"]["text"] or ""}
    elif screen in ("options", "details"):
        variant = {"name": f["summary"]["text"], "pro": "CHECKED" in f["tier"]["flags"],
                   "terms": "CHECKED" in f["terms"]["flags"], "error": f["validation"]["text"] or ""}
    elif screen in ("review", "receipt"):
        variant = {"summary": f["summary"]["text"]}
    return {"screen": screen, "variant": canonical(variant), "provenance": "fixture-oracle",
            "note": "Source-defined finite wizard page/values/validation; labels do not use signature IDs."}


def loop_label(features, observation):
    f = fields(features)
    texts = {n["text"] for n in features["nodes"] if n["text"]}
    try:
        number = int(observation.removeprefix("obs-"))
    except ValueError as error:
        raise ValueError("invalid observation ID") from error
    if "buttonMeasurable" in f:
        screen, variant = "habit-type", "base"
    elif "xTimesPerWeekRadioButton" in f:
        screen = "binary-frequency"
        variant = canonical(sorted(key for key, node in f.items() if "CHECKED" in node["flags"]))
    elif "nameInput" in f:
        screen = "numeric-form" if "targetInput" in f else "binary-form"
        values = {"name": finite_value(f["nameInput"], {"Tap benchmark binary", "Tap benchmark quantity", "Tap benchmark limit"}),
                  "reminder": f["reminderTimePicker"]["text"]}
        if screen == "numeric-form":
            values.update(unit=finite_value(f["unitInput"], {"units"}), target=finite_value(f["targetInput"], {"0", "5"}),
                          comparison=f["targetTypePicker"]["text"], frequency=f["numericalFrequencyPicker"]["text"])
        else:
            values.update(frequency=f["boolean_frequency_picker"]["text"], name_error_icon=7 <= number <= 16)
        variant = canonical(values)
    elif {"At least", "At most"} <= texts:
        screen, variant = "target-comparison", "base"
    elif {"Every day", "Every week", "Every month"} <= texts:
        screen, variant = "numeric-period", "base"
    elif {"Hide archived", "Hide completed", "Sort"} <= texts:
        screen = "filter-menu"
        variant = canonical(sorted(key for key, node in f.items() if "CHECKED" in node["flags"]))
    elif "Habits" in texts:
        screen = "habit-list"
        variant = "empty" if "You have no active habits" in texts else "nonempty"
    else:
        raise ValueError(f"unreviewed Loop foreground screen: {observation}")
    return {"screen": screen, "variant": variant, "provenance": "analyst-provisional",
            "note": "Provisional foreground-screen/condition annotation; list names are presentation data, not execution equivalence. "
                    "Binary error icon reviewed in screenshots 5/7/11/15; literal form bindings retained."}


def case(source, run_dir, package, observation, features, labels):
    """One corpus case: retained features, labels and the owned nodes of the raw snapshot."""
    # Resolve under the run directory, not the absolute paths recorded on the author's machine.
    try:
        raw_snapshot = (run_dir / "observations" / f"{observation}.json").read_bytes()
        snapshot = json.loads(raw_snapshot)
    except (OSError, ValueError) as error:
        raise ValueError(f"cannot read pinned raw snapshot {source}/{observation}") from error
    own = [node for node in snapshot["nodes"] if node["package"] == package]
    volatile = set(features["policy"]["volatile_resources"])
    reconstructed = []
    for node in own:
        hidden = node["password"] or node["resource"] in volatile
        item = {key: node[key] for key in ("package", "depth", "resource", "class", "password")}
        item.update(flags=sorted(set(node["flags"]) & SIGNATURE_FLAGS))
        item.update({key: None if hidden else node[key] for key in ("text", "description", "hint")})
        reconstructed.append(item)
    if reconstructed != features["nodes"] or snapshot["rotation"] != features["rotation"]:
        raise ValueError("raw snapshot disagrees with the pinned retained features")
    # Keep complete owned nodes (including bounds/native selector proposals), but do not
    # publish phone status-bar/notification content or unrelated package selectors.
    return {"id": f"{source}/{observation}", "source": source, "observation": observation,
            "features": features, "labels": labels, "snapshot": {**snapshot, "nodes": own},
            "snapshot_sha256": hashlib.sha256(raw_snapshot).hexdigest(),
            "removed_foreign_nodes": len(snapshot["nodes"]) - len(own)}


def build(evidence_root):
    corpus = {"format": CORPUS_FORMAT, "sources": {}, "cases": []}
    for source, run, package in (("wizard", "native-machine-run-5", WIZARD), ("loop", "real-app-loop/run-2", LOOP)):
        try:
            raw = (evidence_root / run / "graph.json").read_bytes()
            graph = json.loads(raw)
        except (OSError, ValueError) as error:
            raise ValueError(f"cannot read source graph {run}") from error
        if (hashlib.sha256(raw).hexdigest(), graph["context"]["apk_sha256"]) != PINS[source]:
            raise ValueError("source graph/APK differs from the pinned annotation evidence")
        if graph["context"]["package"] != package or any(a["status"] != "succeeded" for a in graph["attempts"].values()):
            raise ValueError("unexpected package or incomplete source run")
        corpus["sources"][source] = {"package": package, "description": f"Retained {run}; API 29, fictional local data only.",
                                    "graph_sha256": hashlib.sha256(raw).hexdigest(), "apk_sha256": graph["context"]["apk_sha256"],
                                    "typed_values": sorted({graph["actions"][a["action"]]["scenario"]["value"]
                                                            for a in graph["attempts"].values()
                                                            if graph["actions"][a["action"]]["verb"] == "fill"}),
                                    "label_scope": "Foreground screen families and literal observable conditions, not robot reuse, execution identity or backend equivalence."}
        if source == "wizard":
            selected = sorted({obs for state in graph["states"].values()
                               for obs in (state["observations"][0], state["observations"][-1])})
        else:
            selected = sorted(graph["observations"])
        for observation in selected:
            evidence = graph["observations"][observation]["evidence"]
            if evidence["moving"]:
                raise ValueError("unstable evidence cannot receive these labels")
            labels = wizard_label(evidence["features"]) if source == "wizard" else loop_label(evidence["features"], observation)
            corpus["cases"].append(case(source, evidence_root / run, package, observation, evidence["features"], labels))
    validate_corpus(corpus)
    wizard = [c for c in corpus["cases"] if c["source"] == "wizard"]
    if (len({(c["labels"]["screen"], c["labels"]["variant"]) for c in wizard}) != 30
            or len({c["labels"]["screen"] for c in wizard}) != 7):
        raise ValueError("source-defined wizard coverage is incomplete")
    return corpus


def held_out(run_dirs):
    """A held-out wizard corpus from fresh runs, labeled only by the fixed source oracle.

    The oracle (``wizard_label``) predates the runs, so the labels are frozen before any policy
    sees this evidence. Each run is a source; it must use the pinned fixture APK and share no
    snapshot with the in-sample corpus. A run that stopped early still contributes its states;
    moving captures are counted and left out.
    """
    from tap_explorer.store import GraphStore

    corpus, moving = {"format": CORPUS_FORMAT, "sources": {}, "cases": []}, 0
    for run_dir in run_dirs:
        if (run_dir / "graph.db").is_file():
            with GraphStore(run_dir / "graph.db") as store:
                graph = store.document()
        else:
            graph = json.loads((run_dir / "graph.json").read_bytes())
        if graph["context"]["package"] != WIZARD or graph["context"]["apk_sha256"] != PINS["wizard"][1]:
            raise ValueError(f"held-out run {run_dir.name} must use the pinned wizard fixture APK")
        source = run_dir.name
        corpus["sources"][source] = {
            "package": WIZARD, "description": f"Fresh run {run_dir.name}; held out from policy design; fictional local data only.",
            "graph_sha256": hashlib.sha256(canonical(graph).encode()).hexdigest(), "apk_sha256": graph["context"]["apk_sha256"],
            "typed_values": sorted({graph["actions"][a["action"]]["scenario"]["value"] for a in graph["attempts"].values()
                                    if a["status"] == "succeeded" and graph["actions"][a["action"]]["verb"] == "fill"}),
            "label_scope": "Foreground screen families and literal observable conditions from the fixture's source-defined oracle."}
        # First and last capture of each state, as in the in-sample wizard source; a capture
        # with no state is moving evidence and receives no label.
        selected = sorted({obs for state in graph["states"].values()
                           for obs in (state["observations"][0], state["observations"][-1])})
        moving += sum(graph["observations"][obs]["evidence"]["moving"] for obs in graph["observations"])
        for observation in selected:
            evidence = graph["observations"][observation]["evidence"]
            corpus["cases"].append(case(source, run_dir, WIZARD, observation, evidence["features"],
                                        wizard_label(evidence["features"])))
    validate_corpus(corpus)
    in_sample = json.loads((Path(__file__).parent / "corpus-v2.json").read_bytes())
    if {c["snapshot_sha256"] for c in in_sample["cases"]} & {c["snapshot_sha256"] for c in corpus["cases"]}:
        raise ValueError("a held-out run shares snapshots with the in-sample corpus")
    return corpus, moving


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path)
    parser.add_argument("--held-out", type=Path, nargs="+", metavar="RUN",
                        help="build a held-out corpus from fresh wizard run directories instead")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if (args.evidence_root is None) == (args.held_out is None):
        parser.error("pass exactly one of --evidence-root and --held-out")
    try:
        if args.held_out is not None:
            corpus, moving = held_out(args.held_out)
            print(f"Held out {len(corpus['cases'])} oracle-labeled cases; {moving} moving captures left out.")
        else:
            corpus = build(args.evidence_root)
        with args.out.open("x", encoding="utf-8") as output:
            # Compact committed evidence avoids an enormous pretty-printed diff. Human labels
            # need a review view, not editing thousands of repeated hierarchy lines by hand.
            json.dump(corpus, output, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            output.write("\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"corpus build: {error}\n")
    print(f"Saved {len(corpus['cases'])} evidence-only cases; no screenshots, device calls or approvals.")
