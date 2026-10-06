import hashlib
import http.client
import json
import threading
from pathlib import Path

import pytest

from tap_explorer import labels
from tap_explorer.cli import main

CORPUS = Path(__file__).parents[1] / "benchmarks" / "corpus-v2.json"


@pytest.fixture(scope="module")
def full():
    return json.loads(CORPUS.read_text(encoding="utf-8"))


def _corpus(tmp_path, full):
    """Two loop cases; the first has a local snapshot and screenshot pinned by its SHA-256."""
    corpus = {"format": full["format"], "sources": {"loop": full["sources"]["loop"]},
              "cases": [case for case in full["cases"] if case["source"] == "loop"][:2]}
    evidence = tmp_path / "observations"
    evidence.mkdir()
    first = corpus["cases"][0]
    raw = b'{"nodes": []}'
    (evidence / f"{first['observation']}.json").write_bytes(raw)
    (evidence / f"{first['observation']}.png").write_bytes(b"\x89PNG fake")
    first["snapshot_sha256"] = hashlib.sha256(raw).hexdigest()
    path = tmp_path / "corpus.json"
    path.write_text(json.dumps(corpus))
    return corpus, path, evidence


def test_decisions_record_what_was_reviewed_and_images_need_a_pin(tmp_path, full):
    corpus, path, evidence = _corpus(tmp_path, full)
    review = labels.LabelReview(path, "loop", evidence, tmp_path / "decisions.json")
    first, second = corpus["cases"]

    listing = review.listing()
    assert [case["image"] for case in listing["cases"]] == [True, False]
    assert review.image(0) == b"\x89PNG fake"
    with pytest.raises(ValueError):
        review.image(1)
    review.decide({"id": first["id"], "decision": "accept", "note": "looks right"})
    review.decide({"id": second["id"], "decision": "correct", "screen": "habit-list", "variant": " "})
    saved = json.loads((tmp_path / "decisions.json").read_text())
    assert saved["decisions"][first["id"]] == {
        "decision": "accept", "note": "looks right",
        "proposed": {"screen": first["labels"]["screen"], "variant": first["labels"]["variant"]}}
    assert (saved["decisions"][second["id"]]["screen"], saved["decisions"][second["id"]]["variant"]) == ("habit-list", None)
    with pytest.raises(ValueError):
        review.decide({"id": first["id"], "decision": "correct", "screen": ""})
    review.decide({"id": second["id"], "decision": "clear"})
    assert second["id"] not in json.loads((tmp_path / "decisions.json").read_text())["decisions"]


def test_apply_marks_reviewed_cases_and_refuses_stale_decisions(tmp_path, full):
    corpus, path, evidence = _corpus(tmp_path, full)
    review = labels.LabelReview(path, "loop", evidence, tmp_path / "decisions.json")
    first, second = corpus["cases"]
    review.decide({"id": first["id"], "decision": "accept"})
    review.decide({"id": second["id"], "decision": "correct", "screen": "other", "variant": "x"})
    decisions = json.loads((tmp_path / "decisions.json").read_text())

    result = labels.apply(corpus, decisions)
    reviewed = {case["id"]: case["labels"] for case in result["cases"]}
    assert reviewed[first["id"]]["provenance"] == "human-reviewed"
    assert reviewed[first["id"]]["screen"] == first["labels"]["screen"]
    assert (reviewed[second["id"]]["screen"], reviewed[second["id"]]["variant"]) == ("other", "x")
    assert corpus["cases"][0]["labels"]["provenance"] == "analyst-provisional"
    corpus["cases"][0]["labels"]["screen"] = "relabeled"
    with pytest.raises(ValueError, match="changed since"):
        labels.apply(corpus, decisions)


def test_cli_apply_writes_a_new_corpus_only(tmp_path, full, capsys):
    corpus, path, evidence = _corpus(tmp_path, full)
    labels.LabelReview(path, "loop", evidence, tmp_path / "d.json").decide(
        {"id": corpus["cases"][0]["id"], "decision": "accept"})
    with pytest.raises(SystemExit):
        main(["label-review", "--corpus", str(path), "--decisions", str(tmp_path / "d.json"), "--apply", str(path)])
    assert main(["label-review", "--corpus", str(path), "--decisions", str(tmp_path / "d.json"),
                 "--apply", str(tmp_path / "new.json")]) == 0
    assert json.loads(capsys.readouterr().out)["human_reviewed"] == 1


def test_server_needs_token_and_origin_for_decisions(tmp_path, full):
    corpus, path, evidence = _corpus(tmp_path, full)
    review = labels.LabelReview(path, "loop", evidence, tmp_path / "decisions.json")
    server = labels.create_server(review)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    host = f"127.0.0.1:{server.server_port}"
    try:
        def request(method, url, body=None, headers=None):
            connection = http.client.HTTPConnection(host)
            connection.request(method, url, body, headers or {})
            response = connection.getresponse()
            return response.status, response.getheader("Content-Security-Policy"), response.read()

        status, policy, page = request("GET", "/")
        assert status == 200 and review.token.encode() in page and policy is not None and "script-src 'nonce-" in policy
        body = json.dumps({"id": corpus["cases"][0]["id"], "decision": "accept"})
        assert request("POST", "/api/decide", body, {"Origin": f"http://{host}"})[0] == 403
        status, _, reply = request("POST", "/api/decide", body,
                                   {"Origin": f"http://{host}", "X-Explorer-Token": review.token})
        assert status == 200 and json.loads(reply)["decision"]["decision"] == "accept"
        assert request("GET", "/api/image/1")[0] == 400
    finally:
        server.shutdown()
        server.server_close()
