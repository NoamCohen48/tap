#!/usr/bin/env python3
"""Exercise daemon administration through a built tap executable, with fake ADB and no devices.

Run after installing clients/python: python .github/scripts/daemon_cli_smoke.py /path/to/tap
The Python SDK is used only to create test sessions, never by the CLI under test.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# Installed by the smoke job; the repository-root type checker has no client source path.
from tap_e2e import TapClient  # pyright: ignore[reportMissingImports]


def main() -> int:
    tap = Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory(prefix="tap-cli-smoke-") as temp:
        root = Path(temp)
        state = root / "state"
        adb = root / "adb"
        adb.write_text("#!/bin/sh\ncase \"$1\" in\n  devices) printf 'List of devices attached\\n' ;;\n  version) printf 'Android Debug Bridge version 1.0.41\\n' ;;\n  *) echo 'unexpected device access' >&2; exit 64 ;;\nesac\n")
        adb.chmod(0o700)
        marker = root / "agent-invoked"
        agent = root / "tap-agent"
        agent.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 97\n")
        agent.chmod(0o700)
        environment = dict(os.environ, PATH=f"{root}{os.pathsep}{os.environ.get('PATH', '')}")
        environment.pop("TAP_SCRCPY_SERVER", None)

        def run(*args: str, code: int = 0, env: dict[str, str] | None = None) -> str:
            result = subprocess.run([str(tap), "--state-dir", str(state), *args], env=env or environment, text=True, capture_output=True, timeout=150)
            assert result.returncode == code, (args, result.returncode, result.stdout, result.stderr)
            if code == 0 and (not args or args[0] in {"help", "completion", "version", "--version", "--help", "-h"} or "--help" in args):
                assert not result.stderr, (args, result.stderr)
            # Newer JVM/native runtimes can emit Netty native-access warnings on channel creation.
            # They are not CLI failures; help/completion above must still be side-effect-free.
            return result.stdout

        def data(*args: str, code: int = 0) -> dict:
            output = run(*args, "--json", code=code)
            try:
                return json.loads(output)
            except json.JSONDecodeError as error:
                raise AssertionError(f"CLI returned invalid JSON for {args}: {output!r}") from error

        for args in ((), ("--help",), ("-h",), ("help",), ("start", "--help"), ("help", "sessions", "close"), ("unquarantine", "--help"), ("config", "logging-policy", "--help"), ("help", "config", "logging-policy", "rolling")):
            assert "Usage:" in run(*args)
        assert not state.exists(), "help must not create daemon state"
        assert "tap daemon" in run("--version")
        assert data("status", code=1)["state"] == "stopped"
        run("devices", "--force", code=2)
        assert "max-age=3600000ms max-size=268435456B hard-size=1073741824B min-free=268435456B" in run("config", "logging-policy", "show")
        assert not state.exists(), "default policy inspection must not create state"
        run("config", "logging-policy", "rolling", "--max-age", "10m", "--max-size", "2MiB", "--hard-size", "3MiB", "--min-free", "0")
        assert (state / "logging-policy.properties").stat().st_mode & 0o777 == 0o600
        before = (state / "logging-policy.properties").read_bytes()
        run("config", "logging-policy", "rolling", "--hard-size", "1MiB", code=2)
        assert (state / "logging-policy.properties").read_bytes() == before
        for shell in ("bash", "zsh", "fish"):
            script = root / f"tap.{shell}"
            script.write_text(run("completion", shell))
            if shell == "bash":
                subprocess.run(["bash", "-n", str(script)], check=True)
            elif shell == "zsh" and shutil.which("zsh"):
                subprocess.run(["zsh", "-n", str(script)], check=True)
            elif shell == "fish" and shutil.which("fish"):
                subprocess.run(["fish", "--no-execute", str(script)], check=True)

        try:
            run("start", "--adb", str(adb), "--driver-idle-timeout-ms", "1000")
            assert data("status")["healthy"]
            assert data("devices")["devices"] == []
            assert data("sessions")["sessions"] == []
            descriptor = json.loads((state / "daemon.json").read_text())
            with TapClient.create(f"127.0.0.1:{descriptor['port']}", token=descriptor["token"]) as client:
                connection = client.connect("cli-smoke-held", hold=60)
                session_id = connection.id
                assert data("sessions", "show", session_id)["name"] == "cli-smoke-held"
                run("restart", code=1)
                run("sessions", "close", session_id, code=2)
                closed = data("sessions", "close", session_id, "--force", "--reason", "CLI smoke")
                assert closed["closed"] and closed["cleanup_issues"] == {}
                assert closed["reason"] == "administrator: CLI smoke"
                connection.close()
                client.connect("cli-smoke-restart", hold=60)
                changed_environment = dict(environment, TAP_SCRCPY_SERVER="/must-not-be-inherited.jar")
                run("restart", "--force", env=changed_environment)
            assert data("sessions")["sessions"] == []
            config = data("config", "show")
            assert config["running"]["adb"]["value"] == str(adb)
            assert config["running"]["driver_idle_timeout_ms"]["value"] == "1000"
            assert "scrcpy_server" not in config["running"]
            run("restart")
            assert data("status")["healthy"]
            run("doctor")
            run("logs", "--tail", "1")
            bundle = root / "diagnostics.zip"
            run("diagnostics", "--out", str(bundle))
            current_token = json.loads((state / "daemon.json").read_text())["token"]
            with zipfile.ZipFile(bundle) as archive:
                for name in archive.namelist():
                    content = archive.read(name).decode()
                    assert current_token not in content
                    if name.endswith(".json"):
                        json.loads(content)
            run("diagnostics", "--out", str(bundle), code=1)
            assert not marker.exists(), "tap must never invoke tap-agent"
        finally:
            run("stop")
        assert data("status", code=1)["state"] == "stopped"
    print("daemon CLI: help, discovery, close, restart, config, doctor, logs, diagnostics and completion passed (no devices)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
