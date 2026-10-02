"""Read-only Connect service forwarding inventory and event streams to tap.v1.

No Tap connection is created. This module never calls Attach, Observe, Events (which
renews owner activity), Execute, Screenshot, or app lifecycle methods.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator

import grpc
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from tap_e2e.client import Endpoint, resolve_endpoint
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import client_connection_pb2_grpc, device_pb2_grpc, video_pb2_grpc
from tap_e2e.proto import device_pb2 as device
from tap_e2e.proto import video_pb2 as video

from ._gen import watcher_pb2 as pb


class Watcher:
    """Shared daemon reads only; the token is kept on the local back end."""

    def __init__(self, endpoint: Endpoint | None = None) -> None:
        self._endpoint = endpoint
        self._channel: grpc.aio.Channel | None = None
        self._opening = asyncio.Lock()
        self._metadata: tuple[tuple[str, str], ...] = ()
        self._connections: (
            client_connection_pb2_grpc.ClientConnectionServiceStub | None
        ) = None
        self._devices: device_pb2_grpc.DeviceServiceStub | None = None
        self._video: video_pb2_grpc.VideoServiceStub | None = None

    async def _open(self) -> None:
        async with self._opening:
            if self._channel is not None:
                return
            try:
                endpoint = self._endpoint or await asyncio.to_thread(resolve_endpoint)
            except Exception as error:
                raise ConnectError(Code.UNAVAILABLE, str(error)) from error
            self._metadata = (
                (("authorization", f"Bearer {endpoint.token}"),)
                if endpoint.token
                else ()
            )
            self._channel = grpc.aio.insecure_channel(
                endpoint.address,
                options=[("grpc.max_receive_message_length", 64 * 1024 * 1024)],
            )
            self._connections = client_connection_pb2_grpc.ClientConnectionServiceStub(
                self._channel
            )
            self._devices = device_pb2_grpc.DeviceServiceStub(self._channel)
            self._video = video_pb2_grpc.VideoServiceStub(self._channel)

    async def list_devices(
        self, request: pb.ListDevicesRequest, ctx: RequestContext
    ) -> pb.ListDevicesResponse:
        """Inventory plus ownership metadata; no driver reads or owner activity."""
        await self._open()
        assert self._devices is not None and self._connections is not None
        try:
            devices, owners = await asyncio.gather(
                self._devices.ListDevices(
                    device.ListDevicesRequest(), metadata=self._metadata, timeout=30
                ),
                self._connections.ListConnections(
                    connection.ListConnectionsRequest(),
                    metadata=self._metadata,
                    timeout=10,
                ),
            )
        except grpc.aio.AioRpcError as error:
            raise _error(error) from error
        return pb.ListDevicesResponse(
            devices=devices.devices, connections=owners.connections
        )

    async def watch_events(
        self, request: pb.WatchEventsRequest, ctx: RequestContext
    ) -> AsyncGenerator[pb.WatchEventsResponse, None]:
        """Relay backlog/live updates; cancellation only cancels this reader's RPC."""
        if not request.observed_connection_id or request.after_seq < 0:
            raise ConnectError(
                Code.INVALID_ARGUMENT,
                "observed_connection_id is required; after_seq must be non-negative",
            )
        await self._open()
        assert self._connections is not None
        call = self._connections.WatchEvents(
            connection.WatchEventsRequest(
                observed_connection_id=request.observed_connection_id,
                after_seq=request.after_seq,
            ),
            metadata=self._metadata,
        )
        try:
            async for update in call:
                yield pb.WatchEventsResponse(update=update)
        except grpc.aio.AioRpcError as error:
            raise _error(error) from error
        finally:
            call.cancel()

    async def watch_video(
        self, request: video.WatchVideoRequest, ctx: RequestContext
    ) -> AsyncGenerator[video.WatchVideoResponse, None]:
        """Relay shared video without attachment, owner calls, input or screen wake."""
        if not request.serial:
            raise ConnectError(Code.INVALID_ARGUMENT, "serial is required")
        await self._open()
        assert self._video is not None
        call = self._video.WatchVideo(request, metadata=self._metadata)
        try:
            async for update in call:
                yield update
        except grpc.aio.AioRpcError as error:
            raise _error(error) from error
        finally:
            call.cancel()

    async def close(self) -> None:
        """Close only the channel: no connection/device is owned or disconnected."""
        async with self._opening:
            if self._channel is not None:
                await self._channel.close()
                self._channel = None


def _error(error: grpc.aio.AioRpcError) -> ConnectError:
    codes = {
        grpc.StatusCode.INVALID_ARGUMENT: Code.INVALID_ARGUMENT,
        grpc.StatusCode.NOT_FOUND: Code.NOT_FOUND,
        grpc.StatusCode.UNAUTHENTICATED: Code.UNAUTHENTICATED,
        grpc.StatusCode.PERMISSION_DENIED: Code.PERMISSION_DENIED,
        grpc.StatusCode.RESOURCE_EXHAUSTED: Code.RESOURCE_EXHAUSTED,
        grpc.StatusCode.UNIMPLEMENTED: Code.UNIMPLEMENTED,
    }
    return ConnectError(
        codes.get(error.code(), Code.UNAVAILABLE),
        error.details() or "daemon request failed",
    )
