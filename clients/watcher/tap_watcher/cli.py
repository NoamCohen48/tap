"""Launch the standalone loopback watcher; never start the Tap daemon."""

from __future__ import annotations

import argparse
import secrets
import socket

import uvicorn

from .server import create_app


def main(argv: list[str] | None = None) -> None:
    """Serve the watcher on a chosen loopback port and print its per-launch login URL."""
    parser = argparse.ArgumentParser(prog="tap-watcher")
    parser.add_argument(
        "--port",
        type=int,
        default=0,
        help="loopback port (default: choose an unused port)",
    )
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("--port must be 0..65535")
    token = secrets.token_urlsafe(32)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", args.port))
        listener.listen(128)
        port = listener.getsockname()[1]
        print(f"Tap Watch: http://127.0.0.1:{port}/login?t={token}", flush=True)
        config = uvicorn.Config(
            create_app(token), host="127.0.0.1", port=port, access_log=False
        )
        uvicorn.Server(config).run(sockets=[listener])


if __name__ == "__main__":
    main()
