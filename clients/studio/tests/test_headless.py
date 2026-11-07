"""`tap-studio replay` and `export`: the same run with no browser, and nothing before it is checked."""
from __future__ import annotations

import json
import os
import stat

import pytest

from tap_e2e import TapClient, TapError
from tap_studio import cli, headless
from tap_studio.recording import FORMAT

from .conftest import TOKEN

X = {"node": {"resource": {"name": "x"}}}
ONE = {"wait_visible": {"selector": X, "exactly_one": True}}
BACK = {"id": "s1", "action": {"command": {"press_key": {"key_code": 4}}}}
HOME = {"id": "s2", "action": {"command": {"press_key": {"key_code": 3}}}}
TYPED = {"id": "t1", "type": {"selector": X, "parameter": "user"}}
SECRET = {"id": "p1", "type": {"selector": X, "secret": "pw"}}
FAILING = {"id": "f1", "assertion": {"selector": X, "check": "CHECK_COUNT", "count": 99}}
VALUE = "hunter2-value"


def write(tmp_path, *steps, parameters=(), secrets=()):
    path = tmp_path / "flow.tap-recording.json"
    path.write_text(json.dumps({
        "format": FORMAT,
        "recorded_at": "2026-09-29T17:42:10Z",
        "recorder": "tap-studio 0.0.1",
        "device": {"serial": "emulator-5554"},
        "secrets": list(secrets),
        "parameters": list(parameters),
        "steps": list(steps),
    }))
    return str(path)


@pytest.fixture
def factory(daemon):
    return lambda: TapClient.create(daemon.address, TOKEN)


def keys(daemon):
    return [c.press_key.key_code for c in daemon.devices.commands if c.WhichOneof("op") == "press_key"]


def attached(daemon):
    return [c for c in daemon.devices.commands if c.WhichOneof("op") == "device_info"]


def test_a_passing_run_exits_zero_and_writes_private_results(tmp_path, daemon, factory, capsys):
    results = tmp_path / "out.json"
    code = headless.replay(write(tmp_path, BACK, HOME), "emulator-5554", results=str(results), client_factory=factory)
    assert code == headless.PASSED
    assert keys(daemon) == [4, 3]
    document = json.loads(results.read_text())
    assert document["status"] == "RUN_STATUS_PASSED" and document["ran"] == 2
    assert stat.S_IMODE(os.stat(results).st_mode) == 0o600
    assert "All 2 steps passed." in capsys.readouterr().out


def test_a_failing_step_exits_one_stops_there_and_retries_nothing(tmp_path, daemon, factory, capsys):
    results = tmp_path / "out.json"
    code = headless.replay(write(tmp_path, BACK, FAILING, HOME), "emulator-5554", results=str(results), client_factory=factory)
    assert code == headless.FAILED
    assert keys(daemon) == [4]  # never reached the step after it, and no second attempt
    assert len([c for c in daemon.devices.commands if c.WhichOneof("op") == "count"]) == 1
    document = json.loads(results.read_text())
    assert document["status"] == "RUN_STATUS_FAILED" and document["failed_step_id"] == "f1"
    assert "FAIL f1" in capsys.readouterr().out


def test_the_device_is_detached_after_a_failure(tmp_path, daemon, factory):
    headless.replay(write(tmp_path, FAILING), "emulator-5554", client_factory=factory)
    assert daemon.devices.detaches


def test_parameters_use_the_declared_value_or_the_given_one(tmp_path, daemon, factory):
    path = write(tmp_path, TYPED, parameters=[{"name": "user", "value": "ada"}])
    assert headless.replay(path, "emulator-5554", client_factory=factory) == headless.PASSED
    assert headless.replay(path, "emulator-5554", parameters={"user": "grace"}, client_factory=factory) == headless.PASSED
    assert [c.type_text.text for c in daemon.devices.commands if c.WhichOneof("op") == "type_text"] == ["ada", "grace"]


def test_a_secret_comes_from_the_environment_and_never_reaches_output(tmp_path, daemon, factory, capsys):
    results = tmp_path / "out.json"
    code = headless.replay(
        write(tmp_path, SECRET, secrets=["pw"]), "emulator-5554", secret_env={"pw": "MY_PW"}, results=str(results), environ={"MY_PW": VALUE}, client_factory=factory
    )
    assert code == headless.PASSED
    assert [c.type_text.text for c in daemon.devices.commands if c.WhichOneof("op") == "type_text"] == [VALUE]
    captured = capsys.readouterr()
    assert VALUE not in captured.out + captured.err + results.read_text()


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"secret_env": {}}, "--secret pw=ENV_VAR"),
        ({"secret_env": {"pw": "UNSET"}}, "UNSET is not set"),
        ({"secret_env": {"pw": "MY_PW", "other": "MY_PW"}}, "no secret other"),
        ({"secret_env": {"pw": "MY_PW"}, "parameters": {"nobody": "x"}}, "no parameter 'nobody'"),
        ({"secret_env": {"pw": "MY_PW"}, "from_step": "nope"}, "no step 'nope'"),
    ],
)
def test_wrong_options_are_refused_before_touching_anything(tmp_path, kwargs, message, capsys):
    def no_server():
        raise AssertionError("must not connect")

    code = headless.replay(write(tmp_path, SECRET, secrets=["pw"]), "emulator-5554", environ={"MY_PW": VALUE}, client_factory=no_server, **kwargs)
    assert code == headless.INVALID
    assert message in capsys.readouterr().err


def test_an_invalid_file_is_refused(tmp_path, capsys):
    path = tmp_path / "bad.json"
    path.write_text('{"format": "nope"}')
    assert headless.replay(str(path), "s") == headless.INVALID
    assert headless.replay(str(tmp_path / "missing.json"), "s") == headless.INVALID


def test_results_never_replace_a_file(tmp_path, capsys):
    results = tmp_path / "out.json"
    results.write_text("mine")

    def no_server():
        raise AssertionError("must not connect")

    assert headless.replay(write(tmp_path, BACK), "s", results=str(results), client_factory=no_server) == headless.INVALID
    assert results.read_text() == "mine"


def test_no_server_says_how_to_start_one_and_does_not_start_it(tmp_path, capsys):
    def no_server():
        raise TapError("connection refused")

    assert headless.replay(write(tmp_path, BACK), "s", client_factory=no_server) == headless.UNAVAILABLE
    assert "tap start" in capsys.readouterr().err


def test_from_step_starts_there(tmp_path, daemon, factory):
    assert headless.replay(write(tmp_path, BACK, HOME), "emulator-5554", from_step="s2", client_factory=factory) == headless.PASSED
    assert keys(daemon) == [3]


def test_an_interrupt_stops_says_so_and_detaches(tmp_path, daemon, factory, monkeypatch, capsys):
    def interrupted(*args):
        raise KeyboardInterrupt

    monkeypatch.setattr("tap_studio.headless.steps.run", interrupted)
    results = tmp_path / "out.json"
    assert headless.replay(write(tmp_path, BACK, HOME), "emulator-5554", results=str(results), client_factory=factory) == headless.INTERRUPTED
    assert "may have sent its input" in capsys.readouterr().err
    assert json.loads(results.read_text())["status"] == "RUN_STATUS_STOPPED"
    assert daemon.devices.detaches


def test_evidence_only_when_asked_and_only_after_a_failure(tmp_path, daemon, factory):
    evidence = tmp_path / "evidence"
    headless.replay(write(tmp_path, BACK), "emulator-5554", evidence_dir=str(evidence), client_factory=factory)
    assert not evidence.exists()
    headless.replay(write(tmp_path, FAILING), "emulator-5554", client_factory=factory)
    assert not evidence.exists()
    assert headless.replay(write(tmp_path, FAILING), "emulator-5554", evidence_dir=str(evidence), client_factory=factory) == headless.FAILED
    made = list(evidence.iterdir())
    assert made and all(stat.S_IMODE(os.stat(p).st_mode) == 0o600 for p in made)
    assert stat.S_IMODE(os.stat(evidence).st_mode) == 0o700


def test_the_command_line_runs_a_replay(tmp_path, monkeypatch):
    seen = {}

    def fake_replay(document, serial, **options):
        seen.update(document=document, serial=serial, **options)
        return 7

    monkeypatch.setattr("tap_studio.cli.headless.replay", fake_replay)
    code = cli.main(["replay", "flow.json", "--serial", "s", "--param", "a=b=c", "--secret", "pw=MY_PW", "--from-step", "s2", "--results", "r.json"])
    assert code == 7
    assert seen["parameters"] == {"a": "b=c"} and seen["secret_env"] == {"pw": "MY_PW"}
    assert seen["from_step"] == "s2" and seen["results"] == "r.json"


@pytest.mark.parametrize("flags", [["--param", "novalue"], ["--secret", "=x"], ["--param", "a=1", "--param", "a=2"]])
def test_bad_pairs_are_refused_and_do_not_echo_a_value(flags, capsys):
    assert cli.main(["replay", "f.json", "--serial", "s", *flags]) == headless.INVALID


def test_the_command_line_exports_to_a_new_file_only(tmp_path, capsys):
    path = write(tmp_path, BACK)
    assert cli.main(["export", path]) == 0
    assert "def test_flow(tap_device):" in capsys.readouterr().out
    out = tmp_path / "test_flow.py"
    assert cli.main(["export", path, "-o", str(out), "--name", "Checkout"]) == 0
    assert "def test_checkout(tap_device):" in out.read_text()
    assert cli.main(["export", path, "-o", str(out)]) == headless.INVALID


def test_exporting_a_bad_file_says_so(tmp_path, capsys):
    assert cli.main(["export", str(tmp_path / "missing.json")]) == headless.INVALID
    assert "cannot read" in capsys.readouterr().err



@pytest.mark.parametrize("where", ["missing/out.json", "evidence-file"])
def test_where_results_and_evidence_go_is_checked_before_the_device(tmp_path, where, capsys):
    (tmp_path / "evidence-file").write_text("not a directory")
    options = {"results": str(tmp_path / where)} if where.endswith(".json") else {"evidence_dir": str(tmp_path / where)}

    def no_server():
        raise AssertionError("checked after the device was touched")

    assert headless.replay(write(tmp_path, BACK), "emulator-5554", client_factory=no_server, **options) == headless.INVALID
    assert "cannot" in capsys.readouterr().err


def test_a_release_that_fails_does_not_change_the_result(tmp_path, daemon, factory, monkeypatch, capsys):
    from tap_e2e import Device

    def refuse(self):
        raise TapError("detach went wrong")

    monkeypatch.setattr(Device, "detach", refuse)
    assert headless.replay(write(tmp_path, BACK), "emulator-5554", client_factory=factory) == headless.PASSED
    assert "could not release" in capsys.readouterr().err
