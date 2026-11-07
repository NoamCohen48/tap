"""Precondition rules proposed from outside the run, checked against what the run recorded.

Some rules no snapshot shows: Catima's Save refuses a balance that does not parse, Notes' OK a
title a note already has. A person, an agent or a model can often name them from the screen's
texts or the app's source. ``questions`` writes what such a proposer needs
(`tap-rule-questions/1`): every outcome-dependent control, the facts before each attempt and
the vocabulary of facts a rule may use. ``apply`` reads the answer (`tap-rule-candidates/1`)
and checks each rule against every recorded attempt: a rule no attempt contradicts is offered
as an answer to the method's question; a contradicted one is listed with the attempts that
contradict it. Nothing is chosen for the reviewer, and the explorer itself calls no model:
whoever fills the file decides where the questions go.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping

QUESTIONS_FORMAT = "tap-rule-questions/1"
CANDIDATES_FORMAT = "tap-rule-candidates/1"
MAX_RULES = 200
MAX_FACTS = 6

INSTRUCTIONS = (
    "Each control below led to different screens from different states. For each outcome, "
    "propose the rule that decides it as {screen, method, mode: all|any, facts, reason}, using "
    "only facts from the vocabulary. The run checks every rule against the attempts listed; "
    "a rule that names a cause no fact shows (a value format, a duplicate) belongs in reason.")


def _groups(model: Mapping) -> dict[tuple[str, str], list[dict]]:
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for screen in model["screens"]:
        for method in screen["methods"]:
            if method.get("group"):
                groups[(screen["id"], method["group"])].append(method)
    return groups


def questions(model: Mapping) -> dict:
    """The `tap-rule-questions/1` document for a `tap-robots/1` draft."""
    controls, vocabulary = [], set()
    for (screen, _), methods in sorted(_groups(model).items()):
        outcomes = []
        for method in methods:
            vocabulary.update(fact for observed in method["observed"] for fact in observed["facts"])
            outcomes.append({"method": method["name"], "returns": method["returns"],
                             "draft": {"mode": method["when_mode"], "facts": method["when"]},
                             "attempts": method["observed"]})
        controls.append({"screen": screen, "verb": methods[0]["verb"], "label": methods[0]["label"],
                         "outcomes": outcomes})
    return {"format": QUESTIONS_FORMAT, "package": model["package"], "instructions": INSTRUCTIONS,
            "vocabulary": sorted(vocabulary), "controls": controls}


def _holds(rule: Mapping, facts: set[str]) -> bool:
    check = all if rule["mode"] == "all" else any
    return check(fact in facts for fact in rule["facts"])


def apply(model: dict, candidates: Mapping) -> list[dict]:
    """Attach checked rules to their methods (``proposed``); returns the rules refused, with why.

    A rule must name a method of an outcome-dependent control and use only facts some attempt
    of that run had. It is contradicted by each attempt of its method it does not hold for, and
    each attempt of a sibling outcome it does hold for.
    """
    if not isinstance(candidates, Mapping) or candidates.get("format") != CANDIDATES_FORMAT:
        raise ValueError(f"not a {CANDIDATES_FORMAT} document")
    source, rules = candidates.get("source"), candidates.get("rules")
    if not isinstance(source, str) or not source.strip() or len(source) > 200:
        raise ValueError("candidates need a source (who or what proposed them)")
    if not isinstance(rules, list) or len(rules) > MAX_RULES:
        raise ValueError(f"rules must be a list of at most {MAX_RULES}")
    groups = _groups(model)
    methods = {(screen, method["name"]): (group, method) for (screen, group), members in groups.items()
               for method in members}
    vocabulary = {fact for members in groups.values() for method in members
                  for observed in method["observed"] for fact in observed["facts"]}
    refused = []
    for rule in rules:
        reason = _invalid(rule, methods, vocabulary)
        if reason:
            refused.append({"rule": rule, "reason": reason})
            continue
        group, method = methods[(rule["screen"], rule["method"])]
        siblings = [other for other in groups[(rule["screen"], group)] if other is not method]
        contradictions = sorted(
            [observed["attempt"] for observed in method["observed"] if not _holds(rule, set(observed["facts"]))]
            + [observed["attempt"] for other in siblings for observed in other["observed"]
               if _holds(rule, set(observed["facts"]))])
        method.setdefault("proposed", []).append({
            "mode": rule["mode"], "facts": list(dict.fromkeys(rule["facts"])), "source": source,
            "reason": rule.get("reason", ""), "contradictions": contradictions})
    return refused


def _invalid(rule: object, methods: Mapping, vocabulary: set[str]) -> str | None:
    if not isinstance(rule, Mapping) or not {"screen", "method", "mode", "facts"} <= set(rule) or (
            set(rule) - {"screen", "method", "mode", "facts", "reason"}):
        return "fields are screen, method, mode, facts and an optional reason"
    if (rule["screen"], rule["method"]) not in methods:
        return "no outcome-dependent method by that screen and name"
    if rule["mode"] not in ("all", "any"):
        return "mode is all or any"
    facts = rule["facts"]
    if not isinstance(facts, list) or not 1 <= len(facts) <= MAX_FACTS or not all(isinstance(f, str) for f in facts):
        return f"facts are 1 to {MAX_FACTS} strings"
    unknown = [fact for fact in facts if fact not in vocabulary]
    if unknown:
        return "facts no attempt had: " + ", ".join(unknown[:3])
    if not isinstance(rule.get("reason", ""), str) or len(rule.get("reason", "")) > 1000:
        return "reason is a string of at most 1000 characters"
    return None


def consistent(method: Mapping) -> list[dict]:
    """The proposed rules no recorded attempt contradicts."""
    return [rule for rule in method.get("proposed", []) if not rule["contradictions"]]
