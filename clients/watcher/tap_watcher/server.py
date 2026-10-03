"""Loopback browser server with a per-launch login cookie and no owning APIs."""

from __future__ import annotations

import contextlib
import hashlib
import hmac
import pathlib
import re
from collections.abc import AsyncIterator
from http.cookies import CookieError, SimpleCookie

from starlette.applications import Starlette
from starlette.background import BackgroundTask
from starlette.middleware import Middleware
from starlette.requests import Request
from starlette.responses import (
    FileResponse,
    HTMLResponse,
    PlainTextResponse,
    RedirectResponse,
    Response,
)
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles
from starlette.types import ASGIApp, Receive, Scope, Send

from ._gen.watcher_connect import WatcherServiceASGIApplication
from .recordings import Library, default_directory
from .service import Watcher

STATIC = pathlib.Path(__file__).parent / "static"


def cookie_name(token: str) -> str:
    """Keeps different watcher launches from overwriting one another's cookies."""
    return "tap_watcher_" + hashlib.sha256(token.encode()).hexdigest()[:12]


def create_app(
    token: str,
    service: Watcher | None = None,
    static_dir: pathlib.Path = STATIC,
    recordings_dir: pathlib.Path | None = None,
    keep_days: int = 0,
) -> Starlette:
    """The Connect service, the library downloads and the built page."""
    watcher = service or Watcher(
        Library(recordings_dir or default_directory(), keep_days)
    )
    library = watcher.recorders.library

    @contextlib.asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        await watcher.start()
        try:
            yield
        finally:
            await watcher.close()

    def login(request: Request) -> Response:
        if not hmac.compare_digest(
            request.query_params.get("t", "").encode(), token.encode()
        ):
            return PlainTextResponse("wrong or missing launch token", status_code=401)
        response = RedirectResponse("/", status_code=303)
        response.set_cookie(
            cookie_name(token), token, httponly=True, samesite="strict", path="/"
        )
        return response

    def recording_file(request: Request) -> Response:
        try:
            path = library.file(request.path_params["id"], request.path_params["name"])
        except KeyError:
            return PlainTextResponse("recording not found", status_code=404)
        media = "application/json" if path.suffix == ".json" else "video/mp4"
        return FileResponse(
            path, media_type=media, headers={"Cache-Control": "no-store"}
        )

    def recording_zip(request: Request) -> Response:
        identifier = request.path_params["id"]
        try:
            archive = library.zip(identifier)
        except KeyError:
            return PlainTextResponse("recording not found", status_code=404)
        serial = next((r.serial for r in library.list() if r.id == identifier), "")
        name = (
            "tap-"
            + re.sub(r"[^A-Za-z0-9_.-]", "_", serial or "recording")
            + "-"
            + identifier[:8]
            + ".zip"
        )
        return FileResponse(
            archive,
            media_type="application/zip",
            filename=name,
            headers={"Cache-Control": "no-store"},
            background=BackgroundTask(archive.unlink, missing_ok=True),
        )

    connect = WatcherServiceASGIApplication(watcher)
    routes: list[Route | Mount] = [
        Route("/login", login),
        Route("/recordings/{id}.zip", recording_zip),
        Route("/recordings/{id}/{name}", recording_file),
        Mount(connect.path, app=connect),
    ]
    if (static_dir / "index.html").is_file():
        routes.append(Mount("/", Page(directory=static_dir, html=True)))
    else:
        routes.append(
            Route(
                "/",
                lambda request: HTMLResponse(
                    "<h1>Tap Watch</h1><p>Build clients/watcher/web with bun run build, then restart.</p>",
                    status_code=503,
                ),
            )
        )
    return Starlette(
        routes=routes, middleware=[Middleware(Guard, token=token)], lifespan=lifespan
    )


class Page(StaticFiles):
    """The built page. Its HTML is revalidated on every load, so a rebuilt page (new hashed
    asset names) is picked up; the hashed assets themselves may be cached."""

    def file_response(self, full_path, stat_result, scope, status_code=200) -> Response:
        response = super().file_response(full_path, stat_result, scope, status_code)
        if str(full_path).endswith(".html"):
            response.headers["Cache-Control"] = "no-cache"
        return response


class Guard:
    """Host and same-origin validation precede constant-time cookie authentication."""

    def __init__(self, app: ASGIApp, token: str) -> None:
        self.app = app
        self.token = token.encode()
        self.cookie = cookie_name(token)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        headers = {
            key.decode("latin1").lower(): value.decode("latin1")
            for key, value in scope["headers"]
        }
        host = headers.get("host", "")
        hostname = host if host.endswith("]") else host.rsplit(":", 1)[0]
        problem: tuple[int, str] | None = None
        if hostname not in {"localhost", "127.0.0.1", "[::1]"}:
            problem = (421, "loopback hosts only")
        elif headers.get("origin") not in (None, f"http://{host}"):
            problem = (403, "cross-origin request refused")
        elif scope["path"] != "/login":
            cookies = SimpleCookie()
            try:
                cookies.load(headers.get("cookie", ""))
            except CookieError:
                cookies = SimpleCookie()
            presented = cookies.get(self.cookie)
            if presented is None or not hmac.compare_digest(
                presented.value.encode(), self.token
            ):
                problem = (401, "open the login URL printed by tap-watcher")
        if problem is None:
            await self.app(scope, receive, send)
        elif scope["type"] == "websocket":
            await send({"type": "websocket.close", "code": 1008})
        else:
            await PlainTextResponse(problem[1], status_code=problem[0])(
                scope, receive, send
            )
