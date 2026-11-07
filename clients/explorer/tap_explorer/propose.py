"""Ask a model, through the `pi` agent CLI, to answer a `tap-rule-questions/1` file.

The explorer itself still calls no provider: this runs `pi` as a child process, read-only, on
the person's own credentials, and only after they pass ``send``. What leaves the machine is the
questions file (screen texts, field names and the values the explorer typed) and, with a
source directory, whatever source files the model chooses to read there. The answer is a
`tap-rule-candidates/1` document; `robots --rules` checks it against every recorded attempt,
so a wrong rule is shown as contradicted, never applied.
"""

from __future__ import annotations

import json
import re
import subprocess
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path

from .rules import CANDIDATES_FORMAT, MAX_FACTS, QUESTIONS_FORMAT

DEFAULT_MODEL = "openai-codex/gpt-6-luna"
TIMEOUT_SECONDS = 1800
READ_ONLY_TOOLS = "read,grep,find,ls"
Runner = Callable[[Sequence[str], Path, int], str]


def prompt(questions: Mapping, source: Path | None) -> str:
    """The message sent with the questions file attached."""
    where = (f"The app's source is in the current directory ({source.name}): read it to find the code "
             "that decides each outcome (validation before a save, a duplicate check, a value parse). "
             if source else "No source is available: reason from the screen texts and field names. ")
    return (
        f"The attached file is a {QUESTIONS_FORMAT} document from exploring the Android app "
        f"{questions['package']}. {questions['instructions']} {where}"
        f"Each rule's facts must be copied exactly from the vocabulary, 1 to {MAX_FACTS} of them; "
        "a rule may name only a method listed under its control. Prefer the rule the code states over "
        "one that merely fits the attempts, and say in reason where the code states it (file:line). "
        "Propose nothing for an outcome you cannot explain. Reply with only one JSON object, no prose: "
        f'{{"format": "{CANDIDATES_FORMAT}", "source": "<model>", "rules": [{{"screen": "...", '
        '"method": "...", "mode": "all", "facts": ["..."], "reason": "..."}]}')


def command(pi: str, model: str, questions_path: Path, message: str, source: Path | None) -> list[str]:
    """A `pi` invocation that cannot write files, run commands or load the person's extensions."""
    return [pi, "--print", "--no-session", "--no-context-files", "--no-extensions", "--no-skills",
            "--no-prompt-templates", "--no-mcp", "--no-approve", "--model", model, "--thinking", "high",
            *(["--tools", READ_ONLY_TOOLS] if source else ["--no-tools"]),
            f"@{questions_path.resolve()}", message]


def _run(argv: Sequence[str], cwd: Path, timeout: int) -> str:
    done = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout, check=False)
    if done.returncode != 0:
        raise ValueError(f"pi exited {done.returncode}: {done.stderr.strip()[-500:]}")
    return done.stdout


def answer(text: str) -> dict:
    """The candidates object in a model's reply: the last fenced JSON block, or the whole reply."""
    fenced = re.findall(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    body = fenced[-1] if fenced else text[text.find("{"):text.rfind("}") + 1]
    try:
        value = json.loads(body)
    except ValueError as error:
        raise ValueError(f"the reply holds no JSON object: {error}") from error
    if not isinstance(value, dict) or value.get("format") != CANDIDATES_FORMAT or not isinstance(
            value.get("rules"), list):
        raise ValueError(f"the reply is not a {CANDIDATES_FORMAT} document")
    return value


def propose(questions_path: Path, out: Path, source: Path | None = None, model: str = DEFAULT_MODEL,
            pi: str = "pi", run: Runner = _run, timeout: int = TIMEOUT_SECONDS) -> dict:
    """Send the questions (and the source directory, read-only) to ``model``; write its candidates."""
    questions = json.loads(questions_path.read_text(encoding="utf-8"))
    if questions.get("format") != QUESTIONS_FORMAT:
        raise ValueError(f"{questions_path} is not a {QUESTIONS_FORMAT} document")
    if source is not None and not source.is_dir():
        raise ValueError(f"{source} is not a directory")
    cwd = source if source else out.parent
    reply = run(command(pi, model, questions_path, prompt(questions, source), source), cwd, timeout)
    candidates = answer(reply)
    candidates["source"] = f"{model} via pi" + (" (read source)" if source else "")
    out.write_text(json.dumps(candidates, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {"rules": len(candidates["rules"]), "out": str(out), "source": candidates["source"]}


def outgoing(questions_path: Path, source: Path | None) -> str:
    """What ``propose`` would send, for the person to read before passing ``send``."""
    questions = json.loads(questions_path.read_text(encoding="utf-8"))
    texts = sorted({fact for fact in questions.get("vocabulary", []) if "=" in fact or fact.startswith("text ")})
    lines = [f"{questions_path} ({questions_path.stat().st_size} bytes): {len(questions.get('controls', []))} "
             f"controls of {questions.get('package')}, {len(questions.get('vocabulary', []))} facts, among them:"]
    lines += [f"  {fact}" for fact in texts[:40]] + ([f"  … and {len(texts) - 40} more"] if len(texts) > 40 else [])
    lines.append(f"source: the model may read any file under {source.resolve()} (read, grep, find, ls; no writes, "
                 "no commands)" if source else "source: none")
    return "\n".join(lines)
