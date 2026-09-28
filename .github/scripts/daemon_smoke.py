#!/usr/bin/env python3
"""Calls every tap.v1 RPC that needs no device against a running daemon (`tap start`), through
tap-e2e. The native image registers protobuf messages by reflection, so a message missing from
its metadata fails only when a call carries it; this catches that without an emulator."""

from __future__ import annotations

import sys

from tap_e2e import TapClient


def main() -> int:
    with TapClient.create() as client:
        info = client.info()
        print("info:", info.daemon_version, info.protocol_version)
        print("devices:", [d.serial for d in client.devices()])
        connection = client.connect("daemon-smoke", hold=60)
        try:
            assert connection.id in {c.id for c in client.connections()}
            assert client.resume("daemon-smoke").id == connection.id
            log = connection.event_log()
            assert log.events == () and log.dropped == 0, log
            print("held connection, list, resume, events: ok")
        finally:
            connection.close()
        with client.connect("daemon-smoke-observed") as observed:
            assert observed.usable
        print("observed connection: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
