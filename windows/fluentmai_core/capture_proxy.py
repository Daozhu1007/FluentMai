from __future__ import annotations

from pathlib import Path
import os
import subprocess
from .network_recovery import (
    NoopSystemProxyBackend,
    ProxySnapshot,
    SystemProxyBackend,
    WindowsRegistryProxyBackend,
)


class CaptureHelperController:
    def __init__(
        self,
        helper_path: str | None = None,
        backend: SystemProxyBackend | None = None,
    ):
        self.helper_path = helper_path or os.environ.get("FLUENTMAI_PROXY_HELPER", "")
        self.backend = backend or NoopSystemProxyBackend()
        self.process: subprocess.Popen | None = None
        self.snapshot: ProxySnapshot | None = None

    def find_helper(self) -> str | None:
        if self.helper_path and Path(self.helper_path).exists():
            return self.helper_path
        cwd = Path.cwd()
        for candidate in cwd.glob("maimaidx-prober-proxy-windows*.exe"):
            return str(candidate)
        return None

    def start(
        self,
        *,
        config_path: str,
        addr: str = "127.0.0.1:8033",
        edit_system_proxy: bool = False,
    ) -> None:
        helper = self.find_helper()
        if not helper:
            raise FileNotFoundError("Official maimaidx-prober proxy helper executable was not found.")
        if self.process and self.process.poll() is None:
            raise RuntimeError("Capture helper is already running.")
        args = [
            helper,
            "-config",
            config_path,
            "-addr",
            addr,
        ]
        if not edit_system_proxy:
            args.append("-no-edit-global-proxy")
        else:
            self.snapshot = self.backend.snapshot()
            self.backend.apply(addr)
        self.process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def stop(self) -> None:
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.snapshot is not None:
            self.backend.restore(self.snapshot)
            self.snapshot = None
