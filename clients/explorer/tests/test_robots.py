import ast
import json

from tap_explorer.cli import main
from tap_explorer.robots import (
    _alternatives,
    _either,
    _screen_names,
    _untested,
    _widen,
    ask,
    build,
    precondition_choices,
    python,
    render_selector,
    report,
    snake,
    write,
)

PACKAGE = "p"


def _n(depth, cls, resource=None, text=None, flags=("ENABLED",), **extra):
    return {"package": PACKAGE, "depth": depth, "class": f"android.widget.{cls}",
            "resource": f"{PACKAGE}:id/{resource}" if resource else None, "text": text,
            "description": None, "hint": extra.pop("hint", None), "flags": list(flags), **extra}


def _res(name):
    return {"node": {"all_of": {"nodes": [{"resource": {"name": name}}, {"match": {
        "mode": "MATCH_EXACT", "property": "PROPERTY_PACKAGE_NAME", "value": PACKAGE}}]}}}


def _home(rows=()):
    return [_n(0, "FrameLayout"), _n(1, "TextView", None, "Requests"),
            _n(1, "Button", "start", "New request", ("ENABLED", "CLICKABLE")),
            _n(1, "RecyclerView", "requests", None, ("ENABLED", "SCROLLABLE")),
            *(_n(2, "TextView", None, row) for row in rows)]


def _form(name="", terms=False, summary="Plan: Basic", validation=False):
    nodes = [_n(0, "FrameLayout"), _n(1, "TextView", None, "Your name"),
             _n(1, "EditText", "name", name or "Name", ("ENABLED", "CLICKABLE", "EDITABLE"), hint="Name"),
             _n(1, "CheckBox", "terms", "Accept terms",
                ("ENABLED", "CLICKABLE", "CHECKABLE", *(("CHECKED",) if terms else ()))),
             _n(1, "TextView", "summary", summary),
             _n(1, "Button", "next", "Continue", ("ENABLED", "CLICKABLE")),
             _n(1, "Button", "help", "Help", ("ENABLED", "CLICKABLE"))]
    nodes += [_n(1, "TextView", f"line{index}", f"Static line {index}") for index in range(8)]
    if validation:
        nodes.append(_n(1, "TextView", "validation", "A name is required."))
    return nodes


def _done():
    return [_n(0, "FrameLayout"), _n(1, "TextView", None, "Saved"),
            _n(1, "Button", "home", "Return home", ("ENABLED", "CLICKABLE"))]


def _run(tmp_path, help_label="Help"):
    """Home -> form; Continue rejected while the name is empty; fill; Continue -> done -> home."""
    snapshots = {"obs-1": _home(), "obs-2": _form(), "obs-3": _form(validation=True, summary="Plan: Pro"),
                 "obs-4": _form(name="Ada", terms=True, summary="Plan: Pro"), "obs-5": _done(), "obs-6": _home(rows=["Ada"])}
    (tmp_path / "observations").mkdir()
    observations = {}
    for observation, nodes in snapshots.items():
        path = tmp_path / "observations" / f"{observation}.json"
        path.write_text(json.dumps({"nodes": nodes}))
        observations[observation] = {"evidence": {"snapshot": str(path)}}
    states = {f"s{index}": {"observations": [f"obs-{index}"]} for index in range(1, 7)}

    def action(source, verb, resource, label, status="attempted", **extra):
        return {"source": source, "verb": verb, "status": status, "scenario": extra.pop("scenario", {}),
                "target": {"selector": _res(resource), "label": label, "provenance": "snapshot-capability/1",
                           "blocked_reason": None}, **extra}

    actions = {"a1": action("s1", "tap", "start", "New request"),
               "a2": action("s2", "tap", "next", "Continue"),
               "a3": action("s3", "fill", "name", "Name", scenario={"value": "Ada"}),
               "a4": action("s4", "tap", "next", "Continue"),
               "a5": action("s5", "tap", "home", "Return home"),
               "a6": action("s2", "tap", "help", help_label, status="proposed")}
    attempts = {f"t{index}": {"action": f"a{index}", "status": "succeeded", "before": f"obs-{index}",
                              "destination": f"s{index + 1}"} for index in range(1, 5)}
    attempts["t5"] = {"action": "a5", "status": "succeeded", "before": "obs-5", "destination": "s6"}
    (tmp_path / "graph.json").write_text(json.dumps({
        "context": {"package": PACKAGE}, "states": states, "observations": observations,
        "actions": actions, "attempts": attempts}))
    return tmp_path


def _screen(model, name):
    return next(screen for screen in model["screens"] if screen["name"] == name)


def _method(screen, name):
    return next(method for method in screen["methods"] if method["name"] == name)


def test_robots_group_screens_and_name_them_from_titles(tmp_path):
    model = build(_run(tmp_path))

    assert [screen["name"] for screen in model["screens"]] == ["RequestsRobot", "YourNameRobot", "SavedRobot"]
    form = _screen(model, "YourNameRobot")
    assert form["members"] == ["obs-2", "obs-3", "obs-4"]
    assert _screen(model, "RequestsRobot")["members"] == ["obs-1", "obs-6"]
    assert model["typed_values"] == ["Ada"]
    assert all(predicate["value"] != "Ada" for screen in model["screens"] for predicate in screen["landmark"])
    assert all(screen["landmark_undistinguished_observations"] == 0 for screen in model["screens"])


def test_outcome_dependent_control_splits_into_methods_with_observed_preconditions(tmp_path):
    form = _screen(build(_run(tmp_path)), "YourNameRobot")

    rejected, forward = _method(form, "tap_next_rejected"), _method(form, "tap_next_to_saved")
    assert (rejected["returns"], rejected["when"], rejected["when_unseparated"]) == ("YourNameRobot", ["name is empty"], 0)
    assert (forward["returns"], forward["when"]) == ("SavedRobot", ["name is not empty"])
    assert rejected["outcome_dependent"] and forward["outcome_dependent"]
    enter = _method(form, "enter_name")
    assert (enter["parameter"], enter["examples"], enter["returns"]) == ("value", ["Ada"], "YourNameRobot")
    assert [entry["label"] for entry in form["unexplored"]] == ["Help"]


def test_expectations_parameterize_data_and_name_presence_conditions(tmp_path):
    form = _screen(build(_run(tmp_path)), "YourNameRobot")

    by_name = {expectation["name"]: expectation for expectation in form["expectations"]}
    assert by_name["expect_summary"]["kind"] == "value"
    assert by_name["expect_summary"]["examples"] == ["Plan: Basic", "Plan: Pro"]
    assert by_name["expect_terms_checked"]["kind"] == "checked"
    shown = by_name["expect_validation_a_name_is_required"]
    assert (shown["kind"], shown["absent_name"]) == ("shown", "expect_no_validation")
    # The test sets the name itself: an input binding is never an expectation.
    assert not any(expectation["resource"] == "name" for expectation in form["expectations"])


def test_generated_module_compiles_and_chains_robots(tmp_path):
    source = python(build(_run(tmp_path)))

    tree = ast.parse(source)
    classes = {node.name: node for node in tree.body if isinstance(node, ast.ClassDef)}
    assert set(classes) == {"Robot", "RequestsRobot", "YourNameRobot", "SavedRobot"}
    methods = {item.name for item in classes["YourNameRobot"].body if isinstance(item, ast.FunctionDef)}
    assert {"verify", "enter_name", "tap_next_rejected", "tap_next_to_saved", "expect_summary",
            "expect_terms_checked", "expect_no_validation"} <= methods
    assert 'self.app.element(res("next")).tap()' in source
    assert "return SavedRobot(self.app, self.timeout).verify()" in source
    assert "REVIEW: observed only when: name is empty." in source


def test_hostile_labels_stay_inside_docstrings(tmp_path):
    run = _run(tmp_path)
    graph = json.loads((run / "graph.json").read_text())
    graph["actions"]["a1"]["target"]["label"] = 'Say """hi""" \\ now\nraise SystemExit'
    (run / "graph.json").write_text(json.dumps(graph))

    tree = ast.parse(python(build(run)))
    assert not any(isinstance(node, ast.Raise) for node in ast.walk(tree)
                   if getattr(node, "exc", None) is not None and "SystemExit" in ast.dump(node))


def test_review_decisions_rename_drop_confirm_and_promote(tmp_path):
    review = {"format": "tap-robots-review/1", "screens": {"YourNameRobot": {
        "name": "NameFormRobot", "methods": {"tap_next_to_saved": "submit"}, "drop": ["expect_no_validation"],
        "confirmed": ["submit", "tap_next_rejected"], "expectations": ["expect_summary"]}}}
    model = build(_run(tmp_path), review)

    form = _screen(model, "NameFormRobot")
    assert _method(form, "submit")["confirmed"]
    assert _method(_screen(model, "RequestsRobot"), "tap_start")["returns"] == "NameFormRobot"
    expectations = {item["name"]: item for item in form["expectations"]}
    assert expectations["expect_summary"]["promoted"]
    assert "absent_name" not in expectations["expect_validation_a_name_is_required"]
    assert not any(item["kind"] == "outcome-depends" for item in model["review"])
    assert not any(item["kind"] == "name" and item["screen"] == "YourNameRobot" for item in model["review"])
    source = python(model)
    assert "class NameFormRobot(Robot):" in source and "Precondition: name is not empty." in source


def test_review_queue_orders_unconfirmed_outcomes_before_names(tmp_path):
    items = build(_run(tmp_path))["review"]

    kinds = [item["kind"] for item in items]
    assert kinds.index("outcome-depends") < kinds.index("frontier") < kinds.index("name")


def test_report_lists_review_items_and_variant_facts(tmp_path):
    text = report(build(_run(tmp_path)))

    assert "- [ ] **outcome-depends** `YourNameRobot` `tap_next_rejected`" in text
    assert "validation = 'A name is required.'" in text


def test_cli_writes_the_three_outputs(tmp_path, capsys):
    (tmp_path / "run").mkdir()
    run = _run(tmp_path / "run")

    assert main(["robots", "--run", str(run), "--out", str(tmp_path / "out")]) == 0
    assert json.loads(capsys.readouterr().out)["robots"] == 3
    assert {path.name for path in (tmp_path / "out").iterdir()} == {"robots.json", "robots.py", "REVIEW.md"}
    assert json.loads((tmp_path / "out" / "robots.json").read_text())["status"] == "unverified draft"


def test_write_rejects_a_foreign_review_format(tmp_path):
    (tmp_path / "run").mkdir()
    run = _run(tmp_path / "run")
    (tmp_path / "review.json").write_text(json.dumps({"format": "other"}))

    try:
        write(run, tmp_path / "out", tmp_path / "review.json")
    except ValueError as error:
        assert "review" in str(error)
    else:
        raise AssertionError("foreign review format accepted")


def test_snake_splits_single_letter_words_in_camel_case():
    assert snake("ChooseAPlanRobot") == "choose_a_plan_robot"
    assert snake("HTTPServer") == "http_server"
    assert snake("button1") == "button1"


def test_render_selector_drops_own_package_and_refuses_picks():
    assert render_selector(_res("next"), PACKAGE) == 'res("next")'
    foreign = {"node": {"resource": {"name": "button1", "package": "android"}}}
    assert render_selector(foreign, PACKAGE) == 'res_id("android", "button1")'
    assert render_selector({**_res("next"), "at": 1}, PACKAGE) is None
    row = {"node": {"all_of": {"nodes": [
        {"match": {"mode": "MATCH_EXACT", "property": "PROPERTY_CLASS_NAME", "value": "android.widget.LinearLayout"}},
        {"flag": {"property": "FLAG_CLICKABLE", "value": True}},
        {"related": {"relation": "RELATION_DESCENDANT", "node": {"match": {
            "mode": "MATCH_EXACT", "property": "PROPERTY_TEXT", "value": "Run"}}}}]}}}
    assert (render_selector(row, PACKAGE, {"Run": "label"}) ==
            'class_name("android.widget.LinearLayout").clickable().has_descendant(text(label))')


def test_list_state_becomes_a_check_to_ask_about(tmp_path):
    model = build(_run(tmp_path))
    home = _screen(model, "RequestsRobot")

    check = next(item for item in home["expectations"] if item["kind"] == "list")
    assert (check["name"], check["examples"], check["rows"]) == (
        "expect_requests_empty", ["empty", "nonempty"], ["android.widget.TextView"])
    source = python(model)
    assert 'rows = self.app.wait(class_name("android.widget.TextView").has_parent(res("requests")), self.timeout)' in source


def test_field_error_becomes_a_named_check_without_an_assertion(tmp_path):
    run = _run(tmp_path)
    for observation in ("obs-3", "obs-4"):  # the rejection's error, still shown once the name is filled
        path = run / "observations" / f"{observation}.json"
        nodes = json.loads(path.read_text())["nodes"]
        next(node for node in nodes if node["resource"] == "p:id/name")["error"] = "Cannot be blank"
        path.write_text(json.dumps({"nodes": nodes}))
    model = build(run)
    # An error is what a rejection left behind, never a precondition to set up.
    assert not any("error" in label for method in _screen(model, "YourNameRobot")["methods"]
                   for label in method["when"] + method["alternatives"] + method["either"])

    check = next(item for item in _screen(model, "YourNameRobot")["expectations"] if item["kind"] == "error")
    assert (check["name"], check["resource"], check["examples"]) == ("expect_name_error", "name", ["Cannot be blank"])
    source = python(model)
    assert 'def expect_name_error(self, error: str = "Cannot be blank") -> YourNameRobot:' in source
    assert "cannot assert a field error" in source  # snapshots are diagnostic; no silent pass
    compile(source, "robots.py", "exec")


def test_workflow_is_proposed_and_emitted_only_once_accepted(tmp_path):
    run = _run(tmp_path)
    form = _screen(build(run), "YourNameRobot")
    assert form["workflows"] == [{"name": "fill_and_tap_next_to_saved", "id": "fill_and_tap_next_to_saved",
                                  "steps": ["enter_name", "tap_next_to_saved"], "parameters": ["name"],
                                  "returns": "SavedRobot", "accepted": False}]
    assert "def fill_and_tap_next_to_saved" not in python(build(run))
    review = {"format": "tap-robots-review/1", "screens": {"YourNameRobot": {
        "workflows": {"fill_and_tap_next_to_saved": True}}}}
    source = python(build(run, review))
    assert "    def fill_and_tap_next_to_saved(self, name: str) -> SavedRobot:" in source
    assert "        return self.enter_name(name).tap_next_to_saved()" in source
    declined = {"format": "tap-robots-review/1", "screens": {"YourNameRobot": {
        "workflows": {"fill_and_tap_next_to_saved": False}}}}
    assert not any(item["kind"] == "workflow" for item in build(run, declined)["review"])


def test_either_or_and_alternative_preconditions_are_offered_not_chosen():
    rejected = [{"name is empty", "terms checked"}, {"name = 'Ada'", "terms unchecked"}]
    accepted = [{"name = 'Ada'", "terms checked"}]
    assert _either(rejected, accepted, {}) == ["name is empty", "terms unchecked"]
    assert _alternatives(rejected, accepted, {}) == []
    both = [{"name is empty", "terms unchecked"}]
    assert _alternatives(both, accepted, {"name is empty": 0, "terms unchecked": 1}) == [
        "name is empty", "terms unchecked"]
    assert _either(both, accepted, {}) == []


def test_untested_combination_is_offered_with_its_complement_and_never_chosen():
    """Loop's measurable Save: rejected with name and target empty, rejected with only the
    target empty, accepted with both filled. "target is not empty" separates; whether the name
    matters was never tried, so the draft keeps the observed condition and offers both."""
    fact_of = {"name = 'A'": {"kind": "input", "resource": "name", "value": "A"},
               "name is empty": {"kind": "input", "resource": "name", "value": ""},
               "target = '5'": {"kind": "input", "resource": "target", "value": "5"},
               "target is empty": {"kind": "input", "resource": "target", "value": ""},
               "title = 'New'": {"kind": "text", "resource": "title", "value": "New"}}
    saved = [{"name = 'A'", "target = '5'", "title = 'New'"}]
    rejected = [{"name is empty", "target is empty", "title = 'New'"}, {"name = 'A'", "target is empty", "title = 'New'"}]
    assert _untested(saved, rejected, ["target = '5'"], fact_of) == ["name is not empty"]
    # Once the name was empty with the target filled, the evidence answers the question itself.
    tried = rejected + [{"name is empty", "target = '5'", "title = 'New'"}]
    assert _untested(saved, tried, ["target = '5'"], fact_of) == []

    forward = {"when": ["target is not empty"], "alternatives": [], "either": [],
               "untested": ["name is not empty"], "widen": []}
    back = {"when": ["target is empty"], "alternatives": [], "either": [], "untested": [], "widen": []}
    _widen([forward, back])
    assert precondition_choices(forward)["w1"][1] == {"mode": "all", "facts": ["target is not empty", "name is not empty"]}
    assert precondition_choices(back)["w1"][1] == {"mode": "any", "facts": ["target is empty", "name is empty"]}
    assert forward["when"] == ["target is not empty"]  # offered, not chosen


def test_precondition_choices_drop_repeats():
    method = {"when": ["name is empty"], "alternatives": ["name is empty", "terms unchecked"], "either": [], "widen": []}
    assert list(precondition_choices(method)) == ["y", "2"]


def test_title_less_screens_are_named_from_options_and_flagged():
    dialog = [_n(0, "FrameLayout"), _n(1, "Button", None, "Discard", ("ENABLED", "CLICKABLE")),
              _n(1, "Button", None, "Keep editing", ("ENABLED", "CLICKABLE"))]
    names, sources = _screen_names([("a",), ("b",)], {"a": dialog, "b": _home()}, PACKAGE, set())
    assert (names, sources) == (["DiscardKeepEditingRobot", "RequestsRobot"], ["options", "title"])


def test_ask_records_only_answered_decisions(tmp_path):
    run = _run(tmp_path)
    model = build(run)
    asked = [item["kind"] for item in model["review"]]
    answers = iter(["y", "s", "y", "y", "", "s", "Form", "q"])
    shown = []
    review = ask(model, None, read=lambda prompt: next(answers), show=shown.append)

    decisions = review["screens"]
    assert review["format"] == "tap-robots-review/1"
    assert asked[:2] == ["outcome-depends", "outcome-depends"]
    form = decisions["YourNameRobot"]
    assert form["preconditions"] == {"tap_next_rejected": {"mode": "all", "facts": ["name is empty"]}}
    assert form["workflows"] == {"fill_and_tap_next_to_saved": True}
    assert form["name"] == "FormRobot"
    assert decisions["RequestsRobot"] == {"expectations": ["expect_requests_empty"], "name": "RequestsRobot"}
    assert "SavedRobot" not in decisions
    rebuilt = build(run, review)
    assert _method(_screen(rebuilt, "FormRobot"), "tap_next_rejected")["confirmed"]
    assert not _method(_screen(rebuilt, "FormRobot"), "tap_next_to_saved")["confirmed"]


def test_ask_stops_cleanly_at_end_of_input(tmp_path):
    def closed(prompt):
        raise EOFError

    assert ask(build(_run(tmp_path)), None, read=closed, show=lambda line: None)["screens"] == {}
