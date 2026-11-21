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
    output = agent.toast("Saved", contains=True, package="com.shop", after_seq=2, stream="stream-one")
    assert "history gap" in output
    assert "#8 1000 toast 'Saved order' from 'com.shop'" in output
    assert "Done" not in output
    assert "--after-seq 9 --stream stream-one" in output
    command = fake.devices.commands[-1]
    assert command.WhichOneof("op") == "read_toast_history"
    assert command.read_toast_history.after_sequence == 2
    data = json.loads(agent.toast(as_json=True))
    assert [e["sequence"] for e in data["events"]] == [8, 9]
    assert data["events"][1]["text_truncated"]
    assert "[text truncated]" in agent.toast()


def test_history_empty_reset_and_validation(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(toast_history=pb.ToastHistory(stream_id="new", reset=True, gap=True))
    assert "stream reset" in agent.toast()
    assert "No retained toasts" in agent.toast()
    for kwargs in ({"after_seq": -1}, {"after_seq": True}, {"after_seq": 2**63}, {"stream": "x" * 129}, {"contains": True}):
        with pytest.raises(AgentError):
            agent.toast(**kwargs)
    with pytest.raises(AgentError):
        agent.toast(after_seq=1, wait=True)


def test_cli_forwards_history_fields(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(toast_history=page())
    args = cli.parser().parse_args(["toast", "--after-seq", "4", "--stream", "old", "--json"])
    assert json.loads(cli.run(args, agent))["next_sequence"] == 9
    assert fake.devices.commands[-1].read_toast_history == pb.ReadToastHistory(after_sequence=4, stream_id="old")
