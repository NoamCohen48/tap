"""Real gRPC transfers and defensive decoding; filesystem safety is tested in host core."""
import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import DatabaseBundle, TapClient
from tap_e2e import _gen as pb
from tap_e2e.app_data import CHUNK_BYTES, MAX_BYTES
from .conftest import TOKEN


@pytest.fixture
def app(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("app-data") as connection:
            yield connection.attach_device("emulator-5554").app("com.example.fixture")


def test_probe_list_and_binary_download_are_owner_scoped(fake, app):
    payload = bytes(range(256)) * 8193
    fake.app_data.files["files/state.bin"] = payload
    assert app.data.capabilities().available
    assert app.data.capabilities().reason is None
    entries = app.data.list("files")
    assert len(entries) == 1 and entries[0].name == "state.bin" and entries[0].size_bytes == len(payload)
    assert app.data.read("files/state.bin") == payload
    assert all(r.app == app._target() for r in fake.app_data.requests)
    assert not fake.apps.force_stops


def test_upload_chunks_are_binary_safe_and_explicit_about_overwrite(fake, app):
    payload = bytes(range(256)) * 8193
    app.data.write("files/state.bin", payload)
    parts = fake.app_data.uploads[-1]
    assert parts[0].header == pb.AppDataHeader(app=app._target(), path="files/state.bin", size_bytes=len(payload))
    assert len(parts) == 4
    assert all(len(p.chunk) <= CHUNK_BYTES for p in parts[1:])
    assert b"".join(p.chunk for p in parts[1:]) == payload
    app.data.write("files/empty", b"", overwrite=True)
    assert len(fake.app_data.uploads[-1]) == 1
    assert fake.app_data.uploads[-1][0].header.overwrite
    app.data.delete("files/state.bin")
    assert fake.app_data.requests[-1] == pb.DeleteFileRequest(app=app._target(), path="files/state.bin")


def test_database_bundle_keeps_both_persistent_sidecars_and_can_be_renamed(fake, app):
    fake.app_data.files.update({"databases/state.db": b"base", "databases/state.db-wal": b"wal", "databases/state.db-journal": b"rollback", "databases/state.db-shm": b"not portable"})
    bundle = app.data.export_database("state.db")
    assert bundle == DatabaseBundle("state.db", b"base", b"wal", b"rollback")
    app.data.import_database(bundle, name="restored.db", overwrite=True)
    headers = [part.header for part in fake.app_data.uploads[-1] if part.HasField("header")]
    assert [h.path for h in headers] == ["databases/restored.db", "databases/restored.db-wal", "databases/restored.db-journal"]
    assert all(h.app == app._target() and h.overwrite for h in headers)
    assert not fake.apps.force_stops


@pytest.mark.parametrize("variant", ["empty", "chunk_first", "truncated", "overflow", "wrong_path", "wrong_owner", "duplicate", "oversize", "oversized_chunk", "unset"])
def test_malformed_downloads_are_rejected(fake, app, variant):
    header = pb.AppDataPart(header=pb.AppDataHeader(app=app._target(), path="files/a", size_bytes=1))
    parts = [header, pb.AppDataPart(chunk=b"a")]
    if variant == "empty": parts = []
    elif variant == "chunk_first": parts = parts[1:]
    elif variant == "truncated": parts = parts[:1]
    elif variant == "overflow": parts[1] = pb.AppDataPart(chunk=b"ab")
    elif variant == "wrong_path": header.header.path = "files/b"
    elif variant == "wrong_owner": header.header.app.client_connection_id = "other"
    elif variant == "duplicate": parts += [header]
    elif variant == "oversize": header.header.size_bytes = MAX_BYTES + 1
    elif variant == "oversized_chunk":
        header.header.size_bytes = CHUNK_BYTES + 1
        parts[1] = pb.AppDataPart(chunk=b"a" * (CHUNK_BYTES + 1))
    elif variant == "unset": parts = [pb.AppDataPart()]
    fake.app_data.response_parts = parts
    with pytest.raises(ValueError):
        app.data.read("files/a")


def test_bundle_requires_base_database_first(fake, app):
    fake.app_data.response_parts = [pb.AppDataPart(header=pb.AppDataHeader(app=app._target(), path="databases/a-wal", size_bytes=0))]
    with pytest.raises(ValueError, match="first"):
        app.data.export_database("a")
    with pytest.raises(ValueError, match="nonempty"):
        app.data.import_database(DatabaseBundle("a", b""))
    assert not fake.app_data.uploads
