"""Loopback, launch-token and same-origin protections for shared device data."""

from __future__ import annotations

from starlette.testclient import TestClient
from tap_watcher.server import cookie_name, create_app


def test_login_requires_token_and_sets_strict_http_only_cookie(tmp_path):
    (tmp_path / "index.html").write_text("<h1>Watcher</h1>")
    with TestClient(
        create_app("secret", static_dir=tmp_path), base_url="http://127.0.0.1:9000"
    ) as client:
        assert client.get("/").status_code == 401
        assert client.get("/login?t=wrong").status_code == 401
        response = client.get("/login?t=secret", follow_redirects=False)
        assert response.status_code == 303
        assert "HttpOnly" in response.headers["set-cookie"]
        assert "SameSite=strict" in response.headers["set-cookie"]
        assert client.get("/").status_code == 200
        assert (
            client.post("/tap.watcher.v1.WatcherService/Attach", json={}).status_code
            == 404
        )
        assert (
            client.post("/tap.watcher.v1.WatcherService/Execute", json={}).status_code
            == 404
        )


def test_host_and_origin_checks_apply_even_with_cookie(tmp_path):
    with TestClient(
        create_app("secret", static_dir=tmp_path), base_url="http://127.0.0.1:9000"
    ) as client:
        client.cookies.set(cookie_name("secret"), "secret")
        assert client.get("/", headers={"Host": "evil.example"}).status_code == 421
        assert (
            client.post(
                "/tap.watcher.v1.WatcherService/ListDevices",
                json={},
                headers={"Origin": "http://localhost:9001"},
            ).status_code
            == 403
        )
        assert (
            client.get("/", headers={"Origin": "https://evil.example"}).status_code
            == 403
        )


def test_two_launches_do_not_share_cookie_names():
    assert cookie_name("first") != cookie_name("second")
