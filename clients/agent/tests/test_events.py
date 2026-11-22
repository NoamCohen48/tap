"""Device events in tap-agent: --events/--expect on input steps, wait-event and watch."""
# pyright: reportMissingImports=false
from __future__ import annotations

import json
import threading
import time

import pytest  # type: ignore[import-not-found]
from mcp import Client
from tap_e2e import TapClient
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.targets import UsageError
from tap_agent import events as device_events
from tap_agent.core import EXIT_FAILED, EXIT_USAGE
from tap_agent.mcp_server import create_server

from .conftest import TOKEN


def toast(sequence: int, text: str, package: str = "com.shop") -> pb.DeviceEvent:
    return pb.DeviceEvent(sequence=sequence, package_name=package, toast_shown=pb.ToastShown(text=text))


def window(sequence: int, class_name: str, package: str = "com.shop") -> pb.DeviceEvent:
    return pb.DeviceEvent(
        sequence=sequence, package_name=package,
        window_changed=pb.WindowChanged(class_name=class_name, window_id=-1, changes=[pb.WINDOW_CHANGE_STATE]),
    )


def content(sequence: int, package: str = "com.shop") -> pb.DeviceEvent:
    return pb.DeviceEvent(sequence=sequence, package_name=package, content_changed=pb.ContentChanged(changes=[pb.CONTENT_CHANGE_TEXT]))


def tap_causes(fake, *batches) -> None:
    """The fake device answers a tap and, like a real app, raises these events right after."""

    def respond(command):
        if command.WhichOneof("op") == "tap":
            for batch in batches:
                fake.devices.feed_events(list(batch))
        return None

    fake.devices.responder = respond


def until(condition, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while not condition():
        assert time.monotonic() < deadline, "condition never held"
        time.sleep(0.01)


def test_events_print_what_the_step_caused_in_order_with_content_summed(fake, agent):
    agent.attach("emulator-5554")
    tap_causes(fake, [window(1, "com.shop.CheckoutActivity"), content(2), content(3), toast(4, "Draft saved")])
    out = agent.observing_events(lambda: agent.tap("text=Save"), events=["toast,window", "content"])
    assert out == (
        "tapped text=Save\n"
        "events:\n"
        "  window state com.shop.CheckoutActivity (com.shop)\n"
        '  toast "Draft saved" (com.shop)\n'
        "  content ×2 (com.shop)"
    )
    # One registration per kind, each subscribed before the tap was sent.
    assert sorted(list(r.types) for r in fake.devices.event_requests) == [
        [pb.DEVICE_EVENT_TOAST], [pb.DEVICE_EVENT_WINDOW_CHANGED], [pb.DEVICE_EVENT_CONTENT_CHANGED],
    ]
    until(lambda: fake.devices.event_streams == 0)


def test_a_step_that_causes_nothing_says_so(fake, agent):
    agent.attach("emulator-5554")
    assert agent.observing_events(lambda: agent.tap("text=Save"), events=["toast"]) == "tapped text=Save\nevents:\n  (none)"


def test_expect_marks_the_event_that_met_it(fake, agent):
    agent.attach("emulator-5554")
    tap_causes(fake, [toast(1, "Loading"), toast(2, "Draft SAVED")])
    out = agent.observing_events(lambda: agent.tap("text=Save"), expect=["toast:saved"])
    assert out.splitlines()[1:] == ["events:", '  toast "Loading" (com.shop)', '✓ toast "Draft SAVED" (com.shop)']


def test_an_unmet_expectation_fails_the_step_listing_what_did_happen(fake, agent):
    agent.attach("emulator-5554")
    tap_causes(fake, [toast(1, "Network error")])
    with pytest.raises(AgentError) as failure:
        agent.observing_events(lambda: agent.tap("text=Save"), expect=["toast:saved", "window"], timeout=0.3)
    assert failure.value.exit_code == EXIT_FAILED
    text = str(failure.value)
    assert text.startswith("tapped text=Save\nevents:\n")
    assert '  toast "Network error" (com.shop)' in text
    assert text.endswith("expected event did not happen within 300ms: toast:saved, window")


def test_bad_kinds_and_expectations_are_usage_errors_before_any_input(fake, agent):
    agent.attach("emulator-5554")
    for kwargs in ({"events": ["toasts"]}, {"expect": ["banana"]}, {"expect": ["toast:"]}):
        with pytest.raises(AgentError) as failure:
            agent.observing_events(lambda: agent.tap("text=Save"), **kwargs)
        assert failure.value.exit_code == EXIT_USAGE
    assert not any(c.WhichOneof("op") == "tap" for c in fake.devices.commands)
    assert fake.devices.event_requests == []


def test_a_failing_step_still_closes_its_registrations(fake, agent):
    agent.attach("emulator-5554")
    with pytest.raises(AgentError):
        agent.observing_events(lambda: agent.tap("banana"), events=["toast"])
    until(lambda: fake.devices.event_streams == 0)


def test_watch_until_first_prints_the_first_match(fake, agent):
    agent.attach("emulator-5554")

    def later() -> None:
        until(lambda: fake.devices.event_streams == 1)
        fake.devices.feed_events([toast(1, "Loading"), toast(2, "Message sent")])

    threading.Thread(target=later, daemon=True).start()
    assert agent.watch_first("toast", "SENT", package="com.shop", timeout=2) == 'toast "Message sent" (com.shop)'
    assert fake.devices.event_requests[-1].package_name == "com.shop"


def test_watch_until_first_times_out_and_rejects_unknown_or_several_kinds(fake, agent):
    agent.attach("emulator-5554")
    with pytest.raises(AgentError, match="ToastShownEvent"):
        agent.watch_first("toast", timeout=0.2)
    for kinds in ("banana", ["toast", "window"], "toast,window"):
        with pytest.raises(AgentError) as failure:
            agent.watch_first(kinds, timeout=0.2)
        assert failure.value.exit_code == EXIT_USAGE
    parser = cli.parser()
    for args in (["watch", "--until-first", "toast", "--for", "1s"], ["watch", "--until-first", "toast", "--json"],
                 ["watch", "window", "--until-first", "toast"]):
        with pytest.raises(UsageError):
            cli.run(parser.parse_args(args), agent)
    assert parser.parse_args(["watch", "--until-first", "toast"]).until_first == "toast"
    with pytest.raises(SystemExit):
        parser.parse_args(["watch", "--until-first", "banana"])
    assert cli.main(["watch"]) == EXIT_USAGE  # a stream needs a KIND


def test_watch_streams_lines_until_the_device_is_detached(fake, agent):
    agent.attach("emulator-5554")

    def later() -> None:
        until(lambda: fake.devices.event_streams == 2)
        fake.devices.feed_events([toast(1, "Saved"), window(2, "com.shop.Main")])
        time.sleep(0.1)
        fake.devices.feed_events(None)

    threading.Thread(target=later, daemon=True).start()
    lines = list(agent.watch(["toast", "window"]))
    assert sorted(lines[:2]) == sorted(['toast "Saved" (com.shop)', "window state com.shop.Main (com.shop)"])
    assert lines[2] == "(watch ended: the device was detached)"
    until(lambda: fake.devices.event_streams == 0)


def test_watch_json_and_duration(fake, agent):
    agent.attach("emulator-5554")

    def later() -> None:
        until(lambda: fake.devices.event_streams == 1)
        fake.devices.feed_events([pb.DeviceEvent(sequence=3, text_changed=pb.TextChanged(text="secret", before_text="secre", password=True))])

    threading.Thread(target=later, daemon=True).start()
    started = time.monotonic()
    lines = list(agent.watch(["text"], duration=0.5, as_json=True))
    assert time.monotonic() - started >= 0.5
    (event,) = [json.loads(line) for line in lines]
    assert event["kind"] == "text" and event["password"] is True
    assert event["text"] is None and event["before_text"] is None


def test_cli_parses_the_event_options_on_every_input_verb():
    root = cli.parser()
    args = root.parse_args(["tap", "@e3", "--events", "toast,window", "--expect", "toast:saved", "--event-timeout", "5s"])
    assert (args.events, args.expect_events, args.event_timeout) == (["toast,window"], ["toast:saved"], 5.0)
    assert root.parse_args(["app", "launch", "com.shop", "--expect", "window"]).expect_events == ["window"]
    with pytest.raises(SystemExit):
        root.parse_args(["wait-event", "toast"])


def test_cli_end_to_end(fake, monkeypatch, capsys):
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    assert cli.main(["attach", "emulator-5554", "-s", "cli"]) == 0
    capsys.readouterr()
    tap_causes(fake, [toast(1, "Draft saved")])
    assert cli.main(["tap", "text=Save", "--expect", "toast:saved", "-s", "cli"]) == 0
    assert capsys.readouterr().out == 'tapped text=Save\nevents:\n✓ toast "Draft saved" (com.shop)\n'
    assert cli.main(["watch", "toast", "--text", "x", "-s", "cli"]) == EXIT_USAGE


def test_device_event_lines():
    from tap_e2e import ViewClickedEvent, ViewScrolledEvent

    click = ViewClickedEvent(sequence=1, uptime_ms=0, package_name=None, class_name="android.widget.Button", label="OK", window_id=3)
    assert device_events.line(click) == 'click Button "OK"'
    scroll = ViewScrolledEvent(
        sequence=2, uptime_ms=0, package_name="com.shop", class_name="androidx.recyclerview.widget.RecyclerView", window_id=None,
        scroll_x=0, scroll_y=300, max_scroll_x=0, max_scroll_y=900, delta_x=None, delta_y=120, from_index=3, to_index=9, item_count=40,
    )
    assert device_events.line(scroll) == "scroll RecyclerView x=0/0 y=300/900 dy=+120 items 3-9/40 (com.shop)"


@pytest.fixture
def server(fake, tmp_path):
    agents = []

    def agent_for(session: str) -> Agent:
        agents.append(Agent(session, client=TapClient.create(fake.address, TOKEN), out_dir=tmp_path / "mcp"))
        return agents[-1]

    yield create_server(agent_for, default_session="agent")
    for agent in agents:
        agent.client.close()


@pytest.mark.anyio
async def test_mcp_tools(fake, server):
    async with Client(server, mode="legacy") as client:
        await client.call_tool("attach", {"serial": "emulator-5554"})
        tap_causes(fake, [toast(1, "Draft saved")])
        result = await client.call_tool("tap", {"target": "text=Save", "expect_events": ["toast:saved"], "events": ["window"]})
        assert not result.is_error
        assert result.content[0].text.startswith("tapped text=Save\n")
        assert result.content[0].text.endswith('events:\n✓ toast "Draft saved" (com.shop)')
        result = await client.call_tool("tap", {"target": "text=Save", "expect_events": ["window"], "event_timeout_seconds": 0.2})
        assert result.is_error and "expected event did not happen" in result.content[0].text

        result = await client.call_tool("watch", {"kinds": ["toast"], "duration_seconds": 0.3})
        assert not result.is_error and result.content[0].text == "(no events)"

        subscribed = len(fake.devices.event_requests)

        def later() -> None:
            until(lambda: len(fake.devices.event_requests) > subscribed and fake.devices.event_streams == 1)
            fake.devices.feed_events([toast(5, "Loading"), toast(6, "Message sent")])

        threading.Thread(target=later, daemon=True).start()
        result = await client.call_tool("watch", {"until_first": "toast", "text": "sent", "timeout_seconds": 2})
        assert not result.is_error and result.content[0].text == 'toast "Message sent" (com.shop)'
        for wrong in ({"kinds": ["toast"]}, {"until_first": "toast", "duration_seconds": 1}, {"until_first": "toast", "kinds": ["toast"]}, {"duration_seconds": 1},
                      {"kinds": ["toast"], "duration_seconds": 1, "text": "x"}):
            assert (await client.call_tool("watch", wrong)).is_error
