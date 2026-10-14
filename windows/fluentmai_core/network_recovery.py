from __future__ import annotations

import ctypes
from ctypes import wintypes
from dataclasses import asdict, dataclass
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from typing import Protocol

from .runtime_paths import capture_root


REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
REGISTRY_FIELDS = {
    "proxy_enable": ("ProxyEnable", "dword"),
    "proxy_server": ("ProxyServer", "string"),
    "auto_config_url": ("AutoConfigURL", "string"),
    "proxy_override": ("ProxyOverride", "string"),
    "auto_detect": ("AutoDetect", "dword"),
}
DPAPI_ENTROPY = b"FluentMai.NetworkRecovery.v1"
CRYPTPROTECT_UI_FORBIDDEN = 0x1


@dataclass(frozen=True)
class ProxySnapshot:
    proxy_enable: int | None = None
    proxy_server: str | None = None
    auto_config_url: str | None = None
    proxy_override: str | None = None
    auto_detect: int | None = None
    winhttp_fingerprint: str = ""
    winhttp_dump_b64: str = ""

    @classmethod
    def from_dict(cls, value: dict) -> "ProxySnapshot":
        return cls(
            proxy_enable=value.get("proxy_enable"),
            proxy_server=value.get("proxy_server"),
            auto_config_url=value.get("auto_config_url"),
            proxy_override=value.get("proxy_override"),
            auto_detect=value.get("auto_detect"),
            winhttp_fingerprint=str(value.get("winhttp_fingerprint") or ""),
            winhttp_dump_b64=str(value.get("winhttp_dump_b64") or ""),
        )

    def semantic_hash(self) -> str:
        payload = json.dumps(asdict(self), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class SystemProxyBackend(Protocol):
    def snapshot(self) -> ProxySnapshot: ...
    def apply(self, proxy_server: str) -> None: ...
    def restore(self, snapshot: ProxySnapshot) -> None: ...


class NoopSystemProxyBackend:
    def __init__(self, snapshot: ProxySnapshot | None = None):
        self.current = snapshot or ProxySnapshot()
        self.last_applied: str | None = None
        self.restored: ProxySnapshot | None = None

    def snapshot(self) -> ProxySnapshot:
        return self.current

    def apply(self, proxy_server: str) -> None:
        self.last_applied = proxy_server
        self.current = ProxySnapshot(
            proxy_enable=1,
            proxy_server=proxy_server,
            auto_config_url=None,
            proxy_override="<local>",
            auto_detect=0,
            winhttp_fingerprint=self.current.winhttp_fingerprint,
            winhttp_dump_b64=self.current.winhttp_dump_b64,
        )

    def restore(self, snapshot: ProxySnapshot) -> None:
        self.restored = snapshot
        self.current = snapshot


class WindowsRegistryProxyBackend:
    def __init__(self, *, allow_system_changes: bool = False):
        self.allow_system_changes = allow_system_changes

    def snapshot(self) -> ProxySnapshot:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_PATH, 0, winreg.KEY_READ) as key:
            values = {
                field: _read_reg_value(key, registry_name)
                for field, (registry_name, _kind) in REGISTRY_FIELDS.items()
            }
        winhttp_fingerprint, winhttp_dump_b64 = _winhttp_state()
        return ProxySnapshot(
            **values,
            winhttp_fingerprint=winhttp_fingerprint,
            winhttp_dump_b64=winhttp_dump_b64,
        )

    def apply(self, proxy_server: str) -> None:
        if not self.allow_system_changes:
            raise PermissionError("System proxy changes are disabled for this backend instance.")
        if not proxy_server.startswith("127.0.0.1:"):
            raise ValueError("Capture proxy must bind to 127.0.0.1.")
        import winreg

        with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, REG_PATH, 0, winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1)
            winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, proxy_server)
            winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, "<local>")
            winreg.SetValueEx(key, "AutoDetect", 0, winreg.REG_DWORD, 0)
            _delete_reg_value(key, "AutoConfigURL")
        _notify_wininet()

    def restore(self, snapshot: ProxySnapshot) -> None:
        if not self.allow_system_changes:
            raise PermissionError("System proxy changes are disabled for this backend instance.")
        import winreg

        with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, REG_PATH, 0, winreg.KEY_SET_VALUE) as key:
            for field, (registry_name, kind) in REGISTRY_FIELDS.items():
                value = getattr(snapshot, field)
                if value is None:
                    _delete_reg_value(key, registry_name)
                elif kind == "dword":
                    winreg.SetValueEx(key, registry_name, 0, winreg.REG_DWORD, int(value))
                else:
                    winreg.SetValueEx(key, registry_name, 0, winreg.REG_SZ, str(value))
        _notify_wininet()
        current_fingerprint, _current_dump = _winhttp_state()
        if snapshot.winhttp_fingerprint and current_fingerprint != snapshot.winhttp_fingerprint:
            _restore_winhttp_dump(snapshot.winhttp_dump_b64)


@dataclass(frozen=True)
class RecoveryRecord:
    snapshot: ProxySnapshot
    helper_pid: int | None
    helper_path: str
    proxy_address: str
    created_at: float
    helper_started_at: float | None = None

    def as_dict(self) -> dict:
        return {
            "version": 1,
            "snapshot": asdict(self.snapshot),
            "helper_pid": self.helper_pid,
            "helper_path": self.helper_path,
            "proxy_address": self.proxy_address,
            "created_at": self.created_at,
            "helper_started_at": self.helper_started_at,
        }

    @classmethod
    def from_dict(cls, value: dict) -> "RecoveryRecord":
        if int(value.get("version", 0)) != 1:
            raise ValueError("Unsupported recovery journal version.")
        return cls(
            snapshot=ProxySnapshot.from_dict(value.get("snapshot") or {}),
            helper_pid=_optional_int(value.get("helper_pid")),
            helper_path=str(value.get("helper_path") or ""),
            proxy_address=str(value.get("proxy_address") or ""),
            created_at=float(value.get("created_at") or 0),
            helper_started_at=_optional_float(value.get("helper_started_at")),
        )


class RecoveryJournal:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path else capture_root() / "network-recovery.bin"

    def save(self, record: RecoveryRecord) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        plaintext = json.dumps(record.as_dict(), ensure_ascii=False, sort_keys=True).encode("utf-8")
        protected = dpapi_protect(plaintext)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        try:
            temporary.write_bytes(protected)
            os.replace(temporary, self.path)
        finally:
            temporary.unlink(missing_ok=True)

    def load(self) -> RecoveryRecord | None:
        if not self.path.is_file():
            return None
        plaintext = dpapi_unprotect(self.path.read_bytes())
        return RecoveryRecord.from_dict(json.loads(plaintext.decode("utf-8")))

    def clear(self) -> None:
        self.path.unlink(missing_ok=True)


class NetworkCaptureGuard:
    def __init__(
        self,
        backend: SystemProxyBackend,
        journal: RecoveryJournal,
    ):
        self.backend = backend
        self.journal = journal
        self.record: RecoveryRecord | None = None

    def activate(
        self,
        proxy_address: str,
        *,
        helper_pid: int | None = None,
        helper_path: str = "",
        helper_started_at: float | None = None,
    ) -> ProxySnapshot:
        if self.record is not None:
            raise RuntimeError("Network capture guard is already active.")
        snapshot = self.backend.snapshot()
        record = RecoveryRecord(
            snapshot=snapshot,
            helper_pid=helper_pid,
            helper_path=helper_path,
            proxy_address=proxy_address,
            created_at=time.time(),
            helper_started_at=helper_started_at,
        )
        self.journal.save(record)
        try:
            self.backend.apply(proxy_address)
            applied = self.backend.snapshot()
            if not _is_expected_capture_state(applied, proxy_address, snapshot):
                raise RuntimeError("System proxy activation verification failed.")
        except Exception:
            self.backend.restore(snapshot)
            if self.backend.snapshot() != snapshot:
                raise RuntimeError("System proxy rollback after activation failure was not exact.")
            self.journal.clear()
            raise
        self.record = record
        return snapshot

    def restore(self) -> ProxySnapshot | None:
        record = self.record or self.journal.load()
        if record is None:
            return None
        self.backend.restore(record.snapshot)
        restored = self.backend.snapshot()
        if restored != record.snapshot:
            raise RuntimeError("System proxy restoration verification failed.")
        self.journal.clear()
        self.record = None
        return restored


def recover_pending_network(
    backend: SystemProxyBackend,
    journal: RecoveryJournal | None = None,
) -> bool:
    journal = journal or RecoveryJournal()
    record = journal.load()
    if record is None:
        return False
    terminate_recorded_helper(record)
    backend.restore(record.snapshot)
    if backend.snapshot() != record.snapshot:
        raise RuntimeError("Pending network restoration verification failed.")
    journal.clear()
    return True


def terminate_recorded_helper(record: RecoveryRecord) -> bool:
    if not record.helper_pid or not record.helper_path or record.helper_started_at is None:
        return False
    actual = process_image_path(record.helper_pid)
    if not actual or Path(actual).resolve() != Path(record.helper_path).resolve():
        return False
    actual_started_at = process_start_time(record.helper_pid)
    if actual_started_at is None or abs(actual_started_at - record.helper_started_at) > 1:
        return False
    subprocess.run(
        ["taskkill", "/PID", str(record.helper_pid), "/T", "/F"],
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    return True


def process_image_path(pid: int) -> str:
    if os.name != "nt":
        return ""
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    kernel32 = ctypes.windll.kernel32
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
    if not handle:
        return ""
    try:
        size = wintypes.DWORD(32768)
        buffer = ctypes.create_unicode_buffer(size.value)
        if not ctypes.windll.kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
            return ""
        return buffer.value
    finally:
        kernel32.CloseHandle(handle)


def process_start_time(pid: int) -> float | None:
    if os.name != "nt":
        return None
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    kernel32 = ctypes.windll.kernel32
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
    if not handle:
        return None
    try:
        creation = wintypes.FILETIME()
        exit_time = wintypes.FILETIME()
        kernel = wintypes.FILETIME()
        user = wintypes.FILETIME()
        if not kernel32.GetProcessTimes(
            handle,
            ctypes.byref(creation),
            ctypes.byref(exit_time),
            ctypes.byref(kernel),
            ctypes.byref(user),
        ):
            return None
        ticks = (int(creation.dwHighDateTime) << 32) | int(creation.dwLowDateTime)
        return (ticks - 116444736000000000) / 10_000_000
    finally:
        kernel32.CloseHandle(handle)


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _blob(value: bytes) -> tuple[_DataBlob, ctypes.Array]:
    buffer = ctypes.create_string_buffer(value)
    return _DataBlob(len(value), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte))), buffer


def dpapi_protect(value: bytes) -> bytes:
    return _dpapi(value, protect=True)


def dpapi_unprotect(value: bytes) -> bytes:
    return _dpapi(value, protect=False)


def _dpapi(value: bytes, *, protect: bool) -> bytes:
    if os.name != "nt":
        raise OSError("DPAPI recovery journals are only supported on Windows.")
    input_blob, input_buffer = _blob(value)
    entropy_blob, entropy_buffer = _blob(DPAPI_ENTROPY)
    output_blob = _DataBlob()
    function = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    args = [
        ctypes.byref(input_blob),
        "FluentMai network recovery" if protect else None,
        ctypes.byref(entropy_blob),
        None,
        None,
        CRYPTPROTECT_UI_FORBIDDEN,
        ctypes.byref(output_blob),
    ]
    if not function(*args):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output_blob.pbData, output_blob.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(output_blob.pbData)
        del input_buffer, entropy_buffer


def _read_reg_value(key, name: str):
    import winreg

    try:
        return winreg.QueryValueEx(key, name)[0]
    except FileNotFoundError:
        return None


def _delete_reg_value(key, name: str) -> None:
    import winreg

    try:
        winreg.DeleteValue(key, name)
    except FileNotFoundError:
        pass


def _notify_wininet() -> None:
    wininet = ctypes.windll.wininet
    for option in (39, 37):
        if not wininet.InternetSetOptionW(None, option, None, 0):
            raise ctypes.WinError()


def _winhttp_state() -> tuple[str, str]:
    if os.name != "nt":
        return "", ""
    result = subprocess.run(
        ["netsh", "winhttp", "dump"],
        capture_output=True,
        timeout=10,
        check=False,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    raw = result.stdout or result.stderr or b""
    normalized = b" ".join(raw.split())
    return hashlib.sha256(normalized).hexdigest(), base64.b64encode(raw).decode("ascii")


def _restore_winhttp_dump(dump_b64: str) -> None:
    if not dump_b64:
        raise RuntimeError("WinHTTP changed but no recovery dump is available.")
    root = capture_root()
    root.mkdir(parents=True, exist_ok=True)
    script = root / "winhttp-recovery.netsh"
    try:
        script.write_bytes(base64.b64decode(dump_b64, validate=True))
        result = subprocess.run(
            ["netsh", "exec", str(script)],
            capture_output=True,
            timeout=20,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if result.returncode != 0:
            raise RuntimeError(f"WinHTTP restoration failed with exit code {result.returncode}.")
    finally:
        script.unlink(missing_ok=True)


def _optional_int(value) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _optional_float(value) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _is_expected_capture_state(
    current: ProxySnapshot,
    proxy_address: str,
    original: ProxySnapshot,
) -> bool:
    return (
        current.proxy_enable == 1
        and current.proxy_server == proxy_address
        and current.auto_config_url is None
        and current.proxy_override == "<local>"
        # Windows may discard an explicit AutoDetect=0 value when WinINET is
        # notified.  A missing value is the equivalent persisted state here;
        # AutoConfigURL is still required to be absent above.
        and current.auto_detect in {0, None}
        and current.winhttp_fingerprint == original.winhttp_fingerprint
    )
