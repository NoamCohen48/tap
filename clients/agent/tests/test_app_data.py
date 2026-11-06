"""Agent private-data paths never print contents or implicitly stop/relaunch an app."""
# pyright: reportMissingImports=false
import stat

import pytest
from mcp import Client
from mcp_types import TextContent

from tap_agent import AgentError, cli
from tap_agent.core import EXIT_USAGE
from tap_agent.mcp_server import create_server


@pytest.fixture
def server(agent):
    return create_server(lambda _: agent, default_session="agent")


def test_cli_private_files_and_bundles_use_owner_only_outputs(fake, agent, tmp_path):
    agent.attach("emulator-5554")
    fake.app_data.files.update({"files/secret": b"secret\x00\xff", "databases/test.db": b"db", "databases/test.db-wal": b"wal", "databases/test.db-journal": b"journal"})
    assert "available" in agent.app_data("capabilities", "com.example")
    target = tmp_path / "download"
    args = cli.parser().parse_args(["app-data", "read", "com.example", "files/secret", str(target)])
    result = cli.run(args, agent)
    assert "secret" not in result
    assert target.read_bytes() == b"secret\x00\xff"
    assert stat.S_IMODE(target.stat().st_mode) == 0o600
    with pytest.raises(AgentError, match="already exists"):
        cli.run(args, agent)
    agent.app_data("write", "com.example", "files/new", str(target), overwrite=True)
    assert fake.app_data.uploads[-1][0].header.overwrite
    directory = tmp_path / "bundle"
    agent.app_data("export-db", "com.example", "test.db", str(directory))
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    assert {p.name for p in directory.iterdir()} == {"test.db", "test.db-wal", "test.db-journal"}
    agent.app_data("import-db", "com.example", "test.db", str(directory), overwrite=True)
    assert [p.header.path for p in fake.app_data.uploads[-1] if p.HasField("header")] == ["databases/test.db", "databases/test.db-wal", "databases/test.db-journal"]
    assert not fake.apps.force_stops and not fake.apps.launches


@pytest.mark.parametrize("action,path,local,overwrite", [
    ("read", "files/a", None, False), ("write", "files/a", None, False),
    ("capabilities", "unexpected", None, False), ("delete", "files/a", "local", False),
    ("list", "", None, True), ("import-db", "../escape", "dir", True),
    ("export-db", "test.db-wal", "dir", False),
])
def test_invalid_private_data_arguments_fail_before_session_or_input(agent, action, path, local, overwrite):
    with pytest.raises(AgentError) as error:
        agent.app_data(action, "com.example", path, local, overwrite=overwrite)
    assert error.value.exit_code == EXIT_USAGE


@pytest.mark.anyio
async def test_mcp_private_data_uses_same_core_and_never_returns_bytes(fake, server, tmp_path):
    fake.app_data.files["files/secret"] = b"never print me"
    async with Client(server, mode="legacy") as client:
        await client.call_tool("attach", {"serial": "emulator-5554"})
        result = await client.call_tool("app_data", {"action": "read", "package": "com.example", "path": "files/secret", "local": str(tmp_path / "download")})
        assert not result.is_error
        assert isinstance(result.content[0], TextContent)
        assert "never print me" not in result.content[0].text
        assert (tmp_path / "download").read_bytes() == b"never print me"
