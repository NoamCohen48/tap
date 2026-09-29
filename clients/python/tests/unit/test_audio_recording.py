"""Device audio API: identity, validation and checksum without a physical device."""

import hashlib
from types import SimpleNamespace

import pytest
from tap_e2e import AudioRecording
from tap_e2e import _gen as pb
from tap_e2e.device import Device, Timeouts
from tap_e2e.errors import TapError


class Stub:
    def __init__(self):
        self.started = None
        self.stopped = None
        self.payload = b"opus test bytes"
        self.hash = hashlib.sha256(self.payload).hexdigest()

    def StartAudioRecording(self, request, timeout):
        self.started = (request, timeout)
        return pb.StartAudioRecordingResponse()

    def StopAudioRecording(self, request, timeout):
        self.stopped = (request, timeout)
        return pb.StopAudioRecordingResponse(opus=self.payload, sha256=self.hash)


def attached():
    stub = Stub()
    owner = SimpleNamespace(
        id="owner", client=SimpleNamespace(device_stub=stub), ensure_usable=lambda _: None
    )
    device = Device(owner, pb.AttachResponse(attached_device_id="device", serial="emulator-5554"), "aut", Timeouts())
    return device, stub


def test_start_stop_audio_is_scoped_and_verified():
    device, stub = attached()
    device.start_audio_recording("playback", 12)
    assert stub.started[0].client_connection_id == "owner"
    assert stub.started[0].attached_device_id == "device"
    assert stub.started[0].source == "playback"
    assert stub.started[0].max_seconds == 12
    result = device.stop_audio_recording()
    assert isinstance(result, AudioRecording)
    assert result.bytes == stub.payload
    assert result.extension == "opus"
    assert stub.stopped[0].attached_device_id == "device"


def test_audio_rejects_invalid_input_and_checksum():
    device, stub = attached()
    with pytest.raises(ValueError):
        device.start_audio_recording("call")
    with pytest.raises(ValueError):
        device.start_audio_recording(max_seconds=61)
    assert stub.started is None
    stub.hash = "bad"
    with pytest.raises(TapError, match="checksum"):
        device.stop_audio_recording()
