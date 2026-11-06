"""Invalid Unicode and protocol UTF-16 limits fail locally, not inside protobuf construction."""
import json

import pytest

from tap_agent.targets import UsageError, parse_key, parse_target


@pytest.mark.parametrize("target", [
    "text=\ud800", r'{"text":"\ud800"}',
    "text=" + chr(0x1F600) * 513,
    "pkg=" + chr(0x1F600) * 513 + ",text=ok",
    "class=" + "x" * 1024,  # class suffix shorthand adds a dot before protocol validation
    " " * 65536 + "text=ok",
    "text=ok,index=²", "text=ok,index=" + "9" * 5000, "text=ok,index=2147483648",
    '{"text":"ok","index":' + "9" * 5000 + "}",
])
def test_invalid_unicode_or_oversized_target_is_a_usage_error(target):
    with pytest.raises(UsageError):
        parse_target(target)


@pytest.mark.parametrize("value", ["²", "9" * 5000, "2147483648"])
def test_key_codes_fail_locally_for_invalid_decimal_or_overflow(value):
    with pytest.raises(UsageError):
        parse_key(value)


@pytest.mark.parametrize("structured", [False, True])
def test_astral_text_at_protocol_boundary_is_accepted(structured):
    value = chr(0x1F600) * 512
    target = json.dumps({"text": value}) if structured else "text=" + value
    parsed = parse_target(target)
    assert parsed.selector is not None
    assert parsed.selector.to_proto().node.match.value == value
