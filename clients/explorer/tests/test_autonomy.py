"""Unattended exploration against the fake gRPC daemon: no person approves anything here."""

import json
from pathlib import Path

import pytest
from tap_e2e import proto as pb
from tap_e2e import res
from test_state_machine import PACKAGE, Wizard, resource_of

from tap_explorer.autonomy import (Autonomy, Policy, abstract_facts, generate_values, markdown, report, unattended,
                                   write_report)
from tap_explorer.robots import build
from tap_explorer.robots import write as write_robots
from tap_explorer.score import evaluate
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore


def restart(machine):
    machine.page, machine.name, machine.pro, machine.accept, machine.error = "home", "", False, False, ""
    machine.focus = None


def drive(fake, machine):
    """Serve ``machine`` through the fake daemon; returns the reset callback (clear data + launch)."""
    fake.devices.snapshot = machine.snapshot()

    def responder(command):
        result = machine.execute(command)
        fake.devices.snapshot = machine.snapshot()
        return result

    fake.devices.responder = responder

    def reset():
        machine.resets = getattr(machine, "resets", 0) + 1
        restart(machine)
        fake.devices.snapshot = machine.snapshot()

    return reset


def explore(fake, device, tmp_path, machine, *, policy=Policy(), max_actions=600, resume=False, **options):
    reset = drive(fake, machine)
    with GraphStore(tmp_path / "graph.db") as graph:
        if not resume:
            graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=max_actions, max_depth=100)
        elif max_actions > graph.document()["budgets"]["actions"]:
            graph.extend_budgets(actions=max_actions)
        session = DiscoverySession(device, graph, tmp_path / "observations", package=PACKAGE, resume=resume)
        summary = Autonomy(session, reset=reset, reset_method="fake-restart", policy=policy,
                           journal=tmp_path / "autonomy.json", **options).run()
        return summary, graph.document()


def oracle(machine, doc):
    """(false merges, false splits) of the run's states against the machine's business state."""
    mappings, inverse = {}, {}
    for id, state in doc["states"].items():
        for observation in state["observations"]:
            key = machine.frames[doc["observations"][observation]["evidence"]["after_snapshot_id"]]
            mappings.setdefault(id, set()).add(key)
            inverse.setdefault(key, set()).add(id)
    return (sum(len(keys) > 1 for keys in mappings.values()), sum(len(ids) > 1 for ids in inverse.values()))


PAGES = {"about", "explain", "home", "identity", "options", "receipt", "review"}
FOCUSED = Policy(name="focused", exhaustive=False)


def test_unattended_wizard_reaches_every_page_without_a_person(fake, pilot_device, tmp_path):
    machine = Wizard()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, max_actions=800)
    assert summary["stop"] == "frontier_resolved"
    assert oracle(machine, doc) == (0, 0)
    assert {key[0] for key in machine.oracle} == PAGES
    assert all(a["status"] == "succeeded" for a in doc["attempts"].values())
    assert len(machine.mutations) == len(doc["attempts"])  # No input outside a persisted attempt.
    assert all(action["approval"].startswith("policy default: tier ")
               for action in doc["actions"].values() if action["approval"])
    assert {action["scenario"]["value"] for action in doc["actions"].values()
            if action["verb"] == "fill" and action["scenario"]} == {"", "Ada", "Grace"}
    # Exhaustive: every business state of the wizard, with both names carried through.
    assert len(doc["states"]) == 50 and summary["tiers"]["4"] > 0
    assert not any(action["verb"] == "tap" and action["status"] != "blocked" and action["target"]["label"] in ("", "Ada", "Grace")
                   for action in doc["actions"].values())  # Never a focus tap on the name field.


def test_focused_run_settles_the_wizards_preconditions_without_a_person(fake, pilot_device, tmp_path):
    machine = Wizard()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved" and summary["tiers"]["4"] == 0
    assert {key[0] for key in machine.oracle} == PAGES
    assert oracle(machine, doc) == (0, 0)
    assert summary["tiers"]["2"] > 0 and summary["tiers"]["3"] > 0
    methods = {(screen["id"], method["name"]): method for screen in build(tmp_path)["screens"]
               for method in screen["methods"]}
    rejected = methods[("ChooseAPlanRobot", "tap_next_rejected")]
    review = methods[("ChooseAPlanRobot", "tap_next_to_review_request")]
    named = methods[("YourNameRobot", "tap_next_rejected")]
    assert (rejected["when_mode"], sorted(rejected["when"])) == ("all", ["terms unchecked", "tier checked"])
    assert (review["when_mode"], sorted(review["when"])) == ("any", ["terms checked", "tier unchecked"])
    # The tier-3 experiments separated the name from the summary line that changed with it.
    assert named["when"] == ["name is empty"] and not named["alternatives"]
    # Both names were carried to the pages that show them back, and either one passes.
    assert methods[("YourNameRobot", "tap_next_to_choose_a_plan")]["when"] == ["name is not empty"]
    key = json.loads((Path(__file__).parents[1] / "benchmarks" / "keys" / "wizard.json").read_text())
    scored = evaluate(tmp_path, {**key, "package": PACKAGE})["draft"]
    assert (scored["preconditions"]["right"], scored["preconditions"]["keyed"]) == (4, 4)
    assert (scored["checks"]["recall"], scored["checks"]["precision"]) == (1.0, 1.0)
    assert (scored["methods"]["recall"], scored["methods"]["precision"]) == (1.0, 1.0)


class Shop(Wizard):
    """The review page also offers to delete the request: the default policy holds it back."""

    def snapshot(self):
        result = super().snapshot()
        if self.page == "review":
            result.nodes.add(ref=f"e{len(result.nodes) + 1}", window_package=PACKAGE, depth=1,
                             resource_name=f"{PACKAGE}:id/discard", text="Delete request",
                             class_name="android.widget.Button", interactive=True,
                             flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE],
                             selector=res("discard").to_proto())
        return result


def test_policy_holds_back_a_risky_label_and_reports_it(fake, pilot_device, tmp_path):
    machine = Shop()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert not any(target == "discard" for _, target in machine.mutations)
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    [held] = journal["held"].values()
    assert held["label"] == "Delete request" and "delete" in held["reason"]
    morning = write_report(tmp_path)
    assert morning["held"][0]["label"] == "Delete request"
    assert "Delete request" in (tmp_path / "REPORT.md").read_text()
    allowed = Policy(name="allow-discard", exhaustive=False, allow=("discard",))
    assert allowed.verdict(doc["actions"][held["action"]]) is None


class SingleActivity(Wizard):
    """One activity holding the pages: Back on any of them closes the app."""

    BACK = {page: "launcher" for page in Wizard.BACK}


def test_back_that_left_the_app_is_not_retried_in_every_context(fake, pilot_device, tmp_path):
    machine = SingleActivity()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} == PAGES
    backs = [attempt for attempt in doc["attempts"].values() if doc["actions"][attempt["action"]]["verb"] == "back"]
    # Once per page (each one a reset), not once per name/plan/terms context of each page.
    assert len(backs) == len(PAGES)


class Permission(Wizard):
    """Review on the Basic plan asks for a system permission first (a foreign window), the way
    Loop's Save does when a reminder is set: whether the control leaves depends on state."""

    def execute(self, command) -> pb.CommandResult | None:
        op = command.WhichOneof("op")
        if (op == "tap" and self.page == "options" and not self.pro
                and resource_of(command.tap.selector.node) == "next"):
            self.mutations.append((op, "next"))
            self.page, self.exits = "launcher", getattr(self, "exits", 0) + 1
            return None
        return super().execute(command)


def test_a_tap_that_left_the_app_once_is_tried_in_another_context(fake, pilot_device, tmp_path):
    machine = Permission()
    summary, _ = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} >= {"review", "receipt"}  # Pro + terms passes.
    assert 2 <= getattr(machine, "exits") <= FOCUSED.max_contexts  # Bounded: each exit costs a reset.


class Contact(Wizard):
    """An optional email on the name page: Continue refuses one it cannot parse. It is not kept
    once the page is left, so no state hides it."""

    def __init__(self):
        super().__init__()
        self.email = ""

    def key(self) -> tuple:
        return (*super().key(), self.email) if self.page == "identity" else super().key()

    def snapshot(self):
        result = super().snapshot()
        if self.page == "identity":
            result.nodes.add(ref=f"e{len(result.nodes) + 1}", window_package=PACKAGE, depth=1,
                             resource_name=f"{PACKAGE}:id/email", text=self.email,
                             class_name="android.widget.EditText", interactive=True,
                             flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE], selector=res("email").to_proto())
            self.oracle.add(self.key())
            self.frames[result.snapshot_id] = self.key()
        return result

    def execute(self, command):
        op = command.WhichOneof("op")
        target = resource_of(getattr(command, op).selector.node) if op in ("tap", "set_text") else None
        if op == "set_text" and target == "email":
            self.mutations.append((op, target))
            self.email, self.error = command.set_text.text, ""
            return None
        if op == "tap" and target == "next" and self.page == "identity" and self.name and self.email \
                and "@" not in self.email:
            self.mutations.append((op, target))
            self.error = "Enter a valid email."
            return None
        page = self.page
        result = super().execute(command)
        if self.page != page:
            self.email = ""
        return result


def test_a_malformed_value_reaches_the_validation_a_valid_one_passes(fake, pilot_device, tmp_path):
    machine = Contact()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert any(key[0] == "identity" and key[4] == "Enter a valid email." for key in machine.oracle)
    assert {key[0] for key in machine.oracle} == PAGES
    assert oracle(machine, doc) == (0, 0)


class Browser(Wizard):
    """About opens another app: no node of the package is on screen until a reset."""

    def snapshot(self):
        if self.page != "about":
            return super().snapshot()
        result = pb.ScreenSnapshotResponse(snapshot_id=len(self.frames) + 1)
        result.nodes.add(ref="e1", window_package="com.android.browser", depth=0, text="example.com",
                         class_name="android.widget.FrameLayout", flags=[pb.FLAG_ENABLED])
        self.frames[result.snapshot_id] = ("browser",)
        return result


def test_leaving_the_app_parks_the_action_and_resets(fake, pilot_device, tmp_path):
    machine = Browser()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    parked = {item["label"]: item for item in journal["parked"].values()}
    assert sorted(parked) == ["About", "Back"]  # Back from home leaves the app too.
    assert all(item["reason"].startswith("left the app") for item in parked.values())
    assert [target for _, target in machine.mutations].count("about") == 1  # Never tried again.
    assert any("without verified destination" in episode["reason"] for episode in doc["episodes"])
    assert {key[0] for key in machine.oracle} == PAGES - {"about"}


def test_an_uncertain_outcome_is_never_replayed_and_a_reset_lifts_the_gate(fake, pilot_device, tmp_path):
    machine = Wizard()
    reset = drive(fake, machine)
    serve = fake.devices.responder
    lost = []

    def responder(command):
        if command.WhichOneof("op") == "tap" and "explain" in str(command.tap.selector) and not lost:
            lost.append(command)
            return pb.CommandResult(error=pb.Error(code=pb.ERR_INDETERMINATE))
        return serve(command)

    fake.devices.responder = responder
    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=600, max_depth=100)
        session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
        summary = Autonomy(session, reset=reset, reset_method="fake-restart", policy=FOCUSED,
                           journal=tmp_path / "autonomy.json").run()
        doc = graph.document()
    assert lost and summary["stop"] == "frontier_resolved"
    [uncertain] = [a for a in doc["attempts"].values() if a["status"] == "indeterminate"]
    assert doc["actions"][uncertain["action"]]["status"] == "uncertain"
    assert [a["action"] for a in doc["attempts"].values()].count(uncertain["action"]) == 1
    assert any("uncertain outcome" in episode["reason"] for episode in doc["episodes"])
    assert {key[0] for key in machine.oracle} == PAGES  # Plan details reached from another state.


class Remembers(Wizard):
    """Hidden state: after About, a new request starts with the last name filled in."""

    def __init__(self):
        super().__init__()
        self.seen_about = False

    def execute(self, command):
        before = self.page
        result = super().execute(command)
        if self.page == "about":
            self.seen_about = True
        if before == "home" and self.page == "identity" and self.seen_about:
            self.name = "Ada"
        return result


def test_a_diverging_route_continues_from_where_it_landed(fake, pilot_device, tmp_path):
    machine = Remembers()
    reset = drive(fake, machine)
    original = reset

    def forget():
        machine.seen_about = False
        original()

    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=600, max_depth=100)
        session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
        # Without Back, a dead end needs a reset (no backing out of it), which forgets About.
        no_back = Policy(name="no-back", exhaustive=False, deny=("Back",))
        summary = Autonomy(session, reset=forget, reset_method="fake-restart", policy=no_back,
                           journal=tmp_path / "autonomy.json").run()
        doc = graph.document()
        conflicts = session.behavioral_conflicts()
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    # The diverging input succeeded and was observed: no reset, the run goes on from there.
    assert journal["diverged"] and summary["diverged"] == journal["diverged"]
    assert not any(episode["reason"] == "observed route diverged" for episode in doc["episodes"])
    assert conflicts  # New request → two outcomes: kept as evidence, excluded from routing.
    # Every route beyond home crosses that conflicting edge. Replaying the recorded inputs
    # from a reset reaches the rest: nothing is left unreachable.
    assert summary["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} == PAGES
    assert oracle(machine, doc) == (0, 0)
    assert not journal["parked"]
    assert journal["replays"] and all(replay["result"] == "reached" for replay in journal["replays"])


def test_a_crashed_run_resumes_without_replaying_its_unfinished_intent(fake, pilot_device, tmp_path):
    machine = Wizard()
    first, _ = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED, max_actions=12)
    assert first["stop"] == "action_budget"
    with GraphStore(tmp_path / "graph.db") as graph:
        # A crash after the intent was persisted, before the outcome: the action may have run.
        graph.extend_budgets(actions=13)
        doc = graph.document()
        candidate = next(id for id, action in sorted(doc["actions"].items())
                         if action["status"] == "blocked" and action["verb"] == "tap"
                         and not action["target"].get("blocked_reason"))
        before = max(doc["states"][doc["actions"][candidate]["source"]]["observations"])
        graph.approve_action(candidate, "policy focused: tier 1")
        graph.begin_attempt("attempt-000013", candidate, before)
    second, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED, max_actions=600, resume=True)
    assert second["stop"] == "frontier_resolved"
    assert doc["attempts"]["attempt-000013"]["status"] == "indeterminate"
    assert [a["action"] for a in doc["attempts"].values()].count(candidate) == 1
    assert "resume a stopped run" in [episode["reason"] for episode in doc["episodes"]]
    assert {key[0] for key in machine.oracle} == PAGES
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    assert [run["stop"] for run in journal["runs"]] == ["action_budget", "frontier_resolved"]


def test_a_resumed_run_keeps_its_policy(fake, pilot_device, tmp_path):
    explore(fake, pilot_device, tmp_path, Wizard(), policy=FOCUSED, max_actions=3)
    with pytest.raises(ValueError, match="keep the policy"):
        explore(fake, pilot_device, tmp_path, Wizard(), policy=Policy(), resume=True)


def test_the_morning_report_names_the_stop_and_the_open_work(fake, pilot_device, tmp_path):
    explore(fake, pilot_device, tmp_path, Shop(), policy=FOCUSED)
    summary = report(tmp_path)
    assert summary["stop"] == "frontier_resolved" and summary["attempts"]["succeeded"] > 0
    text = markdown(summary)
    assert "Stopped: **frontier_resolved**" in text and "Held back by the policy" in text


def test_the_report_lists_a_control_parked_in_several_states_once(fake, pilot_device, tmp_path):
    explore(fake, pilot_device, tmp_path, Shop(), policy=FOCUSED)
    summary = report(tmp_path)
    left = {"label": "Back", "value": None, "reason": "left the app"}
    summary["parked"] = [left, left, left, {"label": "Pay", "value": None, "reason": "timed out"}]
    rate = {"label": "Rate", "reason": "label matches hold word 'rate'"}
    summary["held"] = [{**rate, "sources": ["s-1"]}, {**rate, "sources": ["s-1", "s-2"]}]
    text = markdown(summary)
    assert "- 'Back': left the app (3 states)" in text and "- 'Pay': timed out\n" in text
    assert text.count("'Rate'") == 1 and "'Rate': label matches hold word 'rate' (2 state(s))" in text


class Interstitial(Wizard):
    """A system window (Android's one-time "Viewing full screen" notice) covers the app on
    every launch until someone dismisses it on the device."""

    def snapshot(self):
        result = pb.ScreenSnapshotResponse(snapshot_id=len(self.frames) + 1)
        result.nodes.add(ref="e1", window_package="android", depth=0, text="Viewing full screen",
                         class_name="android.widget.FrameLayout", flags=[pb.FLAG_ENABLED])
        self.frames[result.snapshot_id] = ("interstitial",)
        return result


def test_a_window_covering_the_app_after_every_reset_stops_the_run_and_is_named(fake, pilot_device, tmp_path):
    machine = Interstitial()
    summary, _ = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "reset_failed" and getattr(machine, "resets") == 3 and not machine.mutations
    text = markdown(report(tmp_path))
    assert "showed a window of `android` (`observations/obs-" in text and "Nothing." not in text


def test_a_reset_the_device_refuses_is_retried_then_stops_the_run_with_a_report(fake, pilot_device, tmp_path):
    from tap_e2e import WaitTimeoutError

    machine = Browser()  # About leaves the app, so a second episode is needed.
    reset = drive(fake, machine)

    def refused():
        if getattr(machine, "resets", 0) >= 1:  # A permission dialog left in the app's task.
            raise WaitTimeoutError(f"{PACKAGE} to have no process, task or activity after force-stop", "fake", 10_000)
        reset()

    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=600, max_depth=100)
        session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
        summary = Autonomy(session, reset=refused, reset_method="fake-restart", policy=FOCUSED,
                           journal=tmp_path / "autonomy.json").run()
    assert summary["stop"] == "reset_failed" and summary["episodes"] == 1
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    assert [entry.get("error", "")[:16] for entry in journal["resets"][-3:]] == ["WaitTimeoutError"] * 3
    assert "(last: WaitTimeoutError: Timed out" in markdown(report(tmp_path))


class Hidden(Wizard):
    """Branches only other inputs reach: a long press on the terms opens their text, Save sits
    below the fold of a scrolling review page, Back on a filled name asks to discard it, and the
    name field's keyboard action (offered only while it has focus) suggests names for a typed one
    or says one is needed."""

    BACK = {**Wizard.BACK, "terms": "options", "discard": "identity", "suggestions": "identity"}

    def __init__(self):
        super().__init__()
        self.scrolled = False
        self.focus = None

    def key(self) -> tuple:
        if self.page in ("terms", "discard", "suggestions"):
            return (self.page,)
        return (*super().key(), self.scrolled) if self.page == "review" else super().key()

    def _page(self, title, buttons):
        result = pb.ScreenSnapshotResponse(snapshot_id=len(self.frames) + 1)
        for resource, text in (("title", title), *buttons):
            button = resource != "title"
            result.nodes.add(ref=f"e{len(result.nodes) + 1}", window_package=PACKAGE, depth=1,
                             resource_name=f"{PACKAGE}:id/{resource}", text=text, interactive=button,
                             class_name="android.widget.Button" if button else "android.widget.TextView",
                             flags=[pb.FLAG_ENABLED, *([pb.FLAG_CLICKABLE] if button else [])],
                             **({"selector": res(resource).to_proto()} if button else {}))
        self.oracle.add(self.key())
        self.frames[result.snapshot_id] = self.key()
        return result

    def snapshot(self):
        if self.page == "terms":
            return self._page("Fictional terms", [("close_terms", "Close")])
        if self.page == "discard":
            return self._page("Discard this request?", [("discard", "Discard"), ("keep", "Keep editing")])
        if self.page == "suggestions":
            return self._page("Names like yours", [("close_suggestions", "Close")])
        result = super().snapshot()
        nodes = list(result.nodes)
        for node in nodes:
            if self.focus and node.resource_name.endswith(f"/{self.focus}"):
                node.flags.append(pb.FLAG_FOCUSED)
        if self.page == "options":
            for node in nodes:
                if node.resource_name.endswith("/terms"):
                    node.flags.append(pb.FLAG_LONG_CLICKABLE)
        if self.page == "review":
            nodes = [node for node in nodes if self.scrolled or not node.resource_name.endswith("/confirm")]
            nodes.append(pb.ScreenNode(ref="scroll", window_package=PACKAGE, depth=1,
                                       resource_name=f"{PACKAGE}:id/review_scroll", class_name="android.widget.ScrollView",
                                       interactive=True, flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE],
                                       selector=res("review_scroll").to_proto()))
        del result.nodes[:]
        result.nodes.extend(nodes)
        return result

    def back(self):
        if self.page == "identity" and self.name:
            self.page = "discard"
        else:
            super().back()

    def gesture(self, op, target):
        if op == "long_tap" and target == "terms" and self.page == "options":
            self.page = "terms"
        elif op == "scroll" and target == "review_scroll" and self.page == "review":
            self.scrolled = True

    def execute(self, command):
        page, op = self.page, command.WhichOneof("op")
        if op == "perform_ime_action":
            target = resource_of(command.perform_ime_action.selector.node)
            if page != "identity" or target != "name" or self.focus != "name":
                return pb.CommandResult(error=pb.Error(code=pb.ERR_ACTION_REJECTED))  # Not offered.
            self.mutations.append(("ime", target))
            if self.name:
                self.page, self.focus = "suggestions", None
            else:
                self.error = "Type a name to see suggestions."
            return None
        if op == "tap":
            target = resource_of(command.tap.selector.node)
            if page == "terms" and target == "close_terms":
                self.mutations.append(("tap", target))
                self.page = "options"
                return None
            if page == "suggestions" and target == "close_suggestions":
                self.mutations.append(("tap", target))
                self.page = "identity"
                return None
            if page == "discard" and target in ("discard", "keep"):
                self.mutations.append(("tap", target))
                if target == "keep":
                    self.page = "identity"
                else:
                    self.page, self.name, self.pro, self.accept, self.error = "home", "", False, False, ""
                return None
        result = super().execute(command)
        if self.page != "review":
            self.scrolled = False
        if self.page != page:
            self.focus = None
        elif op == "tap" and page == "identity" and resource_of(command.tap.selector.node) == "name":
            self.focus = "name"
        return result


class Typing(Hidden):
    """Typing a name focuses the field, as on Android: its keyboard action is offered right after
    a fill, in states where the field was never tapped (Loop's form)."""

    def execute(self, command):
        result = super().execute(command)
        if command.WhichOneof("op") == "set_text" and self.page == "identity":
            self.focus = "name"
        return result


def test_a_route_never_crosses_a_keyboard_action_it_cannot_focus_again(fake, pilot_device, tmp_path):
    machine = Typing()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} == PAGES | {"terms", "discard", "suggestions"}
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    # Only Back from home, which leaves the app; no route or focus failure parks a control.
    assert [item["label"] for item in journal["parked"].values()] == ["Back"]
    assert len(machine.mutations) == len(doc["attempts"])


class OldDevice(Hidden):
    """API 29: the driver refuses every keyboard action before input."""

    def execute(self, command):
        if command.WhichOneof("op") == "perform_ime_action":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_UNSUPPORTED))
        return super().execute(command)


def test_a_device_without_the_keyboard_action_stops_offering_it(fake, pilot_device, tmp_path):
    machine = OldDevice()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    submits = [attempt for attempt in doc["attempts"].values() if doc["actions"][attempt["action"]]["verb"] == "submit"]
    assert [attempt["status"] for attempt in submits] == ["failed_before_input"]
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    assert list(journal["unsupported_verbs"]) == ["submit"]
    assert "Not available on this device: `submit`" in markdown(report(tmp_path))
    assert {key[0] for key in machine.oracle} == PAGES | {"terms", "discard"}


def test_long_press_scroll_and_back_reach_branches_taps_cannot(fake, pilot_device, tmp_path):
    machine = Hidden()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} == PAGES | {"terms", "discard", "suggestions"}
    # The keyboard action was found by focusing the field, then tried with and without a name.
    assert ("ime", "name") in machine.mutations
    assert any(key[0] == "identity" and key[-1] == "Type a name to see suggestions." for key in machine.oracle)
    assert oracle(machine, doc) == (0, 0)
    assert len(machine.mutations) == len(doc["attempts"])
    # Save was found below the fold, and Back from home left the app once and was parked.
    assert any(key[0] == "review" and key[-1] for key in machine.oracle)
    journal = json.loads((tmp_path / "autonomy.json").read_text())
    assert [item["label"] for item in journal["parked"].values()] == ["Back"]
    model = build(tmp_path)
    methods = {(screen["id"], method["name"]) for screen in model["screens"] for method in screen["methods"]}
    assert ("ChooseAPlanRobot", "long_tap_terms") in methods
    assert ("ReviewRequestRobot", "scroll_review_scroll") in methods
    assert ("YourNameRobot", "press_back_to_discard_this_request") in methods
    assert ("DiscardThisRequestRobot", "tap_discard") in methods
    assert any(screen == "YourNameRobot" and name.startswith("submit_name") for screen, name in methods)
    write_robots(tmp_path, tmp_path / "robots")
    source = (tmp_path / "robots" / "robots.py").read_text()
    compile(source, "robots.py", "exec")
    assert "self.app.device.press_back()" in source and ".long_tap()" in source
    assert ".scroll(Direction.DOWN)" in source and "field.ime_action()" in source


def test_a_physical_device_needs_its_flag_and_an_app_the_run_installs(fake, tmp_path, monkeypatch):
    from conftest import _fake

    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", _fake.TOKEN)
    monkeypatch.setattr("tap_explorer.session.time.sleep", lambda _seconds: None)
    machine = Wizard()
    fake.apps.on_clear = drive(fake, machine)
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk")
    out = tmp_path / "run"
    with pytest.raises(ValueError, match="--physical"):
        unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED)
    with pytest.raises(ValueError, match="clear-data only"):
        unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED, physical=True, reset="snapshot:ready")
    # Installed before the run: the owner's data, which every reset would clear.
    fake.apps.installed = True
    with pytest.raises(ValueError, match="already installed"):
        unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED, physical=True)
    assert not out.exists() and not fake.apps.install_parts and not fake.apps.clears

    fake.apps.installed = False
    first = unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED, max_actions=10, physical=True, verify_drafts=False)
    assert first["stop"] == "action_budget" and fake.apps.install_parts
    # Installed now, by this run: resuming it is allowed.
    second = unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED, max_actions=20, physical=True,
                        resume=True, verify_drafts=False)
    assert second["stop"] in ("action_budget", "frontier_resolved")


def test_auto_explores_drafts_replays_and_reports_through_the_daemon(fake, tmp_path, monkeypatch):
    """``unattended`` end to end: install, clear-data resets, drafts, replay, report, resume."""
    from conftest import _fake

    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", _fake.TOKEN)
    monkeypatch.setattr("tap_explorer.session.time.sleep", lambda _seconds: None)  # The emulator settle wait.
    machine = Wizard()
    fake.apps.on_clear = drive(fake, machine)
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk")
    out = tmp_path / "run"
    with pytest.raises(ValueError, match="emulator"):
        unattended("85e49002", PACKAGE, apk, out, policy=FOCUSED)
    assert not out.exists() and not fake.apps.install_parts

    first = unattended("emulator-5560", PACKAGE, apk, out, policy=FOCUSED, max_actions=20)
    assert first["stop"] == "action_budget" and fake.apps.install_parts and fake.apps.clears
    with pytest.raises(FileExistsError):
        unattended("emulator-5560", PACKAGE, apk, out, policy=FOCUSED)
    other = tmp_path / "other.apk"
    other.write_bytes(b"another build")
    with pytest.raises(ValueError, match="same emulator, package and APK"):
        unattended("emulator-5560", PACKAGE, other, out, policy=FOCUSED, resume=True)

    second = unattended("emulator-5560", PACKAGE, apk, out, policy=FOCUSED, max_actions=600, resume=True)
    assert second["stop"] == "frontier_resolved" and second["report"]["stop"] == "frontier_resolved"
    assert {key[0] for key in machine.oracle} == PAGES
    for name in ("graph.json", "events.json", "autonomy.json", "report.json", "REPORT.md",
                 "robots/verification.json"):
        assert (out / name).is_file(), name
    verification = json.loads((out / "robots" / "verification.json").read_text())
    model = json.loads((out / "robots" / "robots.json").read_text())
    drafted = {f"{screen['name']}.{method['name']}" for screen in model["screens"] for method in screen["methods"]}
    assert set(verification["methods"]) == drafted and any(key.endswith(".press_back") for key in drafted)
    assert {value["status"] for value in verification["methods"].values()} == {"verified"}
    assert json.loads((out / "events.json").read_text())["events"]
    assert all(request.app.package_name == PACKAGE for request in fake.apps.clears)


def test_a_device_that_stops_resetting_still_gets_its_drafts_and_report(fake, tmp_path, monkeypatch):
    """Resets that start failing mid-run (a permission dialog left in the app's task) end the
    run with drafts and a report, and replay, which resets before every method, is not run."""
    from conftest import _fake
    from tap_e2e import WaitTimeoutError

    import tap_explorer.autonomy as autonomy

    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", _fake.TOKEN)
    monkeypatch.setattr("tap_explorer.session.time.sleep", lambda _seconds: None)
    machine = Browser()
    restart_machine = drive(fake, machine)
    stuck = WaitTimeoutError(f"{PACKAGE} to have no process, task or activity after force-stop", "emulator-5560", 10_000)

    def failing_reset_for(link, method, adb="adb"):
        def reset():
            if getattr(machine, "resets", 0) >= 1:
                raise stuck
            restart_machine()
        return reset

    monkeypatch.setattr(autonomy, "reset_for", failing_reset_for)
    apk = tmp_path / "app.apk"
    apk.write_bytes(b"apk")
    result = unattended("emulator-5560", PACKAGE, apk, tmp_path / "run", policy=FOCUSED)
    assert result["stop"] == "reset_failed" and (tmp_path / "run" / "robots" / "robots.json").is_file()
    text = (tmp_path / "run" / "REPORT.md").read_text()
    assert "Replay: not run (the device could not be reset)" in text and "(last: WaitTimeoutError" in text

    # A device error during replay itself leaves the drafts unverified, with the reason.
    monkeypatch.setattr(autonomy, "reset_for", lambda link, method, adb="adb": drive(fake, Wizard()))
    monkeypatch.setattr("tap_explorer.verify.verify", lambda *args, **kwargs: (_ for _ in ()).throw(stuck))
    result = unattended("emulator-5560", PACKAGE, apk, tmp_path / "second", policy=FOCUSED, max_actions=20)
    assert result["stop"] == "action_budget"
    assert "Replay: not run (WaitTimeoutError: Timed out" in (tmp_path / "second" / "REPORT.md").read_text()

    # Resumed, with replay failing again: the first replay's results are stale, never reported.
    stale = tmp_path / "second" / "robots" / "verification.json"
    stale.write_text(json.dumps({"format": "tap-robots-verification/1", "package": PACKAGE, "start": "",
                                 "methods": {"X.y": {"status": "verified", "evidence": [], "steps": []}}}))
    unattended("emulator-5560", PACKAGE, apk, tmp_path / "second", policy=FOCUSED, max_actions=30, resume=True)
    text = (tmp_path / "second" / "REPORT.md").read_text()
    assert not stale.exists() and "verified" not in text.split("## Needs a person")[0].replace("unverified", "")


# --- policy, values and contexts -----------------------------------------------------------


def action(label, resource=None, verb="tap"):
    selector = {"node": {"resource": {"name": f"{PACKAGE}:id/{resource}"}}} if resource else None
    return {"verb": verb, "target": {"label": label, "selector": selector}}


@pytest.mark.parametrize("label,resource,held", [
    ("Delete habit", None, True), ("Log out", None, True), ("logout", None, True),
    ("Settings", "action_logout", True), ("Share", None, True), ("Deleted items", None, False),
    ("Save", "buttonSave", False), ("Sort order", None, False), ("Recall", None, False)])
def test_hold_words_match_whole_words_of_labels_and_resource_names(label, resource, held):
    assert (Policy().verdict(action(label, resource)) is not None) == held


def test_allow_and_deny_name_labels_or_resources():
    policy = Policy(allow=("Delete draft",), deny=("about",))
    assert policy.verdict(action("Delete draft")) is None
    assert policy.verdict(action("Delete habit")) is not None
    assert policy.verdict(action("About", "about")) == "denied by the policy"


@pytest.mark.parametrize("words,values", [
    (["email"], ("", "ada@example.com", "grace@example.org", "ada.example.com")), (["target"], ("", "1", "42", "0")),
    (["phone", "number"], ("", "5550100", "5550199", "12")), (["habit", "name"], ("", "Ada", "Grace"))])
def test_fill_values_follow_what_the_field_is_for(words, values):
    assert generate_values(words) == values


def test_field_values_override_the_generated_ones():
    policy = Policy(values=("x",), field_values={"target": ("7",)})
    assert policy.values_for(action("", "target", "fill")) == ("7",)
    assert policy.values_for(action("", "name", "fill")) == ("x",)


def test_a_policy_file_is_strict_and_round_trips():
    policy = Policy(name="nightly", allow=("Delete draft",), field_values={"target": ("1", "2")}, max_contexts=4)
    assert Policy.load(policy.to_dict()) == policy
    with pytest.raises(ValueError, match="unknown policy keys"):
        Policy.load({"format": "tap-explorer-policy/1", "hold_words": []})
    with pytest.raises(ValueError, match="format"):
        Policy.load({"name": "x"})
    with pytest.raises(ValueError, match="distinct"):
        Policy(values=("a", "a"))
    for wrong in ({"hold": "delete"}, {"field_values": {"name": "Ada"}}, {"exhaustive": "yes"},
                  {"max_contexts": True}, {"values": [1]}):
        with pytest.raises(ValueError, match="policy"):
            Policy.load({"format": "tap-explorer-policy/1", **wrong})


def test_abstract_facts_keep_emptiness_checks_rows_and_typed_values_but_not_other_text():
    def node(resource, cls, text="", flags=("ENABLED",), depth=1):
        return {"package": PACKAGE, "resource": f"{PACKAGE}:id/{resource}" if resource else None, "class": cls,
                "text": text, "flags": list(flags), "depth": depth, "hint": None}
    nodes = [node("name", "android.widget.EditText", "Ada"), node("terms", "android.widget.CheckBox",
             flags=("ENABLED", "CHECKABLE", "CHECKED")), node("summary", "android.widget.TextView", "Name: Ada"),
             node("list", "androidx.recyclerview.widget.RecyclerView"),
             node("row", "android.widget.EditText", "", depth=2)]
    assert abstract_facts(nodes, PACKAGE) == {("input", "name", True), ("checked", "terms", True),
                                              ("rows", "list", True)}
    # The explorer's own values shown back (in the field or a summary) are contexts; one
    # character is too short to recognise, and rows are still data.
    rows = nodes + [node("row_title", "android.widget.TextView", "Grace", depth=2)]
    assert abstract_facts(rows, PACKAGE, typed=["Ada", "Grace", "1"]) == {
        ("input", "name", True), ("checked", "terms", True), ("rows", "list", True), ("shows", "Ada")}
    assert ("shows", "Ada") not in abstract_facts(
        [node("summary", "android.widget.TextView", "Adam")], PACKAGE, typed=["Ada"])





class Measurable(Wizard):
    """Loop's measurable-habit form: Save needs a name and a target; the unit and the notes are
    optional. Which fields Save needs only shows when a field is left empty on its own."""

    FIELDS = ("name", "question", "unit", "target", "notes", "reminder")

    def __init__(self):
        super().__init__()
        self.values: dict[str, str] = dict.fromkeys(self.FIELDS, "")

    def key(self) -> tuple:
        if self.page != "form":
            return (self.page,)
        return (self.page, *(self.values[field] for field in self.FIELDS), self.error)

    def snapshot(self):
        result = pb.ScreenSnapshotResponse(snapshot_id=len(self.frames) + 1)
        if self.page == "launcher":
            result.nodes.add(ref="e1", window_package="com.android.launcher", depth=0, text="Home screen",
                             class_name="android.widget.FrameLayout", flags=[pb.FLAG_ENABLED])
            self.frames[result.snapshot_id] = ("launcher",)
            return result

        def node(resource, text, *, button=False, field=False):
            flags = [pb.FLAG_ENABLED] + ([pb.FLAG_CLICKABLE] if button or field else [])
            result.nodes.add(ref=f"e{len(result.nodes) + 1}", window_package=PACKAGE, depth=1,
                             resource_name=f"{PACKAGE}:id/{resource}", text=text,
                             class_name="android.widget.EditText" if field else
                             "android.widget.Button" if button else "android.widget.TextView",
                             interactive=button or field, flags=flags,
                             **({"selector": res(resource).to_proto()} if button or field else {}))
        if self.page == "home":
            node("title", "Habits")
            node("add", "Add habit", button=True)
        else:
            node("title", "Create habit")
            for field in self.FIELDS:
                node(field, self.values[field], field=True)
            node("validation", self.error)
            node("save", "Save", button=True)
        self.oracle.add(self.key())
        self.frames[result.snapshot_id] = self.key()
        return result

    def back(self):
        self.page, self.error = {"form": "home", "home": "launcher"}[self.page], ""

    def execute(self, command):
        op = command.WhichOneof("op")
        if op == "press_key" and command.press_key.key_code == 4:
            self.mutations.append(("back", None))
            self.back()
            return None
        if op not in ("tap", "set_text"):
            return None
        target = resource_of(getattr(command, op).selector.node)
        self.mutations.append((op, target))
        if op == "set_text":
            self.values[str(target)], self.error = command.set_text.text, ""
        elif target == "add":
            self.page, self.error = "form", ""
            self.values = dict.fromkeys(self.FIELDS, "")
        elif target == "save":
            if self.values["name"] and self.values["target"]:
                self.page = "home"
            else:
                self.error = "Required."
        return None


def test_deciding_experiments_find_which_fields_save_needs(fake, pilot_device, tmp_path):
    machine = Measurable()
    summary, doc = explore(fake, pilot_device, tmp_path, machine, policy=FOCUSED)
    assert summary["stop"] == "frontier_resolved"
    assert oracle(machine, doc) == (0, 0)
    methods = {method["name"]: method for screen in build(tmp_path)["screens"] for method in screen["methods"]
               if method["verb"] == "tap" and "save" in method["name"]}
    saved = next(method for name, method in methods.items() if not name.endswith("rejected"))
    rejected = methods["tap_save_rejected"]
    assert (saved["when_mode"], sorted(saved["when"])) == ("all", ["name is not empty", "target is not empty"])
    assert (rejected["when_mode"], sorted(rejected["when"])) == ("any", ["name is empty", "target is empty"])
    assert not saved["alternatives"] and not rejected["alternatives"]
    assert summary["decisions"] > 0
