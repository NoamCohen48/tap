"""``tap-studio``: serve the studio on a loopback port and open it in the browser."""

from __future__ import annotations

import argparse
import secrets
import socket
import sys
import webbrowser

import uvicorn

from . import __version__
from ._gen import studio_pb2 as studio
from .server import create_app


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
    parser.add_argument("--serial", help="attach this device at start (with --package)")
    parser.add_argument("--package", help="the app under test to attach --serial for")
    parser.add_argument("--version", action="version", version=f"tap-studio {__version__}")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if bool(args.serial) != bool(args.package):
        parser.error("--serial and --package go together")
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
    attach = studio.AttachRequest(serial=args.serial, aut_package=args.package) if args.serial else None
    app = create_app(token, attach=attach)
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning", lifespan="on"))
    try:
        server.run(sockets=[sock])
    except KeyboardInterrupt:
        pass
    return 0
