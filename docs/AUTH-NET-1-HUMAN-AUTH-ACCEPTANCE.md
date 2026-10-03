# AUTH-NET-2F — Owner-operated OAuth acceptance of AUTH-NET-1

Date: 2026-10-03, Asia/Shanghai (+08:00).

**FINAL VERDICT: OWNER_DRIVEN_AUTH_STILL_BLOCKED**

**WIP / DEVICE VALIDATION BLOCKED / NOT ACCEPTED / NOT FOR RELEASE**

Five Owner-operated candidate authorizations captured callbacks; all five callback replays returned **HTTP 404**, and none reached authenticated home. No score request, score retry, import persistence or successful background import was reached. The bounded experiment ended after these five authorization attempts; no speculative production rewrite followed.

**GITHUB REVIEW AVAILABLE:** [draft PR #7](https://github.com/Daozhu1007/FluentMai/pull/7), repository `Daozhu1007/FluentMai`, branch `auth-net-1-v030`.

Implementation SHA: `919ea16aac3ad7dffb0721f17fba67f3ce27cc63`.
Canonical v0.3.0 baseline: `d734660719f94db523af1053bbc915bdc76d7c19`.
The baseline is an ancestor of the implementation. The implementation was pushed as WIP and independently verified on the remote. No merge, tag, release or version bump occurred.

## Source and package controls

The requested feature worktree contains pre-existing, uncommitted OAuth experiments and diagnostic reports. Those files were retained and excluded from the push and APK build. The independent, initially clean detached worktree `D:\Code\FluentMai-authnet-device-919ea16` was used to build the exact implementation. Its sole temporary change was `applicationId = "dev.fluentmai.android.validation"`; the build configuration was restored immediately after APK creation and the tracked worktree was verified clean.

Candidate metadata: `0.3.0-beta`, versionCode **14**. Candidate APK SHA-256: `98dd3d7e386d3b6802748dd31d94c0eda87cbccb896f3d15f86b169e4bf943f5`. A data-preserving side-by-side installation and installed APK readback verified that hash. No auth, retry, timeout, persistence or network code was modified.

Pre-push Gitleaks **8.30.1** scanned **82** commits reachable from the implementation and approximately **3.54 MB** of changed content; no leaks were detected. The production applicationId, manifest and version configuration match the canonical baseline. The local experiments are not part of the implementation commit.

## Original v0.2.9 protection

`dev.fluentmai.android` remains the installed `0.2.9-beta` / versionCode **13** control. Its source commit is unverified; version metadata alone does not establish source provenance. Installed APK SHA-256: `d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`.

The control is **not byte-identical to its pre-validation state**: earlier testing added **3,766 PC upper-bound records** while preserving older records. This task did not import through, clear, uninstall or overwrite it. Read-only before/after checks verified identical installed APK/version/update metadata and identical hashes of all **eight** captured database/preferences files. This establishes preservation relative to this task's start, not relative to the older pre-validation state. All new validation used the candidate package.

## Tablet preflight

Only the connected Xiaomi tablet, model **24018RPACC**, was selected; all ADB commands use its explicit serial internally. The Owner phone is not accessed. This model differs from some earlier records; current `ro.build.characteristics=tablet` verifies the selected device.

| Fact | Current observation |
| --- | --- |
| Android | 16 / API 36 |
| WeChat | 8.0.72 / versionCode 3085, primary instance |
| Local time at preflight | 2026-10-03 16:43:53 +08:00 |
| Network | Connected Wi-Fi, 5240 MHz; names and addresses withheld |
| Global HTTP proxy | Unset (`null`) |
| Private DNS mode / specifier | Unset (`null`) / unset (`null`) |
| Always-on VPN | Unset (`null`) |
| Competing VPN | No active VPN network agent; Clash Meta process absent |
| Existing original VPN entry | Prepared original package, active type -1; dormant bound service, not an active tunnel |

Candidate capture subsequently established its own VPN, `tun0`, active type 1, Hook listeners **8284 / 9457**, and the `ImportForegroundService` waiting state. The system battery/settings page was dismissed without changing battery policy. Filtered, allowlisted logcat and host timing collection began before authorization generation.

## Human authorization and import matrix

The Owner performs the WeChat interaction. During authorize generation through callback and authenticated-home determination, the Agent uses passive logging only: no screenshots, UI dumps, injected taps or WeChat navigation. A new authorization transaction is generated for each actual attempt; no callback/code/state is reused.

| Attempt | Latest fresh entry generated (+08:00) | Callback captured (+08:00) | authorize_to_callback_ms | Callback HTTP | Authenticated home | error100001 | Result |
| --- | --- | --- | ---: | ---: | --- | --- | --- |
| 1 | 16:51:44.651 | 16:51:57.918 | 13267 | 404 | NO | YES | Finished / FAILED |
| 2 | 16:54:19.803 | 16:54:44.529 | 24726 | 404 | NO | UNOBSERVED | Auth failure; process absent before result readback |
| 3 | 16:55:53.155 | 16:56:58.226 | 65071 | 404 | NO | YES | Finished / FAILED |
| 4 | 16:58:03.556 | 16:59:03.613 | 60057 | 404 | NO | YES | Finished / FAILED |
| 5 | 17:01:06.513 | 17:01:38.790 | 32277 | 404 | NO | YES | Finished / FAILED |

Preparation that encountered the battery/settings page did not generate an authorization transaction and is not counted as an auth attempt. Before attempt 1, the Agent initially generated a preparation link at **16:46:59.961**. A second fresh entry was subsequently observed at **16:51:44.651**, while the Agent was passively waiting; the callback followed that latest entry. The initial preparation link produced no separately observed callback/import. There were five actual Owner-operated callback/import attempts, not six tested authorizations.

The timing column measures the stock **generated authorization entry** event to the **first bridge callback-capture** event on the tablet clock, not the beginning of the authorize HTTP request or the time spent on a consent screen. Those earlier timestamps are unobserved. Latency includes manual handling and must not be presented as isolated network latency. Attempt 1 uses the most recently generated entry before its callback; per-transaction fingerprint linkage was not available for attempts 1-4.

All five callbacks report `hasCode=true`, `hasState=true`, and `duplicatedHttpInPath=false`; replay-header count is **6**. Authorize/import cookie metadata reports zero cookies and an empty name set for the observed auth stages. Exhaustive callback query key names were not retained for attempts 1-4; the stock presence metadata confirms `code` and `state`. A safe live parser for attempt 5 independently retained the query key names **`code`, `r`, `state`, `t`**, and optional fingerprints only; no values were saved. The Agent did not replay or reuse any prior entry, callback, code or state. Ordinary repeated browser/local-hook hits are not counted as new authorizations; each import makes one `auth-callback` attempt.

Fresh primary application diagnostics and read-only `ImportTaskStore` observations were captured after the timing-sensitive region for attempts **1, 3, 4 and 5**, without debugger suspend, breakpoints, method invocation or field writes. Each reports `Finished`, an auth error and no import result. Attempt **2** retained its live request logs and unchanged database counts, but its app process was absent before diagnostics could be inspected. Its final home path, backend error code and task phase are therefore **UNOBSERVED**; another attempt's report is not substituted. Its non-retryable login-home failure independently establishes unsuccessful authentication. Process absence after failure is not attributed to a product defect or to a successful background import.

The prior control success reached auth HTTP 200 and authenticated `/maimai-mobile/home/` HTTP 200, then reproduced recent-record timeout/EOF, ADVANCED and EXPERT 30-second timeouts, MASTER chunked EOF, parsed 140 records and failed three difficulties. This is the target failure, not proof of candidate acceptance.

## Actual request-attempt matrix

All request facts below come from the candidate's stock structured log events. `bytes` is the client's reported body-length field; it is not an independently measured wire byte count. Callback `outcome=success` means a response was received, including a 404; it does **not** mean OAuth succeeded.

| Auth attempt | Request category | Request attempt | elapsedMs | outcome | willRetry | status | bytes | Safe error class |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | --- |
| 1 | auth-callback | 1/1 | 794 | success | false | 404 | 555 | none |
| 1 | login-home | 1/3 | 19088 | non_retryable_failure | false | 200 | 7366 | IOException |
| 2 | auth-callback | 1/1 | 810 | success | false | 404 | 555 | none |
| 2 | login-home | 1/3 | 5099 | non_retryable_failure | false | 200 | 7366 | IOException |
| 3 | auth-callback | 1/1 | 940 | success | false | 404 | 555 | none |
| 3 | login-home | 1/3 | 3168 | non_retryable_failure | false | 200 | 7366 | IOException |
| 4 | auth-callback | 1/1 | 689 | success | false | 404 | 555 | none |
| 4 | login-home | 1/3 | 4799 | non_retryable_failure | false | 200 | 7366 | IOException |
| 5 | auth-callback | 1/1 | 794 | success | false | 404 | 555 | none |
| 5 | login-home | 1/3 | 6901 | non_retryable_failure | false | 200 | 7366 | IOException |

For attempts 1/3/4/5, primary diagnostics additionally confirm final home path `/maimai-mobile/error/` and error **100001**. The login-home response's HTTP 200 is an error page, not authenticated home. The exact final path and error code are unobserved for attempt 2.

| Intended import coverage | Real-device result in this task |
| --- | --- |
| Recent records | Not reached; zero observed requests |
| BASIC | Not reached; zero observed requests |
| ADVANCED | Not reached; zero observed requests |
| EXPERT | Not reached; zero observed requests |
| MASTER | Not reached; zero observed requests |
| RE_MASTER | Not reached; zero observed requests |
| Rating-target supplemental pages | Not reached; zero observed requests |
| PC synchronization | Not reached; zero observed requests |
| Persistence | Not reached; no import batch written |

The candidate started with **0** scores, chart play counts, recent plays, rating rows, quarantine rows and import batches. Read-only database checks after **every** attempt retained those counts and returned `quick_check=ok`. This is auth-abort preservation of empty candidate data, not evidence of PARTIAL persistence or retention of populated failed-difficulty history.

## Retry, PARTIAL, COMPLETE and background acceptance

Source and targeted deterministic tests establish the intended **60-second score timeout**, **three score attempts**, **two supplemental attempts**, partial-persistence semantics and single-use callback policy. Actual auth failures were classified non-retryable and were not retried. Score timeout/chunked-EOF recovery was **not naturally exercised**; `REAL_DEVICE_RETRY_RECOVERY_VALIDATED` is not claimed.

No successful difficulty was fetched and no useful parsed result was written. `REAL_DEVICE_PARTIAL_PERSISTENCE_VALIDATED` is not claimed. No complete candidate import or authenticated-home success occurred. `AUTH_NET_1_COMPLETE_IMPORT_VALIDATED` is not claimed.

The conditional successful background test was **not entered** because no normal authenticated import could proceed. Explicit Finished/FAILED reporting in four attempts does not establish foreground-service survival or successful persistence during a background score import. The Agent did not background or kill an active import to manufacture a result, and did not damage network connectivity.

## Automated versus human interpretation

| Candidate interaction mode | Callback 404 | Authenticated home |
| --- | --- | --- |
| Earlier Agent-driven WeChat navigation (task-provided history) | 5/5 | 0/5 |
| Current Owner-operated WeChat authorization | 5/5 | 0/5 |

`AUTOMATED_WECHAT_FLOW_INTERFERENCE_SUPPORTED` is **not established** by this comparison: human operation did not recover candidate authentication. The earlier Owner-reported v0.2.9 success proves that authentication was not permanently unavailable, but is not a current candidate success. Timing/session/browser/server differences and the relationship between versions remain unresolved; neither a permanent OAuth outage nor an AUTH-NET-1 regression is established. No auth rewrite is justified by this bounded outcome.

## Tests

Targeted tests passed on the exact implementation: `WahlapImportHttpClientTest`, `WahlapAuthCaptureStoreTest`, `ImportForegroundServiceTest`, `WahlapResilientFetcherTest`, and `RealWahlapImportAdapterTest`.

The practical Android suite, `gradlew.bat test --continue --console=plain`, completed with **520** reported test executions: **515 passed, 4 failed, 1 skipped**. Failures are the two known `PlayActivityPersistenceTest` migration cases in both Debug and Release:

- `sevenToEightMigrationRetainsExistingExactPc`
- `sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables`

Both produce `SQLiteCantOpenDatabaseException` / `SQLITE_CANTOPEN`. Both were rerun in isolation on the clean canonical `d734660` baseline and failed identically. They remain documented environmental/baseline failures; no test was weakened or suppressed. Routine Gradle up-to-date tasks were reused.

## Current repository and handoff

- Pushed branch: `auth-net-1-v030`.
- Implementation SHA: `919ea16aac3ad7dffb0721f17fba67f3ce27cc63`.
- Additional documentation commit: this privacy-safe report is the sole documentation change being committed separately after the implementation; its SHA is reported in the final handoff. A document cannot embed its own eventual Git commit hash.
- Device acceptance: **BLOCKED**; `OWNER_DRIVEN_AUTH_STILL_BLOCKED`.
- Merged: **NO**.
- Released: **NO**.
- Original package: installed APK and all eight captured database/preferences hashes unchanged since this task's start; no new imports or package replacement.
- Existing local experimental source/test/report files: retained.
- Candidate build worktree: exact `919ea16`, clean after applicationId restoration.
- Cleanup at **17:02:39 +08:00**: no active VPN/TUN network agent, Hook listeners or capture/import services; original and candidate packages retained. Only owned host collectors/logcat subprocesses were stopped, with the ADB server retained.

Private local safe evidence is stored under `C:\Users\Daozh\.codex\diagnostics\AUTH-NET-2F-20261003`. Raw OAuth values, Cookie values, account identifiers, private serials and sensitive URLs are excluded from this report and publication.

Primary evidence: `preflight.json`, `build-result.json`, `candidate-identity.json`, `original-before/after.json`, `logcat-safe.jsonl`, `attempt1-5-prepared/capture/events.json`, four fresh `attemptN-diagnostic.txt` / `attemptN-task-observation.json` pairs (N=1/3/4/5), five `attemptN-after-data.json` checks, `test-summary.json`, and `baseline-migration-summary.json`. Retrospective query-key extraction did not recover attempts 1-4; this missing optional metadata did not trigger extra authorization attempts. Pre-existing experimental source/test bytes were compared to their prior preserved snapshot and matched. No original or candidate raw database was retained by the in-memory database count collector.

**Final disposition: retain the pushed branch and draft PR as WIP, unaccepted and unavailable for merge/release. GITHUB REVIEW AVAILABLE.**
