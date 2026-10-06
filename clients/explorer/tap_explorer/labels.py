"""Operator review of corpus labels: screenshot, proposed label, accept or correct. No device.

Analyst labels in a discovery corpus are provisional. This loopback page shows each case's
local screenshot beside its proposed screen/variant label, and records the operator's decision
in a `tap-label-review/1` file. `apply` turns decided cases into ``human-reviewed`` labels in a
**new** corpus file; the input corpus is never rewritten.

A screenshot is shown only when the local observation snapshot still has the SHA-256 the
corpus pinned, so a label is never reviewed against another run's picture. A decision records
the label it saw: if the corpus proposes a different label later, `apply` refuses it.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from .benchmark import validate_corpus

REVIEW_FORMAT = "tap-label-review/1"
DECISIONS = ("accept", "correct")


def _proposed(case: dict) -> dict:
    return {"screen": case["labels"]["screen"], "variant": case["labels"]["variant"]}


class LabelReview:
    """The cases of one corpus source, their pinned screenshots and the decisions file."""

    def __init__(self, corpus_path: Path, source: str, evidence: Path, decisions_path: Path):
        self.corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
        validate_corpus(self.corpus)
        if source not in self.corpus["sources"]:
            raise ValueError(f"unknown corpus source {source!r}")
        self.source, self.evidence, self.path = source, evidence.resolve(), decisions_path
        self.cases = [case for case in self.corpus["cases"] if case["source"] == source]
        self.token = secrets.token_urlsafe(32)
        self.lock = threading.Lock()
        self.review = (json.loads(decisions_path.read_text(encoding="utf-8")) if decisions_path.is_file()
                       else {"format": REVIEW_FORMAT, "source": source, "decisions": {}})
        if self.review.get("format") != REVIEW_FORMAT or self.review.get("source") != source:
            raise ValueError("decisions file is for another format or source")
        self.pinned = {case["id"]: self._pinned(case) for case in self.cases}

    def _pinned(self, case: dict) -> bool:
        try:
            raw = (self.evidence / f"{case['observation']}.json").read_bytes()
        except OSError:
            return False
        return hashlib.sha256(raw).hexdigest() == case["snapshot_sha256"]

    def listing(self) -> dict:
        decisions = self.review["decisions"]
        return {"source": self.source, "description": self.corpus["sources"][self.source]["description"],
                "screens": sorted({case["labels"]["screen"] for case in self.cases}),
                "cases": [{"index": index, "id": case["id"], "observation": case["observation"],
                           **_proposed(case), "provenance": case["labels"]["provenance"],
                           "note": case["labels"]["note"], "image": self.pinned[case["id"]],
                           "decision": decisions.get(case["id"])}
                          for index, case in enumerate(self.cases)]}

    def image(self, index: int) -> bytes:
        case = self.cases[index]
        if not self.pinned[case["id"]]:
            raise ValueError("screenshot not pinned to this corpus case")
        return (self.evidence / f"{case['observation']}.png").read_bytes()

    def decide(self, body: dict) -> dict:
        case = next((case for case in self.cases if case["id"] == body.get("id")), None)
        if case is None:
            raise ValueError("unknown case")
        with self.lock:
            decisions = self.review["decisions"]
            if body.get("decision") == "clear":
                decisions.pop(case["id"], None)
            elif body.get("decision") in DECISIONS:
                entry = {"decision": body["decision"], "proposed": _proposed(case)}
                if body["decision"] == "correct":
                    screen, variant = body.get("screen"), body.get("variant")
                    if not isinstance(screen, str) or not screen.strip():
                        raise ValueError("a correction needs a screen label")
                    if variant is not None and not isinstance(variant, str):
                        raise ValueError("variant must be text or null")
                    entry.update(screen=screen.strip(), variant=variant.strip() if variant and variant.strip() else None)
                note = body.get("note")
                if isinstance(note, str) and note.strip():
                    entry["note"] = note.strip()[:500]
                decisions[case["id"]] = entry
            else:
                raise ValueError("decision must be accept, correct or clear")
            _write_atomic(self.path, self.review)
            return {"id": case["id"], "decision": decisions.get(case["id"])}


def _write_atomic(path: Path, document: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    with os.fdopen(handle, "w", encoding="utf-8") as file:
        json.dump(document, file, indent=2, ensure_ascii=False, sort_keys=True)
        file.write("\n")
    os.replace(temporary, path)


def apply(corpus: dict, review: dict) -> dict:
    """A new corpus with the decided cases relabeled ``human-reviewed``; nothing else changes.

    Refuses a decision whose recorded proposal no longer matches the corpus label, and a
    decision for a case the corpus does not have.
    """
    validate_corpus(corpus)
    if review.get("format") != REVIEW_FORMAT:
        raise ValueError("unsupported label review file")
    result = json.loads(json.dumps(corpus))
    cases = {case["id"]: case for case in result["cases"] if case["source"] == review.get("source")}
    for case_id, decision in sorted(review["decisions"].items()):
        case = cases.get(case_id)
        if case is None:
            raise ValueError(f"decision for unknown case {case_id}")
        if decision.get("proposed") != _proposed(case):
            raise ValueError(f"{case_id}: the corpus label changed since it was reviewed")
        labels = case["labels"]
        if decision["decision"] == "correct":
            labels["screen"], labels["variant"] = decision["screen"], decision["variant"]
            verdict = f"Operator corrected the analyst label {decision['proposed']['screen']}/{decision['proposed']['variant']}."
        else:
            verdict = "Operator accepted the analyst label."
        labels["provenance"] = "human-reviewed"
        labels["note"] = " ".join(part for part in (labels["note"], verdict, decision.get("note")) if part)
    validate_corpus(result)
    return result


_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Label review</title><link rel="icon" href="data:,">
<style nonce="__NONCE__">
:root{--bg:#f7f7f5;--fg:#1d1d1b;--muted:#6b6b66;--card:#fff;--line:#deded8;--ok:#1f7a3a;--fix:#a35200;--focus:#2856c7}
@media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ececea;--muted:#9a9a94;--card:#21211f;--line:#383835;--ok:#5cc27a;--fix:#f0a050;--focus:#7aa2ff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
header{position:sticky;top:0;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 16px;display:flex;gap:16px;align-items:baseline;flex-wrap:wrap;z-index:1}
h1{font-size:16px;margin:0}.muted{color:var(--muted)}main{padding:16px;display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(300px,1fr))}
.case{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px;display:grid;grid-template-columns:120px 1fr;gap:10px}
.case.current{outline:2px solid var(--focus)}.case img{width:120px;border-radius:4px;border:1px solid var(--line)}
.noimg{width:120px;height:200px;display:grid;place-items:center;border:1px dashed var(--line);border-radius:4px;color:var(--muted);text-align:center;font-size:12px}
.label{font-weight:600;word-break:break-word}.variant{font-family:ui-monospace,monospace;font-size:12px;word-break:break-all}
.state{font-size:12px;font-weight:600}.state.accept{color:var(--ok)}.state.correct{color:var(--fix)}
input{width:100%;font:inherit;padding:4px 6px;margin:2px 0;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:4px}
button{font:inherit;padding:4px 10px;margin:4px 4px 0 0;border:1px solid var(--line);border-radius:4px;background:var(--bg);color:var(--fg);cursor:pointer}
button:focus-visible,input:focus-visible{outline:2px solid var(--focus)}
</style></head><body>
<header><h1>Label review</h1><span id="summary" class="muted"></span>
<span class="muted">Keys: j/k move, a accept, c correct, x clear</span></header>
<main id="cases"></main>
<datalist id="screens"></datalist>
<script nonce="__NONCE__">
const token = "__TOKEN__";
let data = null, current = 0;
const el = (tag, props = {}, ...children) => { const node = Object.assign(document.createElement(tag), props); node.append(...children); return node; };
async function decide(item, body) {
  const response = await fetch("/api/decide", {method: "POST", headers: {"Content-Type": "application/json", "X-Explorer-Token": token}, body: JSON.stringify({id: item.id, ...body})});
  const result = await response.json();
  if (!response.ok) { alert(result.error); return; }
  item.decision = result.decision; render();
}
function card(item, index) {
  const screen = el("input", {value: item.decision?.screen ?? item.screen, placeholder: "screen label"});
  screen.setAttribute("list", "screens"); screen.setAttribute("aria-label", "screen label");
  const variant = el("input", {value: item.decision?.variant ?? item.variant ?? "", placeholder: "variant (empty = unknown)"});
  variant.setAttribute("aria-label", "variant label");
  const note = el("input", {value: item.decision?.note ?? "", placeholder: "note (optional)"});
  note.setAttribute("aria-label", "note");
  const state = item.decision ? el("div", {className: "state " + item.decision.decision, textContent: item.decision.decision === "accept" ? "Accepted" : "Corrected"}) : el("div", {className: "state muted", textContent: "Not reviewed"});
  const picture = item.image ? el("a", {href: "/api/image/" + item.index, target: "_blank", title: "Open full size"}, el("img", {src: "/api/image/" + item.index, alt: "Screenshot of " + item.observation, loading: "lazy"})) : el("div", {className: "noimg", textContent: "No pinned screenshot"});
  const accept = el("button", {textContent: "Accept", onclick: () => decide(item, {decision: "accept", note: note.value})});
  const correct = el("button", {textContent: "Correct", onclick: () => decide(item, {decision: "correct", screen: screen.value, variant: variant.value, note: note.value})});
  const clear = el("button", {textContent: "Clear", onclick: () => decide(item, {decision: "clear"})});
  const node = el("section", {className: "case" + (index === current ? " current" : "")}, picture,
    el("div", {}, el("div", {className: "muted", textContent: item.observation + " · " + item.provenance}),
      el("div", {className: "label", textContent: item.screen}), el("div", {className: "variant", textContent: item.variant ?? "(variant unknown)"}),
      state, screen, variant, note, accept, correct, clear));
  node.addEventListener("click", () => { current = index; mark(); });
  node.accept = accept; node.correct = correct; node.clear = clear;
  return node;
}
function mark() { [...document.querySelectorAll(".case")].forEach((node, index) => node.classList.toggle("current", index === current)); }
function render() {
  const reviewed = data.cases.filter(item => item.decision).length;
  document.getElementById("summary").textContent = `${data.source}: ${reviewed} of ${data.cases.length} reviewed. ${data.description}`;
  document.getElementById("screens").replaceChildren(...data.screens.map(value => el("option", {value})));
  document.getElementById("cases").replaceChildren(...data.cases.map(card));
}
document.addEventListener("keydown", event => {
  if (event.target instanceof HTMLInputElement || !data) return;
  const nodes = document.querySelectorAll(".case");
  if (event.key === "j" || event.key === "k") { current = Math.max(0, Math.min(nodes.length - 1, current + (event.key === "j" ? 1 : -1))); mark(); nodes[current].scrollIntoView({block: "nearest"}); }
  else if (event.key === "a") nodes[current]?.accept.click();
  else if (event.key === "c") nodes[current]?.correct.click();
  else if (event.key === "x") nodes[current]?.clear.click();
});
fetch("/api/cases").then(response => response.json()).then(result => { data = result; render(); });
</script></body></html>
"""


def create_server(review: LabelReview, *, port: int = 0) -> ThreadingHTTPServer:
    """Loopback only; decisions require the page's session token and same origin."""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def reply(self, status, data, kind="application/json", nonce=None):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            scripts = f"'nonce-{nonce}'" if nonce else "'none'"
            self.send_header("Content-Security-Policy", f"default-src 'self'; img-src 'self' data:; script-src {scripts}; "
                                                        f"style-src {scripts}; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def loopback(self):
            if urlparse("http://" + self.headers.get("Host", "")).hostname not in ("127.0.0.1", "localhost"):
                raise ValueError("non-loopback Host refused")

        def do_GET(self):
            try:
                self.loopback()
                path = unquote(urlparse(self.path).path)
                if path == "/":
                    nonce = secrets.token_urlsafe(16)
                    page = _PAGE.replace("__NONCE__", nonce).replace("__TOKEN__", review.token)
                    self.reply(200, page.encode(), "text/html; charset=utf-8", nonce)
                elif path == "/api/cases":
                    self.reply(200, json.dumps(review.listing()).encode())
                elif path.startswith("/api/image/") and path.removeprefix("/api/image/").isdigit():
                    self.reply(200, review.image(int(path.removeprefix("/api/image/"))), "image/png")
                else:
                    self.reply(404, b'{"error":"not found"}')
            except Exception:
                self.reply(400, b'{"error":"request unavailable"}')

        def do_POST(self):
            try:
                self.loopback()
                if (self.headers.get("Origin") != "http://" + self.headers.get("Host", "") or not secrets.compare_digest(
                        self.headers.get("X-Explorer-Token", ""), review.token)):
                    self.reply(403, b'{"error":"same-origin session token required"}')
                    return
                size = int(self.headers.get("Content-Length", "0"))
                if urlparse(self.path).path != "/api/decide" or not 0 < size <= 4096:
                    raise ValueError("unknown request")
                self.reply(200, json.dumps(review.decide(json.loads(self.rfile.read(size)))).encode())
            except Exception as error:
                self.reply(409, json.dumps({"error": str(error)}).encode())

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def serve(review: LabelReview, *, port: int = 0) -> None:
    server = create_server(review, port=port)
    print(f"Label review: http://127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
