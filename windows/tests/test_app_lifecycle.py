from __future__ import annotations

from fluentmai_core.app_lifecycle import recover_network_before_window
from fluentmai_core.network_recovery import (
    NoopSystemProxyBackend,
    ProxySnapshot,
    RecoveryJournal,
    RecoveryRecord,
)


def test_startup_recovery_is_noop_without_journal(tmp_path):
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    backend = NoopSystemProxyBackend(ProxySnapshot(proxy_enable=0))

    assert not recover_network_before_window(backend, journal)
    assert backend.restored is None


def test_startup_recovery_consumes_protected_journal_before_window(tmp_path):
    original = ProxySnapshot(
        proxy_enable=0,
        proxy_server="user-proxy-before",
        auto_config_url="https://example.invalid/proxy.pac",
        proxy_override="<local>",
        auto_detect=1,
        winhttp_fingerprint="before",
    )
    backend = NoopSystemProxyBackend(
        ProxySnapshot(proxy_enable=1, proxy_server="127.0.0.1:43210")
    )
    journal = RecoveryJournal(tmp_path / "recovery.bin")
    journal.save(
        RecoveryRecord(
            snapshot=original,
            helper_pid=None,
            helper_path="",
            proxy_address="127.0.0.1:43210",
            created_at=1234.5,
        )
    )

    assert recover_network_before_window(backend, journal)
    assert backend.current == original
    assert not journal.path.exists()
