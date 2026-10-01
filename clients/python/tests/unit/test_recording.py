"""Device recording API: identity, validation and checksum without a physical device."""

import hashlib
from types import SimpleNamespace

import pytest
from tap_e2e import Recording
from tap_e2e import _gen as pb
from tap_e2e.device import Device, Timeouts
from tap_e2e.errors import TapError


class Stub:
    def __init__(self):
        self.payload = b"opus test bytes"
        self.hash = hashlib.sha256(self.payload).hexdigest()
        self.media_started = None
        self.media_stopped = None

    def StartRecording(self, request, timeout):
        self.media_started = (request, timeout)
        return pb.StartRecordingResponse()

    def StopRecording(self, request, timeout):
        self.media_stopped = (request, timeout)
        return pb.StopRecordingResponse(data=self.payload, format="mkv", sha256=self.hash)


def attached():
    stub = Stub()
    owner = SimpleNamespace(
        id="owner", client=SimpleNamespace(device_stub=stub), ensure_usable=lambda _: None
    )
    device = Device(owner, pb.AttachResponse(attached_device_id="device", serial="emulator-5554"), Timeouts())
    return device, stub


def test_audio_only_recording_sends_no_video_and_the_audio_limit():
    device, stub = attached()
    device.start_recording(video=False, audio_source="mic")
    request = stub.media_started[0]
    assert (request.client_connection_id, request.attached_device_id) == ("owner", "device")
    assert request.video is False
    assert request.audio_source == "mic"
    assert request.max_seconds == 60
    assert Recording(b"x", "opus").media_type == "audio/ogg"


def test_recording_selects_tracks_and_checks_artifact():
    device, stub = attached()
    device.start_recording(audio_source="playback", max_seconds=12)
    assert stub.media_started[0].video is True
    assert stub.media_started[0].audio_source == "playback"
    assert stub.media_started[0].max_seconds == 12
    result = device.stop_recording()
    assert isinstance(result, Recording)
    assert result.extension == "mkv"
    assert result.media_type == "video/x-matroska"
    assert result.bytes == stub.payload
    assert stub.media_stopped[0].client_connection_id == "owner"


def test_recording_validation_and_corrupt_checksum():
    device, stub = attached()
    with pytest.raises(ValueError):
        device.start_recording(video=False)
    with pytest.raises(ValueError):
        device.start_recording(max_seconds=31)
    with pytest.raises(ValueError):
        device.start_recording(audio_source="call")
    assert stub.media_started is None
    stub.hash = "bad"
    with pytest.raises(TapError, match="checksum"):
        device.stop_recording()
