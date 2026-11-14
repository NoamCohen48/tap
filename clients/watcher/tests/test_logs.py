"""The back end follows every attached device's log and serves it per serial."""

from __future__ import annotations

import asyncio
from unittest.mock import Mock

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from fakes import FakeDaemon, attached, line, serve, until
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import watch_pb2 as watch

from tap_watcher._gen import watcher_pb2 as pb
from tap_watcher.recordings import Library
from tap_watcher.service import Watcher


async def running(tmp_path, daemon: FakeDaemon):
    server, endpoint = await serve(daemon)
    watcher = Watcher(Library(tmp_path), endpoint)
    await watcher.start()
    await until(lambda: watcher.activity.connected)
    return server, watcher


def messages(records) -> list[tuple[int, str]]:
    return [(r.seq, r.entry.line.message) for r in records]


def test_a_page_reads_the_retained_log_and_then_follows_it(tmp_path):
    async def scenario():
        daemon = FakeDaemon()
        daemon.logs["a1"] = [line(1, 10), line(2, 20)]
        daemon.push(attached("a1", "serial"))
        server, watcher = await running(tmp_path, daemon)
        ctx = Mock(spec=RequestContext)
        try:
            await until(lambda: watcher.logs._devices.get("serial") is not None)
            await until(lambda: watcher.logs._devices["serial"].seq == 2)
            # The read-only call: no connection id, only the attachment.
            assert daemon.log_calls == [("a1", 0)]
            stream = watcher.watch_log(pb.WatchLogRequest(serial="serial"), ctx)
            first = await anext(stream)
            assert messages(first.records) == [(1, "line 1"), (2, "line 2")]
            assert (first.dropped, first.unavailable) == (0, "")
            assert {r.clock_id for r in first.records} == {"clock"}
            daemon.log("a1", line(3, 30))
            assert messages((await anext(stream)).records) == [(3, "line 3")]
            await stream.aclose()

            # The device is detached and attached again: its cursors restart, the serial's
            # sequence goes on, and the first attachment's lines are still there.
            daemon.log("a1", None)
            await until(lambda: "a1" not in watcher.logs._following)
            daemon.logs["a2"] = [line(1, 40, "second attachment")]
            daemon.push(attached("a2", "serial"))
            await until(lambda: watcher.logs._devices["serial"].seq == 4)
            resumed = watcher.watch_log(
                pb.WatchLogRequest(serial="serial", after_seq=2), ctx
            )
            assert messages((await anext(resumed)).records) == [
                (3, "line 3"),
                (4, "second attachment"),
            ]
            await resumed.aclose()

            with pytest.raises(ConnectError) as refused:
                await anext(watcher.watch_log(pb.WatchLogRequest(), ctx))
            assert refused.value.code == Code.INVALID_ARGUMENT
        finally:
            await watcher.close()
            await server.stop(0)

    asyncio.run(scenario())


def test_attachments_older_than_the_watcher_are_followed_from_the_snapshot(tmp_path):
    async def scenario():
        daemon = FakeDaemon()
        daemon.snapshot = [
            connection.ConnectionEntry(
                client_connection_id="c1",
                name="suite",
                attached_devices=[
                    connection.AttachedDeviceEntry(
                        attached_device_id="a1", serial="serial"
                    ),
                    connection.AttachedDeviceEntry(
                        attached_device_id="off", serial="quiet"
                    ),
                ],
            )
        ]
        daemon.logs["a1"] = [line(1, 10)]
        daemon.log_off.add("off")
        server, watcher = await running(tmp_path, daemon)
        ctx = Mock(spec=RequestContext)
        try:
            await until(lambda: len(daemon.log_calls) == 2)
            await until(lambda: watcher.logs._devices["serial"].seq == 1)
            await until(lambda: watcher.logs._devices["quiet"].unavailable != "")
            quiet = watcher.watch_log(pb.WatchLogRequest(serial="quiet"), ctx)
            first = await anext(quiet)
            assert not first.records and "capture is off" in first.unavailable
            await quiet.aclose()
            # Told again about an attachment it already follows: still one stream.
            daemon.push(attached("a1", "serial"))
            await until(lambda: len(watcher.activity._history) == 1)
            await asyncio.sleep(0.05)
            assert [call for call in daemon.log_calls if call[0] == "a1"] == [("a1", 0)]
        finally:
            await watcher.close()
            await server.stop(0)

    asyncio.run(scenario())


def test_the_history_keeps_the_newest_entries_and_reports_what_left(tmp_path):
    async def scenario():
        daemon = FakeDaemon()
        daemon.logs["a1"] = [line(cursor, cursor) for cursor in range(1, 6)]
        daemon.push(attached("a1", "serial"))
        server, endpoint = await serve(daemon)
        watcher = Watcher(Library(tmp_path), endpoint)
        watcher.logs._capacity = 3
        await watcher.start()
        ctx = Mock(spec=RequestContext)
        try:
            await until(lambda: watcher.logs._devices.get("serial") is not None)
            await until(lambda: watcher.logs._devices["serial"].seq == 5)
            stream = watcher.watch_log(pb.WatchLogRequest(serial="serial"), ctx)
            first = await anext(stream)
            assert [r.seq for r in first.records] == [3, 4, 5]
            assert first.dropped == 2
            await stream.aclose()
            assert [
                r.seq for r in watcher.logs.records("serial", "clock", 4, 5)
            ] == [4, 5]
            assert watcher.logs.records("serial", "other-clock", 0, 10) == []
        finally:
            await watcher.close()
            await server.stop(0)

    asyncio.run(scenario())


def test_watch_log_is_the_only_log_call_the_watcher_makes(tmp_path):
    async def scenario():
        daemon = FakeDaemon()
        daemon.logs["a1"] = [line(1, 10)]
        daemon.push(attached("a1", "serial"))
        server, watcher = await running(tmp_path, daemon)
        try:
            await until(lambda: len(daemon.log_calls) == 1)
            assert {name for name, _ in daemon.calls} <= {
                "Info",
                "ListDevices",
                "Watch",
                "WatchVideo",
                "WatchLog",
            }
            assert isinstance(daemon.activities[0], watch.Activity)
        finally:
            await watcher.close()
            await server.stop(0)

    asyncio.run(scenario())
