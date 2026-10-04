"""The back end's one channel to the Tap daemon, limited to read-only calls.

Nothing here calls Connect, Attach, Observe, Events (which renews a held connection), Execute,
Screenshot or app lifecycle methods. The token stays in this process.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator

import grpc
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from tap_e2e.client import Endpoint, resolve_endpoint
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import client_connection_pb2_grpc, device_pb2_grpc, watch_pb2_grpc
from tap_e2e.proto import device_pb2 as device
from tap_e2e.proto import watch_pb2 as watch


class Daemon:
    """Opens lazily (the daemon may start after the watcher) and reopens after a failure."""

    def __init__(self, endpoint: Endpoint | None = None) -> None:
        self._endpoint = endpoint
        self._channel: grpc.aio.Channel | None = None
        self._opening = asyncio.Lock()
        self._metadata: tuple[tuple[str, str], ...] = ()
        self._connections: (
            client_connection_pb2_grpc.ClientConnectionServiceStub | None
        ) = None
        self._devices: device_pb2_grpc.DeviceServiceStub | None = None
        self._watch: watch_pb2_grpc.WatchServiceStub | None = None

    async def _open(self) -> None:
        async with self._opening:
            if self._channel is not None:
                return
            try:
                endpoint = self._endpoint or await asyncio.to_thread(resolve_endpoint)
            except Exception as error:
                raise ConnectError(
                    Code.UNAVAILABLE, f"Tap daemon not found: {error}"
                ) from error
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
            self._watch = watch_pb2_grpc.WatchServiceStub(self._channel)

    async def reset(self) -> None:
        """Drop the channel so the next call resolves the endpoint again (a restarted daemon
        writes a new port and token)."""
        async with self._opening:
            if self._channel is not None and self._endpoint is None:
                await self._channel.close()
                self._channel = None

    async def info(self) -> connection.InfoResponse:
        await self._open()
        assert self._connections is not None
        try:
            return await self._connections.Info(
                connection.InfoRequest(), metadata=self._metadata, timeout=5
            )
        except grpc.aio.AioRpcError as error:
            raise to_connect(error) from error

    async def list_devices(self) -> device.ListDevicesResponse:
        await self._open()
        assert self._devices is not None
        try:
            return await self._devices.ListDevices(
                device.ListDevicesRequest(), metadata=self._metadata, timeout=30
            )
        except grpc.aio.AioRpcError as error:
            raise to_connect(error) from error

    async def watch(self, after_seq: int) -> AsyncGenerator[watch.WatchResponse, None]:
        await self._open()
        assert self._watch is not None
        call = self._watch.Watch(
            watch.WatchRequest(after_seq=after_seq), metadata=self._metadata
        )
        try:
            async for response in call:
                yield response
        except grpc.aio.AioRpcError as error:
            raise to_connect(error) from error
        finally:
            call.cancel()

    async def watch_video(
        self, serial: str
    ) -> AsyncGenerator[watch.WatchVideoResponse, None]:
        await self._open()
        assert self._watch is not None
        call = self._watch.WatchVideo(
            watch.WatchVideoRequest(serial=serial), metadata=self._metadata
        )
        try:
            async for response in call:
                yield response
        except grpc.aio.AioRpcError as error:
            raise to_connect(error) from error
        finally:
            call.cancel()

    async def close(self) -> None:
        """Closes only the channel: the watcher owns no connection or device."""
        async with self._opening:
            if self._channel is not None:
                await self._channel.close()
                self._channel = None


_CODES = {
    grpc.StatusCode.INVALID_ARGUMENT: Code.INVALID_ARGUMENT,
    grpc.StatusCode.NOT_FOUND: Code.NOT_FOUND,
    grpc.StatusCode.FAILED_PRECONDITION: Code.FAILED_PRECONDITION,
    grpc.StatusCode.UNAUTHENTICATED: Code.UNAUTHENTICATED,
    grpc.StatusCode.PERMISSION_DENIED: Code.PERMISSION_DENIED,
    grpc.StatusCode.RESOURCE_EXHAUSTED: Code.RESOURCE_EXHAUSTED,
    grpc.StatusCode.UNIMPLEMENTED: Code.UNIMPLEMENTED,
}


def to_connect(error: grpc.aio.AioRpcError) -> ConnectError:
    """The daemon's status as a Connect error; anything unexpected is UNAVAILABLE."""
    return ConnectError(
        _CODES.get(error.code(), Code.UNAVAILABLE),
        error.details() or "daemon request failed",
    )
