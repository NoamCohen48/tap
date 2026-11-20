"""CLI/MCP share a cursor; acknowledgement never follows a failed read or renderer."""
# pyright: reportMissingImports=false
import json
import stat
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

import pytest
from tap_e2e import Toast, ToastEvent, ToastHistory

from tap_agent.toast_inbox import ToastInbox


def history(after=0, stream="one", reset=False):
    return ToastHistory(stream, tuple(ToastEvent(i, i * 1000, Toast(f"toast-{i}", "com.shop"), False)
                                    for i in range(1, 4) if i > after), 3, 1, reset, reset)


def test_cursor_survives_instances_is_attachment_specific_and_private(tmp_path):
    device = Mock(attached_device_id="attachment-a")
    device.toast_history.side_effect = lambda after, stream_id: history(after)
    assert len(ToastInbox(tmp_path).read(device, lambda p: p.events)) == 3
    assert ToastInbox(tmp_path).read(device, lambda p: p.events) == ()
    device.toast_history.assert_called_with(3, stream_id="one")
    device.attached_device_id = "attachment-b"
    assert len(ToastInbox(tmp_path).read(device, lambda p: p.events)) == 3
    for path in (tmp_path / "toast-inbox").iterdir():
        assert stat.S_IMODE(path.stat().st_mode) == 0o600
        if path.suffix == ".json":
            assert "toast-" not in path.read_text()


def test_read_or_render_failure_does_not_acknowledge(tmp_path):
    device = Mock(attached_device_id="attachment-a")
    inbox = ToastInbox(tmp_path)
    device.toast_history.side_effect = RuntimeError("lost")
    with pytest.raises(RuntimeError):
        inbox.read(device, lambda p: p)
    device.toast_history.side_effect = lambda after, stream_id: history(after)
    with pytest.raises(ZeroDivisionError):
        inbox.read(device, lambda p: 1 / 0)
    assert len(inbox.read(device, lambda p: p.events)) == 3


def test_failed_cursor_write_does_not_acknowledge(tmp_path, monkeypatch):
    device = Mock(attached_device_id="attachment-a")
    device.toast_history.side_effect = lambda after, stream_id: history(after)
    inbox = ToastInbox(tmp_path)
    with monkeypatch.context() as patch:
        patch.setattr("tap_agent.toast_inbox.os.replace", Mock(side_effect=OSError("disk full")))
        with pytest.raises(OSError, match="disk full"):
            inbox.read(device, lambda p: p.events)
    assert not list((tmp_path / "toast-inbox").glob("*.json"))
    assert len(inbox.read(device, lambda p: p.events)) == 3


def test_concurrent_readers_do_not_duplicate_acknowledged_events(tmp_path):
    device = Mock(attached_device_id="attachment-a")
    device.toast_history.side_effect = lambda after, stream_id: history(after)
    with ThreadPoolExecutor(max_workers=8) as pool:
        pages = list(pool.map(lambda _: ToastInbox(tmp_path).read(device, lambda p: p.events), range(8)))
    assert sum(len(events) for events in pages) == 3


def test_corrupt_cursor_is_explicit_not_silently_acknowledged(tmp_path):
    device = Mock(attached_device_id="attachment-a")
    device.toast_history.side_effect = lambda after, stream_id: history(after)
    inbox = ToastInbox(tmp_path)
    inbox.read(device, lambda p: p)
    path = next((tmp_path / "toast-inbox").glob("*.json"))
    path.write_text(json.dumps({"sequence": True, "stream": "one"}))
    with pytest.raises(ValueError, match="Invalid toast inbox cursor"):
        inbox.read(device, lambda p: p)
