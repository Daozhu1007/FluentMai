# AUTH-NET-2C / 2D — same-tablet boundaries, 2026-10-03

UTC+08:00. **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

2C compared the installed historical control with the exact early AUTH-NET candidate.
2D tested whether the same visible failure occurred in an original HTTPS browser
flow with FluentMai capture/replay absent, including one alternate saved Wi-Fi.

## Environment

- Xiaomi 24018RPACC tablet / Android 16 / API 36; primary WeChat 8.0.72 / 3085.
- Historical control: `dev.fluentmai.android`, 0.2.9-beta / 13;
  installed APK SHA-256
  `d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`.
  Source SHA unknown; version is not source provenance.
- 2C candidate: exact `919ea16`, `dev.fluentmai.android.validation`,
  0.3.0-beta / 14; installed APK SHA-256
  `98dd3d7e386d3b6802748dd31d94c0eda87cbccb896f3d15f86b169e4bf943f5`.
- Canonical comparison source: `d734660`. Existing source experiments were excluded
  from diagnostic/candidate builds and restored afterward.
- Same primary WeChat session; no proxy, custom Private DNS or competing active
  VPN observed. Device/account data was retained. The Owner phone was not accessed.

## Procedure

2C ran A1/B1/A2/B2/A3/B3 interleaved, with one package's capture active at a time
and fresh authorization per attempt. Only applicationId changed for the exact
candidate build. 2D used a temporary Activity that retained the original HTTPS
redirect and had no VPN/capture/replay/import path: RAW1–RAW3, then three ordinary
control captures FM1–FM3, then one fresh NETRAW1 on an already-configured Wi-Fi.
The diagnostic package was removed and original network restored.

## Observations

### 2C — 11:20–11:45

| Run | Callback time | Generation→capture, seconds | Replay HTTP | Authenticated Home | error100001 |
| --- | --- | ---: | ---: | --- | --- |
| A1 | 11:25:20.403 | 148.683 | 404 | NO | UNOBSERVED |
| B1 | 11:33:31.112 | 50.782 | 404 | NO | UNOBSERVED |
| A2 | 11:34:51.643 | 22.683 | 404 | NO | UNOBSERVED |
| B2 | 11:36:16.750 | 22.861 | 404 | NO | YES |
| A3 | 11:37:54.616 | 22.755 | 404 | NO | UNOBSERVED |
| B3 | 11:39:44.370 | 21.915 | 404 | NO | YES |

All six established capture/VPN, received a fresh code-bearing callback, started
login and ended FAILED. The control and candidate each authenticated 0/3; score
import was unreached. Capture→app replay-start intervals were 17–26 ms.
Generation→capture includes share/open/manual preparation and is not code age or
isolated network latency. The faster later pairs reproduced the same failure.

Observed authorize/pre-replay/callback jars were empty on both sides. Detailed
raw browser Cookie presence and delivered UA were unobserved. B3 retained query
keys `code`, `r`, `state`, `t`; earlier full key sets were unavailable.
Control counts remained 1,732 scores, 1,732 chart play counts, 100 recent plays,
two rating rows, four quarantine rows and two import batches; candidate tables
stayed empty. Read-only integrity/preservation checks passed.

### 2D — 14:48–15:13

| Group | Fresh attempts | Visible outcome | Protocol-level callback status | Capture/replay |
| --- | ---: | --- | --- | --- |
| RAW1–RAW3, original Wi-Fi | 3 | Callback page 404, no authenticated Home | UNOBSERVED | Absent |
| FM1–FM3 control | 3 | FAILED, no authenticated Home | 404 in all three app replays | Present |
| NETRAW1, alternate saved Wi-Fi | 1 | Callback page 404, no authenticated Home | UNOBSERVED | Absent |

Direct browser Copy Link returned HTTPS callback host/path and keys `r,t` only;
this is not the actual code-bearing wire query. All seven authorize states were
locally compared as distinct, and each FM callback state matched its own authorize
state; comparison fingerprints are omitted. FM code novelty was not independently
recovered after ring-buffer expiry. Bare public probes returned authorize 302,
callback 404 and cookie-free Home 302, with correct observed time/reachability.
These probes did not use a valid OAuth transaction.

Alternate Wi-Fi access reproduced the direct visible failure. Independent public
egress was not measured, so shared upstream/network causes remain possible.
Control database/preferences and prior identities remained intact; two app-owned
files changed through ordinary execution, so whole-directory identity is not claimed.

## Verdict at the time

- 2C: `TABLET_SHARED_AUTH_FAILURE`; no current-version-only regression shown.
- 2D: `RAW_OAUTH_TRANSACTION_REJECTED_OUTSIDE_FLUENTMAI`, describing the visible
  operational boundary. Failure existed without capture, HTTP rewrite or app replay.

Neither round proved a production defect, cookie-loss cause, expired code, invalid
WeChat session, service outage or timing cause. No auth fix was published.

## Evidence limitations

HTTP transport completion with 404 is not auth success. The historical control
diagnostics are less complete than B2/B3; missing error codes remain unobserved.
RAW visible 404 cannot be promoted to a measured HTTP response. Cookie-free dummy
probes cannot establish rejection of valid OAuth. Auth-abort data preservation
does not validate score retries, PARTIAL persistence or successful background import.

## Later status

**STILL_VALID.** These bounded measurements remain valid. Later real phone
successes challenge general outage hypotheses, not the historical tablet outcomes.

## Superseded by

No observation superseded. [2G](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md)
adds the reviewed-candidate comparison. [2K](AUTH-NET-2K.md) provides the later
first-hop differential; it does not reconstruct missing RAW wire metadata.

## Raw evidence provenance

Directory names: `AUTH-NET-2C-20261003-104138` (including `continuation`),
`AUTH-NET-2D-20261003`. Primary artifacts: per-run record/capture/data JSON,
`verified-matrix.json`, B2/B3 diagnostics, network differential/restoration and
final preservation checks. Local narratives: `AUTH-NET-2C-SAME-TABLET-AB.md`,
`AUTH-NET-2D-RAW-HTTPS-OAUTH.md`. No artifacts, state fingerprints or private
installed paths/serials are committed.
