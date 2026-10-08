"""Score robot drafts against a hand-written answer key; no device, daemon or AI provider.

An answer key (``tap-robots-key/1``) says what a good robot for the app contains, in terms the
draft cannot game: screens are recognized from observation content, methods are a control
(resource ID) and the screen it leads to, preconditions are sets of observable facts, and
checks are a resource and the kind of assertion. Generated names never count.

Two readings of the same run are scored:

- ``draft``: the model exactly as ``robots`` writes it, with no human decision;
- ``answered``: the model after a reviewer who knows the key answers every question that
  ``robots --ask`` asks, through the same code path (an upper bound on what review can fix;
  questions the draft offers no right answer for stay wrong).

``effort`` counts those questions. A real reviewer's ``review.json`` can be scored too.

Optional key methods and checks (``"optional": true``) are neither required nor wrong.
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from collections.abc import Mapping
from pathlib import Path

KEY_FORMAT = "tap-robots-key/1"
REPORT_FORMAT = "tap-robots-score/1"
KEY_PROVENANCE = {"fixture-source", "analyst-provisional", "human-reviewed"}
CHECK_KINDS = {"value", "checked", "shown", "list", "error"}
UNKNOWN = "?"


def validate_key(key: Mapping) -> None:
    """Reject a malformed key before it can produce a misleading score."""
    if not isinstance(key, Mapping) or key.get("format") != KEY_FORMAT:
        raise ValueError("unsupported answer key")
    if key.get("provenance") not in KEY_PROVENANCE or not isinstance(key.get("package"), str):
        raise ValueError("answer key needs a package and a known provenance")
    screens = key.get("screens")
    if not isinstance(screens, list) or not screens:
        raise ValueError("answer key needs screens")
    ids = [screen.get("id") for screen in screens]
    if len(set(ids)) != len(ids) or not all(isinstance(value, str) and value != UNKNOWN for value in ids):
        raise ValueError("screen IDs must be unique strings")
    for screen in screens:
        if not isinstance(screen.get("recognize"), list) or not screen["recognize"]:
            raise ValueError(f"screen {screen['id']} needs recognize rules")
        for method in screen.get("methods", []):
            if method.get("verb") not in ("tap", "fill") or not isinstance(method.get("control"), str):
                raise ValueError(f"invalid method on {screen['id']}")
            if method.get("to") not in ids:
                raise ValueError(f"method on {screen['id']} leads to an unknown screen")
            when = method.get("when")
            if when is not None and (when.get("mode") not in ("all", "any") or not when.get("facts")):
                raise ValueError(f"invalid precondition on {screen['id']}")
        for check in screen.get("checks", []):
            if check.get("kind") not in CHECK_KINDS or not isinstance(check.get("resource"), str):
                raise ValueError(f"invalid check on {screen['id']}")


# --- evidence ---------------------------------------------------------------------------------


def _short(resource: str | None) -> str | None:
    return resource.rsplit("/", 1)[-1] if resource else None


def _matches(rule: Mapping, nodes: list[dict]) -> bool:
    if "absent" in rule:
        return all(_short(node.get("resource")) != rule["absent"] for node in nodes)
    return any((rule.get("resource") is None or _short(node.get("resource")) == rule["resource"])
               and (rule.get("text") is None or node.get("text") == rule["text"]) for node in nodes)


def recognizer(key: Mapping):
    """Observation features -> key screen ID (first screen whose rules all match), or ``?``."""
    def recognize(features: Mapping | None) -> str:
        nodes = (features or {}).get("nodes", [])
        return next((screen["id"] for screen in key["screens"]
                     if all(_matches(rule, nodes) for rule in screen["recognize"])), UNKNOWN)
    return recognize


def _resource(selector) -> str | None:
    """The resource name a stored selector targets, if it names exactly one."""
    found: list[str] = []

    def walk(value) -> None:
        if isinstance(value, Mapping):
            resource = value.get("resource")
            if isinstance(resource, Mapping) and isinstance(resource.get("name"), str):
                found.append(_short(resource["name"]) or "")
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(selector)
    return found[0] if len(set(found)) == 1 else None


class _Evidence:
    def __init__(self, doc: Mapping, key: Mapping):
        recognize = recognizer(key)
        self.screen_of_observation = {obs: recognize(value["evidence"].get("features"))
                                      for obs, value in doc["observations"].items()}
        self.screen_of_state = {state: Counter(self.screen_of_observation[obs] for obs in value["observations"])
                                .most_common(1)[0][0] for state, value in doc["states"].items()}
        self.doc = doc

    def attempt(self, attempt_id: str) -> tuple[str, str, str | None, str]:
        """(source screen, verb, control, destination screen) of one attempt."""
        attempt = self.doc["attempts"][attempt_id]
        action = self.doc["actions"][attempt["action"]]
        target = action.get("target") or {}
        control = _resource(target.get("selector")) or target.get("label")
        return (self.screen_of_state.get(action["source"], UNKNOWN), action["verb"], control,
                self.screen_of_state.get(attempt.get("destination") or "", UNKNOWN))


# --- scoring ----------------------------------------------------------------------------------


def _normal(when: Mapping | None) -> tuple[str, frozenset[str]] | None:
    if not when or not when.get("facts"):
        return None
    facts = frozenset(when["facts"])
    return ("all" if len(facts) == 1 else when.get("mode", "all"), facts)


def _offered(method: Mapping) -> list[tuple[str, frozenset[str]]]:
    """Every precondition a reviewer can pick for this method with one ``--ask`` answer."""
    options = [_normal({"mode": method.get("when_mode", "all"), "facts": method["when"]})]
    options += [_normal({"mode": "all", "facts": [label]}) for label in method.get("alternatives", [])]
    if method.get("either"):
        options.append(_normal({"mode": "any", "facts": method["either"]}))
    options += [_normal(condition) for condition in method.get("widen", [])]
    return [option for option in options if option is not None]


def _ratio(part: int, whole: int) -> float | None:
    return round(part / whole, 3) if whole else None


def score(model: Mapping, doc: Mapping, key: Mapping) -> dict:
    """Score one robots model (``tap-robots/1``) built from ``doc`` against ``key``."""
    validate_key(key)
    evidence = _Evidence(doc, key)
    key_screens = {screen["id"]: screen for screen in key["screens"]}

    # Screens: each robot stands for the key screen most of its member observations show.
    robot_screen: dict[str, str] = {}
    robots = []
    for robot in model["screens"]:
        shown = Counter(evidence.screen_of_observation.get(obs, UNKNOWN) for obs in robot["members"])
        majority, count = shown.most_common(1)[0]
        robot_screen[robot["name"]] = majority
        robots.append({"robot": robot["name"], "screen": majority, "members": len(robot["members"]),
                       "purity": _ratio(count, len(robot["members"])), "mixed": sorted(set(shown) - {majority})})
    per_screen = Counter(robot_screen.values())
    observed = {screen for screen in evidence.screen_of_observation.values() if screen != UNKNOWN}
    screens = {
        "key": len(key_screens), "observed": len(observed), "robots": len(robots),
        "split": sorted(screen for screen, count in per_screen.items() if count > 1 and screen != UNKNOWN),
        "merged": sorted(entry["robot"] for entry in robots if entry["mixed"]),
        "unrecognized_observations": sum(screen == UNKNOWN for screen in evidence.screen_of_observation.values()),
        "missing": sorted(set(key_screens) - set(robot_screen.values())),
        "robots_detail": robots,
    }

    # verify(): a landmark must hold on every observation of its screen and on no other.
    landmark_rows = []
    by_screen: dict[str, list[list[dict]]] = defaultdict(list)
    for obs, value in doc["observations"].items():
        features = value["evidence"].get("features")
        if features:
            by_screen[evidence.screen_of_observation[obs]].append(features["nodes"])
    for robot in model["screens"]:
        rules = [{"resource": item["value"]} if item["kind"] == "res" else {"text": item["value"]}
                 for item in robot["landmark"]]
        own = by_screen.get(robot_screen[robot["name"]], [])
        others = [nodes for screen, rows in by_screen.items() if screen != robot_screen[robot["name"]] for nodes in rows]
        held = sum(all(_matches(rule, nodes) for rule in rules) for nodes in own) if rules else 0
        wrong = sum(all(_matches(rule, nodes) for rule in rules) for nodes in others) if rules else 0
        landmark_rows.append({"robot": robot["name"], "recall": _ratio(held, len(own)), "false_matches": wrong})
    landmarks = {"robots": len(landmark_rows),
                 "exact": sum(row["recall"] == 1.0 and row["false_matches"] == 0 for row in landmark_rows),
                 "detail": landmark_rows}

    # Methods: (screen, verb, control, destination screen); names never count.
    keyed_methods = {(screen_id, method["verb"], method["control"], method["to"]): method
                     for screen_id, screen in key_screens.items() for method in screen.get("methods", [])}
    wanted = {identity: method for identity, method in keyed_methods.items() if not method.get("optional")}
    seen_transitions = {evidence.attempt(attempt_id) for attempt_id, attempt in doc["attempts"].items()
                        if attempt.get("status") == "succeeded"}
    drafted: dict[tuple, Mapping] = {}
    extra = []
    for robot in model["screens"]:
        for method in robot["methods"]:
            source, verb, control, _ = evidence.attempt(method["evidence"][0])
            identity = (robot_screen[robot["name"]], verb, control, robot_screen.get(method["returns"], UNKNOWN))
            if identity in wanted:
                drafted[identity] = method
            elif identity not in keyed_methods:
                extra.append({"robot": robot["name"], "method": method["name"], "as": list(identity)})
    observable = [identity for identity in wanted if identity in seen_transitions]
    methods = {"key": len(wanted), "observed": len(observable), "drafted": len(drafted), "extra": extra,
               "recall": _ratio(len(drafted), len(wanted)),
               "recall_observed": _ratio(sum(identity in drafted for identity in observable), len(observable)),
               "precision": _ratio(len(drafted), len(drafted) + len(extra)),
               "missing": sorted([list(identity) for identity in wanted if identity not in drafted]),
               "replay_verified": sum(method.get("replay") == "verified" for method in drafted.values())}

    # Preconditions: only on drafted methods, against the key's condition (or its absence).
    conditions = Counter()
    condition_rows = []
    for identity, method in drafted.items():
        truth = _normal(wanted[identity].get("when"))
        chosen = _normal({"mode": method.get("when_mode", "all"), "facts": method["when"]})
        if truth is None:
            verdict = "right" if chosen is None else "spurious"
        elif chosen == truth:
            verdict = "right"
        elif truth in _offered(method):
            verdict = "offered"
        else:
            verdict = "missing" if chosen is None else "wrong"
        conditions[verdict] += 1
        if truth is not None or chosen is not None:
            condition_rows.append({"method": list(identity), "verdict": verdict,
                                   "key": [truth[0], sorted(truth[1])] if truth else None,
                                   "draft": [chosen[0], sorted(chosen[1])] if chosen else None})
    keyed = sum(1 for identity in drafted if wanted[identity].get("when"))
    preconditions = {"keyed": keyed, "right": sum(row["verdict"] == "right" and row["key"] is not None for row in condition_rows),
                     **{verdict: conditions[verdict] for verdict in ("offered", "missing", "wrong", "spurious")},
                     "detail": condition_rows}

    # Checks: (screen, resource, kind); optional key checks are neither required nor wrong.
    required = {(screen_id, check["resource"], check["kind"]) for screen_id, screen in key_screens.items()
                for check in screen.get("checks", []) if not check.get("optional")}
    allowed = required | {(screen_id, check["resource"], check["kind"]) for screen_id, screen in key_screens.items()
                          for check in screen.get("checks", []) if check.get("optional")}
    emitted = {(robot_screen[robot["name"]], item["resource"], item["kind"])
               for robot in model["screens"] for item in robot["expectations"]}
    checks = {"key": len(required), "drafted": len(emitted), "right": len(emitted & required),
              "recall": _ratio(len(emitted & required), len(required)),
              "precision": _ratio(len(emitted & allowed), len(emitted)),
              "missing": sorted([list(item) for item in required - emitted]),
              "extra": sorted([list(item) for item in emitted - allowed])}

    return {"screens": screens, "landmarks": landmarks, "methods": methods,
            "preconditions": preconditions, "checks": checks}


# --- a reviewer who knows the key -------------------------------------------------------------


def oracle_answers(model: Mapping, doc: Mapping, key: Mapping) -> tuple[list[str], dict]:
    """The answers a reviewer who knows ``key`` gives to ``robots --ask``, in its order.

    Returns the answers and the effort: questions asked, answered, and those whose right
    answer the draft did not offer (left skipped).
    """
    from .robots import _ASKED, precondition_choices

    evidence = _Evidence(doc, key)
    key_screens = {screen["id"]: screen for screen in key["screens"]}
    robot_screen = {}
    for robot in model["screens"]:
        shown = Counter(evidence.screen_of_observation.get(obs, UNKNOWN) for obs in robot["members"])
        robot_screen[robot["id"]] = shown.most_common(1)[0][0]
    returns_screen = {robot["name"]: robot_screen[robot["id"]] for robot in model["screens"]}
    screens = {robot["id"]: robot for robot in model["screens"]}
    answers: list[str] = []
    effort = Counter()
    for item in model["review"]:
        if item["kind"] not in _ASKED:
            continue
        effort["asked"] += 1
        robot = screens[item["screen"]]
        truth = key_screens.get(robot_screen[robot["id"]], {})
        answer = "s"
        if item["kind"] in ("name", "dialog-name"):
            answer = truth.get("name", "")
        elif item["kind"] == "outcome-depends":
            method = next(method for method in robot["methods"] if method["name"] == item["method"])
            _, verb, control, _ = evidence.attempt(method["evidence"][0])
            target = returns_screen.get(method["returns"], UNKNOWN)
            keyed = next((entry for entry in truth.get("methods", [])
                          if (entry["verb"], entry["control"], entry["to"]) == (verb, control, target)), None)
            if keyed is None:
                answer = "d"
            else:
                wanted = _normal(keyed.get("when"))
                choices = {choice: _normal(condition) for choice, (_, condition) in precondition_choices(method).items()}
                answer = next((choice for choice, value in choices.items() if value == wanted), "s")
        elif item["kind"] == "workflow":
            answer = "y" if truth.get("workflow") else "n"
        elif item["kind"] == "list-check":
            resource = next(entry["resource"] for entry in robot["expectations"] if entry["name"] == item["method"])
            answer = "y" if any(check["resource"] == resource and check["kind"] == "list"
                                for check in truth.get("checks", [])) else "n"
        effort["unanswerable" if answer == "s" else "answered"] += 1
        effort[f"kind:{item['kind']}"] += 1
        answers.append(answer)
    return answers, {"asked": effort["asked"], "answered": effort["answered"], "unanswerable": effort["unanswerable"],
                     "by_kind": {name.removeprefix("kind:"): count for name, count in sorted(effort.items())
                                 if name.startswith("kind:")}}


def evaluate(run: Path, key: Mapping, review: Mapping | None = None, verification: Mapping | None = None) -> dict:
    """Score the draft, the key-answered draft and (optionally) a real reviewer's decisions."""
    from .robots import ask, build
    from .verify import _document

    validate_key(key)
    doc = _document(run)
    draft = build(run, verification=verification)
    if draft["package"] != key["package"]:
        raise ValueError("the run and the answer key are for different packages")
    answers, effort = oracle_answers(draft, doc, key)
    feed = iter(answers)
    answered_review = ask(draft, None, read=lambda _prompt: next(feed), show=lambda _text: None)
    report = {"format": REPORT_FORMAT, "package": key["package"], "key_provenance": key["provenance"],
              "draft": score(draft, doc, key),
              "answered": score(build(run, review=answered_review, verification=verification), doc, key),
              "effort": effort}
    if review is not None:
        report["reviewed"] = score(build(run, review=review, verification=verification), doc, key)
    return report


def summary(report: Mapping) -> str:
    """A short plain-text table of the headline numbers."""
    readings = [name for name in ("draft", "answered", "reviewed") if name in report]
    rows = [
        ("screens: robots / key screens observed", lambda r: f"{r['screens']['robots']} / {r['screens']['observed']}"),
        ("screens: split / mixed robots", lambda r: f"{len(r['screens']['split'])} / {len(r['screens']['merged'])}"),
        ("verify(): exact landmarks", lambda r: f"{r['landmarks']['exact']} / {r['landmarks']['robots']}"),
        ("methods: recall (observed)", lambda r: f"{r['methods']['recall_observed']}"),
        ("methods: precision", lambda r: f"{r['methods']['precision']}"),
        ("preconditions: right / keyed", lambda r: f"{r['preconditions']['right']} / {r['preconditions']['keyed']}"),
        ("preconditions: offered / wrong / missing / spurious",
         lambda r: "{offered} / {wrong} / {missing} / {spurious}".format(**r["preconditions"])),
        ("checks: recall / precision", lambda r: f"{r['checks']['recall']} / {r['checks']['precision']}"),
    ]
    width = max(len(label) for label, _ in rows)
    lines = [f"{'':{width}}  " + "  ".join(f"{name:>10}" for name in readings)]
    for label, value in rows:
        lines.append(f"{label:{width}}  " + "  ".join(f"{value(report[name]):>10}" for name in readings))
    effort = report["effort"]
    lines.append(f"\nreview questions: {effort['asked']} asked, {effort['answered']} answerable from the key, "
                 f"{effort['unanswerable']} with no right option offered "
                 f"({', '.join(f'{kind} {count}' for kind, count in effort['by_kind'].items())})")
    lines.append(f"key provenance: {report['key_provenance']}")
    return "\n".join(lines)


def load_key(path: Path) -> dict:
    key = json.loads(path.read_text(encoding="utf-8"))
    validate_key(key)
    return key
