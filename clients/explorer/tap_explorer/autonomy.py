"""Unattended exploration of a disposable emulator: a policy approves, a scheduler chooses.

The operator approves a :class:`Policy` once, before the run, instead of every action. Every
input still goes through :class:`~tap_explorer.session.DiscoverySession`: intent persisted
before input, a fresh exact-one check, no replay of an uncertain outcome. What the policy
holds back, and what the run could not settle, waits in the journal for the morning.

The scheduler works in tiers, cheapest knowledge first:

1. a control never tried on this screen (a fill counts per value);
2. a known control in a new *abstract context* of its screen: which inputs are empty, which
   checkboxes are checked, which lists have rows. These are the precondition experiments
   (Save with the name empty but the target filled) that drafts would otherwise ask about.
   Deciding experiments come first: one input at a time flipped from a context where several
   could each explain an outcome (see :meth:`Autonomy._questions`);
3. a control whose outcome already varied (two destination screens), from further states of
   its screen: text that happened to change together with the deciding input (a summary line)
   is separated by evidence instead of becoming a review question;
4. (``exhaustive`` only) any other untried action, e.g. the second fill value carried through
   the following screens, until the action budget runs out.

Within a tier, the nearest candidate wins (the current state first, then the shortest observed
route). A route that diverges continues from the state it reached: that input succeeded and was
observed, and its edge, now with two outcomes, is no longer routed. An uncertain outcome, a
screen that leaves the app or a dead end ends the episode: the caller's ``reset`` restores the starting condition (app data cleared and
the app cold-launched, or an emulator snapshot) and exploration continues in the same graph.

Screens here are the drafts' screen hypotheses (complete-link similarity); they only order
the work. Identity, routing and the input gates are the session's and are not loosened.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from collections import deque
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field, fields
from pathlib import Path

from tap_e2e import CommandError, Device, ErrorCode, TapError

from .live import PilotStopped
from .robots import _exceptions, _separate
from .session import DiscoverySession
from .similarity import _transient_subtrees, cluster_evidence, editable, node_evidence, shows
from .similarity import _parents as parents_of
from .store import INPUT_VERBS

FORMAT = "tap-explorer-autonomy/1"
POLICY_FORMAT = "tap-explorer-policy/1"
REPORT_FORMAT = "tap-explorer-report/1"
UNREACHABLE = 1 << 30  # Choice.distance with no observed route from the current state.

# Labels that can reach outside a disposable emulator (money, messages, accounts, other apps)
# or destroy data the run may still need. Phrases match whole words, case-insensitively.
HOLD_WORDS = ("delete", "remove", "erase", "wipe", "pay", "purchase", "buy", "checkout", "subscribe",
              "donate", "send", "share", "post", "publish", "invite", "call", "rate", "report",
              "log out", "logout", "sign out", "signout", "uninstall", "deactivate", "close account")

_NUMERIC = {"number", "num", "amount", "count", "quantity", "qty", "age", "price", "cost", "year", "years",
            "day", "days", "week", "weeks", "hour", "hours", "minute", "minutes", "seconds", "target",
            "goal", "limit", "size", "weight", "height", "interval", "times", "repetitions", "reps",
            "score", "percent", "total", "numerator", "denominator", "value"}


def _words(*values: str | None) -> list[str]:
    """Lowercase words of labels and resource names (``habit_name``/``habitName`` → habit name)."""
    words: list[str] = []
    for value in values:
        if value:
            value = re.sub(r"([a-z])([A-Z])", r"\1 \2", value.rsplit("/", 1)[-1])
            words += [word for word in re.split(r"[^0-9a-zA-Z]+", value.lower()) if word]
    return words


def _contains(words: list[str], phrase: str) -> bool:
    wanted = phrase.lower().split()
    return any(words[index:index + len(wanted)] == wanted for index in range(len(words) - len(wanted) + 1))


def _resources(selector: object) -> list[str]:
    """Resource names a native selector matches on (``{"node": {"resource": {"name": …}}}``)."""
    found: list[str] = []
    if isinstance(selector, Mapping):
        for key, value in selector.items():
            if key == "resource" and isinstance(value, Mapping) and isinstance(value.get("name"), str):
                found.append(value["name"])
            else:
                found += _resources(value)
    elif isinstance(selector, list):
        for item in selector:
            found += _resources(item)
    return found


def _short(resource: str) -> str:
    return resource.rsplit("/", 1)[-1]


# One value per kind of field that its format check should refuse: the explorer's own, so a
# field showing one is known to hold a malformed value (see :func:`abstract_facts`).
MALFORMED = {"email": "ada.example.com", "phone": "12", "url": "example dot com", "number": "0"}


def generate_values(words: list[str]) -> tuple[str, ...]:
    """Empty, one valid value, a second, different valid value and, for a field with a format
    (email, phone, link, number), one its validation should refuse, by what the field is for."""
    if "email" in words or "mail" in words:
        return ("", "ada@example.com", "grace@example.org", MALFORMED["email"])
    if {"phone", "tel", "mobile"} & set(words):
        return ("", "5550100", "5550199", MALFORMED["phone"])
    if {"url", "website", "link", "homepage"} & set(words):
        return ("", "https://example.com", "https://example.org", MALFORMED["url"])
    if _NUMERIC & set(words):
        return ("", "1", "42", MALFORMED["number"])
    return ("", "Ada", "Grace")


@dataclass(frozen=True)
class Policy:
    """What an unattended run may do, approved by the operator once, before the run.

    ``hold``: label/resource phrases never approved (default :data:`HOLD_WORDS`); ``allow``:
    labels or resource names exempt from ``hold``; ``deny``: labels or resource names never
    touched. ``values``: fill values for every field (default: generated per field), and
    ``field_values`` per resource name. ``focus_taps``: retry a text field's tap in every
    context (off: once per screen, which shows what tapping it opens, such as a date picker,
    and focuses it for its keyboard action, plus where that action is still unknown).
    ``exhaustive``: run tier 4.
    ``max_contexts``: new abstract contexts each tried control is retried in, most new facts first;
    ``max_experiments``: source states per outcome-dependent control (tier 3).
    ``permissions``: how a runtime-permission dialog an input opens is answered (``deny``,
    ``allow``, or ``park`` to stop there like at any foreign window); deny explores the app's
    no-permission path without hardware such as a camera feed.
    """

    name: str = "default"
    hold: tuple[str, ...] = HOLD_WORDS
    allow: tuple[str, ...] = ()
    deny: tuple[str, ...] = ()
    values: tuple[str, ...] | None = None
    field_values: Mapping[str, tuple[str, ...]] = field(default_factory=dict)
    focus_taps: bool = False
    exhaustive: bool = True
    max_contexts: int = 8
    max_experiments: int = 8
    permissions: str = "deny"

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("policy name required")
        if self.permissions not in ("deny", "allow", "park"):
            raise ValueError("policy permissions must be deny, allow or park")
        if self.max_contexts < 1 or self.max_experiments < 1:
            raise ValueError("max_contexts and max_experiments must be positive")
        for values in [*([self.values] if self.values is not None else []), *self.field_values.values()]:
            if not values or len(set(values)) != len(values) or any(
                    not isinstance(value, str) or len(value) > 200 for value in values):
                raise ValueError("fill values must be distinct strings of at most 200 characters")

    @classmethod
    def load(cls, data: Mapping) -> Policy:
        """Read a ``tap-explorer-policy/1`` object; unknown keys are refused, not ignored."""
        if data.get("format") != POLICY_FORMAT:
            raise ValueError(f"policy format must be {POLICY_FORMAT}")
        known = {item.name for item in fields(cls)}
        unknown = set(data) - known - {"format"}
        if unknown:
            raise ValueError(f"unknown policy keys: {sorted(unknown)}")
        def strings(key: str, items: object) -> tuple[str, ...]:
            if not isinstance(items, list) or not all(isinstance(item, str) for item in items):
                raise ValueError(f"policy {key} must be a list of strings")
            return tuple(items)

        values = dict(data)
        values.pop("format")
        for key in ("name", "permissions"):
            if not isinstance(values.get(key, "default"), str):
                raise ValueError(f"policy {key} must be a string")
        for key in ("hold", "allow", "deny"):
            if key in values:
                values[key] = strings(key, values[key])
        if values.get("values") is not None:
            values["values"] = strings("values", values["values"])
        if "field_values" in values:
            if not isinstance(values["field_values"], dict):
                raise ValueError("policy field_values must map resource names to lists of strings")
            values["field_values"] = {key: strings(f"field_values[{key!r}]", items)
                                      for key, items in values["field_values"].items()}
        for key in ("focus_taps", "exhaustive"):
            if key in values and not isinstance(values[key], bool):
                raise ValueError(f"policy {key} must be true or false")
        for key in ("max_contexts", "max_experiments"):
            if key in values and (not isinstance(values[key], int) or isinstance(values[key], bool)):
                raise ValueError(f"policy {key} must be an integer")
        return cls(**values)

    def to_dict(self) -> dict:
        return {"format": POLICY_FORMAT, "name": self.name, "hold": list(self.hold), "allow": list(self.allow),
                "deny": list(self.deny), "values": None if self.values is None else list(self.values),
                "field_values": {key: list(value) for key, value in sorted(self.field_values.items())},
                "focus_taps": self.focus_taps, "exhaustive": self.exhaustive, "max_contexts": self.max_contexts,
                "max_experiments": self.max_experiments, "permissions": self.permissions}

    def _named(self, names: tuple[str, ...], label: str, resources: list[str]) -> bool:
        lowered = {label.lower(), *(resource.lower() for resource in resources),
                   *(_short(resource).lower() for resource in resources)}
        return any(name.lower() in lowered for name in names)

    def verdict(self, action: Mapping) -> str | None:
        """``None`` when the policy approves the action, else why it is held back."""
        target = action["target"]
        label = target.get("label") or ""
        resources = _resources(target.get("selector"))
        if self._named(self.deny, label, resources):
            return "denied by the policy"
        if self._named(self.allow, label, resources):
            return None
        words = _words(label, *resources)
        for phrase in self.hold:
            if _contains(words, phrase):
                return f"label matches hold word {phrase!r}"
        return None

    def values_for(self, action: Mapping) -> tuple[str, ...]:
        resources = _resources(action["target"].get("selector"))
        for resource in resources:
            for key in (resource, _short(resource)):
                if key in self.field_values:
                    return self.field_values[key]
        if self.values is not None:
            return self.values
        return generate_values(_words(*resources, action["target"].get("label")))


def _scenario_id(action_id: str, value: str) -> str:
    # Same derivation as DiscoverySession.approve: a distinct value is a distinct scenario.
    return action_id + "/value-" + hashlib.sha256(value.encode()).hexdigest()[:16]


# Verbs retried in a new context (tier 2): a tap, Back or a keyboard action can depend on what
# the screen holds (Save with an empty name; Back on a dirty form asks to discard; Search with
# no query). A long press or scroll rarely does, and each would multiply the work by the number
# of contexts.
_CONTEXT_VERBS = ("tap", "back", "submit")


def _key(selector: object) -> str:
    return json.dumps(selector, sort_keys=True, separators=(",", ":"))


def _controlled(facts: Iterable) -> frozenset:
    """The facts a test sets itself: an input empty or filled, a box checked or not."""
    return frozenset(fact for fact in facts if fact[0] in ("input", "checked") and isinstance(fact[2], bool))


def _asks(questions: Iterable[tuple[frozenset, tuple]], facts: frozenset) -> bool:
    """Whether trying the control in this context answers one of its open questions."""
    controlled = _controlled(facts)
    return any(rest <= controlled and fact not in controlled for rest, fact in questions)


def _flips(questions: Mapping[tuple, list], screen: int, kind: str, resources: Iterable[str],
           facts: frozenset) -> list[tuple]:
    """The open questions' facts that one input on ``resources`` here would flip, from a
    context holding all of the question's facts, into the context that answers it."""
    names = {_short(resource) for resource in resources}
    controlled = _controlled(facts)
    return sorted({fact for control, items in questions.items() if control[0] == screen
                   for rest, fact in items
                   if fact[0] == kind and fact[1] in names and rest | {fact} <= controlled}, key=repr)


def abstract_facts(nodes: Sequence[Mapping], package: str, typed: Iterable[str] = ()) -> frozenset:
    """The context a precondition experiment varies: input emptiness (or a :data:`MALFORMED`
    value the explorer typed), check state, list rows, and which of the explorer's own ``typed``
    values the screen shows.

    Other texts are left out: they are data, and a context per text would turn every value on
    screen into new precondition work. A typed value shown back (in its field or a summary line)
    is the exception: carrying a second value through the screens that show it is what lets a
    check on that text be drafted, since a check needs the value to vary. Values of one character
    are too short to recognise in a text and are ignored.
    """
    owned = [node for node in nodes if node.get("package") == package]
    parents = parents_of(owned)
    transient = _transient_subtrees(owned, parents)
    lists = {index for index, node in enumerate(owned) if (node.get("class") or "").endswith(
        ("RecyclerView", "ListView", "GridView"))}
    typed = set(typed)
    wanted = [value for value in typed if len(value) > 1]
    facts = set()
    for index, node in enumerate(owned):
        if index in transient:
            continue
        ancestors, parent = [], parents[index]
        while parent is not None:
            ancestors.append(parent)
            parent = parents[parent]
        if lists & set(ancestors):
            continue  # Row content is data.
        if wanted and node.get("text") and node.get("showing_hint") is not True:
            facts.update(("shows", value) for value in wanted if shows(node["text"], value))
        resource = node.get("resource") or ""
        if not resource.startswith(f"{package}:id/"):
            continue
        short = _short(resource)
        flags = node.get("flags", ())
        if editable(node):
            empty = node.get("showing_hint") is True or not node.get("text") or node.get("text") == node.get("hint")
            malformed = node.get("text") in typed and node.get("text") in MALFORMED.values()
            facts.add(("input", short, "malformed" if malformed else not empty))
        if "CHECKABLE" in flags:
            facts.add(("checked", short, "CHECKED" in flags))
        if index in lists:
            facts.add(("rows", short, any(parents[other] == index for other in range(len(owned)))))
    return frozenset(facts)


@dataclass
class Choice:
    tier: int
    distance: int
    source: str
    action: str
    value: str | None
    label: str
    gain: int = 0  # Tier 2: facts of this context the control was never tried with.
    decides: bool = False  # Tier 2: a deciding experiment (see Autonomy._questions).


class Journal:
    """``autonomy.json``: what the policy held back, what was parked and why, and the run log."""

    def __init__(self, path: Path, policy: Policy, reset_method: str):
        self.path = path
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("format") != FORMAT:
                raise ValueError("not a tap-explorer-autonomy/1 journal")
            if data["policy"] != policy.to_dict():
                raise ValueError("a resumed run must keep the policy it started with")
            self.data = data
        else:
            self.data = {"format": FORMAT, "policy": policy.to_dict(), "reset_method": reset_method,
                         "runs": [], "held": {}, "parked": {}, "tiers": {"1": 0, "2": 0, "3": 0, "4": 0},
                         "navigations": 0, "resets": [], "replays": [], "skipped_focus_taps": 0, "context_cap": 0,
                         "unsupported_verbs": {}, "escapes": 0, "diverged": 0, "decisions": 0}
        self.listeners: list[Callable[[dict], None]] = []

    def save(self) -> None:
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.data, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
                             encoding="utf-8")
        os.replace(temporary, self.path)

    def _notify(self, item: dict) -> None:
        for listener in self.listeners:
            listener(item)

    def hold(self, key: str, item: dict) -> None:
        entry = self.data["held"].get(key)
        if entry is None:
            self.data["held"][key] = {**item, "sources": [item["source"]]}
            self._notify({"kind": "held", **item})
        elif item["source"] not in entry["sources"]:
            entry["sources"].append(item["source"])

    def park(self, key: str, item: dict) -> None:
        if key not in self.data["parked"]:
            self.data["parked"][key] = item
            self._notify({"kind": "parked", **item})

    def parked(self, key: str) -> bool:
        return key in self.data["parked"]


class Autonomy:
    """Drive one :class:`DiscoverySession` without a person, inside the session's gates.

    ``reset`` restores the run's starting condition (see :meth:`DiscoverySession.reset`);
    ``reset_method`` names it in the graph's episodes. ``max_resets`` bounds the episodes.
    """

    def __init__(self, session: DiscoverySession, *, reset: Callable[[], Device | None], reset_method: str,
                 policy: Policy = Policy(), journal: Path, max_resets: int = 50,
                 notify: Callable[[dict], None] | None = None):
        if max_resets < 1:
            raise ValueError("at least one reset is needed to establish the starting condition")
        self.session, self.store, self.policy = session, session.store, policy
        self._reset_device, self.reset_method, self.max_resets = reset, reset_method, max_resets
        self.journal = Journal(journal, policy, reset_method)
        if notify is not None:
            self.journal.listeners.append(notify)
        self.package = session.package
        self._nodes: dict[str, list[dict] | None] = {}
        self._contexts: dict[tuple[str, tuple[str, ...]], frozenset] = {}
        self._screens: tuple[tuple, dict[str, int]] | None = None
        self.failures: dict[str, int] = {}
        self.fresh_episode = False
        self.resets_this_run = 0
        self.escape: Choice | None = None  # The current state's untried Back (see candidates).

    # --- model -------------------------------------------------------------------------------

    def _snapshot(self, doc: dict, state: str) -> list[dict] | None:
        if state not in self._nodes:
            evidence = doc["observations"][doc["states"][state]["observations"][0]]["evidence"]
            try:
                self._nodes[state] = json.loads(Path(evidence["snapshot"]).read_text(encoding="utf-8"))["nodes"]
            except (OSError, ValueError, KeyError):
                self._nodes[state] = None
        return self._nodes[state]

    def _screen_of(self, doc: dict) -> dict[str, int]:
        typed = tuple(sorted({action["scenario"]["value"] for action in doc["actions"].values()
                              if action["verb"] == "fill" and action["status"] != "blocked"
                              and isinstance(action["scenario"].get("value"), str) and action["scenario"]["value"]}))
        states = tuple(sorted(doc["states"]))
        if self._screens is None or self._screens[0] != (states, typed):
            items = {}
            for state in states:
                nodes = self._snapshot(doc, state)
                owned = [node for node in nodes or () if node.get("package") == self.package]
                items[state] = node_evidence(self.package, owned, typed) if owned else (self.package, frozenset(), frozenset())
            groups = sorted(set(cluster_evidence(items).values()))
            self._screens = ((states, typed), {state: index for index, members in enumerate(groups) for state in members})
        return self._screens[1]

    def _facts(self, doc: dict, state: str, typed: Sequence[str] = ()) -> frozenset:
        key = (state, tuple(typed))
        if key not in self._contexts:
            nodes = self._snapshot(doc, state)
            self._contexts[key] = abstract_facts(nodes, self.package, typed) if nodes else frozenset()
        return self._contexts[key]

    def _distances(self) -> dict[str, int]:
        """Observed single-outcome route lengths from the current state, as session.route plans."""
        current = self.session.current
        if current is None:
            return {}
        edges: dict[str, set[str]] = {}
        for edge in self.session.routable_edges():
            edges.setdefault(edge["source"], set()).add(edge["destination"])
        distance = {current: 0}
        queue = deque([current])
        while queue:
            state = queue.popleft()
            for destination in edges.get(state, ()):
                if destination not in distance:
                    distance[destination] = distance[state] + 1
                    queue.append(destination)
        return distance

    def _questions(self, doc: dict, screen: Mapping[str, int], facts: Mapping[str, frozenset]) -> dict[tuple, list]:
        """Open precondition questions: per control whose outcome varied, ``(rest, fact)`` pairs.

        For each outcome, the controlled facts (an input empty or filled, a box checked) common
        to every context it followed are its candidate preconditions; one that some other
        outcome's context lacked could be the cause. It is answered by a context holding the
        rest of them but not it; until then the question is open, and its experiment (the
        control tried there, or the one fill or tap that makes such a context) is deciding.
        Ablation one fact at a time, as delta debugging narrows a failing input: Loop's numeric
        Save, tried once with every field filled, then with each field emptied in turn.
        """
        seen: dict[tuple, list[tuple[frozenset, int]]] = {}
        for attempt in doc["attempts"].values():
            action = doc["actions"][attempt["action"]]
            destination = attempt.get("destination")
            if (attempt["status"] != "succeeded" or action["verb"] not in _CONTEXT_VERBS
                    or destination not in screen or action["source"] not in screen):
                continue
            control = (screen[action["source"]], action["verb"], _key(action["target"].get("selector")))
            seen.setdefault(control, []).append((_controlled(facts[action["source"]]), screen[destination]))
        questions: dict[tuple, list] = {}
        for control, items in seen.items():
            outcomes = {outcome for _, outcome in items}
            if len(outcomes) < 2:
                continue
            for outcome in sorted(outcomes):
                common = frozenset.intersection(*(context for context, other in items if other == outcome))
                for fact in sorted(common, key=repr):
                    if all(fact in context for context, other in items if other != outcome):
                        continue  # Every other outcome had it too: not a cause.
                    rest = common - {fact}
                    if any(rest <= context and fact not in context for context, _ in items):
                        continue  # Answered.
                    questions.setdefault(control, []).append((rest, fact))
        return questions

    @staticmethod
    def _exceptions(doc: dict, screen: Mapping[str, int],
                    facts: Mapping[str, frozenset]) -> dict[tuple, list[tuple[frozenset, frozenset]]]:
        """Lone exceptions to a simpler rule, per control: (controlled context, full context).

        The draft offers the rule that would hold if one observation had another cause
        (``robots._exceptions``: Catima's Save rejected once with every field filled, because
        its balance "Grace" does not parse). Trying the control again with the same fields empty
        and filled but other typed values says which: the same outcome again backs the observed
        rule, another one points at a value. Open until the control has been tried twice in
        that controlled context.
        """
        seen: dict[tuple, list[tuple[frozenset, frozenset, int]]] = {}
        for attempt in doc["attempts"].values():
            action = doc["actions"][attempt["action"]]
            destination = attempt.get("destination")
            if (attempt["status"] != "succeeded" or action["verb"] not in _CONTEXT_VERBS
                    or destination not in screen or action["source"] not in screen):
                continue
            control = (screen[action["source"]], action["verb"], _key(action["target"].get("selector")))
            full = facts[action["source"]]
            seen.setdefault(control, []).append((_controlled(full), full, screen[destination]))
        result: dict[tuple, list[tuple[frozenset, frozenset]]] = {}
        for control, items in seen.items():
            outcomes = {outcome for _, _, outcome in items}
            if len(outcomes) < 2:
                continue
            for outcome in sorted(outcomes):
                ordered = [item for item in items if item[2] == outcome] + [item for item in items if item[2] != outcome]
                members = [{repr(fact) for fact in context} for context, _, other in ordered if other == outcome]
                others = [{repr(fact) for fact in context} for context, _, other in ordered if other != outcome]
                when, left = _separate(members, others, {})
                for _, _, index in _exceptions(members, others, when, left, {}):
                    context, full, _ = ordered[index]
                    if sum(item[0] == context for item in items) < 2 and (context, full) not in result.get(control, []):
                        result.setdefault(control, []).append((context, full))
        return result

    def candidates(self) -> list[Choice]:
        """Every action the policy allows and the tiers still want, nearest first, reachable or not."""
        doc = self.store.view()
        screen = self._screen_of(doc)
        typed = sorted({doc["actions"][attempt["action"]]["scenario"].get("value") or ""
                        for attempt in doc["attempts"].values()
                        if doc["actions"][attempt["action"]]["verb"] == "fill"} - {""})
        facts = {state: self._facts(doc, state, typed) for state in doc["states"]}
        tried, contexts = set(), set()
        outcomes: dict[tuple, set[int]] = {}
        sources: dict[tuple, set[str]] = {}
        # What each state offers: a scroll that revealed controls its source did not offer is
        # worth repeating from where it landed, until the content ends or stops revealing.
        offered: dict[str, set[tuple[str, str]]] = {}
        for id, action in doc["actions"].items():
            if "/value-" not in id:
                offered.setdefault(action["source"], set()).add((action["verb"], _key(action["target"].get("selector"))))
        revealing: set[tuple[str, str]] = set()
        experimented: dict[tuple, list[frozenset]] = {}  # Contexts each control was retried in.
        covered: dict[tuple, set] = {}  # Facts of every context each control was tried in.
        filled_fields: set[tuple] = set()
        for _, attempt in sorted(doc["attempts"].items()):
            action = doc["actions"][attempt["action"]]
            source, selector, verb = action["source"], _key(action["target"].get("selector")), action["verb"]
            control = (screen[source], verb, selector)
            if (control in filled_fields or control in tried) and facts[source] not in experimented.get(control, []):
                experimented.setdefault(control, []).append(facts[source])
            covered.setdefault(control, set()).update(facts[source])
            if verb == "fill":
                value = action["scenario"].get("value")
                filled_fields.add(control)
                tried.add((screen[source], "fill", selector, value))
                contexts.add((screen[source], "fill", selector, bool(value), facts[source]))
                continue
            tried.add((screen[source], verb, selector))
            contexts.add((screen[source], verb, selector, facts[source]))
            sources.setdefault((screen[source], verb, selector), set()).add(source)
            destination = attempt.get("destination")
            if attempt["status"] == "succeeded" and destination in screen:
                outcomes.setdefault((screen[source], verb, selector), set()).add(screen[destination])
                if verb == "scroll" and offered.get(destination, set()) - offered.get(source, set()):
                    revealing.add((destination, selector))
        questions = self._questions(doc, screen, facts)
        exceptions = self._exceptions(doc, screen, facts)
        fills = {(action["source"], _key(action["target"].get("selector")))
                 for action in doc["actions"].values() if action["verb"] == "fill"}
        # A keyboard action is offered only while its field has focus, so it is discovered per
        # state: a field whose screen has one is focused again where that state lacks it.
        submits = {(action["source"], _key(action["target"].get("selector")))
                   for action in doc["actions"].values() if action["verb"] == "submit"}
        submit_screens = {(screen[source], selector) for source, selector in submits}
        # Back that left the app from a screen leaves it from every context there (a
        # single-activity app), and each try costs a reset: one exit settles it. A tap's exit
        # can depend on state (Save opens the notification permission dialog only when a
        # reminder is set), so taps stay in tier 2, bounded by ``max_contexts``.
        left = set()
        for item in self.journal.data["parked"].values():
            action = doc["actions"].get(item["action"]) if item.get("attempt") else None
            if action is not None and action["verb"] == "back" and action["source"] in screen:
                left.add((screen[action["source"]], "back", _key(action["target"].get("selector"))))
        distance = self._distances() if self.session.current else {}
        result: list[Choice] = []
        focus = capped = 0
        for id, action in sorted(doc["actions"].items()):
            target, verb = action["target"], action["verb"]
            if (action["status"] != "blocked" or verb not in INPUT_VERBS or target.get("blocked_reason")
                    or "/value-" in id or self.journal.parked(id) or verb in self.journal.data["unsupported_verbs"]):
                continue
            source, selector = action["source"], _key(target.get("selector"))
            held = self.policy.verdict(action)
            if held:
                self.journal.hold(f"{verb}:{selector}", {"action": id, "source": source,
                                  "label": target.get("label"), "reason": held})
                continue
            # Each control is retried in at most ``max_contexts`` contexts; the next one is the
            # context adding the most facts it was never tried with (``gain``), then the nearest.
            control = (screen[source], verb, selector)
            context_ok = len(experimented.get(control, ())) < self.policy.max_contexts
            gain = len(facts[source] - covered.get(control, set()))
            reach = distance.get(source, UNREACHABLE)
            if verb != "fill":
                if (verb == "tap" and not self.policy.focus_taps and (source, selector) in fills
                        and control in tried and not ((screen[source], selector) in submit_screens
                                                      and (source, selector) not in submits)):
                    focus += 1
                    continue
                decides = False
                if control not in tried or (verb == "scroll" and (source, selector) in revealing):
                    tier = 1
                elif control not in left and any(_controlled(facts[source]) == context and facts[source] != full
                                                 for context, full in exceptions.get(control, ())):
                    tier, decides = 2, True  # Retry a lone exception with other values.
                elif control not in left and (_asks(questions.get(control, ()), facts[source])
                                              or (verb == "tap" and _flips(questions, screen[source], "checked",
                                                                           _resources(target.get("selector")),
                                                                           facts[source]))):
                    tier, decides = 2, True
                elif (verb in _CONTEXT_VERBS and (*control, facts[source]) not in contexts and context_ok
                      and control not in left):
                    tier = 2
                elif (len(outcomes.get(control, ())) > 1
                      and len(sources[control]) < self.policy.max_experiments):
                    tier = 3
                elif self.policy.exhaustive:
                    tier = 4
                else:
                    capped += verb in _CONTEXT_VERBS and (*control, facts[source]) not in contexts
                    continue
                result.append(Choice(tier, reach, source, id, None, target.get("label") or "",
                                     gain if tier == 2 else 0, decides))
                continue
            values = self.policy.values_for(action)

            def done(value: str) -> bool:
                scenario = _scenario_id(id, value)
                return ((scenario in doc["actions"] and doc["actions"][scenario]["status"] != "blocked")
                        or self.journal.parked(scenario))

            # A deciding fill empties (or fills) one field of a context the control was tried in.
            deciding = set()
            for flip in _flips(questions, screen[source], "input", _resources(target.get("selector")), facts[source]):
                value = next((value for value in values if bool(value) != flip[2]), None)
                if value is not None and value not in deciding and not done(value):
                    deciding.add(value)
                    result.append(Choice(2, reach, source, id, value, target.get("label") or "", 0, True))
            klasses: set[bool] = set()
            for value in values:
                first_of_class = bool(value) not in klasses
                klasses.add(bool(value))
                if value in deciding or done(value):
                    continue
                if (screen[source], "fill", selector, value) not in tried:
                    tier = 1
                elif (first_of_class and context_ok
                      and (screen[source], "fill", selector, bool(value), facts[source]) not in contexts):
                    tier = 2
                elif self.policy.exhaustive:
                    tier = 4
                else:
                    continue
                result.append(Choice(tier, reach, source, id, value, target.get("label") or "",
                                     gain if tier == 2 else 0))
        self.journal.data["skipped_focus_taps"] = focus
        self.journal.data["context_cap"] = capped
        # A dead end (a state no observed edge leaves yet, such as a form just filled) would
        # cost a reset and the route back. Its own untried Back is a real experiment instead,
        # and often a branch of its own (a "discard changes?" dialog). Routes never infer it.
        self.escape = None
        current = self.session.current
        for id, action in sorted(doc["actions"].items()):
            if (current in screen and action["source"] == current and action["verb"] == "back"
                    and action["status"] == "blocked" and not self.journal.parked(id)
                    and not self.policy.verdict(action)
                    and (screen[current], "back", _key(action["target"].get("selector"))) not in left):
                self.escape = Choice(0, 0, current, id, None, action["target"].get("label") or "Back")
                break
        # Fills first within a state: inputs set before the submit control is tried there. Back
        # last: it usually leaves the screen whose other controls are still waiting.
        return sorted(result, key=lambda c: (c.tier, not c.decides, -c.gain, c.distance, c.source, c.value is None,
                                             doc["actions"][c.action]["verb"] == "back", c.action,
                                             "" if c.value is None else c.value))

    # --- loop --------------------------------------------------------------------------------

    def _withdraw(self) -> None:
        for id in [id for id, action in self.store.view()["actions"].items() if action["status"] == "pending"]:
            self.store.withdraw_action(id)

    def _episode(self, reason: str) -> str | None:
        """Reset; returns a stop reason when no further episode can start."""
        failures = 0
        while True:
            if self.resets_this_run >= self.max_resets:
                return "reset_budget"
            self.resets_this_run += 1
            self._withdraw()
            attempts = len(self.store.view()["attempts"])
            try:
                self.session.reset(self._reset_device, method=self.reset_method, reason=reason[:200])
                self.journal.data["resets"].append({"after_attempts": attempts, "reason": reason[:200]})
                self.fresh_episode = True
                return None
            except (PilotStopped, TapError) as error:
                # A TapError is the device refusing the reset itself (a force-stop that never
                # finished): no episode began, so trying again is safe.
                if "elapsed-time" in str(error):
                    return "time_budget"
                self.journal.data["resets"].append({"after_attempts": attempts, "reason": reason[:200],
                                                    "error": f"{type(error).__name__}: {error}"[:300]
                                                    if isinstance(error, TapError) else str(error)[:200]})
                failures += 1
                if failures >= 3:
                    return "reset_failed"
                reason = f"previous reset did not reach a stable app screen: {error}"
            finally:
                self.journal.save()

    def _history(self, candidates: list[Choice]) -> tuple[str, list[dict]] | None:
        """The nearest frontier state a recorded history reaches from here (fresh episodes only):
        lowest tier first, then the shortest history. Each target is replayed at most once
        unsuccessfully."""
        failed = {replay["to"] for replay in self.journal.data["replays"] if replay["result"] != "reached"}
        best: tuple[tuple[int, int], str, list[dict]] | None = None
        seen: set[str] = set()
        for choice in candidates:
            if choice.source in seen or choice.source in failed:
                continue
            seen.add(choice.source)
            steps = self.session.history(choice.source)
            if steps and steps[0]["source"] == self.session.current:
                rank = (choice.tier, len(steps))
                if best is None or rank < best[0]:
                    best = (rank, choice.source, steps)
        return None if best is None else (best[1], best[2])

    def _replay(self, target: str, steps: list[dict]) -> str | None:
        """Replay a history to ``target``; returns a stop reason only when a budget ended."""
        record = {"to": target, "steps": len(steps), "after_attempts": len(self.store.view()["attempts"]),
                  "result": "reached"}
        self.journal.data["replays"].append(record)
        self.fresh_episode = False
        try:
            self.session.replay(steps)
            return None
        except PilotStopped as error:
            self._withdraw()
            record["result"] = f"stopped: {error}"[:200]
            if self.session.continue_after_divergence():
                self.journal.data["diverged"] = self.journal.data.get("diverged", 0) + 1
            if "elapsed-time" in str(error):
                return "time_budget"
            if "action budget" in str(error):
                return "action_budget"
            return None
        except CommandError as error:
            self._withdraw()
            record["result"] = f"{type(error).__name__}: {error}"[:200]
            return None
        finally:
            self.journal.save()

    def _focus(self, choice: Choice) -> bool:
        """Tap a keyboard action's field first when it has no focus and its tap is untried;
        True when that was the step taken. A tried tap is the session's to repeat."""
        doc = self.store.view()
        selector = doc["actions"][choice.action]["target"].get("selector")
        if any(c.verb == "submit" and c.selector == selector for c in self.session.fresh):
            return False
        tap = next((id for id, action in sorted(doc["actions"].items())
                    if action["source"] == choice.source and action["verb"] == "tap" and action["status"] == "blocked"
                    and action["target"].get("selector") == selector and not action["target"].get("blocked_reason")),
                   None)
        if tap is None:
            return False
        self.session.approve(tap, reason=f"policy {self.policy.name}: focus the field for its keyboard action")
        self.session.step(self.store.next_action()["action"])
        return True

    def _fail(self, choice: Choice, error: BaseException) -> None:
        key = choice.action if choice.value is None else _scenario_id(choice.action, choice.value)
        if isinstance(error, CommandError) and error.code == ErrorCode.UNSUPPORTED:
            # The device cannot do this kind of input at all (the keyboard action before API 30).
            verb = self.store.view()["actions"][choice.action]["verb"]
            self.journal.data["unsupported_verbs"][verb] = str(error)[:300]
            return
        self.failures[key] = self.failures.get(key, 0) + 1
        if self.failures[key] >= 2:
            self.journal.park(key, {"action": choice.action, "value": choice.value, "label": choice.label,
                                    "source": choice.source, "reason": f"{type(error).__name__}: {error}"[:300]})

    def run(self) -> dict:
        """Explore until the frontier is resolved or a budget ends; returns the run summary."""
        run = {"started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "resumed": self.session.resumed, "stop": None}
        self.journal.data["runs"].append(run)
        self.journal.save()
        stop = self._episode("resume a stopped run" if self.session.resumed else "establish the starting condition")
        limit = 4 * self.store.document()["budgets"]["actions"] + 100
        iterations = 0
        while stop is None:
            iterations += 1
            if iterations > limit:
                stop = "iteration_limit"
                break
            if self.session.halted or self.session.current is None:
                stop = self._episode(self.session.halted or "the app was not on a recognized, stable screen")
                continue
            if self.store.next_action()["reason"] == "action_budget":
                stop = "action_budget"
                break
            candidates = self.candidates()
            reachable = [choice for choice in candidates if choice.distance != UNREACHABLE]
            if not reachable and candidates and not self.fresh_episode and self.escape is not None:
                reachable = [self.escape]
            if not reachable:
                if candidates and not self.fresh_episode:
                    stop = self._episode("no observed route from here to the remaining frontier")
                    continue
                history = self._history(candidates)
                if history is not None:
                    stop = self._replay(*history)
                    continue
                for choice in candidates:
                    self.journal.park(choice.action if choice.value is None else _scenario_id(choice.action, choice.value),
                                      {"action": choice.action, "value": choice.value, "label": choice.label,
                                       "source": choice.source, "reason": "no observed route from the starting condition"})
                stop = "frontier_resolved" if not candidates else "unreachable_frontier"
                break
            choice = reachable[0]
            try:
                if choice.source != self.session.current:
                    self.journal.data["navigations"] += 1
                    self.fresh_episode = False
                    try:
                        self.session.navigate(choice.source)
                    except PilotStopped as error:  # The route failed, not the choice: say so.
                        raise PilotStopped(f"the route to its screen failed: {error}") from error
                    continue
                if (self.store.view()["actions"][choice.action]["verb"] == "submit" and self._focus(choice)):
                    self.fresh_episode = False
                    continue
                self.session.approve(choice.action, value=choice.value,
                                     reason=f"policy {self.policy.name}: " + (f"tier {choice.tier}" if choice.tier
                                                                              else "Back out of a dead end"))
                selected = self.store.next_action()["action"]
                self.fresh_episode = False
                if choice.tier:
                    self.journal.data["tiers"][str(choice.tier)] += 1
                    self.journal.data["decisions"] = self.journal.data.get("decisions", 0) + choice.decides
                else:
                    self.journal.data["escapes"] = self.journal.data.get("escapes", 0) + 1
                self.session.step(selected)
            except PilotStopped as error:
                self._withdraw()
                if "elapsed-time" in str(error):
                    stop = "time_budget"
                elif "action budget" in str(error):
                    stop = "action_budget"
                elif self.session.continue_after_divergence():
                    self.journal.data["diverged"] = self.journal.data.get("diverged", 0) + 1
                elif self.session.halted == "known command success without verified destination":
                    attempt = max(self.store.view()["attempts"])
                    self.journal.park(attempt, {"action": choice.action, "value": choice.value, "label": choice.label,
                                                "source": choice.source, "attempt": attempt,
                                                "reason": f"left the app or opened a foreign window: {error}"})
                elif not self.session.halted:
                    self._fail(choice, error)
            except CommandError as error:
                # A safe rejection sent no input (failed_before_input); anything else halted.
                self._withdraw()
                self._fail(choice, error)
            finally:
                self.journal.save()
        run["stop"] = stop
        doc = self.store.document()
        summary = {"stop": stop, "states": len(doc["states"]), "attempts": len(doc["attempts"]),
                   "episodes": len(doc["episodes"]), "held": len(self.journal.data["held"]),
                   "parked": len(self.journal.data["parked"]), "tiers": dict(self.journal.data["tiers"]),
                   "escapes": self.journal.data.get("escapes", 0), "diverged": self.journal.data.get("diverged", 0),
                   "decisions": self.journal.data.get("decisions", 0),
                   "conflicts": len(self.session.behavioral_conflicts())}
        run["summary"] = summary
        self.journal.save()
        return summary


# --- morning report ----------------------------------------------------------------------------


def report(run: Path) -> dict:
    """The ``tap-explorer-report/1`` summary of a run directory: what to read in the morning."""
    from .store import GraphStore

    with GraphStore(run / "graph.db") as store:
        doc = store.document()
    journal = json.loads((run / "autonomy.json").read_text(encoding="utf-8")) if (run / "autonomy.json").is_file() else {}
    robots = run / "robots"
    model = json.loads((robots / "robots.json").read_text(encoding="utf-8")) if (robots / "robots.json").is_file() else None
    verification = (json.loads((robots / "verification.json").read_text(encoding="utf-8"))
                    if (robots / "verification.json").is_file() else None)
    outcomes: dict[str, int] = {}
    for attempt in doc["attempts"].values():
        outcomes[attempt["status"]] = outcomes.get(attempt["status"], 0) + 1
    replay: dict[str, int] = {}
    for entry in (verification or {}).get("methods", {}).values():
        replay[entry["status"]] = replay.get(entry["status"], 0) + 1
    questions: dict[str, int] = {}
    for item in (model or {}).get("review", []):
        questions[item["kind"]] = questions.get(item["kind"], 0) + 1
    runs = journal.get("runs", [])
    stop = runs[-1].get("stop") if runs else None
    blocked = None
    if stop == "reset_failed" and doc["observations"]:  # What the device showed instead of the app.
        evidence = doc["observations"][max(doc["observations"])]["evidence"]
        snapshot = run / "observations" / Path(evidence["snapshot"]).name
        nodes = json.loads(snapshot.read_text(encoding="utf-8"))["nodes"] if snapshot.is_file() else []
        blocked = {"error": journal["resets"][-1]["error"] if journal.get("resets") else None,
                   "screenshot": f"observations/{Path(evidence['screenshot']).name}" if evidence.get("screenshot") else None,
                   "windows": sorted({node["package"] for node in nodes
                                      if node.get("package") and node["package"] != doc["context"].get("package")})}
    return {"format": REPORT_FORMAT, "package": doc["context"].get("package"), "serial": doc["context"].get("serial"),
            "stop": stop, "runs": len(runs), "blocked": blocked,
            "states": len(doc["states"]), "observations": len(doc["observations"]),
            "attempts": outcomes, "episodes": [{"after_attempts": e["after_attempts"], "reason": e["reason"]}
                                               for e in doc["episodes"]],
            "tiers": journal.get("tiers", {}), "escapes": journal.get("escapes", 0),
            "diverged": journal.get("diverged", 0), "decisions": journal.get("decisions", 0),
            "navigations": journal.get("navigations", 0),
            "held": sorted(journal.get("held", {}).values(), key=lambda item: item["label"] or ""),
            "parked": sorted(journal.get("parked", {}).values(), key=lambda item: (item["reason"], item["label"] or "")),
            "robots": None if model is None else {
                "screens": len(model["screens"]), "status": model["status"],
                "methods": sum(len(screen["methods"]) for screen in model["screens"]),
                "outcome_dependent": sum(method["outcome_dependent"] for screen in model["screens"]
                                         for method in screen["methods"])},
            "replay": replay, "unverified": runs[-1].get("unverified") if runs else None, "questions": questions, "unsupported_verbs": journal.get("unsupported_verbs", {})}


def markdown(summary: Mapping) -> str:
    """REPORT.md: the run's outcome first, then what needs a person."""
    lines = [f"# Exploration report: {summary['package']}", "",
             f"- Stopped: **{summary['stop']}** after {summary['runs']} run(s) on `{summary['serial']}`",
             f"- {summary['states']} states from {summary['observations']} observations; attempts "
             + (", ".join(f"{count} {status}" for status, count in sorted(summary["attempts"].items())) or "none"),
             f"- {len(summary['episodes'])} episode(s) (resets); {summary['navigations']} routed navigations"
             + (f", {summary['diverged']} diverged and continued from where they landed" if summary.get("diverged") else ""),
             "- Scheduler tiers: " + ", ".join(f"tier {tier}: {count}" for tier, count in sorted(summary["tiers"].items()))
             + (f" ({summary['decisions']} of tier 2 deciding experiments)" if summary.get("decisions") else "")
             + (f"; {summary['escapes']} Back out of a dead end instead of a reset" if summary.get("escapes") else "")]
    if summary["robots"]:
        robots = summary["robots"]
        lines.append(f"- Robot drafts: {robots['screens']} screens, {robots['methods']} methods "
                     f"({robots['outcome_dependent']} outcome-dependent), status `{robots['status']}`")
    if summary["replay"]:
        lines.append("- Replay: " + ", ".join(f"{count} {status}" for status, count in sorted(summary["replay"].items())))
    if summary.get("unverified"):
        lines.append(f"- Replay: not run ({summary['unverified']}); the drafts are unverified")
    if summary.get("unsupported_verbs"):
        lines.append("- Not available on this device: " + ", ".join(
            f"`{verb}` ({reason})" for verb, reason in sorted(summary["unsupported_verbs"].items())))
    lines += ["", "## Needs a person", ""]
    if summary.get("blocked"):
        blocked = summary["blocked"]
        lines += ["Three resets in a row did not bring back the app's starting screen"
                  + (f" (last: {blocked['error']})" if blocked["error"] else "") + "."
                  + (f" The last screen showed a window of {', '.join(f'`{name}`' for name in blocked['windows'])}"
                     if blocked["windows"] else "")
                  + (f" (`{blocked['screenshot']}`)" if blocked["screenshot"] else "")
                  + ". Dismiss it on the device, or prepare the device so it does not appear, then resume.", ""]
    if summary["questions"]:
        lines.append("Draft review questions (`tap-explorer robots --ask`): "
                     + ", ".join(f"{count} {kind}" for kind, count in sorted(summary["questions"].items())))
        lines.append("")
    if summary["held"]:
        lines += ["Held back by the policy (allowlist them to explore):", ""]
        held: dict[str, set] = {}  # One line per label: a menu row and its text are two selectors.
        for item in summary["held"]:
            held.setdefault(f"{item['label']!r}: {item['reason']}", set()).update(item["sources"])
        lines += [f"- {line} ({len(sources)} state(s))" for line, sources in held.items()]
        lines.append("")
    if summary["parked"]:
        lines += ["Parked (the run could not settle these):", ""]
        groups: dict[str, int] = {}  # The same control parked in several states is one line.
        for item in summary["parked"]:
            line = (f"{item['label']!r}" + (f" = {item['value']!r}" if item.get("value") is not None else "")
                    + f": {item['reason']}")
            groups[line] = groups.get(line, 0) + 1
        lines += [f"- {line}" + (f" ({count} states)" if count > 1 else "") for line, count in groups.items()]
        lines.append("")
    if not (summary.get("blocked") or summary["questions"] or summary["held"] or summary["parked"]):
        lines += ["Nothing.", ""]
    if summary["episodes"]:
        lines += ["## Episodes", ""]
        lines += [f"{index}. after {episode['after_attempts']} attempts: {episode['reason']}"
                  for index, episode in enumerate(summary["episodes"], 1)]
        lines.append("")
    return "\n".join(lines)


def _note_unverified(run: Path, reason: str) -> None:
    path = run / "autonomy.json"
    journal = json.loads(path.read_text(encoding="utf-8"))
    journal["runs"][-1]["unverified"] = reason
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(journal, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def write_report(run: Path) -> dict:
    summary = report(run)
    (run / "report.json").write_text(json.dumps(summary, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
                                     encoding="utf-8")
    (run / "REPORT.md").write_text(markdown(summary), encoding="utf-8")
    return summary


# --- device wiring -----------------------------------------------------------------------------


class _Link:
    """One attachment to the emulator at a time; a snapshot load needs it closed first."""

    def __init__(self, serial: str, package: str, apk: Path, activity: str | None):
        self.serial, self.package, self.apk, self.activity = serial, package, apk, activity
        self.client = self.connection = None
        self.attached: Device | None = None
        self.events: list[dict] = []
        self.dropped = 0

    @property
    def device(self) -> Device:
        if self.attached is None:
            raise RuntimeError("the emulator is not attached")
        return self.attached

    def open(self) -> Device:
        from tap_e2e import TapClient

        self.client = TapClient.create()
        self.connection = self.client.connect("tap-explorer-auto")
        self.attached = self.connection.attach_device(self.serial, wait_for_device=0)
        return self.attached

    def launch(self) -> None:
        self.device.app(self.package).cold_launch(self.activity)

    def close(self) -> None:
        connection, client, device = self.connection, self.client, self.attached
        if connection is None or client is None:
            return
        self.client = self.connection = self.attached = None
        try:
            events = connection.event_log()
            self.events += [event.to_dict() for event in events.events]
            self.dropped += events.dropped
            if device is not None:
                detail = device.detach()
                if detail:
                    raise RuntimeError(f"device left unclean: {detail}")
        finally:
            connection.close()
            client.close()


def reset_for(link: _Link, method: str, adb: str = "adb") -> Callable[[], Device | None]:
    """``clear-data`` (app data and permissions cleared, cold launch) or ``snapshot:NAME``.

    A snapshot reset detaches first (loading under a live session quarantines the device),
    reinstalls the run's APK so an old build in the snapshot is never explored, and returns
    the new attachment.
    """
    if method == "clear-data":
        def clear() -> Device | None:
            app = link.device.app(link.package)
            app.clear_data()
            app.cold_launch(link.activity)
            return None
        return clear
    if method.startswith("snapshot:") and method != "snapshot:":
        from .verify import emulator_snapshot_reset

        load = emulator_snapshot_reset(link.serial, method.removeprefix("snapshot:"), adb)

        def restore() -> Device | None:
            link.close()
            load()
            device = link.open()
            device.app(link.package).install(link.apk)
            link.launch()
            return device
        return restore
    raise ValueError("reset must be clear-data or snapshot:NAME")


def unattended(serial: str, package: str, apk: Path, out: Path, *, policy: Policy = Policy(),
               reset: str = "clear-data", max_actions: int = 300, max_minutes: float = 60,
               max_resets: int = 50, activity: str | None = None, resume: bool = False,
               verify_drafts: bool = True, adb: str = "adb", notify: Callable[[dict], None] | None = None,
               physical: bool = False) -> dict:
    """Explore ``package`` on emulator ``serial`` unattended, then draft, replay and report.

    ``physical`` accepts a device that is not an emulator. Its resets are ``clear-data`` only,
    and the run refuses a package already installed there (its data may be the owner's): the
    run installs the app itself, and a resumed run must be one that did.

    Writes ``graph.db``/``graph.json``, ``observations/``, ``autonomy.json``, ``events.json``,
    ``robots/`` (drafts, ``verification.json``) and ``REPORT.md``/``report.json`` under ``out``.
    """
    from .robots import write as write_robots
    from .store import GraphStore
    from .verify import verify

    if not serial.startswith("emulator-") and not physical:
        raise ValueError("unattended runs reset app data: only an emulator serial is accepted "
                         "(a device needs physical=True / --physical)")
    if physical and reset != "clear-data":
        raise ValueError("a physical device resets with clear-data only")
    if max_actions < 1 or not 0 < max_minutes <= 24 * 60:
        raise ValueError("budgets must be positive (at most 24 hours)")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if resume:
        if not (out / "graph.db").is_file():
            raise ValueError("--resume needs an existing run directory")
    elif out.exists():
        raise FileExistsError(f"{out} exists; pass --resume to continue it")
    link = _Link(serial, package, apk, activity)
    try:
        device = link.open()
        if physical and not resume and device.app(package).is_installed():
            raise ValueError(f"{package} is already installed on {serial}: its data may be the owner's, and "
                             "every reset clears it. Uninstall it first, or use an emulator")
    except BaseException:
        link.close()
        raise
    if not resume:
        out.mkdir(parents=True)
    with GraphStore(out / "graph.db") as store:
        try:
            if resume:
                doc = store.document()
                context = doc["context"]
                if (context.get("apk_sha256") != digest or context.get("package") != package
                        or context.get("serial") != serial):
                    raise ValueError("a resumed run must use the same emulator, package and APK")
                if physical and not context.get("installed_by_run"):
                    raise ValueError("a physical device can resume only a run that installed the app itself")
                if max_actions > doc["budgets"]["actions"]:
                    store.extend_budgets(actions=max_actions)
            else:
                store.initialize({"serial": serial, "package": package, "apk_sha256": digest,
                                  "policy": f"autonomy/{policy.name}", "reset": reset,
                                  **({"installed_by_run": True} if physical else {})},
                                 max_actions=max_actions, max_depth=100)
            device.app(package).install(apk)
            reset_device = reset_for(link, reset, adb)
            session = DiscoverySession(device, store, out / "observations", package=package,
                                       max_elapsed=max_minutes * 60, resume=resume, captures=3, confirm_after=0.3,
                                       permission=None if policy.permissions == "park" else policy.permissions)
            summary = Autonomy(session, reset=reset_device, reset_method=reset, policy=policy,
                               journal=out / "autonomy.json", max_resets=max_resets, notify=notify).run()
            (out / "graph.json").write_text(json.dumps(store.document(), indent=2, sort_keys=True) + "\n",
                                            encoding="utf-8")
            # New drafts make an earlier run's replay results stale, whether or not replay runs now.
            (out / "robots" / "verification.json").unlink(missing_ok=True)
            if store.document()["attempts"]:
                write_robots(out, out / "robots")
            if not verify_drafts or not store.document()["attempts"]:
                pass
            elif summary["stop"] == "reset_failed":  # Replay starts every method with a reset.
                _note_unverified(out, "the device could not be reset")
            else:
                try:
                    verify(out, out / "robots", open_app=lambda: link.device.app(package), reset=reset_device)
                    write_robots(out, out / "robots", verification_path=out / "robots" / "verification.json")
                except TapError as error:  # The drafts stay; the report says why they are unverified.
                    _note_unverified(out, f"{type(error).__name__}: {error}"[:300])
        finally:
            link.close()
            (out / "events.json").write_text(json.dumps({"dropped": link.dropped, "events": link.events},
                                                        indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {**summary, "report": write_report(out)}
