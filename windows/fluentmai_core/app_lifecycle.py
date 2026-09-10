from __future__ import annotations

from .network_recovery import (
    RecoveryJournal,
    SystemProxyBackend,
    WindowsRegistryProxyBackend,
    recover_pending_network,
)


def recover_network_before_window(
    backend: SystemProxyBackend | None = None,
    journal: RecoveryJournal | None = None,
) -> bool:
    journal = journal or RecoveryJournal()
    if not journal.path.exists():
        return False
    backend = backend or WindowsRegistryProxyBackend(allow_system_changes=True)
    return recover_pending_network(backend, journal)
