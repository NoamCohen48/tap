import ast
import json

from tap_explorer.cli import main
from tap_explorer.robots import (
    DETOUR,
    _alternatives,
    _carry,
    _edits_in_place,
    _either,
    _evidence_level,
    _exceptions,
    _expectations,
    _facts,
    _methods,
    _opened,
    _screen_names,
    _separate,
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
from tap_explorer.similarity import join_in_place, join_sections, node_evidence, section_evidence

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



def test_a_method_whose_input_opened_a_permission_dialog_answers_it_the_same_way(tmp_path):
    run = _run(tmp_path)
    graph = json.loads((run / "graph.json").read_text())
    graph["attempts"]["t1"]["permissions"] = ["DENY"]
    (run / "graph.json").write_text(json.dumps(graph))
    model = build(run)

    assert _method(_screen(model, "RequestsRobot"), "tap_start")["permission"] == "DENY"
    assert _method(_screen(model, "SavedRobot"), "tap_home")["permission"] is None
    source = python(model)
    ast.parse(source)
    assert 'self.app.element(res("start")).tap()\n        self._answer_permission(PermissionChoice.DENY)' in source
    assert source.count("self._answer_permission(") == 1

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


def test_preconditions_prefer_what_the_test_controls_over_literal_values_and_labels():
    """Loop's measurable Save, once: typed 'Grace' and set to "At most". The literal value and
    the dropdown's label each separate it alone; "name and target filled" is the app's rule."""
    def fact(kind, resource, value):
        return {"kind": kind, "resource": resource, "value": value}

    fact_of = {"name = 'Grace'": fact("input", "name", "Grace"), "name is not empty": fact("input", "name", None),
               "name is empty": fact("input", "name", ""), "target is not empty": fact("input", "target", None),
               "target is empty": fact("input", "target", ""), "type = 'At most'": fact("text", "type", "At most"),
               "type = 'At least'": fact("text", "type", "At least")}
    saved = [{"name = 'Grace'", "name is not empty", "target is not empty", "type = 'At most'"}]
    rejected = [{"name is empty", "target is empty", "type = 'At least'"},
                {"name is not empty", "target is empty", "type = 'At least'"},
                {"name is empty", "target is not empty", "type = 'At least'"}]
    members, others = _evidence_level(saved, rejected, {}, fact_of)
    assert _separate(members, others, {}) == (["name is not empty", "target is not empty"], 0)
    members, others = _evidence_level(rejected, saved, {}, fact_of)
    assert _separate(members, others, {})[1] == 1 and _either(members, others, {}) == ["name is empty", "target is empty"]
    # Nothing the test controls separates a value-only rule: the literal is kept.
    typed = [{"name = 'Grace'", "name is not empty"}]
    assert _evidence_level(typed, [{"name is not empty"}], {}, fact_of) == (typed, [{"name is not empty"}])


def test_a_hidden_rule_keeps_the_general_facts_instead_of_a_literal_value():
    """Notes' new-note OK: created with 'Grace', but also stayed with 'Grace' (a title already
    taken shows only a toast). The literal separates this evidence by accident; what the test
    controls ("title is not empty") is kept, with the conflict left unseparated."""
    def fact(kind, resource, value):
        return {"kind": kind, "resource": resource, "value": value}

    fact_of = {"title = 'Grace'": fact("input", "title", "Grace"), "title = 'Ada'": fact("input", "title", "Ada"),
               "title is not empty": fact("input", "title", None), "title is empty": fact("input", "title", "")}
    created = [{"title = 'Grace'", "title is not empty"}, {"title = 'Grace'", "title is not empty"}]
    stayed = [{"title is empty"}, {"title = 'Ada'", "title is not empty"}]
    members, others = _evidence_level(created, stayed, {}, fact_of)
    assert _separate(members, others, {}) == (["title is not empty"], 1)


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


def test_a_scrolled_page_is_the_same_robot_and_what_the_scroll_changed_is_no_check(tmp_path):
    def about(names, header):
        return [_n(0, "FrameLayout"), _n(1, "ScrollView", "scroll", None, ("ENABLED", "SCROLLABLE")),
                *(_n(1, "ImageView", f"decor{index}") for index in range(10)),
                *([_n(2, "TextView", "version", "Version 2.3")] if header else []),
                *(_n(2, "TextView", None, name) for name in names)]

    snapshots = {"obs-1": about(["Ada", "Grace", "Hopper", "Lovelace"], True),
                 "obs-2": about(["Knuth", "Liskov", "Ritchie", "Thompson"], False)}
    (tmp_path / "observations").mkdir()
    observations = {}
    for observation, nodes in snapshots.items():
        path = tmp_path / "observations" / f"{observation}.json"
        path.write_text(json.dumps({"nodes": nodes}))
        observations[observation] = {"evidence": {"snapshot": str(path)}}
    (tmp_path / "graph.json").write_text(json.dumps({
        "context": {"package": PACKAGE}, "observations": observations,
        "states": {"s1": {"observations": ["obs-1"]}, "s2": {"observations": ["obs-2"]}},
        "actions": {"a1": {"source": "s1", "verb": "scroll", "status": "attempted", "scenario": {},
                           "target": {"selector": _res("scroll"), "label": "scroll", "direction": "DOWN",
                                      "provenance": "snapshot-capability/1", "blocked_reason": None}}},
        "attempts": {"t1": {"action": "a1", "status": "succeeded", "before": "obs-1", "after": "obs-2",
                            "destination": "s2"}}}))

    model = build(tmp_path)

    assert [screen["members"] for screen in model["screens"]] == [["obs-1", "obs-2"]]
    assert model["screens"][0]["expectations"] == []  # Not "version shown": it scrolled away.
    # The scroll the check-free page came from is what the join needs: without it, two screens.
    graph = json.loads((tmp_path / "graph.json").read_text())
    graph["attempts"] = {}
    (tmp_path / "graph.json").write_text(json.dumps(graph))
    assert len(build(tmp_path)["screens"]) == 2


def test_inputs_that_edit_a_screen_in_place_join_its_variants_and_nothing_else():
    """Typing, or checking a box that is still there afterwards, never navigates, whatever it
    shows or hides; a choice dialog that closes on selection, or a plain button, stays apart."""
    radio = ("ENABLED", "CLICKABLE", "CHECKABLE")
    sort = [_n(0, "FrameLayout"), _n(1, "RadioButton", "custom", "Custom", radio),
            _n(1, "RadioButton", "ascending", "Ascending", radio)]
    custom = [_n(0, "FrameLayout"), _n(1, "RadioButton", "custom", "Custom", (*radio, "CHECKED"))]
    note = [_n(0, "FrameLayout"), _n(1, "EditText", "body", "")]
    typed = [_n(0, "FrameLayout"), _n(1, "EditText", "body", "Ada"), _n(1, "Button", "undo", "Undo")]
    picker = [_n(0, "FrameLayout"), _n(1, "RadioButton", "at_least", "At least", radio)]
    form = [_n(0, "FrameLayout"), _n(1, "Button", "target", "At least")]
    snapshots = {"sort": sort, "custom": custom, "note": note, "typed": typed, "picker": picker,
                 "form": form, "back": note}
    doc = {"actions": {"pick": {"verb": "tap", "target": {"selector": _res("custom")}},
                       "type": {"verb": "fill", "target": {"selector": _res("body")}},
                       "choose": {"verb": "tap", "target": {"selector": _res("at_least")}},
                       "undo": {"verb": "tap", "target": {"selector": _res("undo")}}},
           "attempts": {"1": {"action": "pick", "status": "succeeded", "before": "sort", "after": "custom"},
                        "2": {"action": "type", "status": "succeeded", "before": "note", "after": "typed"},
                        "3": {"action": "choose", "status": "succeeded", "before": "picker", "after": "form"},
                        "4": {"action": "undo", "status": "succeeded", "before": "typed", "after": "back"}}}

    pairs = _edits_in_place(doc, snapshots, PACKAGE)
    assert sorted(pairs) == [("note", "typed"), ("sort", "custom")]
    clusters = join_in_place({id: (id,) for id in snapshots}, pairs)
    assert clusters["sort"] == clusters["custom"] == ("custom", "sort")
    assert clusters["note"] == clusters["typed"] == ("note", "typed")
    assert clusters["picker"] == ("picker",) and clusters["back"] == ("back",)


def test_a_radio_condition_reads_as_the_button_that_is_checked():
    members = [{"title is not empty", "text checked", "checklist unchecked"}]
    others = [{"title is not empty", "text unchecked", "checklist checked"}]
    rank = {"title is not empty": 0, "text checked": 1, "checklist unchecked": 1,
            "text unchecked": 1, "checklist checked": 1}
    assert _separate(members, others, rank) == (["text checked"], 0)


def _sections(snapshots, opened=(), clusters=None):
    clusters = clusters or {id: (id,) for id in snapshots}
    sections = {id: section_evidence(PACKAGE, nodes) for id, nodes in snapshots.items()}
    return join_sections(clusters, sections, opened)


def test_a_screen_with_an_optional_section_is_one_robot_and_a_mode_or_dialog_is_not():
    """Notes: the tab strip and Open note appear once there are two notes (data); the search bar
    is opened by a tap (a mode); a dialog adds a window; another dialog has another title."""
    toolbar = [_n(0, "FrameLayout"), _n(1, "Toolbar", "toolbar"), _n(2, "ImageButton", "new_note", "New note"),
               _n(1, "EditText", "body", "")]
    one = toolbar
    two = [*toolbar, _n(1, "HorizontalScrollView", "tab_strip"), _n(2, "Button", "open_note", "Open note")]
    search = [*toolbar, _n(1, "EditText", "search_query", ""), _n(1, "ImageButton", "search_next", "Next")]
    search_two = [*two, _n(1, "EditText", "search_query", ""), _n(1, "ImageButton", "search_next", "Next")]
    dialog = [*toolbar, _n(0, "FrameLayout"), _n(1, "TextView", "title", "Delete note?"),
              _n(1, "Button", "button1", "OK")]
    rename = [_n(0, "FrameLayout"), _n(1, "TextView", "alert_title", "Rename note"), _n(1, "EditText", "name", ""),
              _n(1, "Button", "button1", "OK")]
    new_note = [_n(0, "FrameLayout"), _n(1, "TextView", "alert_title", "Add a new note"),
                _n(1, "EditText", "name", ""), _n(1, "RadioButton", "type_checklist", "Checklist"),
                _n(1, "Button", "button1", "OK")]
    snapshots = {"one": one, "two": two, "search": search, "search_two": search_two, "dialog": dialog,
                 "rename": rename, "new_note": new_note}

    clusters = _sections(snapshots, opened=[("one", "search")])
    assert clusters["one"] == clusters["two"] == ("one", "two")
    assert clusters["search"] == clusters["search_two"] == ("search", "search_two")
    assert clusters["dialog"] == ("dialog",) and clusters["rename"] == ("rename",) and clusters["new_note"] == ("new_note",)
    # Without the tap that opened it, the search bar would read as a section of the note.
    assert _sections(snapshots)["one"] == _sections(snapshots)["search"]


def test_a_list_hidden_behind_its_empty_placeholder_is_the_same_screen():
    """Fossify's checklist draws a placeholder, not an empty list; rows are data either way."""
    header = [_n(0, "FrameLayout"), _n(1, "TextView", None, "Groceries"), _n(1, "ImageButton", "fab", "Add")]
    empty = [*header, _n(1, "TextView", "placeholder", "The checklist is empty")]
    full = [*header, _n(1, "RecyclerView", "items"), _n(2, "CheckBox", "item", "Milk", ("ENABLED", "CHECKABLE"))]
    clusters = _sections({"empty": empty, "full": full})
    assert clusters["empty"] == clusters["full"]

    fact = {"kind": "list", "resource": "items", "value": "nonempty", "rank": 2}
    assert _expectations([{"items nonempty": fact}, {}]) == [
        {"kind": "list", "resource": "items", "examples": ["nonempty"]}]
    assert _expectations([{"items nonempty": fact}, {"items nonempty": fact}]) == []


def test_a_typed_value_shown_inside_a_longer_text_is_data():
    nodes = [_n(0, "FrameLayout"), _n(1, "TextView", None, "• Grace"), _n(1, "TextView", None, "Open note"),
             _n(1, "TextView", None, "a b")]
    assert node_evidence(PACKAGE, nodes, ["Grace", "a"])[2] == frozenset({"Open note", "a b"})


def test_a_tap_that_toggles_a_checkable_row_opens_nothing():
    """A checklist row holds its checkbox: what the tap shows next (a "checked items" group) is
    data. A plain button's tap is an input that may open a mode."""
    rows = [_n(0, "FrameLayout"), _n(1, "LinearLayout", "row"), _n(2, "CheckBox", "box", "Milk", ("ENABLED", "CHECKABLE")),
            _n(1, "LinearLayout", "row"), _n(2, "CheckBox", "box", "Eggs", ("ENABLED", "CHECKABLE")),
            _n(1, "ImageButton", "open_search", "Search")]
    doc = {"actions": {"check": {"verb": "tap", "target": {"selector": _res("row")}},
                       "search": {"verb": "tap", "target": {"selector": _res("open_search")}},
                       "select": {"verb": "long_tap", "target": {"selector": _res("row")}}},
           "attempts": {"1": {"action": "check", "status": "succeeded", "before": "a", "after": "b"},
                        "2": {"action": "search", "status": "succeeded", "before": "a", "after": "c"},
                        "3": {"action": "select", "status": "succeeded", "before": "a", "after": "d"}}}
    assert _opened(doc, {id: rows for id in "abcd"}, PACKAGE) == [("a", "c"), ("a", "d")]


def test_a_rule_no_snapshot_shows_is_drafted_from_what_was_typed_before_in_the_episode():
    """Notes' OK stays on the dialog for an empty title, and for a title a note already has
    (only a toast says so). A reset starts a new episode, where the title is new again."""
    def dialog(title):
        return [_n(0, "FrameLayout"), _n(1, "EditText", "title", title),
                _n(1, "Button", "ok", "OK", ("ENABLED", "CLICKABLE"))]

    snapshots = {"o1": dialog("Ada"), "o2": dialog("Ada"), "o3": dialog(""), "o4": dialog("Ada"), "o5": dialog("Grace")}
    outcomes = [("o1", "main"), ("o2", "dialog"), ("o3", "dialog"), ("o4", "main"), ("o5", "main")]
    doc = {"episodes": [{"after_attempts": 0}, {"after_attempts": 3}],
           "actions": {"ok": {"verb": "tap", "source": "dialog", "target": {"selector": _res("ok"), "label": "OK"}}},
           "attempts": {f"attempt-{index}": {"action": "ok", "status": "succeeded", "before": before,
                                             "destination": destination}
                        for index, (before, destination) in enumerate(outcomes, 1)}}
    typed = {"Ada", "Grace"}
    facts = {id: _facts(PACKAGE, sorted(typed), nodes, {"title"}) for id, nodes in snapshots.items()}
    screens = [{"id": "AddNoteRobot", "methods": [], "unexplored": []}, {"id": "MainRobot", "methods": [], "unexplored": []}]
    _methods(doc, PACKAGE, typed, screens, {"dialog": 0, "main": 1}, facts)

    when = {method["name"]: (method["when_mode"], method["when"], method["when_unseparated"]) for method in screens[0]["methods"]}
    assert when["tap_ok_to_main"] == ("all", ["title is not empty", "title is a new value"], 0)
    assert when["tap_ok_rejected"] == ("any", ["title is empty", "title repeats an earlier value"], 0)


def test_a_field_another_tab_shows_decides_a_save_on_this_tab():
    """Catima's Save on the Options tab is rejected for an empty store name or card ID the Card
    tab showed, and switches back to it. The Card tab's values carry over while Save stays on
    screen, across a dialog dismissed in between; a Save whose form never showed the Card tab
    (an episode that starts on Options) is unknown, not a counterexample."""
    def card(name, id):
        return [_n(0, "FrameLayout"), _n(1, "EditText", "name", name), _n(1, "EditText", "id", id),
                _n(1, "TextView", "tab_options", "Options", ("ENABLED", "CLICKABLE"))]

    def options(note):
        return [_n(0, "FrameLayout"), _n(1, "EditText", "note", note),
                _n(1, "Button", "save", "Save", ("ENABLED", "CLICKABLE")),
                _n(1, "Button", "date", "Pick a date", ("ENABLED", "CLICKABLE"))]

    dialog = [_n(0, "FrameLayout"), _n(1, "Button", "cancel", "Cancel", ("ENABLED", "CLICKABLE"))]
    steps = [(card("", "Ada"), "tab", "options"), (options(""), "save", "card"),
             (card("Ada", "Ada"), "tab", "options"), (options("Grace"), "date", "dialog"),
             (dialog, "cancel", "options"), (options("Grace"), "save", "list"),
             (card("Ada", ""), "tab", "options"), (options(""), "save", "card"),
             (card("Ada", "Ada"), "tab", "options"), (options(""), "save", "list"),
             (options("Ada"), "save", "card")]
    # Save floats over both tabs, as Catima's does.
    actions = {"tab": {"verb": "tap", "status": "attempted", "source": "card", "target": {"selector": _res("tab_options"), "label": "Options"}},
               "save": {"verb": "tap", "status": "attempted", "source": "options", "target": {"selector": _res("save"), "label": "Save"}},
               "date": {"verb": "tap", "status": "attempted", "source": "options", "target": {"selector": _res("date"), "label": "Pick a date"}},
               "cancel": {"verb": "tap", "status": "attempted", "source": "dialog", "target": {"selector": _res("cancel"), "label": "Cancel"}},
               "card-save": {"verb": "tap", "status": "proposed", "source": "card", "target": {"selector": _res("save"), "label": "Save"}}}
    doc = {"episodes": [{"after_attempts": 0}, {"after_attempts": 6}, {"after_attempts": 10}], "actions": actions,
           "attempts": {f"attempt-{index:02}": {"action": action, "status": "succeeded", "before": f"o{index}",
                                                "destination": destination}
                        for index, (_, action, destination) in enumerate(steps, 1)}}
    typed = {"Ada", "Grace"}
    facts = {f"o{index}": _facts(PACKAGE, sorted(typed), nodes, {"name", "id", "note"})
             for index, (nodes, _, _) in enumerate(steps, 1)}
    screens = [{"id": f"{name}Robot", "methods": [], "unexplored": []} for name in ("Card", "Options", "Dialog", "List")]
    _methods(doc, PACKAGE, typed, screens, {"card": 0, "options": 1, "dialog": 2, "list": 3}, facts)

    when = {method["name"]: (method["when_mode"], method["when"]) for method in screens[1]["methods"]}
    assert when["tap_save_to_list"] == ("all", ["id is not empty", "name is not empty"])
    assert when["tap_save_to_card"] == ("any", ["id is empty", "name is empty"])


def test_a_carried_field_is_dropped_after_a_long_trip_away_from_its_form():
    filled = {"name = 'Ada'": {"kind": "input", "resource": "name", "value": "Ada", "rank": 0}}
    form, save = {"save", "tabs"}, "save"
    carried = {}
    _carry(carried, filled, form, save)
    assert _carry(carried, {}, form, save) == filled
    for _ in range(DETOUR):
        _carry(carried, {}, {"elsewhere"}, "elsewhere")
    assert _carry(carried, {}, form, save) == filled
    for _ in range(DETOUR + 1):
        _carry(carried, {}, {"elsewhere"}, "elsewhere")
    assert _carry(carried, {}, form, save) == {}


def test_a_rule_that_holds_but_for_one_observation_is_offered_with_that_observation_named():
    """Catima's Save: rejected often for an empty store or ID, and once with every field filled
    (its balance did not parse), which pulls "note is empty" into the success rule. Without
    that one, store and ID filled separate; without any other one, nothing shorter does."""
    saved = [{"store is not empty", "id is not empty", "note is empty"}] * 2
    rejected = [{"store is empty", "id is not empty", "note is empty"},
                {"store is empty", "id is not empty", "note is not empty"},
                {"store is not empty", "id is empty", "note is empty"},
                {"store is not empty", "id is empty", "note is not empty"}] * 2 + [
                {"store is not empty", "id is not empty", "note is not empty"}]
    when, unseparated = _separate(saved, rejected, {})
    assert sorted(when) == ["id is not empty", "note is empty", "store is not empty"] and unseparated == 0
    assert [(mode, sorted(facts), index) for mode, facts, index in _exceptions(saved, rejected, when, 0, {})] == [
        ("all", ["id is not empty", "store is not empty"], 10)]
    # Evidence the observed rule already explains in fewest facts offers nothing longer.
    assert _exceptions(saved, rejected[:8], ["store is not empty", "id is not empty"], 0, {}) == []
    choices = precondition_choices({"when": when, "when_mode": "all", "alternatives": [], "either": [],
                                    "exceptions": [{"mode": "all", "facts": ["id is not empty", "store is not empty"],
                                                    "except": "attempt-000095"}]})
    assert choices["x1"] == ("all: id is not empty and store is not empty (if attempt-000095 had another cause)",
                             {"mode": "all", "facts": ["id is not empty", "store is not empty"]})
