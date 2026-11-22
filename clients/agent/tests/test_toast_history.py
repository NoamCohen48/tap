"""Retained toast history is ordered evidence, not a wait or a current-screen query."""
# pyright: reportMissingImports=false
import json

import pytest
from tap_e2e import _gen as pb

from tap_agent import cli
from tap_agent.core import AgentError


def page():
    return pb.ToastHistory(stream_id="stream-one", next_sequence=9, oldest_sequence=8, gap=True, events=[
        pb.ToastEvent(sequence=8, at_epoch_ms=1000, toast=pb.Toast(text="Saved order", package_name="com.shop")),
        pb.ToastEvent(sequence=9, at_epoch_ms=2000, toast=pb.Toast(text="Done", package_name="com.other"), text_truncated=True),
    ])


def test_history_read_filters_without_losing_the_high_water_mark(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(toast_history=page())
    output = agent.toast("saved", package="com.shop", after="stream-one:2")
    assert "history gap" in output
    assert "#8 1000 toast 'Saved order' from 'com.shop'" in output
    assert "Done" not in output
    assert output.splitlines()[-1] == "next: --after stream-one:9"
    command = fake.devices.commands[-1]
    assert command.WhichOneof("op") == "read_toast_history"
    assert command.read_toast_history.after_sequence == 2
    data = json.loads(agent.toast(as_json=True))
    assert [e["sequence"] for e in data["events"]] == [8, 9]
    assert data["events"][1]["text_truncated"]
    assert data["next"] == "stream-one:9"
    assert "[text truncated]" in agent.toast()
    assert "Saved order" not in agent.toast("saved", exact=True) and "Saved order" in agent.toast("Saved order", exact=True)


def test_history_empty_reset_and_validation(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(toast_history=pb.ToastHistory(stream_id="new", reset=True, gap=True))
    assert "stream reset" in agent.toast()
    assert "No retained toasts" in agent.toast()
    for kwargs in ({"after": "s:-1"}, {"after": "nine"}, {"after": "s:"}, {"after": f"s:{2**63}"}, {"after": "x" * 129 + ":1"},
                   {"after": "s:١"}, {"exact": True}, {"timeout": 2}):
        with pytest.raises(AgentError):
            agent.toast(**kwargs)
    with pytest.raises(AgentError):
        agent.toast(after="s:1", wait=True)


def test_cli_forwards_history_fields(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(toast_history=page())
    args = cli.parser().parse_args(["toast", "--after", "old:4", "--json"])
    assert json.loads(cli.run(args, agent))["next_sequence"] == 9
    assert fake.devices.commands[-1].read_toast_history == pb.ReadToastHistory(after_sequence=4, stream_id="old")
