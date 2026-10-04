# Early AUTH-NET-1 / 2A / 2B evidence — 2026-10-03

Historical curation; UTC+08:00. **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Preserve the early authentication and capture investigations without merging
pre-capture errors, callback rejection and unreached score-import stages.

## Environment

- Initial report device model: Xiaomi 23116PN5BC, Android 16 / API 36.
  This report series must not be treated as new evidence from tablet 24018RPACC.
- Exact initial `919ea16` APK: `dev.fluentmai.android`, 0.3.0-beta / 14,
  SHA-256 `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da`.
- Stock `d734660` reference APK used in the later replacement A/B:
  SHA-256 `bbfb425b0f16c3d89090af1e763db58a2576ff863623bc95419e792a54b42a99`.
- Original eight-run matrix used an explicitly authorized clean installation;
  it did not validate an in-place upgrade. Subsequent replacement A/B preserved
  app data; WeChat login was retained.

## Procedure

The initial matrix used five warm, two cold and one background import. A later
post-maintenance round opened three fresh entries. Capture revalidation and a
data-preserving stock/current replacement A/B followed. 2B then inspected complete
capture framing and bounded replay variants using fresh transactions, plus one
original HTTPS browser authorization with capture/replay absent. Temporary
diagnostic builds were restored; no diagnostic source is imported here.

## Observations

| Round | Measured outcome | Limit |
| --- | --- | --- |
| Eight imports, 00:49–01:31 | All callback 404; Home HTTP 200 error page / error100001; FAILED 8 | Score fetch, retry and persistence unreached; background run ended in auth failure |
| Three entries, 07:36–07:43 | WebView proxy-connect errors; no captured callback, home request or backend import | First run retained only the error prefix; do not infer its full suffix or error100001 |
| Current then stock, 08:40–08:55 | 919ea16 0/3 and d734660 0/3 authenticated; all callbacks captured and replayed 404 | First current run's exact home path/error code unobserved; other five establish error100001 |
| 2B six app transactions | One complete inbound header chunk and one callback emission each; all replayed 404 then unauthenticated Home | Request-builder/native transport observations are not a decrypted browser wire capture |
| 2B original HTTPS browser, opened 09:50 | Visible callback 404 with no app replay | Wire status/Location/Set-Cookie unobserved |

Capture revalidation established ordinary VPN/listener/callback operation after
the proxy-error round. A missing before-disable snapshot prevents attribution of
the earlier proxy errors to Clash Meta. A dormant prepared VPN entry is not an
active tunnel.

The 2A source audit confirmed stock process-global client ownership versus
`919ea16`'s separate authorize/import clients. Two retained current authorize
summaries had empty jars; the first summary was unavailable. Thus actual loss of
an authorize-issued cookie was not demonstrated. Data checks preserved the existing
1,730 scores through the stock/current replacement and restored the original APK.

2B's six complete callbacks contained Cookie names `_ga`, `_ga_HMWCCH9Y0H`, `_gid`,
query keys `code`, `r`, `state`, `t`, and a WeChat UA. Local comparisons recorded
fresh distinct codes, matching authorize/captured state and captured/replay code/state;
ephemeral comparison digests are deliberately omitted. Header sizes were 1,060–1,062
bytes and Cookie material was present in the first complete chunk. Variants covering
captured versus fallback UA, reduced headers, existing cellular access, native HTTP
and native Wi-Fi-bound replay all failed. The intercepted browser request received
a local 202 with no redirect; the app HTTPS replay was the first upstream callback
request in that topology. Browser code consumption was not established.

## Verdict at the time

Initial authentication remained blocked. The post-maintenance pre-capture round
classified `POST_MAINTENANCE_AUTH_FAILURE_CHANGED`; the later captured stock/current
comparison classified `BASELINE_ALSO_FAILS_IDENTICALLY`. 2A established an ownership
difference, not its causal relevance. 2B classified `OWNER_AUTH_CONTINUITY_BLOCKED`, with no
proven production auth fix. A one-buffer framing assumption was identified as a
latent defect by code/tests, but fragmentation did not explain these complete
captures and no framing change was deployed as an auth repair.

## Evidence limitations

The clean-install eight-run matrix, stock replacement A/B, and diagnostic variants
are different experiments; their state cannot be treated as identical. Direct
browser 404 is a visible observation, not an independently measured HTTP status.
Successful peer/other-device imports were Owner reports with unknown auth method
and session metadata. Preservation after auth aborts is not PARTIAL persistence.

## Later status

**STILL_VALID.** The measured failures and noncausal boundaries remain valid.
Later phone successes do not negate these earlier failures. The reviewed
complete-attempt owner addresses the structural lifetime gap, but its tests do not
prove that gap caused the historical 404s.

## Superseded by

No observation is replaced. For the later implementation see
[review fix](../../../AUTH-NET-1-REVIEW-FIX.md); for the measured phone differential
see [2K](AUTH-NET-2K.md). Do not reuse 2B's historical parameter-continuity checks as
proof of fresh query-value integrity in 2K, where it was not measured.

## Raw evidence provenance

Directory names: `AUTH-NET-1-2026-10-02`, `AUTH-NET-2A-2026-10-03`,
`AUTH-NET-2-CLASH-2026-10-03`, `AUTH-NET-2B-2026-10-03`.
Primary artifacts include initial `verified-matrix-summary.json`,
`verified-post-maintenance-summary.json`, per-run capture/safe-event records,
stock/restored APK verification and 2B `verified-summary.json` / restoration checks.
Local narratives: `AUTH-NET-1-OWNER-DEVICE-VALIDATION.md`,
`AUTH-NET-2A-OAUTH-CONTINUITY-AB.md`, `AUTH-NET-2B-OWNER-CONTINUITY-FIX.md`.
No raw artifacts or temporary fingerprint material are committed.
