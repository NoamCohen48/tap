"""The studio server's access control and its first endpoints."""

from __future__ import annotations

import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from tap_studio import __version__
from tap_studio.server import Studio, cookie_name, create_app

TOKEN = "launch-token"
BASE = "http://127.0.0.1:8787"
SERVICE = "/tap.studio.v1.StudioService"
INFO = f"{SERVICE}/Info"
JSON = {"content-type": "application/json"}


def call(client, method: str, body: str = "{}", **headers):
    """A unary Connect call with the JSON codec, as Connect-Web makes it."""
    return client.post(f"{SERVICE}/{method}", content=body, headers={**JSON, **headers})


@pytest.fixture
def service():
    return Studio()


@pytest.fixture
def app(tmp_path, service):
    return create_app(TOKEN, static_dir=tmp_path / "missing", service=service)


@pytest.fixture
def anonymous(app):
    return TestClient(app, base_url=BASE)


@pytest.fixture
def signed_in(app):
    client = TestClient(app, base_url=BASE)
    response = client.get(f"/login?t={TOKEN}", follow_redirects=False)
    assert response.status_code == 303
    return client


def test_login_sets_a_strict_http_only_cookie_and_redirects_home(anonymous):
    response = anonymous.get(f"/login?t={TOKEN}", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/"
    cookie = response.headers["set-cookie"]
    assert cookie.startswith(f"{cookie_name(TOKEN)}={TOKEN};")
    assert "HttpOnly" in cookie and "SameSite=strict" in cookie and "Path=/" in cookie


@pytest.mark.parametrize("query", ["", "?t=", "?t=wrong"])
def test_login_refuses_a_wrong_token(anonymous, query):
    response = anonymous.get(f"/login{query}", follow_redirects=False)
    assert response.status_code == 401
    assert "set-cookie" not in response.headers


def test_everything_else_needs_the_cookie(anonymous):
    for path in ("/", "/assets/x.js", f"{INFO}?encoding=json&message=%7B%7D"):
        assert anonymous.get(path).status_code == 401, path
    assert call(anonymous, "Info").status_code == 401
    anonymous.cookies.set(cookie_name(TOKEN), "wrong")
    assert call(anonymous, "Info").status_code == 401


def test_cookie_of_another_studio_does_not_count(anonymous):
    anonymous.cookies.set(cookie_name("other"), TOKEN)
    assert call(anonymous, "Info").status_code == 401


def test_info(signed_in):
    response = call(signed_in, "Info")
    assert response.status_code == 200
    assert response.json() == {"recorder": f"tap-studio {__version__}", "format": "tap-recording/1"}


def test_info_over_get_for_side_effect_free_methods(signed_in):
    response = signed_in.get(f"{INFO}?encoding=json&message=%7B%7D")
    assert response.json()["format"] == "tap-recording/1"


def test_no_recording_yet_is_not_found(signed_in):
    response = call(signed_in, "GetRecording")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_recording_in_progress_is_returned(service, signed_in):
    from tap_studio.recording import loads
    from tests.test_recording import EXAMPLE, text

    service.recording = loads(text(EXAMPLE))
    body = call(signed_in, "GetRecording").json()
    assert body["recording"]["steps"][0]["app"]["operation"] == "cold_launch"


@pytest.mark.parametrize("host", ["evil.example:8787", "192.168.1.5:8787", "127.0.0.1.evil.example", ""])
def test_non_loopback_host_is_refused_even_with_the_token(app, host):
    client = TestClient(app, base_url=BASE)
    response = client.get(f"/login?t={TOKEN}", headers={"host": host}, follow_redirects=False)
    assert response.status_code == 421


@pytest.mark.parametrize("host", ["localhost:8787", "[::1]:8787", "127.0.0.1"])
def test_loopback_hosts_are_served(app, host):
    client = TestClient(app, base_url=BASE)
    assert client.get(f"/login?t={TOKEN}", headers={"host": host}, follow_redirects=False).status_code == 303


def test_cross_origin_calls_are_refused_and_same_origin_pass(signed_in):
    assert call(signed_in, "Info", origin="http://localhost:9999").status_code == 403
    assert call(signed_in, "Info", origin=BASE).status_code == 200


def test_plain_reads_are_not_origin_checked(signed_in):
    # A cross-origin page cannot read the response (no CORS headers), and reads change nothing.
    response = signed_in.get(f"{INFO}?encoding=json&message=%7B%7D", headers={"origin": "http://localhost:9999"})
    assert response.status_code == 200


def test_websocket_needs_cookie_and_same_origin(app, signed_in):
    with pytest.raises(WebSocketDisconnect) as closed:
        with TestClient(app, base_url=BASE).websocket_connect("/ws"):
            pass
    assert closed.value.code == 1008
    with pytest.raises(WebSocketDisconnect) as closed:
        with signed_in.websocket_connect("/ws", headers={"origin": "http://localhost:9999"}):
            pass
    assert closed.value.code == 1008


def test_unbuilt_page_says_how_to_build_it(signed_in):
    response = signed_in.get("/")
    assert response.status_code == 503
    assert "bun run build" in response.text


def test_built_page_is_served(tmp_path):
    (tmp_path / "assets").mkdir()
    (tmp_path / "index.html").write_text("<!doctype html><title>Tap Studio</title>")
    (tmp_path / "assets" / "app.js").write_text("console.log(1)")
    client = TestClient(create_app(TOKEN, static_dir=tmp_path), base_url=BASE)
    client.get(f"/login?t={TOKEN}")
    assert "<title>Tap Studio</title>" in client.get("/").text
    assert client.get("/assets/app.js").text == "console.log(1)"
    assert call(client, "Info").status_code == 200
