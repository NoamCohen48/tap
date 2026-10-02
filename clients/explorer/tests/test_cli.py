"""Offline CLI round-trip, invalid imports, and explicit recovery confirmation."""

import json

import pytest

from tap_explorer.cli import main
from tap_explorer.store import GraphStore


def test_initialize_status_export_import(tmp_path, capsys):
    context = tmp_path / "context.json"
    context.write_text('{"app":"fixture","starting_condition":"Home"}', encoding="utf-8")
    db = str(tmp_path / "graph.db")
    assert main(["--db", db, "init", "--context", str(context)]) == 0
    assert main(["--db", db, "status"]) == 0
    status = json.loads(capsys.readouterr().out)
    assert status["next"]["reason"] == "frontier_resolved"
    assert "not whole-app" in status["coverage"]
    assert main(["--db", db, "export"]) == 0
    text = capsys.readouterr().out
    exported = tmp_path / "export.json"
    exported.write_text(text, encoding="utf-8")
    other = str(tmp_path / "other.db")
    assert main(["--db", other, "import", str(exported)]) == 0
    assert main(["--db", other, "export"]) == 0
    assert capsys.readouterr().out == text


def test_missing_db_does_not_create_file(tmp_path):
    db = tmp_path / "missing.db"
    with pytest.raises(SystemExit) as exc:
        main(["--db", str(db), "status"])
    assert exc.value.code == 1
    assert not db.exists()


def test_recovery_requires_confirmation(tmp_path, capsys):
    db = tmp_path / "graph.db"
    with GraphStore(db) as store:
        store.initialize({})
        store.add_observation("o", {})
        store.add_state("s", "o", signature="s", depth=0)
        store.add_action("a", "s", "back")
        store.approve_action("a", "operator")
        store.begin_attempt("try", "a", "o")
    with pytest.raises(SystemExit):
        main(["--db", str(db), "recover"])
    with GraphStore(db) as store:
        assert store.document()["attempts"]["try"]["status"] == "intent"
    assert main(["--db", str(db), "recover", "--executor-stopped"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["interrupted"] == 1
    assert result["next"]["reason"] == "recovery_required"


@pytest.mark.parametrize("text", ["not JSON", "[]", '{"format":"future"}'])
def test_invalid_import_reports_error(tmp_path, text, capsys):
    source = tmp_path / "bad.json"
    source.write_text(text, encoding="utf-8")
    with pytest.raises(SystemExit) as exc:
        main(["--db", str(tmp_path / "graph.db"), "import", str(source)])
    assert exc.value.code == 1
    assert "tap-explorer:" in capsys.readouterr().err


def test_sample_missing_apk_fails_before_contacting_a_device(tmp_path, monkeypatch):
    from tap_e2e import TapClient

    def forbidden(*args, **kwargs):
        pytest.fail("must not contact a daemon/device without a readable sample APK")

    monkeypatch.setattr(TapClient, "create", forbidden)
    output = tmp_path / "evidence"
    with pytest.raises(SystemExit) as failure:
        main(["sample", "--serial", "fake", "--apk", str(tmp_path / "missing.apk"), "--out", str(output)])
    assert failure.value.code == 1
    assert not output.exists()


def test_sample_rejects_offline_db_option_before_input(tmp_path):
    with pytest.raises(SystemExit) as failure:
        main(["--db", str(tmp_path / "graph.db"), "sample", "--serial", "fake",
              "--apk", "missing.apk", "--out", str(tmp_path / "evidence")])
    assert failure.value.code == 2
    assert not (tmp_path / "graph.db").exists()
