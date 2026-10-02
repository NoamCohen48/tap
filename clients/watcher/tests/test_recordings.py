"""Saved recordings survive application restarts and remain authenticated."""

import io
import json
import zipfile

from starlette.testclient import TestClient
from test_export import document

from tap_watcher.recordings import Recordings
from tap_watcher.server import create_app


def test_save_list_playback_and_download_survive_restart(tmp_path):
    directory = tmp_path / "library"
    source = document()
    source["serial"] = "fixture"
    with TestClient(
        create_app("secret", recordings_dir=directory), base_url="http://127.0.0.1:9000"
    ) as client:
        assert client.get("/recordings").status_code == 401
        client.get("/login?t=secret")
        first = client.post("/recordings", json=source)
        assert first.status_code == 200
        identifier = first.json()["id"]
        second = client.post("/recordings", json=source)
        assert second.status_code == 200
        assert second.json()["id"] != identifier
        assert len(client.get("/recordings").json()["recordings"]) == 2
    with TestClient(
        create_app("new-token", recordings_dir=directory),
        base_url="http://127.0.0.1:9000",
    ) as client:
        assert client.get(f"/recordings/{identifier}/video.mp4").status_code == 401
        client.get("/login?t=new-token")
        assert len(client.get("/recordings").json()["recordings"]) == 2
        response = client.get(
            f"/recordings/{identifier}/video.mp4", headers={"Range": "bytes=0-15"}
        )
        assert response.status_code == 206
        assert len(response.content) == 16
        with zipfile.ZipFile(
            io.BytesIO(client.get(f"/recordings/{identifier}/clip.zip").content)
        ) as archive:
            assert json.loads(archive.read("steps.json"))["serial"] == "fixture"
        assert client.get("/recordings/not-a-valid-id/video.mp4").status_code == 404
        assert client.get(f"/recordings/{identifier}/recording.json").status_code == 404


def test_quota_never_removes_existing_recordings(tmp_path, monkeypatch):
    import pytest

    import tap_watcher.recordings as module
    from tap_watcher.export import export_clip

    shelf = Recordings(tmp_path)
    source = document()
    archive = export_clip(source)
    saved = shelf.save(archive, source)
    monkeypatch.setattr(module, "MAX_LIBRARY_BYTES", 1)
    with pytest.raises(ValueError, match="2 GiB"):
        shelf.save(archive, source)
    assert shelf.file(saved["id"], "clip.zip").read_bytes() == archive
    assert len(shelf.list()) == 1
    assert not list(tmp_path.glob(".saving-*"))
