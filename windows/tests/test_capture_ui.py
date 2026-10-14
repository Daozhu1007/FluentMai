from __future__ import annotations

from fluentmai_core.automatic_import import CaptureImportResult
from fluentmai_core.capture_session import CaptureError
from fluentmai_core.models import ImportSummary
import ui_home


def _summary() -> ImportSummary:
    return ImportSummary(
        batch_id="fixture-batch",
        source="wahlap-wechat",
        fetched_count=50,
        parsed_count=50,
        inserted=48,
        updated=1,
        skipped_duplicate=1,
        quarantined=0,
        rejected=0,
        failed=0,
        current_version_id=25500,
        b35_count=35,
        b15_count=15,
        b35_rating=10000,
        b15_rating=4500,
        rating_before=14490,
        rating_after=14500,
    )


def test_capture_worker_emits_safe_progress_and_summary(monkeypatch, qapp):
    class Backend:
        def __init__(self, *, allow_system_changes):
            assert allow_system_changes

    class Controller:
        def __init__(self, backend):
            assert isinstance(backend, Backend)

    def runner(controller, *, progress, cancel_event, install_ca):
        assert isinstance(controller, Controller)
        assert install_ca
        assert not cancel_event.is_set()
        progress("network_proxy_active", {"proxy_port": 43210})
        return CaptureImportResult(
            summary=_summary(),
            captured_pages=5,
            captured_bytes=12345,
            helper_version="fixture",
            certificate_fingerprint="safe-fixture",
            elapsed_seconds=1.25,
        )

    monkeypatch.setattr(ui_home, "WindowsRegistryProxyBackend", Backend)
    monkeypatch.setattr(ui_home, "LocalCaptureController", Controller)
    monkeypatch.setattr(ui_home, "run_wahlap_capture_import", runner)
    worker = ui_home.CaptureImportWorker()
    progress_events = []
    results = []
    worker.progress.connect(lambda stage, info: progress_events.append((stage, info)))
    worker.completed.connect(results.append)

    worker.run()

    assert progress_events == [("network_proxy_active", {"proxy_port": 43210})]
    assert results[0]["success"]
    assert results[0]["summary"]["rating_after"] == 14500
    assert results[0]["network_restored"]


def test_capture_worker_cancel_is_reported_after_controller_cleanup(monkeypatch, qapp):
    monkeypatch.setattr(ui_home, "WindowsRegistryProxyBackend", lambda **_kwargs: object())
    monkeypatch.setattr(ui_home, "LocalCaptureController", lambda _backend: object())

    def cancelled(_controller, *, cancel_event, **_kwargs):
        assert cancel_event.is_set()
        raise CaptureError("cancelled")

    monkeypatch.setattr(ui_home, "run_wahlap_capture_import", cancelled)
    worker = ui_home.CaptureImportWorker()
    worker.request_cancel()
    results = []
    worker.completed.connect(results.append)

    worker.run()

    assert results == [
        {
            "success": False,
            "cancelled": True,
            "error_category": "cancelled",
            "network_restored": True,
        }
    ]


def test_unclassified_worker_failure_does_not_claim_network_restored(monkeypatch, qapp):
    monkeypatch.setattr(ui_home, "WindowsRegistryProxyBackend", lambda **_kwargs: object())
    monkeypatch.setattr(ui_home, "LocalCaptureController", lambda _backend: object())
    monkeypatch.setattr(
        ui_home,
        "run_wahlap_capture_import",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("restore failed")),
    )
    worker = ui_home.CaptureImportWorker()
    results = []
    worker.completed.connect(results.append)

    worker.run()

    assert not results[0]["success"]
    assert results[0]["error_category"] == "network_restoration_unverified"
    assert not results[0]["network_restored"]


def test_import_center_exposes_explicit_capture_and_fixed_cancel_controls(qapp):
    page = ui_home.HomeInterface()

    assert page.capture_btn.accessibleName() == "开始微信抓取"
    assert page.capture_btn.minimumHeight() >= 40
    assert "#2563eb" in page.capture_btn.styleSheet()
    assert page.capture_cancel_btn.accessibleName() == "取消微信抓取并恢复网络"
    assert not page.capture_cancel_btn.isEnabled()
    assert "#f1f5f9" in page.capture_cancel_btn.styleSheet()

    page._on_capture_progress("fetching_difficulty", {"difficulty": 2})
    assert "3/5" in page.capture_status.text()
    page._on_capture_progress("network_restored", {})
    assert "精确恢复" in page.capture_status.text()
    page.close()
    page.deleteLater()


def test_prepare_to_close_requests_cancel_and_waits_for_recovery(qapp):
    class Worker:
        cancelled = False

        def isRunning(self):
            return True

        def request_cancel(self):
            self.cancelled = True

        def wait(self, timeout_ms):
            assert timeout_ms == 1234
            return True

    page = ui_home.HomeInterface()
    worker = Worker()
    page._capture_worker = worker

    assert page.prepare_to_close(1234)
    assert worker.cancelled
    page._capture_worker = None
    page.close()
    page.deleteLater()
