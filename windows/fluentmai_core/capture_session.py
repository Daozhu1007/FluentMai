from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
from typing import Callable

from capture_helper.ipc import receive_event
from .network_recovery import (
    NetworkCaptureGuard,
    RecoveryJournal,
    SystemProxyBackend,
    process_start_time,
    recover_pending_network,
)
from .runtime_paths import capture_root, executable_root, source_root


ProgressCallback = Callable[[str, dict], None]


class CaptureError(RuntimeError):
    def __init__(self, category: str):
        self.category = category
        super().__init__(category)


@dataclass
class LocalCaptureResult:
    home_html: str
    pages: list[tuple[int, str]]
    captured_pages: int
    captured_bytes: int
    helper_version: str
    certificate_fingerprint: str


class LocalCaptureController:
    def __init__(
        self,
        backend: SystemProxyBackend,
        *,
        journal: RecoveryJournal | None = None,
        helper_path: str | None = None,
    ):
        self.backend = backend
        self.journal = journal or RecoveryJournal()
        self.helper_path = helper_path or os.environ.get("FLUENTMAI_CAPTURE_HELPER", "")

    def capture(
        self,
        *,
        progress: ProgressCallback | None = None,
        cancel_event: threading.Event | None = None,
        wait_timeout: float = 180,
        request_timeout: float = 30,
        retries: int = 2,
        retry_delay: float = 1.5,
        install_ca: bool = True,
    ) -> LocalCaptureResult:
        progress = progress or (lambda _stage, _info: None)
        cancel_event = cancel_event or threading.Event()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(0.5)
        ipc_port = int(listener.getsockname()[1])
        proxy_port = _free_loopback_port()
        proxy_address = f"127.0.0.1:{proxy_port}"
        token = secrets.token_hex(32)
        command, helper_image = self._helper_command(ipc_port, proxy_port)
        process: subprocess.Popen | None = None
        connection: socket.socket | None = None
        guard = NetworkCaptureGuard(self.backend, self.journal)
        home_html = ""
        pages: list[tuple[int, str]] = []
        helper_version = ""
        certificate_fingerprint = ""
        captured_pages = 0
        captured_bytes = 0
        completed = False
        deadline = time.monotonic() + wait_timeout + (5 * (request_timeout * (retries + 1) + retry_delay * retries)) + 30

        try:
            if self.journal.path.exists():
                progress("recovering_previous_session", {})
                recover_pending_network(self.backend, self.journal)
                progress("previous_session_recovered", {})
            progress("starting_helper", {"proxy_port": proxy_port})
            process = subprocess.Popen(
                command,
                cwd=source_root(),
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            bootstrap = {
                "token": token,
                "install_ca": install_ca,
                "wait_timeout": wait_timeout,
                "request_timeout": request_timeout,
                "retries": retries,
                "retry_delay": retry_delay,
            }
            assert process.stdin is not None
            process.stdin.write(json.dumps(bootstrap, separators=(",", ":")) + "\n")
            process.stdin.flush()
            process.stdin.close()
            helper_started_at = process_start_time(process.pid)

            startup_deadline = time.monotonic() + 15
            while connection is None and time.monotonic() < startup_deadline:
                if cancel_event.is_set():
                    raise CaptureError("cancelled")
                try:
                    connection, peer = listener.accept()
                except socket.timeout:
                    if process.poll() is not None:
                        raise CaptureError("helper_exited_before_ipc")
                    continue
            if connection is None:
                raise CaptureError("helper_ipc_timeout")
            if peer[0] != "127.0.0.1":
                raise CaptureError("ipc_non_loopback_peer")
            auth = receive_event(connection, timeout=10)
            if auth.get("type") != "auth" or not secrets.compare_digest(str(auth.get("token") or ""), token):
                raise CaptureError("ipc_authentication_failed")
            del auth, token, bootstrap

            while time.monotonic() < deadline:
                if cancel_event.is_set():
                    raise CaptureError("cancelled")
                try:
                    event = receive_event(connection, timeout=0.5)
                except socket.timeout:
                    if process.poll() is not None:
                        raise CaptureError("helper_exited")
                    continue
                except (ConnectionError, OSError, ValueError, json.JSONDecodeError) as exc:
                    raise CaptureError("helper_ipc_closed") from exc
                event_type = str(event.get("type") or "")
                if event_type == "certificate":
                    certificate_fingerprint = str(event.get("fingerprint") or "")
                    progress("certificate_ready", {"installed": bool(event.get("installed"))})
                elif event_type == "ready":
                    helper_version = str(event.get("helper_version") or "")
                    guard.activate(
                        proxy_address,
                        helper_pid=process.pid,
                        helper_path=helper_image,
                        helper_started_at=helper_started_at,
                    )
                    progress("network_proxy_active", {"proxy_port": proxy_port})
                    focused = focus_wechat_window()
                    progress("waiting_for_wechat", {"wechat_focused": focused})
                elif event_type == "session_captured":
                    progress(
                        "session_captured",
                        {
                            "home_status": int(event.get("home_status") or 0),
                            "home_bytes": int(event.get("home_bytes") or 0),
                            "session_cookie_count": int(event.get("session_cookie_count") or 0),
                            "request_cookie_count": int(event.get("request_cookie_count") or 0),
                            "response_cookie_count": int(event.get("response_cookie_count") or 0),
                            "response_cookie_deletion_count": int(event.get("response_cookie_deletion_count") or 0),
                            "browser_header_count": int(event.get("browser_header_count") or 0),
                            "browser_total_header_count": int(event.get("browser_total_header_count") or 0),
                            "browser_authorization_present": bool(event.get("browser_authorization_present")),
                            "home_auth_failure_marker": bool(event.get("home_auth_failure_marker")),
                            "home_record_link_marker": bool(event.get("home_record_link_marker")),
                            "home_player_data_marker": bool(event.get("home_player_data_marker")),
                            "home_rating_marker": bool(event.get("home_rating_marker")),
                        },
                    )
                elif event_type == "progress":
                    safe_info = {key: value for key, value in event.items() if key not in {"type", "body"}}
                    progress(str(event.get("stage") or "capture_progress"), safe_info)
                elif event_type == "page":
                    body = str(event.pop("body", ""))
                    if event.get("page_kind") == "home":
                        home_html = body
                    elif event.get("page_kind") == "difficulty":
                        pages.append((int(event.get("difficulty")), body))
                    progress(
                        "page_captured",
                        {
                            "page_kind": event.get("page_kind"),
                            "difficulty": event.get("difficulty"),
                            "http_status": int(event.get("http_status") or 0),
                            "bytes": int(event.get("bytes") or 0),
                            "elapsed_ms": int(event.get("elapsed_ms") or 0),
                        },
                    )
                elif event_type == "complete":
                    captured_pages = int(event.get("captured_pages") or len(pages))
                    captured_bytes = int(event.get("captured_bytes") or 0)
                    completed = True
                    break
                elif event_type == "error":
                    raise CaptureError(str(event.get("category") or "helper_error"))

            if not completed:
                raise CaptureError("capture_deadline_exceeded")
            if len(pages) != 5 or {difficulty for difficulty, _html in pages} != set(range(5)):
                raise CaptureError("incomplete_difficulty_pages")
            progress("restoring_network", {})
            return LocalCaptureResult(
                home_html=home_html,
                pages=sorted(pages),
                captured_pages=captured_pages,
                captured_bytes=captured_bytes,
                helper_version=helper_version,
                certificate_fingerprint=certificate_fingerprint,
            )
        finally:
            if connection is not None:
                try:
                    connection.close()
                except OSError:
                    pass
            listener.close()
            _stop_process(process)
            if guard.record is not None or self.journal.path.exists():
                guard.restore()
                progress("network_restored", {})

    def _helper_command(self, ipc_port: int, proxy_port: int) -> tuple[list[str], str]:
        cert_dir = capture_root() / "certs"
        if self.helper_path:
            executable = Path(self.helper_path).expanduser().resolve()
            if not executable.is_file():
                raise FileNotFoundError(f"Capture helper not found: {executable}")
            return (
                [
                    str(executable),
                    "--ipc-port",
                    str(ipc_port),
                    "--proxy-port",
                    str(proxy_port),
                    "--cert-dir",
                    str(cert_dir),
                ],
                str(executable),
            )

        packaged = executable_root() / "FluentMaiCaptureProxy.exe"
        if getattr(sys, "frozen", False) and packaged.is_file():
            return (
                [
                    str(packaged),
                    "--ipc-port",
                    str(ipc_port),
                    "--proxy-port",
                    str(proxy_port),
                    "--cert-dir",
                    str(cert_dir),
                ],
                str(packaged),
            )

        return (
            [
                sys.executable,
                "-m",
                "capture_helper.main",
                "--ipc-port",
                str(ipc_port),
                "--proxy-port",
                str(proxy_port),
                "--cert-dir",
                str(cert_dir),
            ],
            str(Path(sys.executable).resolve()),
        )


def _free_loopback_port() -> int:
    candidate = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        candidate.bind(("127.0.0.1", 0))
        return int(candidate.getsockname()[1])
    finally:
        candidate.close()


def _stop_process(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            capture_output=True,
            timeout=10,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def focus_wechat_window() -> bool:
    if os.name != "nt":
        return False
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    candidates: list[tuple[int, int, int]] = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def callback(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        title = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, title, length + 1)
        normalized_title = title.value.strip()
        priority = 1 if normalized_title in {"舞萌 | 中二", "舞萌｜中二"} else 0
        if priority == 0 and normalized_title not in {"微信", "WeChat", "Weixin"}:
            return True
        rect = wintypes.RECT()
        if user32.GetWindowRect(hwnd, ctypes.byref(rect)):
            area = max(0, rect.right - rect.left) * max(0, rect.bottom - rect.top)
            candidates.append((priority, area, int(hwnd)))
        return True

    user32.EnumWindows(callback, 0)
    if not candidates:
        return False
    _priority, _area, hwnd = max(candidates)
    user32.ShowWindow(hwnd, 9)
    return bool(user32.SetForegroundWindow(hwnd))
