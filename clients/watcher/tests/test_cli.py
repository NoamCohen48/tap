import pytest

from tap_watcher import __version__
from tap_watcher.cli import main


def test_version_prints_the_installed_version_and_exits(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as exit_info:
        main(["--version"])
    assert exit_info.value.code == 0
    assert capsys.readouterr().out.strip() == f"tap-watcher {__version__}"
