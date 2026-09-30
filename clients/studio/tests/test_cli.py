"""The ``tap-studio`` process: how it shuts down."""

from __future__ import annotations

import asyncio
import signal

import uvicorn

from tap_studio.cli import _Server


class _Service:
    def __init__(self) -> None:
        self.closed = 0

    async def close(self) -> None:
        self.closed += 1


def test_an_exit_signal_releases_the_device_before_uvicorn_waits_for_open_streams():
    # A page's Frames stream ends only when the device is released, and uvicorn waits for open
    # responses before it shuts the app down: the release has to start at the signal.
    service = _Service()
    server = _Server(service, uvicorn.Config(lambda scope, receive, send: None))  # type: ignore[arg-type]

    async def signalled() -> None:
        server.handle_exit(signal.SIGTERM, None)
        server.handle_exit(signal.SIGINT, None)  # a second Ctrl-C does not release twice
        await asyncio.sleep(0)

    asyncio.run(signalled())
    assert server.should_exit
    assert service.closed == 1
