"""No paid API calls: every provider response uses a local scripted transport."""

import json

import pytest

from tap_explorer.ai import NANO_MODEL, OpenAIVision, ProposalError, validate_proposal


@pytest.fixture
def evidence(tmp_path):
    image = tmp_path / "screen.png"
    image.write_bytes(b"\x89PNG\r\n\x1a\nfixture")
    return image, {"candidate_ids": ["s/tap-1"], "nodes": [{"text": "Settings"}]}


def proposal():
    return {"title": "Library", "summary": "No tracks are visible.", "actions": [
        {"candidate": "s/tap-1", "intent": "Open settings", "risk": "unknown", "reason": "Label suggests settings."}]}


def completed(value):
    return {"status": "completed", "output": [{"type": "message", "content": [
        {"type": "output_text", "text": json.dumps(value)}]}], "usage": {"input_tokens": 100, "output_tokens": 80}}


def test_nano_only_no_tools_review_and_cache(tmp_path, evidence):
    image, data = evidence
    calls = []

    def transport(body, key):
        calls.append(body)
        assert key == "fake-key"
        assert body["model"] == NANO_MODEL
        assert body["store"] is False
        assert "tools" not in body
        assert body["max_output_tokens"] == 2048
        assert body["input"][1]["content"][1]["detail"] == "low"
        return completed(proposal())

    ai = OpenAIVision(tmp_path / "ai", api_key="fake-key", transport=transport)
    with pytest.raises(ProposalError, match="review"):
        ai.interpret("obs-000001", image, data, reviewed_non_sensitive=False)
    assert not calls
    assert ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True) == proposal()
    assert ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True) == proposal()
    assert len(calls) == 1
    assert "fake-key" not in (tmp_path / "ai" / "obs-000001.json").read_text()


@pytest.mark.parametrize("model", ["gpt-5.4", "gpt-5.4-mini", "gpt-6-astra", "gpt-5.4-nano"])
def test_no_expensive_or_implicit_model_upgrade(tmp_path, model):
    with pytest.raises(ValueError, match="nano"):
        OpenAIVision(tmp_path / "ai", model=model)


def test_missing_key_makes_no_call(tmp_path, evidence):
    image, data = evidence

    def forbidden(*args):
        pytest.fail("missing credentials must never make a request")

    ai = OpenAIVision(tmp_path / "ai", api_key="", transport=forbidden)
    with pytest.raises(ProposalError, match="not configured"):
        ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True)
    assert not list((tmp_path / "ai").glob("*.json"))


def test_failed_request_reserves_budget_no_retry_no_fallback(tmp_path, evidence):
    image, data = evidence
    calls = []

    def unavailable(body, key):
        calls.append(body["model"])
        raise ProposalError("model unavailable")

    ai = OpenAIVision(tmp_path / "ai", api_key="fake", max_calls=1, transport=unavailable)
    with pytest.raises(ProposalError, match="unavailable"):
        ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True)
    with pytest.raises(ProposalError, match="no implicit retry"):
        ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True)
    with pytest.raises(ProposalError, match="budget"):
        ai.interpret("obs-000002", image, data, reviewed_non_sensitive=True)
    assert calls == [NANO_MODEL]


@pytest.mark.parametrize("invalid", [
    {**proposal(), "approve": True},
    {**proposal(), "actions": [{**proposal()["actions"][0], "candidate": "invented"}]},
    {**proposal(), "actions": [{**proposal()["actions"][0], "selector": {}}]},
    {**proposal(), "actions": [{**proposal()["actions"][0], "risk": "safe"}]},
    {**proposal(), "actions": proposal()["actions"] * 2},
])
def test_model_cannot_invent_references_or_grant_authority(invalid):
    with pytest.raises(ProposalError):
        validate_proposal(invalid, {"s/tap-1"})


@pytest.mark.parametrize("response", [
    {"status": "incomplete", "output": []},
    {"status": "completed", "output": []},
    {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal"}]}]},
])
def test_refused_or_incomplete_responses_are_not_proposals(tmp_path, evidence, response):
    image, data = evidence
    ai = OpenAIVision(tmp_path / "ai", api_key="fake", transport=lambda *_: response)
    with pytest.raises(ProposalError):
        ai.interpret("obs-000001", image, data, reviewed_non_sensitive=True)
    assert json.loads((tmp_path / "ai" / "obs-000001.json").read_text())["status"] == "failed"
