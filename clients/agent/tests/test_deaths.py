"""App deaths in tap-agent: every step reports new watched deaths once, --expect-death, the
deaths tool, history and recordings, over the in-process fake daemon."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import json
import stat
import threading

import pytest  # type: ignore[import-not-found]
from mcp import Client
from tap_e2e import TapClient
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.mcp_server import create_server

from .conftest import TOKEN

PKG = "com.example.shop"
TRACE = (
    "FATAL EXCEPTION: main\nProcess: com.example.shop, PID: 23554\njava.lang.IllegalStateException: Out of stock\n"
    + "".join(f"\tat com.example.shop.Cart.f{i}(Cart.kt:{i})\n" for i in range(8))
)


def death(sequence: int, package: str = PKG, watched: bool = True, kind=pb.AppDeath.KIND_CRASH) -> pb.AppDeath:
    return pb.AppDeath(
        sequence=sequence,
        kind=kind,
        package_name=package,
        process_name=package,
        pid=23554,
        at_epoch_ms=1_790_000_014_107,
        exception_class="" if kind == pb.AppDeath.KIND_ANR else "java.lang.IllegalStateException",
        message="Input dispatching timed out" if kind == pb.AppDeath.KIND_ANR else "Out of stock",
        stack_trace="" if kind == pb.AppDeath.KIND_ANR else TRACE,
        watched=watched,
    )


def crashes(fake, record: pb.AppDeath):
    """The next command fails as AUT_CRASHED carrying ``record``, which the server then holds."""

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("device_info"):
            return None
        fake.devices.deaths.append(record)
        return pb.CommandResult(error=pb.Error(code=pb.ERR_AUT_CRASHED, detail="WAIT_TIMEOUT", message=f"{PKG} crashed", app_death=record))

    fake.devices.responder = respond


def test_a_step_reports_each_new_watched_death_once_with_frames_and_a_trace_file(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.deaths += [death(1), death(2, package="com.google.android.gms", watched=False)]
    out = agent.reporting_deaths(lambda: agent.tap("text=Pay"))
    lines = out.splitlines()
    assert lines[0] == "tapped text=Pay"
    assert lines[1] == f"! {PKG} crashed (pid 23554): java.lang.IllegalStateException: Out of stock"
    assert lines[2:7] == [f"    at com.example.shop.Cart.f{i}(Cart.kt:{i})" for i in range(5)]
    assert lines[7].startswith("  … 3 more frames; full trace: ")
    trace = agent.out_dir / "deaths" / "emulator-5554-0001-com.example.shop.txt"
    assert str(trace) in lines[7] and "Cart.f7" in trace.read_text()
    assert stat.S_IMODE(trace.stat().st_mode) == 0o600
    assert lines[8] == f"  its screen is gone; `app launch {PKG}` to continue"
    assert "gms" not in out, "an unwatched app's death is not reported"
    # Reported once: the next step says nothing more.
    assert agent.reporting_deaths(lambda: agent.tap("text=Pay")) == "tapped text=Pay"
    # Another agent process (the CLI) shares what was reported.
    other = Agent("agent", client=agent.client, out_dir=agent.out_dir)
    assert other.reporting_deaths(lambda: other.tap("text=Pay")) == "tapped text=Pay"


def test_an_anr_says_the_app_may_still_be_stuck(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.deaths.append(death(1, kind=pb.AppDeath.KIND_ANR))
    out = agent.reporting_deaths(lambda: agent.tap("text=Pay"))
    assert f"! {PKG} stopped responding (pid 23554): Input dispatching timed out" in out
    assert f"`app stop {PKG}`, then launch it again" in out and "full trace" not in out


def test_a_failed_step_keeps_its_error_and_adds_the_death(fake, agent):
    agent.attach("emulator-5554")
    crashes(fake, death(1))
    with pytest.raises(AgentError) as failure:
        agent.reporting_deaths(lambda: agent.tap("text=Pay"))
    message = str(failure.value)
    assert "AUT_CRASHED" in message and "the app died" in message
    assert f"! {PKG} crashed (pid 23554)" in message and "Cart.kt:0" in message


def test_expect_death_turns_the_carried_crash_into_a_success(fake, agent):
    agent.attach("emulator-5554")
    crashes(fake, death(1))
    out = agent.reporting_deaths(lambda: agent.tap("text=Crash"), expect=True)
    assert out.splitlines()[0] == f"✓ {PKG} crashed (pid 23554): java.lang.IllegalStateException: Out of stock (expected)"
    assert "its screen is gone" not in out


def test_expect_death_waits_for_a_late_death_and_fails_without_one(fake, agent):
    agent.attach("emulator-5554")
    later = threading.Timer(0.3, lambda: fake.devices.deaths.append(death(1)))
    later.start()
    out = agent.reporting_deaths(lambda: agent.tap("text=Crash"), expect=True, expect_timeout=5)
    later.join()
    assert out.startswith("tapped text=Crash\n✓ ")
    with pytest.raises(AgentError, match="expected a watched app to crash or stop responding within"):
        agent.reporting_deaths(lambda: agent.tap("text=Crash"), expect=True, expect_timeout=0.3)
    # Another failure is still a failure.
    fake.devices.responder = lambda command: None if command.HasField("device_info") else pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    with pytest.raises(AgentError, match="NOT_FOUND"):
        agent.reporting_deaths(lambda: agent.tap("text=Crash"), expect=True, expect_timeout=0.3)


def test_deaths_lists_looks_up_one_trace_and_speaks_json(fake, agent):
    agent.attach("emulator-5554")
    assert agent.deaths() == "no watched app died on emulator-5554 since it was attached"
    fake.devices.deaths += [death(1), death(2, package="com.google.android.gms", watched=False)]
    assert agent.deaths() == f"#1 {PKG} crashed (pid 23554): java.lang.IllegalStateException: Out of stock"
    assert "#2 com.google.android.gms crashed" in agent.deaths(all_apps=True) and "(not watched)" in agent.deaths(all_apps=True)
    assert agent.deaths(trace=1).endswith(TRACE)
    with pytest.raises(AgentError, match="no death #9"):
        agent.deaths(trace=9)
    (entry,) = json.loads(agent.deaths(as_json=True))
    assert entry["sequence"] == 1 and entry["kind"] == "crash" and len(entry["top_frames"]) == 5
    assert fake.devices.death_requests[-1].package_name == "" and agent.deaths(package=PKG)
    assert fake.devices.death_requests[-1].package_name == PKG


def test_history_lists_watched_deaths_next_to_the_calls(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.deaths += [death(1), death(2, package="com.google.android.gms", watched=False)]
    document = json.loads(agent.history())
    assert [d["sequence"] for d in document["app_deaths"]] == [1]
    assert document["app_deaths"][0]["serial"] == "emulator-5554"


def test_a_recording_keeps_the_deaths_a_step_noticed_without_app_output(fake, agent):
    agent.attach("emulator-5554")
    agent.record("start", "checkout")
    crashes(fake, death(1))
    agent.reporting_deaths(lambda: agent.tap("text=Crash"), expect=True)
    fake.devices.responder = None
    # The crashing tap's outcome is unknown, which ends recorded input until the recording stops.
    agent.record("stop")
    (step,) = json.loads(agent.export("checkout"))["steps"]
    assert step["app_deaths"] == [{
        "serial": "emulator-5554", "sequence": 1, "kind": "crash", "package_name": PKG, "process_name": PKG,
        "exception_class": "java.lang.IllegalStateException", "expected": True,
    }]
    assert "Out of stock" not in json.dumps(step), "a recording keeps no app output"


def test_the_cli_reports_deaths_and_expects_them(fake, monkeypatch, capsys, tmp_path):
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    monkeypatch.setenv("TAP_AGENT_DIR", str(tmp_path / "cli"))
    assert cli.main(["attach", "emulator-5554", "-s", "cli"]) == 0
    fake.devices.deaths.append(death(1))
    capsys.readouterr()
    assert cli.main(["tap", "text=Pay", "-s", "cli"]) == 0
    assert f"! {PKG} crashed" in capsys.readouterr().out
    crashes(fake, death(2))
    assert cli.main(["tap", "text=Crash", "--expect-death", "-s", "cli"]) == 0
    assert f"✓ {PKG} crashed" in capsys.readouterr().out
    fake.devices.responder = None
    assert cli.main(["deaths", "-s", "cli"]) == 0
    assert capsys.readouterr().out.count("#") == 2


@pytest.mark.anyio
async def test_mcp_steps_report_deaths_and_take_expect_death(fake, tmp_path):
    agents = []

    def agent_for(session: str) -> Agent:
        agents.append(Agent(session, client=TapClient.create(fake.address, TOKEN), out_dir=tmp_path / "mcp"))
        return agents[-1]

    try:
        async with Client(create_server(agent_for, default_session="agent"), mode="legacy") as client:
            assert not (await client.call_tool("attach", {"serial": "emulator-5554"})).is_error
            fake.devices.deaths.append(death(1))
            result = await client.call_tool("tap", {"target": "text=Pay"})
            assert not result.is_error and f"! {PKG} crashed" in result.content[0].text
            crashes(fake, death(2))
            result = await client.call_tool("tap", {"target": "text=Crash", "expect_death": True})
            assert not result.is_error and f"✓ {PKG} crashed" in result.content[0].text
            fake.devices.responder = None
            result = await client.call_tool("deaths", {"trace": 2})
            assert not result.is_error and "Cart.kt:7" in result.content[0].text
    finally:
        for agent in agents:
            agent.client.close()
