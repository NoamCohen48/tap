"""Read-only viewer does not acquire a device; boundary tests use an ephemeral loopback server."""

import http.client
import json
import threading

import pytest

from tap_explorer.store import GraphStore
from tap_explorer.web import Workbench, create_server


@pytest.fixture
def viewer(tmp_path):
    run = tmp_path / "run"
    run.mkdir()
    images = run / "observations"
    images.mkdir()
    image = images / "obs-000001.png"
    image.write_bytes(b"\x89PNG\r\n\x1a\nfixture")
    with GraphStore(run / "graph.db") as graph:
        graph.initialize({"package": "controlled.app"})
        graph.add_observation("obs-000001", {"screenshot": str(image)})
        graph.add_state("home", "obs-000001", signature="home", depth=0)
    static = tmp_path / "static"
    static.mkdir()
    (static / "index.html").write_text('<html><head></head><body>viewer</body></html>')
    workbench = Workbench(run)
    server = create_server(workbench, static=static)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield workbench, server, run
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
        workbench.close()
        workbench.close()  # idempotent cleanup, including CLI/server finally blocks


def get(server, path, *, host=None):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port)
    connection.request("GET", path, headers={"Host": host} if host else {})
    response = connection.getresponse()
    status, data, headers = response.status, response.read(), dict(response.getheaders())
    connection.close()
    return status, data, headers


def post(workbench, server, *, origin=True, token=True, body=None):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port)
    headers = {"Content-Type": "application/json"}
    if origin:
        headers["Origin"] = f"http://127.0.0.1:{server.server_port}"
    if token:
        headers["X-Explorer-Token"] = workbench.token
    connection.request("POST", "/api/step", json.dumps(body or {"candidate": "invented"}), headers)
    response = connection.getresponse()
    status, data = response.status, response.read()
    connection.close()
    return status, data


def test_historical_viewer_uses_no_connection_and_retains_real_image(viewer):
    workbench, server, _ = viewer
    status, data, headers = get(server, "/api/run")
    assert status == 200
    snapshot = json.loads(data)
    assert snapshot["live"] is False and workbench.connection is None
    assert snapshot["graph"]["states"]["home"]["observations"] == ["obs-000001"]
    assert headers["Cache-Control"] == "no-store"
    assert get(server, "/api/image/obs-000001")[:2] == (200, b"\x89PNG\r\n\x1a\nfixture")
    assert b'explorer-token' in get(server, "/")[1]


@pytest.mark.parametrize("origin,token", [(False, True), (True, False), (False, False)])
def test_cross_origin_or_tokenless_input_refused(viewer, origin, token):
    workbench, server, _ = viewer
    assert post(workbench, server, origin=origin, token=token)[0] == 403


def test_even_authenticated_historical_view_cannot_execute(viewer):
    workbench, server, _ = viewer
    status, data = post(workbench, server)
    assert status == 409 and b"read-only" in data
    assert not workbench.snapshot()["graph"]["attempts"]


def test_dns_rebinding_host_and_arbitrary_paths_refused(viewer):
    _, server, _ = viewer
    assert get(server, "/api/run", host="attacker.example")[0] == 400
    assert get(server, "/../../etc/passwd")[0] == 404
    assert get(server, "/api/image/../../etc/passwd")[0] == 400


def test_outside_run_artifact_is_not_served(viewer, tmp_path):
    workbench, server, run = viewer
    outside = tmp_path / "secret.png"
    outside.write_bytes(b"do not expose")
    with GraphStore(run / "graph.db") as graph:
        graph.add_observation("outside", {"screenshot": str(outside)})
    assert get(server, "/api/image/outside")[0] == 400
    with pytest.raises(ValueError, match="outside"):
        workbench.image("outside")
