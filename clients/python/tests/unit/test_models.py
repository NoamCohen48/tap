"""Every schema value has a model counterpart, so a proto addition without one fails here."""
# pyright: reportAttributeAccessIssue=false

import json

from tap_e2e import (
    DeviceInfo,
    DeviceState,
    Direction,
    DriverLog,
    ErrorCode,
    FailureReason,
    Hierarchy,
    MatchMode,
    Screenshot,
    StabilitySignal,
)
from tap_e2e import _gen as pb
from tap_e2e import _proto


def _known(enum_type):
    return [(name, number) for name, number in enum_type.items() if not name.endswith("UNSPECIFIED")]


def test_every_proto_enum_value_maps_to_its_own_model_constant():
    for name, number in _known(pb.ErrorCode):
        assert _proto.error_code(number).name == name.removeprefix("ERR_")
    for name, number in _known(pb.FailureReason):
        assert _proto.failure_reason(number).name == name.removeprefix("FAILURE_REASON_")
    for name, number in _known(pb.DeviceState):
        assert _proto.device_state(number).name == name.removeprefix("DEVICE_")
    assert _proto.error_code(0) is ErrorCode.UNKNOWN
    assert _proto.error_code(999) is ErrorCode.UNKNOWN
    assert _proto.failure_reason(999) is FailureReason.UNSPECIFIED
    assert _proto.device_state(0) is DeviceState.UNKNOWN


def test_every_argument_enum_maps_to_a_proto_value():
    assert [n for _, n in _known(pb.MatchMode)] == [_proto.match_mode(m) for m in MatchMode]
    assert [n for _, n in _known(pb.Direction)] == [_proto.direction(d) for d in Direction]
    assert [n for _, n in _known(pb.StabilitySignal)] == [_proto.stability_signal(s) for s in StabilitySignal]


def test_a_screenshot_reads_its_size_from_the_png_header():
    header = b"\x89PNG" + bytes(12) + (1080).to_bytes(4, "big") + (2400).to_bytes(4, "big")
    shot = Screenshot._png(header)
    assert (shot.width, shot.height, shot.media_type, shot.extension) == (1080, 2400, "image/png", "png")
    assert (Screenshot._png(b"abc").width, Screenshot._png(b"abc").height) == (0, 0)


def test_artifacts_save_their_bytes(tmp_path):
    info = DeviceInfo(34, "Google", "Pixel", "sdk", 1080, 2400, 0, None)
    saved = json.loads(info.save(tmp_path / "a" / "info.json").read_text())
    assert saved["api_level"] == 34 and saved["current_package"] is None
    assert Hierarchy("<a/>").save(tmp_path / "h.xml").read_text() == "<a/>"
    assert DriverLog(["one", "two"]).save(tmp_path / "d.txt").read_text() == "one\ntwo"
