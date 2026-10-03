# AUTH-NET-2G / 2H — Same-Tablet A/B and Reference-Compatible Replay Findings

Date: 2026-10-03 (evening, Asia/Shanghai). DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.
Base: reviewed implementation `07eb6eea` (branch `auth-net-1-v030` @ `b0e92e86`). No production behavior modified; the experimental replay strategy stayed local and is not committed.

## AUTH-NET-2G — interleaved same-tablet A/B (agent-driven, both sides)

Setup: Xiaomi 24018RPACC tablet, Android 16, WeChat 8.0.72 primary instance, validated 5 GHz Wi-Fi, no proxy/Private DNS/VPN. A = installed control `dev.fluentmai.android` 0.2.9-beta vc13 (installed APK sha256 `d5804344…`, unchanged, data untouched). B = rebuilt from `07eb6ee` with temporary build-only `applicationId = dev.fluentmai.android.validation` (restored after build; worktree clean). appId-change audit: only the FileProvider authority derives from `applicationId`; no auth/network/VPN package conditionals; shared local ports 8284/9457 used strictly interleaved.

Identical agent-driven flow per attempt: 启动捕获 → 复制授权 (fresh transaction; clipboard) → WeChat 文件传输助手 paste → send → open link → snsapi_base auto-redirect → callback captured by local VPN (935-byte request) → app replay.

| Side | Attempts | Authenticated home |
| --- | --- | --- |
| A (v0.2.9 control) | 6 | 0 — all failed at the login stage |
| B (reviewed 07eb6ee candidate) | 6 | 0 — auth-callback 404 (555 chars) 6/6, then home error page 6/6 |

Classification: **CASE 3 — SHARED_CURRENT_AUTH_FAILURE** (CTO-accepted, with the caveat that “v0.2.9 success was survivor bias” remains plausible but unproven). Capture chain succeeded 12/12 — automation is not the failure point. authorize→callback latency 16.6–90.3 s. Candidate data preserved empty; control preserved.

## AUTH-NET-2H — reference-compatible replay matrix

Reference extracted from public `TrueRou/maimai.py` (v1.5.3, 2026-09-27, actively maintained): `GET https://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx` with query reconstructed from `r,t,code,state` only, fixed Windows WeChat UA (`MicroMessenger/7.0.20.1781(0x6700143B) WindowsWechat(0x6307001e)`), documented Sec-Fetch/Accept header set, **no cookies**, success criterion = first hop `302 + Location`, then follow with the same UA and first-response cookies.

R1 run 1 (fresh transaction, authorize→callback **7.6 s**, under the 10 s target):
- First hop: **HTTP 404**, `Server: nginx/1.29.5`, `Content-Type: text/html`, body 555 chars, body sha256 `e32d0010…` — the identical bare-nginx default page returned to FluentMai's raw replays.
- Same-session R0 (candidate raw replay) also 404 as in all prior evidence.

Route-level probes (no valid OAuth values used):
- Callback with garbage / empty / partial params, over HTTPS and HTTP:80 — **all identical bare nginx 404 (555 bytes)**. Parameters are never evaluated.
- `GET /wc_auth/oauth/authorize/maimai-dx` — **alive**: fresh `302` → `open.weixin.qq.com/connect/oauth2/authorize?appid=wx1fcecfcbd16803b1&redirect_uri=https%3A%2F%2Ftgk-wcaime.wahlap.com%2Fwc_auth%2Foauth%2Fcallback%2Fmaimai-dx%3Fr%3D…%26t%3D…&response_type=code&scope=snsapi_base&state=…` (server-minted `r`/`t` bound inside the redirect_uri; observed 20:27 +08:00).

R1 run 2 skipped as uninformative: the callback route 404s at the nginx level before any parameter or code evaluation, so a second fresh transaction cannot yield a different first hop; R2/R3 isolation is moot for the same reason.

## Interpretation (bounded)

- The OAuth **authorize** side still works and still mints `r`/`t`; the **callback** route is dead at the route level for every client shape, including the byte-faithful public reference shape.
- Therefore today's shared failure is **server-side and shape-independent**; no FluentMai (v0.2.9 or v0.3.0) auth regression is measurable against it.
- maimai.py shows no public 404 reports yet; the window overlaps the National Day holiday, so a temporary maintenance state vs permanent route removal cannot be distinguished from here.

Fingerprint metadata per CTO spec (status, location-present, content-type, body length, body hash, query-key set, timings) was recorded locally per attempt; no response bodies or OAuth values are published. Private evidence: `C:\Users\Daozh\.codex\diagnostics\AUTH-NET-2G-20261003` (attempt logs A1–A6/B1–B6, attempt matrix, R1 run artifacts, screenshots).
