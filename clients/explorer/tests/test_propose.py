import json

import pytest

from tap_explorer.cli import main
from tap_explorer.propose import answer, command, propose
from tap_explorer.rules import questions

from test_rules import _model


def _questions(tmp_path):
    path = tmp_path / "questions.json"
    path.write_text(json.dumps(questions(_model())), encoding="utf-8")
    return path


def test_pi_runs_read_only_with_the_source_as_its_directory_and_its_answer_is_written(tmp_path):
    source = tmp_path / "src"
    source.mkdir()
    calls = []
    rule = {"screen": "OptionsRobot", "method": "tap_save_rejected", "mode": "any",
            "facts": ["store is empty", "balance = 'Grace'"], "reason": "Edit.java:12 parses the balance"}

    def run(argv, cwd, timeout):
        calls.append((argv, cwd))
        return "Here it is:\n```json\n" + json.dumps(
            {"format": "tap-rule-candidates/1", "source": "me", "rules": [rule]}) + "\n```\n"

    out = tmp_path / "rules.json"
    assert propose(_questions(tmp_path), out, source, run=run)["rules"] == 1
    [(argv, cwd)] = calls
    assert cwd == source and argv[argv.index("--tools") + 1] == "read,grep,find,ls"
    assert {"--no-extensions", "--no-context-files", "--no-session"} <= set(argv)
    written = json.loads(out.read_text(encoding="utf-8"))
    assert written["rules"] == [rule] and written["source"] == "openai-codex/gpt-6-luna via pi (read source)"


def test_without_source_pi_gets_no_tools():
    argv = command("pi", "m", __import__("pathlib").Path("q.json"), "msg", None)
    assert "--no-tools" in argv and "--tools" not in argv


@pytest.mark.parametrize("reply", ["no json here", '{"format": "other", "rules": []}',
                                   '{"format": "tap-rule-candidates/1", "rules": {}}'])
def test_a_reply_that_is_not_a_candidates_document_is_refused(reply):
    with pytest.raises(ValueError):
        answer(reply)


def test_without_send_nothing_is_sent_and_what_would_be_is_printed(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr("tap_explorer.propose._run", lambda *a: pytest.fail("sent without --send"))
    assert main(["robots-propose", "--questions", str(_questions(tmp_path)), "--out",
                 str(tmp_path / "rules.json")]) == 0
    printed = capsys.readouterr().out
    assert "balance = 'Grace'" in printed and "nothing sent" in printed
    assert not (tmp_path / "rules.json").exists()
