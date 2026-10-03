"""One daemon Watch stream, relayed to pages gap-free and kept across daemon restarts."""

from __future__ import annotations

import asyncio

from fakes import FakeDaemon, event, serve, until
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import watch_pb2 as watch

from tap_watcher.activity import ActivityHub, ReaderOverflow, Subscription, apply
from tap_watcher.daemon import Daemon


def opened(owner: str, name: str) -> watch.Activity:
    return watch.Activity(
        client_connection_id=owner,
        connection_opened=watch.ConnectionOpened(name=name, held=True),
    )


def attached(owner: str, serial: str) -> watch.Activity:
    return watch.Activity(
        client_connection_id=owner,
        device_attached=watch.DeviceAttached(
            attached_device_id=f"{owner}-{serial}", serial=serial, generation=1
        ),
    )


async def next_batch(hub: ActivityHub, page: Subscription) -> list[watch.Activity]:
    batch = await asyncio.wait_for(hub.next_batch(page), 5)
    assert batch is not None
    return batch


def test_apply_is_idempotent_over_a_snapshot():
    live: dict[str, connection.ConnectionEntry] = {}
    history = [
        opened("c1", "pytest"),
        attached("c1", "serial"),
        opened("c2", "junit"),
        watch.Activity(
            client_connection_id="c2",
            connection_closed=watch.ConnectionClosed(reason="done"),
        ),
    ]
    for activity in history:
        apply(live, activity)
    snapshot = {k: v for k, v in live.items()}
    for activity in history:  # the backlog again, on top of the snapshot
        apply(snapshot, activity)
    assert list(snapshot) == ["c1"]
    assert [d.serial for d in snapshot["c1"].attached_devices] == ["serial"]
    assert snapshot["c1"].HasField("hold")


async def follows_relays_and_survives_a_restart():
    daemon = FakeDaemon()
    daemon.push(opened("c1", "pytest"))
    daemon.snapshot = [
        connection.ConnectionEntry(client_connection_id="c1", name="pytest")
    ]
    server, endpoint = await serve(daemon)
    link = Daemon(endpoint)
    hub = ActivityHub(link)
    following = asyncio.create_task(hub.run(retry_s=0.05))
    try:
        await until(lambda: hub.connected)
        page = hub.subscribe(0)
        assert [c.name for c in page.connections] == ["pytest"]
        assert [a.seq for a in page.backlog] == [1]
        daemon.push(event(1, "serial", 1, 2))
        batch = await next_batch(hub, page)
        assert [a.seq for a in batch] == [2]
        assert batch[0].event.serial == "serial"

        # The daemon restarts (new pid, its seq starts again at 1, c1 is gone).
        daemon.pid = 200
        daemon.activities.clear()
        daemon.snapshot = []
        restarted = opened("c9", "junit")
        restarted.seq = 1
        daemon.activities.append(restarted)  # in the new daemon's backlog only
        daemon.live.put_nowait(None)  # ends the current stream
        batch = await next_batch(hub, page)
        while len(batch) < 2:
            batch += await next_batch(hub, page)
        assert [a.seq for a in batch] == [3, 4]
        assert batch[0].connection_closed.reason == "daemon restarted"
        assert batch[1].connection_opened.name == "junit"
        assert list(hub.connections) == ["c9"]
        assert hub.names == {"c1": "pytest", "c9": "junit"}
        # The second Watch after the restart asked for everything (after_seq 0).
        assert [name for name, _ in daemon.calls].count("Watch") == 2
        assert {name for name, _ in daemon.calls} <= {"Info", "Watch"}
        assert all(
            ("authorization", "Bearer secret") in metadata
            for _, metadata in daemon.calls
        )
    finally:
        following.cancel()
        hub.close()
        await link.close()
        await server.stop(0)


def test_follows_relays_and_survives_a_restart():
    asyncio.run(follows_relays_and_survives_a_restart())


async def slow_page_is_dropped():
    hub = ActivityHub(Daemon(), reader_queue=2)
    page = hub.subscribe(0)
    for index in range(3):
        hub._append(opened(f"c{index}", "x"))
    try:
        await hub.next_batch(page)
    except ReaderOverflow:
        return
    raise AssertionError("expected ReaderOverflow")


def test_a_slow_page_is_dropped_and_resumes_by_seq():
    asyncio.run(slow_page_is_dropped())
    hub = ActivityHub(Daemon(), history=2)
    for index in range(3):
        hub._append(opened(f"c{index}", "x"))
    resumed = hub.subscribe(1)
    assert [a.seq for a in resumed.backlog] == [2, 3]
    assert resumed.dropped == 1
