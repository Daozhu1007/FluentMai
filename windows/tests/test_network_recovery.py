from __future__ import annotations

import os

import pytest

from fluentmai_core.network_recovery import (
    NetworkCaptureGuard,
    NoopSystemProxyBackend,
    ProxySnapshot,
    RecoveryJournal,
    RecoveryRecord,
    WindowsRegistryProxyBackend,
    recover_pending_network,
)


def _snapshot() -> ProxySnapshot:
    return ProxySnapshot(
        proxy_enable=0,
        proxy_server="http=127.0.0.1:7897;https=127.0.0.1:7897",
        auto_config_url="https://proxy.example.invalid/config.pac?private=value",
        proxy_override="localhost;127.*;<local>",
        auto_detect=1,
        winhttp_fingerprint="abc123",
    )


@pytest.mark.skipif(os.name != "nt", reason="DPAPI is a Windows current-user service")
def test_recovery_journal_is_dpapi_protected_and_round_trips(tmp_path):
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    record = RecoveryRecord(
        snapshot=_snapshot(),
        helper_pid=123,
        helper_path=r"C:\Program Files\FluentMai\FluentMaiCaptureProxy.exe",
        proxy_address="127.0.0.1:43210",
        created_at=1234.5,
        helper_started_at=1234.0,
    )

    journal.save(record)

    ciphertext = journal.path.read_bytes()
    assert b"private=value" not in ciphertext
    assert b"ProxyEnable" not in ciphertext
    assert journal.load() == record


def test_guard_restores_exact_snapshot_and_clears_journal(tmp_path):
    snapshot = _snapshot()
    backend = NoopSystemProxyBackend(snapshot)
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    guard = NetworkCaptureGuard(backend, journal)

    guard.activate("127.0.0.1:43210", helper_pid=123, helper_path="helper.exe")
    assert journal.path.exists()
    assert backend.last_applied == "127.0.0.1:43210"

    restored = guard.restore()

    assert restored == snapshot
    assert backend.restored == snapshot
    assert not journal.path.exists()


def test_guard_accepts_windows_discarding_disabled_auto_detect_value(tmp_path):
    snapshot = _snapshot()

    class MissingDisabledAutoDetectBackend(NoopSystemProxyBackend):
        def apply(self, proxy_server: str) -> None:
            super().apply(proxy_server)
            self.current = ProxySnapshot(
                proxy_enable=self.current.proxy_enable,
                proxy_server=self.current.proxy_server,
                auto_config_url=self.current.auto_config_url,
                proxy_override=self.current.proxy_override,
                auto_detect=None,
                winhttp_fingerprint=self.current.winhttp_fingerprint,
                winhttp_dump_b64=self.current.winhttp_dump_b64,
            )

    backend = MissingDisabledAutoDetectBackend(snapshot)
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    guard = NetworkCaptureGuard(backend, journal)

    guard.activate("127.0.0.1:43210")
    assert journal.path.exists()

    assert guard.restore() == snapshot
    assert not journal.path.exists()


def test_apply_failure_restores_before_clearing_journal(tmp_path):
    class FailingBackend(NoopSystemProxyBackend):
        def apply(self, proxy_server: str) -> None:
            super().apply(proxy_server)
            raise RuntimeError("apply failed")

    snapshot = _snapshot()
    backend = FailingBackend(snapshot)
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    guard = NetworkCaptureGuard(backend, journal)

    with pytest.raises(RuntimeError, match="apply failed"):
        guard.activate("127.0.0.1:43210")

    assert backend.restored == snapshot
    assert not journal.path.exists()


def test_activation_is_verified_and_unexpected_state_is_rolled_back(tmp_path):
    class IgnoringBackend(NoopSystemProxyBackend):
        def apply(self, proxy_server: str) -> None:
            self.last_applied = proxy_server

    snapshot = _snapshot()
    backend = IgnoringBackend(snapshot)
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    guard = NetworkCaptureGuard(backend, journal)

    with pytest.raises(RuntimeError, match="activation verification"):
        guard.activate("127.0.0.1:43210")

    assert backend.current == snapshot
    assert backend.restored == snapshot
    assert not journal.path.exists()


def test_pending_recovery_restores_after_previous_process_exit(tmp_path):
    snapshot = _snapshot()
    backend = NoopSystemProxyBackend(ProxySnapshot(proxy_enable=1, proxy_server="127.0.0.1:43210"))
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    journal.save(
        RecoveryRecord(
            snapshot=snapshot,
            helper_pid=None,
            helper_path="",
            proxy_address="127.0.0.1:43210",
            created_at=1234.5,
        )
    )

    assert recover_pending_network(backend, journal)
    assert backend.current == snapshot
    assert not journal.path.exists()


def test_real_backend_is_read_only_without_explicit_system_change_permission():
    backend = WindowsRegistryProxyBackend()
    before = backend.snapshot()

    with pytest.raises(PermissionError):
        backend.apply("127.0.0.1:43210")

    assert backend.snapshot() == before
