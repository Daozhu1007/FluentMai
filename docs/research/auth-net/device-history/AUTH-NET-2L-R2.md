# AUTH-NET-2L-R2 — final client-side auth differential, 2026-10-04

Experiment window 2026-10-04 ≈23:40–00:05 (+08:00), crossing midnight.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Answer the remaining high-value questions after 2K: does fresh-transaction `r/t/state/code`
integrity differ between outcomes; do any remaining nonsecret request header **values**
differ; does edge/gateway affinity correlate with 404 versus 302; and if none explain the
outcome, is the residual best classified as server-side per-transaction state. Reuse the
proven 2K non-invasive observer; extend it; consume at most four fresh OAuth transactions;
stop early on an adjacent FAIL→SUCCESS pair.

## Environment

- Xiaomi 23116PN5BC phone; existing `dev.fluentmai.android`, 0.3.0-beta / 14, APK SHA-256
  `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da` — byte-identical
  before/after; same subject build as 2K. The app process (PID 1167) stayed alive across
  the whole session; both observer attachments targeted it.
- `Observer2L`: the 2K observer (JDI host attach over `adb forward jdwp:`, DEX
  descriptor-slot argument reads at instruction zero, `DefaultHttpResponse` header-getter
  snapshots before redirects, instance-filtered body-buffer observation) plus three
  additions: per-stage secret-query fingerprints, whitelisted nonsecret header-value
  capture, and categorical edge/gateway header capture. No target writes, no target
  invocations, no stream consumption, no extra callback.
- Readiness before any real OAuth: host unit checks (cookie equality, 12-case installed
  classifier parity, authorize secret extraction, integrity MATCH/DIFFERENT/absent paths,
  header-value safety) and a full host-Ktor fixture covering the new events
  (`host-fixture-result-2l.json`, all checks true, zero OAuth). A standalone on-device ART
  fixture was **not** reproducible this round: the phone is a user build
  (`ro.debuggable=0`), its `app_process` front-end rejects `-Xjdwp`/`-Xrunjdwp` outright,
  and `-agentpath` libjdwp JVMTI is debuggable-gated (`run-as` does not lift the gate).
  The on-device substitute was a two-run attach smoke against the debuggable subject app
  (attach, arm, zero errors, zero OAuth, APK unchanged). The ART-layout code paths in
  `Observer2L` are byte-identical to the 2K-proven observer on this exact APK, so 2K's
  on-device fixture validation carries over for those paths; all 2L extensions are pure
  host-side logic covered by the host checks/fixture. This limitation is recorded in
  `readiness.json`.
- A per-run ephemeral HMAC key fingerprinted `r`, `t`, `state`, `code` and Cookie values.
  Only MATCH/MISMATCH plus length/char-class/percent-encoding structure were emitted;
  no values or digest bytes were persisted; the key and fingerprints were zeroed at detach.
  Sensitive-looking response headers (e.g. `eo-log-uuid`) were recorded as
  present/value-withheld. Secret-bearing screenshots taken during UI driving were deleted.

## Procedure

1. Readiness gate (`readiness.json` verdict `OBSERVER_READY`) → APK hash check → JDI attach
   → arm six watched classes → `ARMED` before any authorization.
2. Each attempt: app Import tab → start capture → 复制授权 (this builds a fresh authorize
   chain; the observer captures its 302 Location) → paste the authorize link into the
   WeChat 文件传输助手 self-chat → open it → WeChat auto-completes `snsapi_base`
   authorization → the capture VPN intercepts the callback → the app replays it once →
   followed result and Home classification.
3. Stop rule: adjacent FAIL→SUCCESS pair or four attempts. No failure was manufactured, no
   callback code replayed, no transaction retried. Owner-side UI steps were driven over ADB
   with screenshot verification (no Win32 automation, no native Computer Use needed).

## Observations

### Four fresh transactions — all first-hop 404

| Field | #1 | #2 | #3 | #4 |
| --- | --- | --- | --- | --- |
| First hop | 404 | 404 | 404 | 404 |
| `r` authorize→browser | MATCH | MATCH | MATCH | MATCH |
| `t` authorize→browser | MATCH | MATCH | MATCH | MATCH |
| `state` authorize→browser | MATCH | MATCH | MATCH | MATCH |
| `r` browser→replay | MATCH | MATCH | MATCH | MATCH |
| `t` browser→replay | MATCH | MATCH | MATCH | MATCH |
| `state` browser→replay | MATCH | MATCH | MATCH | MATCH |
| `code` browser→replay | MATCH | MATCH | MATCH | MATCH |
| First-hop request header values vs #1 | baseline | no differing keys | no differing keys | no differing keys |
| First-hop Server | nginx/1.29.5 | nginx/1.29.5 | nginx/1.29.5 | nginx/1.29.5 |
| First-hop EO-Cache-Status | MISS | MISS | MISS | MISS |
| First-hop Location | absent | absent | absent | absent |
| 404 body | 555 bytes | 555 bytes | 555 bytes | 555 bytes |
| Home authenticated | not reached by observer (trailing HOME; first-hop + followed 404 captured) | NO | NO | NO |

Secret-parameter structure (identical shape all attempts): `r` ALNUM_DASH length 12,
`t` HEX length 12, `state` HEX length 64, none percent-encoded; callback query keys
`code, r, state, t`; redirect_uri target classified `tgk-wcaime.wahlap.com` / CALLBACK.

- The 555-byte 404 body SHA-256 equals the 2K FAIL body hash
  `e32d0010b841a289f489aa6a7e6deef59a000799c53497d96171bdaf070ca370` byte-for-byte.
- The authorize chain 302 (tgk-wcaime → open.weixin.qq.com) also showed Server
  nginx/1.29.5 and EO-Cache-Status MISS. WeChat `snsapi_base` consent auto-approved every
  time (no consent page appeared).
- DNS (host vantage): both wahlap hosts resolve to the single address 114.117.135.47.
  Direct phone-side remote attribution is not meaningful while the capture VPN tunnels app
  traffic; the one direct peer observed matched that address over port 80.
- Timing (host breakpoint receipts, non-causal): authorize→callback 335,623 / 75,024 /
  73,166 / 237,451 ms — dominated by human-pace UI driving; capture→replay 323–430 ms;
  replay→first-hop 1,105–1,398 ms. Debugger suspension perturbs these; no contrast is
  claimable (no SUCCESS). `TIMING_DIFFERENTIAL_OBSERVED` is **not** awarded.

### Round-1 anomaly (observer, not OAuth)

The first attachment's overhead stop-guard (2K's 750 ms) fired during attempt #1's trailing
HOME phase because the 2L header-value extraction adds JDWP round-trips inside the suspend
window; the guard was raised to 3,000 ms and the observer re-armed. Attempt #1's OAuth
observations (authorize baseline, browser capture, replay integrity, first hop 404, followed
404) were complete before detach. No transaction was consumed by the anomaly.

## Classification

**CASE E — NO_COMPLETE_DIFFERENTIAL.** No SUCCESS occurred among the four fresh
transactions, so the mission's paired differential (CASES A–D) could not be formed.

## Evidence limitations

- This round adds four integrity-perfect FAILs and no SUCCESS. Measured request header
  values were identical within those four failures. 2K retains the first-hop FAIL→SUCCESS
  pair, but did not measure every value-level field newly added by 2L; those fields cannot
  be declared equal or disproven as explanations across outcomes.
- Timing is host breakpoint-receipt intervals under debugger suspension; never causal.
- The standalone ART fixture could not be re-run on this user build (documented above);
  on-device validation of the unchanged layout paths relies on the 2K fixture plus this
  round's attach smoke. The 2L extensions were validated host-side only.
- One phone, one build, one window (~25 minutes); the server's per-window state is an
  uncontrolled variable everywhere in this history.

## Current interpretation

Intra-transaction OAuth parameter integrity was intact in 4/4 additional fresh failures;
measured first-hop request header values were identical within those four failures. Every
first hop was a fresh MISS on the same edge banner, and the failure body is byte-stable
across two days and separate windows. 2K remains the retained first-hop FAIL→SUCCESS pair;
it did not measure every value-level field newly added by 2L. Thus these observations do
not establish equality of every client-observable field across FAIL and SUCCESS.
`OAUTH_TRANSACTION_INTEGRITY_DEFECT_FOUND`, `REQUEST_VALUE_DIFFERENTIAL_FOUND`, and
`BACKEND_AFFINITY_DIFFERENTIAL_SUPPORTED` are all **not** awarded. No explanatory
client-side defect has been identified. Server-side per-transaction/per-window state
remains the leading residual explanation space, not a proven cause.

**`STOP_CLIENT_SIDE_ROOT_CAUSE_FORENSICS` is recommended.** The formal CASE D gate was not
reached (no pair), but the accumulated evidence makes further broad client-side forensics
low-value. The next
engineering direction remains **BOUNDED_FRESH_OAUTH_TRANSACTION_RETRY** (failed fresh
transaction → discard its single-use code/state → new authorization → bounded retry; never
replay a callback code). Broad client-side root-cause forensics are closed unless a
materially new production symptom contradicts this evidence.

## Raw evidence provenance

Directory name: `AUTH-NET-2L-R2-20261004`.
Primary sources: `readiness.json`, `host-fixture-result-2l.json`, `art-fixture-result-2l.json`,
`smoke-result.json`, `events-attempt1-fail.jsonl`, `events-attempt2-fail.jsonl`,
`events-attempt3-fail.jsonl`, `live-events.jsonl`, `result.json`,
`dns-observations.json`, `net-observations.jsonl`, `OWNER-REPORT.md`, `cleanup.json`,
`final-manifest.json`. Cleanup recorded the unchanged APK, cleared forwards/reverses,
removed phone temp files, stopped the net poller, deleted secret-bearing screenshots, and
zeroed the ephemeral key/fingerprints. No JSONL, screenshot, body, secret value, or
temporary fingerprint is committed.
