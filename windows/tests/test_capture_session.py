from __future__ import annotations

from pathlib import Path
import sys
import threading

import pytest

from fluentmai_core.capture_session import CaptureError, LocalCaptureController
from fluentmai_core.network_recovery import NoopSystemProxyBackend, ProxySnapshot, RecoveryJournal


class FixtureController(LocalCaptureController):
    def _helper_command(self, ipc_port: int, proxy_port: int) -> tuple[list[str], str]:
        script = Path(__file__).with_name("_mock_capture_helper.py")
        return (
            [
                sys.executable,
                str(script),
                "--ipc-port",
                str(ipc_port),
                "--proxy-port",
                str(proxy_port),
                "--cert-dir",
                str(script.parent / "unused-certs"),
            ],
            str(Path(sys.executable).resolve()),
        )


def _controller(tmp_path):
    snapshot = ProxySnapshot(
        proxy_enable=0,
        proxy_server="fixture-before",
        auto_config_url="https://example.invalid/proxy.pac",
        proxy_override="<local>",
        auto_detect=1,
        winhttp_fingerprint="fixture-winhttp",
    )
    backend = NoopSystemProxyBackend(snapshot)
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    return FixtureController(backend, journal=journal), backend, journal, snapshot


def test_success_returns_pages_only_after_network_is_restored(monkeypatch, tmp_path):
    monkeypatch.setenv("FLUENTMAI_MOCK_CAPTURE_SCENARIO", "success")
    monkeypatch.setattr("fluentmai_core.capture_session.focus_wechat_window", lambda: True)
    controller, backend, journal, snapshot = _controller(tmp_path)

    result = controller.capture(install_ca=False, wait_timeout=0.2, request_timeout=0.1, retries=0)

    assert result.helper_version == "fixture-helper"
    assert result.home_html == "<html>home fixture</html>"
    assert [difficulty for difficulty, _body in result.pages] == list(range(5))
    assert backend.last_applied and backend.last_applied.startswith("127.0.0.1:")
    assert backend.current == snapshot
    assert backend.restored == snapshot
    assert not journal.path.exists()


@pytest.mark.parametrize(
    ("scenario", "category", "proxy_was_active"),
    [
        ("bad_auth", "ipc_authentication_failed", False),
        ("error", "capture_timeout", True),
        ("crash", "helper_ipc_closed", True),
    ],
)
def test_failure_paths_restore_if_proxy_was_activated(
    monkeypatch,
    tmp_path,
    scenario,
    category,
    proxy_was_active,
):
    monkeypatch.setenv("FLUENTMAI_MOCK_CAPTURE_SCENARIO", scenario)
    monkeypatch.setattr("fluentmai_core.capture_session.focus_wechat_window", lambda: False)
    controller, backend, journal, snapshot = _controller(tmp_path)

    with pytest.raises(CaptureError) as error:
        controller.capture(install_ca=False, wait_timeout=0.2, request_timeout=0.1, retries=0)

    assert error.value.category == category
    assert bool(backend.last_applied) is proxy_was_active
    assert backend.current == snapshot
    assert not journal.path.exists()


def test_cancel_after_proxy_activation_restores_network(monkeypatch, tmp_path):
    monkeypatch.setenv("FLUENTMAI_MOCK_CAPTURE_SCENARIO", "hang")
    monkeypatch.setattr("fluentmai_core.capture_session.focus_wechat_window", lambda: False)
    controller, backend, journal, snapshot = _controller(tmp_path)
    cancel = threading.Event()

    def progress(stage, _info):
        if stage == "network_proxy_active":
            cancel.set()

    with pytest.raises(CaptureError) as error:
        controller.capture(
            progress=progress,
            cancel_event=cancel,
            install_ca=False,
            wait_timeout=0.2,
            request_timeout=0.1,
            retries=0,
        )

    assert error.value.category == "cancelled"
    assert backend.restored == snapshot
    assert backend.current == snapshot
    assert not journal.path.exists()
