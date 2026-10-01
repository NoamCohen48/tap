"""``tap-studio``: serve the studio on a loopback port and open it in the browser."""

from __future__ import annotations

import argparse
import asyncio
import secrets
import socket
import sys
import webbrowser

import uvicorn

from . import __version__
from ._gen import studio_pb2 as studio
from .server import create_app
from .service import Studio


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="tap-studio",
        description="Inspect an Android app's screen in the browser and record what you do as tap-recording/1 JSON.",
    )
    parser.add_argument("--port", type=int, default=0, help="loopback port to serve on (default: a free one)")
    parser.add_argument("--no-open", action="store_true", help="print the link but do not open a browser")
    parser.add_argument(
        "--page-origin",
        metavar="URL",
        help="print the link on this origin instead (page development: the Vite dev server, e.g. http://127.0.0.1:5173)",
    )
    parser.add_argument("--serial", help="attach this device at start")
    parser.add_argument("--version", action="version", version=f"tap-studio {__version__}")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    token = secrets.token_urlsafe(32)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("127.0.0.1", args.port))
    except OSError as error:
        print(f"tap-studio: cannot listen on 127.0.0.1:{args.port}: {error.strerror}", file=sys.stderr)
        return 1
    port = sock.getsockname()[1]
    origin = (args.page_origin or f"http://127.0.0.1:{port}").rstrip("/")
    link = f"{origin}/login?t={token}"
    print(f"Tap Studio {__version__} on http://127.0.0.1:{port}", flush=True)
    print(f"Open {link}", flush=True)
    if not args.no_open:
        webbrowser.open(link)
    attach = studio.AttachRequest(serial=args.serial) if args.serial else None
    service = Studio()
    app = create_app(token, service=service, attach=attach)
    server = _Server(service, uvicorn.Config(app, log_level="warning", lifespan="on", timeout_graceful_shutdown=5))
    try:
        server.run(sockets=[sock])
    except KeyboardInterrupt:
        pass
    return 0


class _Server(uvicorn.Server):
    """Releases the device as soon as an exit is asked for. uvicorn waits for open responses
    before it shuts the app down, and a page's ``Frames`` stream only ends when the device is
    released: without this, Ctrl-C or SIGTERM would wait on the stream (then, after the graceful
    timeout, cut it) while the device stays attached."""

    def __init__(self, service: Studio, config: uvicorn.Config) -> None:
        super().__init__(config)
        self._service = service
        self._releasing: asyncio.Task[None] | None = None

    def handle_exit(self, sig, frame) -> None:  # noqa: ANN001 - uvicorn's signal handler signature
        if self._releasing is None:
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                loop = None
            if loop is not None:
                self._releasing = loop.create_task(self._service.close())
        super().handle_exit(sig, frame)
