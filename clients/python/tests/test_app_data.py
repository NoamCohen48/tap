"""Fixture-only private files and real SQLite WAL recovery through the public client."""
# pyright: reportMissingImports=false
import sqlite3

import pytest

from tap_e2e import AppDataError, DatabaseBundle, text
from conftest import launch


def test_private_files_and_wal_database_round_trip(tap_device, tmp_path):
    app = launch(tap_device)
    app.force_stop()
    app.launch(".PrivateDataActivity", extras={"seed": True})
    app.wait(text("database value: 41")).visible()
    assert app.data.capabilities().available
    with pytest.raises(AppDataError) as running:
        app.data.list()
    assert running.value.detail == "APP_RUNNING"
    app.force_stop()
    assert app.data.read("files/binary.bin") == bytes(range(256)) * 4
    with pytest.raises(AppDataError) as link:
        app.data.read("files/link")
    assert link.value.detail == "SYMLINK"
    payload = b"private-payload-secret\0\xff\0"
    path = "files/é ' $literal.bin"
    app.data.write(path, payload)
    assert app.data.read(path) == payload
    app.data.delete(path)
    bundle = app.data.export_database("tap-debug.db")
    assert bundle.wal, "The killed fixture must retain committed WAL"
    local = tmp_path / bundle.name
    local.write_bytes(bundle.database)
    (tmp_path / (bundle.name + "-wal")).write_bytes(bundle.wal)
    if bundle.journal is not None:
        (tmp_path / (bundle.name + "-journal")).write_bytes(bundle.journal)
    connection = sqlite3.connect(local)
    try:
        assert connection.execute("SELECT value FROM state").fetchone() == (41,)
        connection.execute("UPDATE state SET value = 99")
        connection.commit()
    finally:
        connection.close()
    changed = DatabaseBundle(bundle.name, local.read_bytes(),
        (tmp_path / (bundle.name + "-wal")).read_bytes() if (tmp_path / (bundle.name + "-wal")).exists() else None,
        (tmp_path / (bundle.name + "-journal")).read_bytes() if (tmp_path / (bundle.name + "-journal")).exists() else None)
    app.clear_data()
    app.data.import_database(bundle)
    assert not app.is_running()
    app.launch(".PrivateDataActivity")
    app.wait(text("database value: 41")).visible()
    app.force_stop()
    app.data.import_database(changed, overwrite=True)
    assert not app.is_running()
    app.launch(".PrivateDataActivity")
    app.wait(text("database value: 99")).visible()
    app.force_stop()
