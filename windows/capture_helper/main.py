from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import parse_qs, urlsplit

from mitmproxy import http, options
from mitmproxy.tools.dump import DumpMaster

from capture_helper import HELPER_VERSION
from capture_helper.certificate import (
    ensure_ca_store,
    install_ca_current_user,
    safe_certificate_fingerprint,
)
from capture_helper.ipc import AsyncIpcClient


TARGET_HOST = "maimai.wahlap.com"
HOME_PREFIX = "/maimai-mobile/home"
LOCAL_CAPTURE_PREFIX = "/maimai-mobile/__fluentmai_local_capture__"
MAX_BROWSER_PAGE_BYTES = 8 * 1024 * 1024


@dataclass(frozen=True)
class CapturedBrowserSession:
    home_html: str
    home_status: int
    request_cookie_count: int = 0
    response_cookie_count: int = 0
    response_cookie_deletion_count: int = 0
    browser_total_header_count: int = 0
    browser_authorization_present: bool = False
    browser_header_count: int = 0


class WahlapCaptureAddon:
    def __init__(
        self,
        master: DumpMaster,
        ipc: AsyncIpcClient,
        *,
        request_timeout: float,
        wait_timeout: float,
        retries: int,
        retry_delay: float,
    ):
        self.master = master
        self.ipc = ipc
        self.request_timeout = request_timeout
        self.wait_timeout = wait_timeout
        self.retries = retries
        self.retry_delay = retry_delay
        self.started = False
        self.timeout_task: asyncio.Task | None = None
        self.browser_timeout_task: asyncio.Task | None = None
        self.capture_nonce = secrets.token_urlsafe(24)
        self.browser_difficulties: set[int] = set()
        self.browser_bytes = 0
        self.browser_lock = asyncio.Lock()

    async def running(self) -> None:
        await self.ipc.send(
            {
                "type": "ready",
                "helper_version": HELPER_VERSION,
                "proxy_host": "127.0.0.1",
            }
        )
        self.timeout_task = asyncio.create_task(self._capture_timeout())

    async def response(self, flow: http.HTTPFlow) -> None:
        if self.started or flow.response is None:
            return
        if flow.request.pretty_host.lower() != TARGET_HOST:
            return
        if urlsplit(flow.request.path).path.rstrip("/") != HOME_PREFIX:
            return

        self.started = True
        if self.timeout_task:
            self.timeout_task.cancel()
        captured = _capture_browser_session(flow)
        _replace_home_response(flow.response, self.capture_nonce, self.retry_delay)
        await self.ipc.send(
            {
                "type": "session_captured",
                "home_status": captured.home_status,
                "home_bytes": len(captured.home_html.encode("utf-8")),
                "session_cookie_count": captured.request_cookie_count,
                "request_cookie_count": captured.request_cookie_count,
                "response_cookie_count": captured.response_cookie_count,
                "response_cookie_deletion_count": captured.response_cookie_deletion_count,
                "browser_header_count": captured.browser_header_count,
                "browser_total_header_count": captured.browser_total_header_count,
                "browser_authorization_present": captured.browser_authorization_present,
                "home_auth_failure_marker": _looks_like_auth_failure(captured.home_html),
                "home_record_link_marker": "/maimai-mobile/record/" in captured.home_html,
                "home_player_data_marker": "playerData" in captured.home_html,
                "home_rating_marker": "rating" in captured.home_html.lower(),
            }
        )
        await self.ipc.send(
            {
                "type": "page",
                "page_kind": "home",
                "http_status": captured.home_status,
                "body": captured.home_html,
                "bytes": len(captured.home_html.encode("utf-8")),
            }
        )
        del captured
        self.browser_timeout_task = asyncio.create_task(self._browser_capture_timeout())

    async def request(self, flow: http.HTTPFlow) -> None:
        if flow.request.pretty_host.lower() != TARGET_HOST:
            return
        parsed = urlsplit(flow.request.path)
        if not parsed.path.startswith(LOCAL_CAPTURE_PREFIX + "/"):
            return

        flow.response = http.Response.make(404, b"", {"cache-control": "no-store"})
        query = parse_qs(parsed.query)
        supplied_nonce = (query.get("nonce") or [""])[0]
        if not secrets.compare_digest(supplied_nonce, self.capture_nonce):
            return
        if parsed.path == LOCAL_CAPTURE_PREFIX + "/error":
            flow.response = http.Response.make(204, b"", {"cache-control": "no-store"})
            await self.ipc.send({"type": "error", "category": "browser_capture_failed"})
            self.master.shutdown()
            return
        match = re.fullmatch(re.escape(LOCAL_CAPTURE_PREFIX) + r"/page/([0-4])", parsed.path)
        if flow.request.method.upper() != "POST" or not match:
            return
        flow.response = http.Response.make(204, b"", {"cache-control": "no-store"})
        content = flow.request.raw_content or b""
        if len(content) > MAX_BROWSER_PAGE_BYTES:
            await self.ipc.send({"type": "error", "category": "browser_capture_too_large"})
            self.master.shutdown()
            return
        body = flow.request.get_text(strict=False)
        try:
            browser_status = int(flow.request.headers.get("x-fluentmai-status", "0"))
        except ValueError:
            browser_status = 0
        if browser_status != 200 or not _looks_like_score_page(body):
            await self.ipc.send(
                {"type": "error", "category": "browser_capture_unexpected_page"}
            )
            self.master.shutdown()
            return
        difficulty = int(match.group(1))
        async with self.browser_lock:
            if difficulty in self.browser_difficulties:
                return
            self.browser_difficulties.add(difficulty)
            self.browser_bytes += len(content)
            await self.ipc.send(
                {
                    "type": "page",
                    "page_kind": "difficulty",
                    "difficulty": difficulty,
                    "http_status": browser_status,
                    "body": body,
                    "bytes": len(content),
                    "attempt": 1,
                    "elapsed_ms": 0,
                }
            )
            if len(self.browser_difficulties) == 5:
                if self.browser_timeout_task:
                    self.browser_timeout_task.cancel()
                await self.ipc.send(
                    {
                        "type": "complete",
                        "captured_pages": 5,
                        "captured_bytes": self.browser_bytes,
                    }
                )
                self.browser_difficulties.clear()
                self.browser_bytes = 0
                await asyncio.sleep(0.1)
                self.master.shutdown()

    async def _capture_timeout(self) -> None:
        try:
            await asyncio.sleep(self.wait_timeout)
            if not self.started:
                await self.ipc.send({"type": "error", "category": "capture_timeout"})
                self.master.shutdown()
        except asyncio.CancelledError:
            return

    async def _browser_capture_timeout(self) -> None:
        try:
            await asyncio.sleep(self.wait_timeout)
            if len(self.browser_difficulties) < 5:
                await self.ipc.send(
                    {"type": "error", "category": "browser_capture_timeout"}
                )
                self.browser_difficulties.clear()
                self.browser_bytes = 0
                self.master.shutdown()
        except asyncio.CancelledError:
            return

def _capture_browser_session(flow: http.HTTPFlow) -> CapturedBrowserSession:
    raw_headers = list(flow.request.headers.items())
    request_cookie_count, _request_deletions = _cookie_metadata(
        flow.request.headers.get("cookie", "")
    )
    response_cookie_count = 0
    response_cookie_deletion_count = 0
    for header in flow.response.headers.get_all("set-cookie"):
        count, deletions = _cookie_metadata(header)
        response_cookie_count += count
        response_cookie_deletion_count += deletions
    return CapturedBrowserSession(
        home_html=flow.response.get_text(strict=False),
        home_status=flow.response.status_code,
        request_cookie_count=request_cookie_count,
        response_cookie_count=response_cookie_count,
        response_cookie_deletion_count=response_cookie_deletion_count,
        browser_total_header_count=len(raw_headers),
        browser_authorization_present=any(
            str(name).lower() == "authorization" for name, _value in raw_headers
        ),
        browser_header_count=len(raw_headers),
    )


def _cookie_metadata(value: str) -> tuple[int, int]:
    cookie = SimpleCookie()
    try:
        cookie.load(value)
    except Exception:
        return 0, 0
    deletions = 0
    for morsel in cookie.values():
        max_age = str(morsel["max-age"] or "").strip()
        if max_age:
            try:
                deletions += int(max_age) <= 0
                continue
            except ValueError:
                pass
        deletions += bool(morsel["expires"] and not morsel.value)
    return len(cookie), deletions


def _capture_prompt_html(nonce: str, retry_delay: float) -> str:
    delay_ms = max(250, min(round(retry_delay * 1000), 5000))
    nonce_js = json.dumps(nonce)
    return f"""<!doctype html><meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>FluentMai</title><body><p id=\"status\">FluentMai is importing local Wahlap scores.</p>
<script>(async()=>{{
const nonce={nonce_js};
const status=document.getElementById('status');
const base='/maimai-mobile/record/musicSort/search/?search=A&sort=1&playCheck=on&diff=';
const sink='{LOCAL_CAPTURE_PREFIX}/page/';
try{{
 for(let difficulty=0;difficulty<5;difficulty++){{
  status.textContent='FluentMai local import: '+(difficulty+1)+'/5';
  const response=await fetch(base+difficulty,{{credentials:'include',cache:'no-store'}});
  const body=await response.text();
  const ack=await fetch(sink+difficulty+'?nonce='+encodeURIComponent(nonce),{{
   method:'POST',cache:'no-store',headers:{{'Content-Type':'text/plain;charset=utf-8','X-FluentMai-Status':String(response.status)}},body
  }});
  if(!ack.ok)throw new Error('local capture rejected');
  if(difficulty<4)await new Promise(resolve=>setTimeout(resolve,{delay_ms}));
 }}
 status.textContent='FluentMai local import complete.';
}}catch(_error){{
 status.textContent='FluentMai local import failed.';
 await fetch('{LOCAL_CAPTURE_PREFIX}/error?nonce='+encodeURIComponent(nonce),{{method:'POST',cache:'no-store'}}).catch(()=>{{}});
}}
}})();</script></body>"""


def _replace_home_response(response: http.Response, nonce: str, retry_delay: float) -> None:
    response.headers.pop("content-encoding", None)
    response.headers.pop("content-security-policy", None)
    response.headers.pop("content-security-policy-report-only", None)
    response.set_text(_capture_prompt_html(nonce, retry_delay))
    response.headers["content-type"] = "text/html; charset=utf-8"
    response.headers["cache-control"] = "no-store"


def _looks_like_score_page(html: str) -> bool:
    return "musicDetail" in html and "music_name_block" in html and "music_score_block" in html


def _looks_like_auth_failure(html: str) -> bool:
    lowered = html.lower()
    return (
        "open.weixin.qq.com/connect/oauth2/authorize" in lowered
        or "/wc_auth/oauth/authorize/" in lowered
        or "title_error" in lowered
        or "登录失败" in html
    )


def _safe_error_category(exc: Exception) -> str:
    text = str(exc)
    allowed = {
        "network_error",
        "network_timeout",
        "authentication_expired",
        "wahlap_challenge_or_unexpected_page",
        "browser_capture_failed",
        "browser_capture_timeout",
        "browser_capture_too_large",
        "browser_capture_unexpected_page",
        "ca_installation_timeout",
        "ca_installation_failed",
        "ca_installation_verification_failed",
    }
    return text if text in allowed else type(exc).__name__


def _read_bootstrap() -> dict:
    line = sys.stdin.readline(65536)
    value = json.loads(line)
    if not isinstance(value, dict):
        raise ValueError("Bootstrap value must be an object.")
    token = str(value.get("token") or "")
    if not re.fullmatch(r"[a-f0-9]{64}", token):
        raise ValueError("Invalid session token.")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--ipc-port", type=int, required=True)
    parser.add_argument("--proxy-port", type=int, required=True)
    parser.add_argument("--cert-dir", required=True)
    return parser


async def _run(args: argparse.Namespace, bootstrap: dict) -> None:
    ipc = AsyncIpcClient(args.ipc_port, str(bootstrap["token"]))
    await ipc.connect()
    master: DumpMaster | None = None
    try:
        info = ensure_ca_store(args.cert_dir)
        if bool(bootstrap.get("install_ca", True)):
            info = install_ca_current_user(info)
        await ipc.send(
            {
                "type": "certificate",
                "installed": info.installed,
                "fingerprint": safe_certificate_fingerprint(info),
            }
        )
        proxy_options = options.Options(
            listen_host="127.0.0.1",
            listen_port=int(args.proxy_port),
            confdir=str(Path(args.cert_dir).resolve()),
            allow_hosts=[r"^maimai\.wahlap\.com(?::443)?$"],
            show_ignored_hosts=False,
        )
        master = DumpMaster(proxy_options, with_termlog=False, with_dumper=False)
        addon = WahlapCaptureAddon(
            master,
            ipc,
            request_timeout=float(bootstrap.get("request_timeout", 30)),
            wait_timeout=float(bootstrap.get("wait_timeout", 180)),
            retries=max(0, min(int(bootstrap.get("retries", 2)), 4)),
            retry_delay=max(0.0, min(float(bootstrap.get("retry_delay", 1.5)), 10.0)),
        )
        master.addons.add(addon)
        await master.run()
    except Exception as exc:
        try:
            await ipc.send({"type": "error", "category": _safe_error_category(exc)})
        except Exception:
            pass
        if master is not None:
            master.shutdown()
    finally:
        await ipc.close()


def main() -> int:
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    try:
        args = _parser().parse_args()
        if not (1024 <= args.ipc_port <= 65535 and 1024 <= args.proxy_port <= 65535):
            return 2
        bootstrap = _read_bootstrap()
        asyncio.run(_run(args, bootstrap))
        return 0
    except Exception:
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
