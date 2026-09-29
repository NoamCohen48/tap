"""The studio's web server on loopback: the page, the login route and ``StudioService`` over Connect.

``StudioService`` (``proto/studio.proto``) is served by connect-python at
``/tap.studio.v1.StudioService/<Method>``; the page calls it with Connect-Web, so both sides use the
generated code of the same proto.

Access control, since any local process or web page can reach a loopback port:

- **Launch token.** ``tap-studio`` prints ``/login?t=<token>``; that request sets an HttpOnly,
  SameSite=Strict cookie holding the token and redirects to ``/``. Every other request needs the
  cookie. The daemon's token never reaches the browser.
- **Host check** against DNS rebinding: the ``Host`` header must name a loopback address.
- **Origin check** for everything that is not a plain read (and for WebSockets, which browsers
  do not subject to CORS): ``Origin``, when sent, must be the page's own origin. Cookies are not
  isolated by port, so without it a page on another localhost port could drive the studio.
"""

from __future__ import annotations

import hashlib
import hmac
import pathlib
from http.cookies import CookieError, SimpleCookie

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.requests import Request
from starlette.responses import HTMLResponse, PlainTextResponse, RedirectResponse, Response
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles
from starlette.types import ASGIApp, Receive, Scope, Send

from . import __version__
from ._gen import studio_connect
from ._gen import studio_pb2 as studio
from .recording import FORMAT

STATIC = pathlib.Path(__file__).resolve().parent / "static"
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "[::1]"})
_READS = frozenset({"GET", "HEAD", "OPTIONS"})


def cookie_name(token: str) -> str:
    """Per-launch cookie name: two studios on one machine never overwrite each other's cookie."""
    return "tap_studio_" + hashlib.sha256(token.encode()).hexdigest()[:12]


class Studio:
    """``tap.studio.v1.StudioService``: the studio's state and the calls the page makes."""

    def __init__(self) -> None:
        self.recording: studio.Recording | None = None

    async def info(self, request: studio.InfoRequest, ctx: RequestContext) -> studio.InfoResponse:
        return studio.InfoResponse(recorder=f"tap-studio {__version__}", format=FORMAT)

    async def get_recording(
        self, request: studio.GetRecordingRequest, ctx: RequestContext
    ) -> studio.GetRecordingResponse:
        if self.recording is None:
            raise ConnectError(Code.NOT_FOUND, "nothing recorded yet")
        return studio.GetRecordingResponse(recording=self.recording)


def create_app(token: str, static_dir: pathlib.Path | None = STATIC, service: Studio | None = None) -> Starlette:
    """The studio's ASGI app. ``static_dir`` is the built page; without an ``index.html`` there,
    ``/`` explains how to build it."""

    def login(request: Request) -> Response:
        # The one route the guard lets through without the cookie; it checks the token itself.
        presented = request.query_params.get("t", "")
        if not hmac.compare_digest(presented.encode(), token.encode()):
            return PlainTextResponse("wrong or missing launch token: open the link tap-studio printed", 401)
        response = RedirectResponse("/", status_code=303)
        response.set_cookie(cookie_name(token), token, httponly=True, samesite="strict", path="/")
        return response

    connect = studio_connect.StudioServiceASGIApplication(service or Studio())
    routes: list[Route | Mount] = [Route("/login", login), Mount(connect.path, app=connect)]
    if static_dir is not None and (static_dir / "index.html").is_file():
        routes.append(Mount("/", StaticFiles(directory=static_dir, html=True)))
    else:
        routes.append(Route("/", lambda request: HTMLResponse(_UNBUILT, status_code=503)))
    return Starlette(routes=routes, middleware=[Middleware(_Guard, token=token)])


class _Guard:
    """ASGI middleware for HTTP and WebSocket scopes: host, origin and token checks."""

    def __init__(self, app: ASGIApp, token: str):
        self.app = app
        self.token = token.encode()
        self.cookie = cookie_name(token)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope["headers"]}
        problem = self._problem(scope, headers)
        if problem is None:
            await self.app(scope, receive, send)
            return
        status, message = problem
        if scope["type"] == "websocket":
            await send({"type": "websocket.close", "code": 1008, "reason": message})
        else:
            await PlainTextResponse(message, status)(scope, receive, send)

    def _problem(self, scope: Scope, headers: dict[str, str]) -> tuple[int, str] | None:
        host = headers.get("host", "")
        hostname = host if host.endswith("]") else host.rsplit(":", 1)[0]
        if hostname not in LOOPBACK_HOSTS:
            return (421, "tap-studio serves loopback addresses only")
        origin = headers.get("origin")
        plain_read = scope["type"] == "http" and scope["method"] in _READS
        if origin is not None and not plain_read and origin != f"http://{host}":
            return (403, "cross-origin request refused")
        if scope["type"] == "http" and scope["path"] == "/login":
            return None
        cookies: SimpleCookie = SimpleCookie()
        try:
            cookies.load(headers.get("cookie", ""))
        except CookieError:  # a malformed cookie header is just no cookie
            cookies = SimpleCookie()
        presented = cookies.get(self.cookie)
        if presented is None or not hmac.compare_digest(presented.value.encode(), self.token):
            return (401, "not signed in: open the link tap-studio printed")
        return None


_UNBUILT = """<!doctype html><meta charset="utf-8"><title>Tap Studio</title>
<p>The Tap Studio page is not built into this installation. In a checkout, run
<code>bun install &amp;&amp; bun run build</code> in <code>clients/studio/web</code>, then restart
<code>tap-studio</code>.</p>
"""
