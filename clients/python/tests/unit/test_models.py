"""Every schema value has a model counterpart, so a proto addition without one fails here."""
# pyright: reportAttributeAccessIssue=false

import json
from dataclasses import replace

import pytest  # type: ignore[import-not-found]

from tap_e2e import (
    DeviceInfo,
    DeviceState,
    Direction,
    DisplayRotation,
    DriverLog,
    ErrorCode,
    FailureReason,
    Hierarchy,
    Long,
    MatchMode,
    Orientation,
    PermissionChoice,
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
    assert [name.removeprefix("ORIENTATION_") for name, _ in _known(pb.Orientation)] == [value.name for value in Orientation]
    assert [name.removeprefix("DISPLAY_ROTATION_") for name, _ in _known(pb.DisplayRotation)] == [
        value.name for value in DisplayRotation
    ]
    assert [n for _, n in _known(pb.PermissionChoice)] == [_proto.permission_choice(c) for c in PermissionChoice]
    assert [n for _, n in _known(pb.Orientation)] == [_proto.orientation(o) for o in Orientation]
    assert [n for _, n in _known(pb.DisplayRotation)] == [_proto.display_rotation(r) for r in DisplayRotation]


def test_device_info_maps_rotation_and_screen_state():
    info = _proto.device_info(
        pb.DeviceInfo(display_width=2400, display_height=1080, display_rotation=3, screen_on=True, keyguard_locked=True)
    )
    assert info.display_rotation is DisplayRotation.RIGHT
    assert info.orientation is Orientation.LANDSCAPE
    assert (info.screen_on, info.keyguard_locked, info.keyguard_secure) == (True, True, False)
    assert (info.stay_awake, info.high_contrast_text, info.color_inversion, info.bold_text) == (False, None, None, False)
    a11y = _proto.device_info(pb.DeviceInfo(stay_awake=True, high_contrast_text=False, color_inversion=True, bold_text=True))
    assert (a11y.stay_awake, a11y.high_contrast_text, a11y.color_inversion, a11y.bold_text) == (True, False, True, True)


def test_intent_extras_keep_their_type_and_refuse_what_am_start_cannot_carry():
    extras = _proto.intent_extras({"q": "shoes", "flag": True, "count": -3, "id": Long(9_000_000_000), "ratio": 0.5})
    assert [e.WhichOneof("value") for e in extras] == ["string_value", "bool_value", "int_value", "long_value", "float_value"]
    assert (extras[1].bool_value, extras[2].int_value, extras[3].long_value, extras[4].float_value) == (True, -3, 9_000_000_000, 0.5)
    with pytest.raises(ValueError, match="Long"):
        _proto.intent_extras({"id": 2**31})
    with pytest.raises(TypeError):
        _proto.intent_extras({"list": [1]})
    with pytest.raises(TypeError):
        Long(True)
    with pytest.raises(ValueError):
        Long(2**63)


def test_a_permission_prompt_leaves_out_choices_this_client_does_not_know():
    prompt = _proto.permission_prompt(
        pb.PermissionPrompt(package_name="com.android.permissioncontroller", choices=[pb.PERMISSION_DENY, 99, pb.PERMISSION_ALLOW])
    )
    assert prompt.choices == (PermissionChoice.DENY, PermissionChoice.ALLOW)


def test_a_screenshot_reads_its_size_from_the_png_header():
    header = b"\x89PNG" + bytes(12) + (1080).to_bytes(4, "big") + (2400).to_bytes(4, "big")
    shot = Screenshot._png(header)
    assert (shot.width, shot.height, shot.media_type, shot.extension) == (1080, 2400, "image/png", "png")
    assert (Screenshot._png(b"abc").width, Screenshot._png(b"abc").height) == (0, 0)


def test_artifacts_save_their_bytes(tmp_path):
    info = DeviceInfo(34, "Google", "Pixel", "sdk", 1080, 2400, DisplayRotation.NATURAL, None, True, False, False, True, True, False, True, 1.3, 420, False, True, True, ("fr-FR", "en"))
    saved = json.loads(info.save(tmp_path / "a" / "info.json").read_text())
    assert saved["api_level"] == 34 and saved["current_package"] is None
    assert saved["display_rotation"] == "NATURAL" and saved["screen_on"] is True
    assert saved["keyboard_shown"] is True and saved["auto_rotate"] is True
    assert saved["animations_enabled"] is False and saved["dark_mode"] is True
    assert saved["font_scale"] == 1.3 and saved["density_dpi"] == 420
    assert (saved["airplane_mode"], saved["wifi_enabled"], saved["mobile_data_enabled"]) == (False, True, True)
    assert saved["system_locales"] == ["fr-FR", "en"]
    assert (saved["stay_awake"], saved["high_contrast_text"], saved["bold_text"]) == (False, None, False)
    assert info.orientation is Orientation.PORTRAIT
    assert replace(info, display_width=2400, display_height=1080).orientation is Orientation.LANDSCAPE
    assert Hierarchy("<a/>").save(tmp_path / "h.xml").read_text() == "<a/>"
    assert DriverLog(["one", "two"]).save(tmp_path / "d.txt").read_text() == "one\ntwo"
