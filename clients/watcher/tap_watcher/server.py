"""Loopback browser server with a per-launch login cookie and no owning APIs."""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import json
import pathlib
from collections.abc import AsyncIterator
from http.cookies import CookieError, SimpleCookie

from av.error import FFmpegError
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.requests import Request
from starlette.responses import (
    HTMLResponse,
    PlainTextResponse,
    RedirectResponse,
    Response,
)
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles
from starlette.types import ASGIApp, Receive, Scope, Send

from ._gen.watcher_connect import WatcherServiceASGIApplication
from .export import MAX_BODY, export_clip
from .service import Watcher

STATIC = pathlib.Path(__file__).parent / "static"


def cookie_name(token: str) -> str:
    """Keep different watcher launches from overwriting one another's cookies."""
    return "tap_watcher_" + hashlib.sha256(token.encode()).hexdigest()[:12]


def create_app(
    token: str, service: Watcher | None = None, static_dir: pathlib.Path = STATIC
) -> Starlette:
    """Serve the generated read-only service and built page; close its channel on exit."""
    watcher = service or Watcher()

    @contextlib.asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
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

    exporting = asyncio.Lock()

    async def export(request: Request) -> Response:
        if exporting.locked():
            return PlainTextResponse("another export is running", status_code=429)
        async with exporting:
            body = bytearray()
            async for chunk in request.stream():
                if len(body) + len(chunk) > MAX_BODY:
                    return PlainTextResponse(
                        "clip request exceeds 48 MiB", status_code=413
                    )
                body.extend(chunk)
            try:
                document = json.loads(body)
                archive = await asyncio.to_thread(export_clip, document)
            except (
                ValueError,
                KeyError,
                TypeError,
                OverflowError,
                FFmpegError,
            ) as error:
                return PlainTextResponse(str(error), status_code=400)
            return Response(
                archive,
                media_type="application/zip",
                headers={
                    "Content-Disposition": 'attachment; filename="tap-clip.zip"',
                    "Cache-Control": "no-store",
                },
            )

    connect = WatcherServiceASGIApplication(watcher)
    routes: list[Route | Mount] = [
        Route("/login", login),
        Route("/export", export, methods=["POST"]),
        Mount(connect.path, app=connect),
    ]
    if (static_dir / "index.html").is_file():
        routes.append(Mount("/", StaticFiles(directory=static_dir, html=True)))
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
