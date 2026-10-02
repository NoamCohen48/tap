"""The watcher never creates an owner or calls owner-renewing Events."""

from __future__ import annotations

import asyncio
from unittest.mock import Mock

import grpc
from connectrpc.request import RequestContext
from tap_e2e.client import Endpoint
from tap_e2e.proto import client_connection_pb2 as connections
from tap_e2e.proto import client_connection_pb2_grpc, device_pb2_grpc, event_log_pb2
from tap_e2e.proto import device_pb2 as devices
from tap_watcher._gen import watcher_pb2 as pb
from tap_watcher.service import Watcher


class Connections(client_connection_pb2_grpc.ClientConnectionServiceServicer):
    def __init__(self):
        self.calls = []
        self.cancelled = asyncio.Event()
        self.keep_open = False

    async def ListConnections(self, request, context):
        self.calls.append(("ListConnections", tuple(context.invocation_metadata())))
        return connections.ListConnectionsResponse(
            connections=[
                connections.ConnectionEntry(
                    client_connection_id="owner",
                    name="tap-agent",
                    attached_devices=[
                        connections.AttachedDeviceEntry(
                            serial="serial", attached_device_id="device"
                        )
                    ],
                )
            ]
        )

    async def WatchEvents(self, request, context):
        self.calls.append(("WatchEvents", tuple(context.invocation_metadata())))
        assert request.observed_connection_id == "owner"
        assert request.after_seq == 4
        try:
            yield connections.WatchEventsResponse(
                events=connections.EventsResponse(
                    events=[event_log_pb2.LoggedEvent(seq=5, serial="serial")]
                )
            )
            if self.keep_open:
                await asyncio.Event().wait()
            yield connections.WatchEventsResponse(
                closing=connections.Closing(reason="owner finished")
            )
        finally:
            self.cancelled.set()


class Devices(device_pb2_grpc.DeviceServiceServicer):
    def __init__(self):
        self.calls = []

    async def ListDevices(self, request, context):
        self.calls.append(("ListDevices", tuple(context.invocation_metadata())))
        return devices.ListDevicesResponse(
            devices=[
                devices.DeviceEntry(
                    serial="serial",
                    state=devices.DEVICE_LEASED,
                    client_connection_id="owner",
                )
            ]
        )


async def exercise(cancel=False):
    server = grpc.aio.server()
    connection_service, device_service = Connections(), Devices()
    connection_service.keep_open = cancel
    client_connection_pb2_grpc.add_ClientConnectionServiceServicer_to_server(
        connection_service, server
    )
    device_pb2_grpc.add_DeviceServiceServicer_to_server(device_service, server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    watcher = Watcher(Endpoint(f"127.0.0.1:{port}", "secret"))
    try:
        ctx = Mock(spec=RequestContext)
        inventory = await watcher.list_devices(pb.ListDevicesRequest(), ctx)
        assert inventory.devices[0].serial == "serial"
        assert inventory.connections[0].name == "tap-agent"
        stream = watcher.watch_events(
            pb.WatchEventsRequest(observed_connection_id="owner", after_seq=4), ctx
        )
        if cancel:
            assert (await anext(stream)).update.events.events[0].seq == 5
            await stream.aclose()
            await asyncio.wait_for(connection_service.cancelled.wait(), 2)
        else:
            updates = [response async for response in stream]
            assert updates[0].update.events.events[0].seq == 5
            assert updates[1].update.closing.reason == "owner finished"
        assert [name for name, _ in connection_service.calls] == [
            "ListConnections",
            "WatchEvents",
        ]
        assert [name for name, _ in device_service.calls] == ["ListDevices"]
        for _, metadata in connection_service.calls + device_service.calls:
            assert ("authorization", "Bearer secret") in metadata
    finally:
        await watcher.close()
        await server.stop(0)


def test_shared_inventory_and_event_stream_use_only_read_rpcs():
    asyncio.run(exercise())


def test_reader_cancellation_does_not_call_disconnect_or_detach():
    asyncio.run(exercise(cancel=True))
