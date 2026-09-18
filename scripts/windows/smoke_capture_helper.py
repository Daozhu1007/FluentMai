from __future__ import annotations

import argparse
from pathlib import Path
import sys
import tempfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT / "windows"))

from fluentmai_core.capture_session import CaptureError, LocalCaptureController
from fluentmai_core.network_recovery import NoopSystemProxyBackend, ProxySnapshot, RecoveryJournal


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", required=True)
    helper = Path(parser.parse_args().helper).resolve()
    if not helper.is_file():
        raise FileNotFoundError(helper)
    root = Path(tempfile.mkdtemp(prefix="FluentMai-PackagedHelper-Smoke-"))
    snapshot = ProxySnapshot(proxy_enable=0, winhttp_fingerprint="fixture")
    backend = NoopSystemProxyBackend(snapshot)
    journal = RecoveryJournal(root / "recovery.bin")
    controller = LocalCaptureController(backend, journal=journal, helper_path=str(helper))
    try:
        controller.capture(
            install_ca=False,
            wait_timeout=0.2,
            request_timeout=0.1,
            retries=0,
            retry_delay=0,
        )
    except CaptureError as exc:
        if exc.category != "capture_timeout":
            raise
    else:
        raise RuntimeError("Packaged helper unexpectedly completed without a Wahlap page.")
    if backend.current != snapshot or journal.path.exists():
        raise RuntimeError("Packaged helper smoke did not restore the simulated proxy state.")
    print("WINDOWS_CAPTURE_HELPER_SMOKE=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
