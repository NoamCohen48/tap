"""Loopback workbench API: one serialized owner; browser IDs never become commands or paths."""

from __future__ import annotations

import hashlib
import json
import secrets
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from .store import GraphStore


class Workbench:
    """Read an existing run, or own a fresh explicitly bootstrapped live run on one worker.

    No daemon auto-start, arbitrary selector endpoint, external-package input or model tools.
    All SDK calls/SQLite access occur on the same worker thread. Read-only viewing acquires no
    device. Closing a live workbench disconnects; it never blindly stops/resets an uncertain app.
    """

    def __init__(self, directory: Path, *, serial: str | None = None, package: str | None = None,
                 apk: Path | None = None, max_actions: int = 50):
        self.directory = directory.resolve()
        self.worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="explorer-owner")
        self.token = secrets.token_urlsafe(32)
        self.live = serial is not None
        self.client = self.connection = self.device = self.session = None
        self.graph = None
        self.proposals = None
        self.closed = False
        try:
            self.worker.submit(self._open, serial, package, apk, max_actions).result()
        except BaseException:
            self.close()
            raise

    def _open(self, serial, package, apk, max_actions):
        self.graph = None
        self.proposals = None
        if not self.live:
            if not (self.directory / "graph.db").is_file():
                raise ValueError("run graph.db not found")
            self.graph = GraphStore(self.directory / "graph.db")
            return
        if not package or apk is None:
            raise ValueError("live pilot requires explicit package and APK")
        apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.directory.mkdir(parents=True, exist_ok=False)
        from tap_e2e import TapClient

        from .ai import OpenAIVision
        from .session import DiscoverySession

        self.graph = GraphStore(self.directory / "graph.db")
        self.graph.initialize({"serial": serial, "package": package, "apk_sha256": apk_hash,
                              "policy": "human-reviewed-discovery/1"}, max_actions=max_actions, max_depth=10)
        self.client = TapClient.create()
        self.connection = self.client.connect("explorer-workbench")
        self.device = self.connection.attach_device(serial, wait_for_device=0)
        app = self.device.app(package)
        if app.is_installed():
            raise ValueError("refusing to replace/relaunch an existing installation; use a fresh dedicated package")
        app.install(apk)
        app.cold_launch()
        self.session = DiscoverySession(self.device, self.graph, self.directory / "observations", package=package)
        self.proposals = OpenAIVision(self.directory / "proposals")
        try:
            self.session.observe()
        except Exception as error:
            self.initial_error = str(error)
        self._export()

    def _export(self):
        if self.graph is not None:
            (self.directory / "graph.json").write_text(json.dumps(self.graph.document(), indent=2, sort_keys=True), encoding="utf-8")
        if self.connection is not None:
            events = self.connection.event_log()
            (self.directory / "events.json").write_text(json.dumps({"dropped": events.dropped,
                "events": [event.to_dict() for event in events.events]}, indent=2, sort_keys=True), encoding="utf-8")

    def _snapshot(self):
        if self.graph is None:
            raise ValueError("graph unavailable")
        doc = self.graph.document()
        interpretations = {}
        for path in sorted((self.directory / "proposals").glob("obs-*.json")):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                interpretations[path.stem] = {key: record.get(key) for key in
                    ("status", "model", "prompt_version", "proposal", "usage", "error")}
            except (OSError, ValueError):
                interpretations[path.stem] = {"status": "unreadable"}
        return {"graph": doc, "next": self.graph.next_action(), "live": self.live,
                "current": self.session.current if self.session else None,
                "halted": self.session.halted if self.session else None,
                "observation": self.session.observation if self.session else None,
                "initial_error": getattr(self, "initial_error", None), "interpretations": interpretations}

    def snapshot(self):
        return self.worker.submit(self._snapshot).result()

    def _artifact(self, observation, kind):
        if self.graph is None:
            raise ValueError("graph unavailable")
        item = self.graph.document()["observations"].get(observation)
        if item is None or kind not in ("screenshot", "snapshot"):
            raise ValueError("unknown observation/artifact")
        path = Path(item["evidence"][kind]).resolve(strict=True)
        if not path.is_relative_to(self.directory) or path.suffix != (".png" if kind == "screenshot" else ".json"):
            raise ValueError("artifact outside selected run or wrong type")
        if path.stat().st_size > 8_000_000:
            raise ValueError("artifact size limit")
        return path

    def image(self, observation):
        return self.worker.submit(lambda: self._artifact(observation, "screenshot").read_bytes()).result()

    def _operate(self, operation, body):
        if self.session is None or self.graph is None or self.proposals is None:
            raise ValueError("historical run is read-only; no device acquired")
        allowed = {"observe": set(), "approve": {"candidate", "reason", "value"},
                   "withdraw": {"candidate"},
                   "step": {"candidate"}, "navigate": {"state"}, "interpret": {"observation", "reviewed_non_sensitive"}}
        if operation not in allowed or not isinstance(body, dict) or set(body) - allowed[operation]:
            raise ValueError("invalid operation fields; selectors/tools/paths are forbidden")
        try:
            if operation == "observe":
                self.session.observe()
            elif operation == "approve":
                self.session.approve(body["candidate"], reason=body["reason"], value=body.get("value"))
            elif operation == "withdraw":
                self.session.withdraw(body["candidate"])
            elif operation == "step":
                self.session.step(body["candidate"])
            elif operation == "navigate":
                self.session.navigate(body["state"])
            else:
                observation = body["observation"]
                snapshot = json.loads(self._artifact(observation, "snapshot").read_text(encoding="utf-8"))
                doc = self.graph.document()
                states = [key for key, state in doc["states"].items() if observation in state["observations"]]
                candidates = {key: action for key, action in doc["actions"].items() if action["source"] in states
                              and not action["target"].get("blocked_reason")}
                evidence = {"candidate_ids": list(candidates), "candidates": [{"id": key,
                    "verb": action["verb"], "label": action["target"].get("label", "")} for key, action in candidates.items()],
                    "nodes": [{key: node.get(key) for key in ("ref", "resource", "class", "text", "description", "hint", "flags")}
                        for node in snapshot["nodes"] if node["package"] == self.session.package and not node["password"]]}
                self.proposals.interpret(observation, self._artifact(observation, "screenshot"), evidence,
                    reviewed_non_sensitive=body.get("reviewed_non_sensitive", False))
            return self._snapshot()
        finally:
            self._export()

    def operate(self, operation, body):
        return self.worker.submit(self._operate, operation, body).result()

    def _close(self):
        try:
            if self.connection:
                self._export()
        finally:
            try:
                if self.connection:
                    self.connection.close()
            finally:
                try:
                    if self.client:
                        self.client.close()
                finally:
                    if self.graph is not None:
                        self.graph.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.worker.submit(self._close).result()
        finally:
            self.worker.shutdown(wait=True)


def create_server(workbench: Workbench, *, port: int = 0, static: Path | None = None):
    """Construct a loopback server; mutation requests require a session token and same origin."""
    static = static if static is not None else Path(__file__).parent / "static"
    if not (static / "index.html").is_file():
        raise ValueError("build clients/explorer/web first (bun run build)")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # URLs/tokens/proposal bodies are not diagnostic logs.

        def reply(self, status, data, kind="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def guarded(self):
            host = urlparse("http://" + self.headers.get("Host", "")).hostname
            if host not in ("127.0.0.1", "localhost"):
                raise ValueError("non-loopback Host refused")

        def do_GET(self):
            try:
                self.guarded()
                path = unquote(urlparse(self.path).path)
                if path == "/api/run":
                    self.reply(200, json.dumps(workbench.snapshot()).encode())
                elif path.startswith("/api/image/"):
                    self.reply(200, workbench.image(path.removeprefix("/api/image/")), "image/png")
                else:
                    file = (static / ("index.html" if path == "/" else path.removeprefix("/"))).resolve()
                    if not file.is_relative_to(static.resolve()) or not file.is_file():
                        self.reply(404, b"not found", "text/plain")
                        return
                    data = file.read_bytes()
                    if file.name == "index.html":
                        data = data.replace(b"</head>", f'<meta name="explorer-token" content="{workbench.token}"></head>'.encode())
                    mime = {".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml"}.get(file.suffix, "application/octet-stream")
                    self.reply(200, data, mime)
            except Exception:
                self.reply(400, b'{"error":"request/artifact unavailable"}')

        def do_POST(self):
            try:
                self.guarded()
                expected_origin = "http://" + self.headers.get("Host", "")
                if (self.headers.get("Origin") != expected_origin or not secrets.compare_digest(
                        self.headers.get("X-Explorer-Token", ""), workbench.token)):
                    self.reply(403, b'{"error":"same-origin session token required"}')
                    return
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 16384:
                    raise ValueError("request size limit")
                body = json.loads(self.rfile.read(size))
                operation = urlparse(self.path).path.removeprefix("/api/")
                result = workbench.operate(operation, body)
                self.reply(200, json.dumps(result).encode())
            except Exception as error:
                self.reply(409, json.dumps({"error": str(error)}).encode())

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def serve(workbench: Workbench, *, port: int = 0):
    """Serve the selected run until stopped, then release the workbench's own connection."""
    server = create_server(workbench, port=port)
    print(f"Explorer workbench: http://127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        workbench.close()
