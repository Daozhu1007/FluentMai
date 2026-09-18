from __future__ import annotations

import asyncio
import json
from pathlib import Path
import socket
import subprocess
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.x509.oid import NameOID
from mitmproxy import http
import pytest

from capture_helper import certificate
from capture_helper.ipc import encode_event, receive_event
from capture_helper.main import (
    LOCAL_CAPTURE_PREFIX,
    TARGET_HOST,
    WahlapCaptureAddon,
    _replace_home_response,
)


SCORE_HTML = """
<html><body>
<form action="/maimai-mobile/record/musicDetail/">
<div class="music_name_block">Fixture</div>
<div class="music_score_block">100.0000%</div>
</form>
</body></html>
"""


def _free_port() -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])
    finally:
        sock.close()


def test_ca_store_has_fluentmai_identity_and_wahlap_name_constraint(monkeypatch, tmp_path):
    monkeypatch.setattr(certificate, "is_ca_installed", lambda _thumbprint: False)

    info = certificate.ensure_ca_store(tmp_path)
    cert = x509.load_pem_x509_certificate(info.certificate_path.read_bytes())
    key = serialization.load_pem_private_key(info.combined_key_path.read_bytes(), password=None)
    names = cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    constraints = cert.extensions.get_extension_for_class(x509.NameConstraints).value
    permitted = {item.value for item in constraints.permitted_subtrees or [] if isinstance(item, x509.DNSName)}

    assert names[0].value == certificate.CA_COMMON_NAME
    assert permitted == {"wahlap.com"}
    assert key.public_key().public_numbers() == cert.public_key().public_numbers()
    assert info.thumbprint == cert.fingerprint(hashes.SHA1()).hex().upper()
    assert (tmp_path / "mitmproxy-dhparam.pem").is_file()
    assert certificate.ensure_ca_store(tmp_path).thumbprint == info.thumbprint


def test_ca_install_timeout_has_safe_category(monkeypatch, tmp_path):
    monkeypatch.setattr(certificate, "is_ca_installed", lambda _thumbprint: False)
    info = certificate.ensure_ca_store(tmp_path)

    def timeout(*_args, **_kwargs):
        raise subprocess.TimeoutExpired("certutil", 30)

    monkeypatch.setattr(certificate.subprocess, "run", timeout)

    with pytest.raises(RuntimeError, match="^ca_installation_timeout$"):
        certificate.install_ca_current_user(info)


def test_ipc_frame_round_trip_uses_length_prefix():
    left, right = socket.socketpair()
    try:
        event = {"type": "progress", "stage": "safe", "count": 3}
        left.sendall(encode_event(event))
        assert receive_event(right, timeout=1) == event
    finally:
        left.close()
        right.close()


def test_intercepted_home_is_replaced_with_no_store_local_prompt():
    response = http.Response.make(
        200,
        b"<html>private fixture home</html>",
        {"content-type": "text/html", "content-encoding": "gzip"},
    )

    _replace_home_response(response, "fixture-nonce", 0)

    assert b"private fixture home" not in response.content
    assert b"FluentMai" in response.content
    assert b"fixture-nonce" in response.content
    assert b"https://" not in response.content
    assert response.content.count(b"musicSort/search") == 1
    assert response.headers["cache-control"] == "no-store"
    assert "content-encoding" not in response.headers


def test_browser_pages_are_short_circuited_locally_and_complete_in_memory():
    class Master:
        def __init__(self):
            self.stopped = False

        def shutdown(self):
            self.stopped = True

    class Ipc:
        def __init__(self):
            self.events = []

        async def send(self, event):
            self.events.append(event)

    master = Master()
    ipc = Ipc()
    addon = WahlapCaptureAddon(
        master=master,
        ipc=ipc,
        request_timeout=1,
        wait_timeout=1,
        retries=0,
        retry_delay=0,
    )

    async def collect():
        for difficulty in range(5):
            flow = http.HTTPFlow(None, None)
            flow.request = http.Request.make(
                "POST",
                f"https://{TARGET_HOST}{LOCAL_CAPTURE_PREFIX}/page/{difficulty}"
                f"?nonce={addon.capture_nonce}",
                SCORE_HTML.encode("utf-8"),
                {"x-fluentmai-status": "200", "content-type": "text/plain"},
            )
            await addon.request(flow)
            assert flow.response is not None
            assert flow.response.status_code == 204

    asyncio.run(collect())

    pages = [event for event in ipc.events if event.get("type") == "page"]
    assert [event["difficulty"] for event in pages] == list(range(5))
    assert all(event["body"] == SCORE_HTML for event in pages)
    assert ipc.events[-1]["type"] == "complete"
    assert ipc.events[-1]["captured_pages"] == 5
    assert master.stopped is True
    assert addon.browser_difficulties == set()
    assert addon.browser_bytes == 0


def test_helper_starts_loopback_proxy_without_installing_ca(tmp_path):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(15)
    ipc_port = int(listener.getsockname()[1])
    proxy_port = _free_port()
    token = "a" * 64
    command = [
        sys.executable,
        "-m",
        "capture_helper.main",
        "--ipc-port",
        str(ipc_port),
        "--proxy-port",
        str(proxy_port),
        "--cert-dir",
        str(tmp_path / "certs"),
    ]
    assert token not in command
    process = subprocess.Popen(
        command,
        cwd=Path(__file__).resolve().parents[1],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    try:
        assert process.stdin is not None
        process.stdin.write(
            json.dumps(
                {
                    "token": token,
                    "install_ca": False,
                    "wait_timeout": 0.5,
                    "request_timeout": 1,
                    "retries": 0,
                    "retry_delay": 0,
                }
            )
            + "\n"
        )
        process.stdin.close()
        connection, peer = listener.accept()
        assert peer[0] == "127.0.0.1"
        try:
            assert receive_event(connection, 5) == {"type": "auth", "token": token}
            events = []
            while True:
                event = receive_event(connection, 10)
                events.append(event)
                if event.get("type") == "ready":
                    probe = socket.create_connection(("127.0.0.1", proxy_port), timeout=3)
                    probe.close()
                if event.get("type") == "error":
                    break
            assert any(event.get("type") == "certificate" for event in events)
            assert any(event.get("type") == "ready" for event in events)
            assert events[-1].get("category") == "capture_timeout"
        finally:
            connection.close()
        assert process.wait(timeout=15) == 0
    finally:
        listener.close()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
