# AUTH-NET-3A — bounded fresh OAuth transaction retry

2026-10-05, Asia/Shanghai. **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**
PR #7 must remain Draft; no merge, release or version bump.

## Engineering transition

**ROOT-CAUSE FORENSICS CLOSED → PRODUCT MITIGATION IMPLEMENTED.**
Fresh authorization retry mitigates intermittent authentication failure. It does not
repair or explain Wahlap server behavior. Server-side per-transaction/per-window state
remains a leading residual explanation space, not a proven cause.

Fetched `origin/auth-net-1-v030` matched the required starting SHA
`8abfaa95d6d8e92fdf93425d5c48ae72429f0b4f`. Implementation uses a fresh isolated
checkout of that remote commit. The main checkout and its untracked artifacts were
left intact. Before implementation, 2L-R2 and this history index were corrected:
four integrity-perfect failures, header values equal within those failures, no
SUCCESS in 2L; 2K retains the first-hop pair without every value-level field added
by 2L. No measured result was removed.

## Retry and ownership design

The default budget is **3 total fresh OAuth transactions per logical import**:
initial authorization plus at most two user-requested fresh authorizations.
The screen shows `授权尝试 N / 3`. There is no automatic WeChat authorization loop.

`WahlapAuthorizationRetryRequiredException` is emitted only after the callback
request completes and the Home response is classified unauthenticated. Callback
non-2xx by itself is insufficient; callback 404 plus authenticated Home remains
success. The existing Home classifier and idempotent score retry policy remain.
Typed cause-chain classification survives diagnostic wrappers without parsing UI
text. Capture/VPN, authorize-generation, network/transport, cancellation,
authenticated score failure and import failure have separate categories.
COMPLETE and PARTIAL remain importer outcomes.

`ImportTaskState.flowState` exposes AUTHORIZING, IMPORTING,
AUTH_RETRY_AVAILABLE, COMPLETE, PARTIAL and FAILED. Retry availability carries
the attempt number, maximum and sanitized AUTHORIZATION_RETRY_REQUIRED category.
After authenticated Home, the retry category/error are cleared and import proceeds.
After rejection 3/3, the state is FAILED and the ordinary start action can begin a
new logical session later. No fourth authorization is created in that session.

Each transaction owns a distinct `WahlapOAuthAttempt`, HTTP client and cookie jar.
The runner closes the consumed attempt in `finally`; authorize failures and capture
shutdown close unfinished attempts. Headers, captured callback URL and transaction
identity are discarded. The HTTP client atomically claims its callback once, so even
an accidental second login call cannot send the old code again.

Authorize entry generation is serialized and cached only for the current transaction;
duplicate copy/hook requests do not allocate another transaction. Cancellation does
not hold the handoff monitor while network I/O completes. Generated `r/t/state` bind
the current callback; capture is also disabled until authorize generation and identity
registration finish. Mismatches, missing/duplicate identity fields, duplicate captures
and queued callbacks with no matching pending capture cannot start the next import.
No OAuth identity is copied into logical retry state.
Callback dispatch is serialized so rejection of a concurrent stale callback cannot
suppress the current transaction's single-use callback via the bridge's busy flag.

The failed foreground service explicitly stops capture and itself. A new service
execution retains the logical import id and increments the attempt number only after
the user requests retry. Expected logical id plus failed-attempt number reject repeated
or stale retry commands. A distinct execution id prevents old service teardown,
progress and authorize-failure events from changing the next execution. Late Hook
teardown cannot close an attempt owned by the import service. `START_NOT_STICKY`
prevents process restart from replaying credentials. Only the authenticated import
pipeline reaches persistence; authorization retries do not repeat persistence.

## Deterministic coverage

All external requests in regression tests use loopback HTTP or synthetic authorize
entries. Service integration uses the real callback/Home client, importer and Room
persistence. These are internal correctness evidence, not device acceptance.

| Required behavior | Coverage |
| --- | --- |
| First rejection exposes retry; attempt closed/discarded | `rejectionDisposesAttemptThenUserRetryImportsAndPersistsExactlyOnce` |
| New attempt/client; stale callback cannot satisfy it | Same service test; `freshRetryGeneratesDistinctAuthorizeAndDoesNotCarryRejectedBrowserContext` |
| Retry success continues once; one actual database batch | Same service test, actual Room score and batch queries |
| Non-2xx callback plus authenticated Home succeeds | `callback404AndAuthenticatedHomeIsSuccessWithoutFreshRetry` |
| 3/3 rejects end FAILED; repeated retry does not create #4 | `threeRejectionsAreBoundedAndRepeatedRetryRequestsCannotCreateFourth` |
| Cancellation propagates with no retry | `cancellationPropagatesAndDoesNotOfferRetry`; existing runner/contract cancellation tests |
| Capture and authorize generation remain separate | `captureFailureAndAuthorizeFailureRemainDistinct`; `rejectedAuthorizeClosesAttemptAndLeavesNoPendingReplay` |
| Transport error is not auth rejection | `callbackTransportFailureIsNotAnAuthRejection`; real runner callback-transport cleanup test |
| Score timeout uses existing score retry after Home | `scoreRetryAfterHomeDoesNotRestartOAuth` (synthetic socket timeout, existing fetcher) |
| PARTIAL after Home persists useful data without OAuth restart | `exhaustedScoreSourceAfterHomeIsPartialWithUsefulRoomPersistence` |
| Old service/failure/queued callback cannot affect new transaction | `staleServiceDestructionFailureAndQueuedCallbackCannotAffectNextTransaction` |
| Terminal/service cleanup has no pending attempt | Above service tests; `destroyingWaitingServiceDisposesPendingAuthorization` |
| Authorize cleanup is immediate while I/O runs | `cancellingAuthorizeWhileIoRunsDoesNotBlockCleanupOrResurrectAttempt` |
| Concurrent copy/hook generation allocates only one transaction | `simultaneousAuthorizeRequestsCreateOnlyOneAttemptAndOneEntry` |
| Stale callback cannot suppress a simultaneous valid callback | `staleCallbackCannotSuppressConcurrentCurrentCallback` |

The existing activity-free capture test now supplies a legitimate matching handoff
before emitting its synthetic callback; its original assertions remain intact.
Cancellation retains the existing removal of the waiting notification.

## Validation results

Final targeted command: `:app:testDebugUnitTest`, filtering
`BoundedFreshAuthServiceTest`, `WahlapOAuthAttemptTest`,
`ImportForegroundServiceTest`, `WahlapImportRunnerTest` and `WahlapHookBridgeTest`.
**38 tests, 5 suites, zero failures/errors/skips; BUILD SUCCESSFUL.**

Full command: `test :core:model:jvmTest :app:assembleDebug`.
**624 JUnit entries in 123 suites: 623 passed, 1 skipped, zero failures/errors;
BUILD SUCCESSFUL.** Entries include debug/release variants, not 624 unique test
methods: JVM model 47, core JVM 140, Android debug 227, Android release 210.
The existing optional `JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`
skipped because `FLUENTMAI_JP_LIVE_FIXTURE` was unset.

The first full run encountered SQLite CANTOPEN in two unchanged on-disk migration
tests with the long Windows temporary directory. Using a short temporary directory
for test workers passed the migration suite and then both full runs; no test or
database implementation was weakened. The test-only Gradle init configuration is
private/untracked. Android version remains 0.3.0-beta / 14 on this branch;
no version file was changed.

### Tablet sequence

ADB targeted the verified **24018RPACC tablet only**. No Owner-phone command,
installation, data clearing or modification occurred. Native desktop Computer Use
was not available among the tools; tablet GUI steps used ADB, with screenshot/UI
verification and no PowerShell/Win32 GUI automation.

The separate reviewed applicationId is `dev.fluentmai.android.validation`, set
using a private build-only Gradle init script; production build configuration is
unchanged. Its initial flow-validation APK SHA-256 was
`da7c611830f88e431580b6b642254099202e59eac43a4b54fdab93dab9e0d888`.
Installed control `dev.fluentmai.android` stayed 0.2.9-beta / 13 with APK SHA-256
`d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`, unchanged
before/after all validation installations. Its installation/update timestamp was
unchanged; it was neither launched nor cleared nor installed over. Control-data
preservation follows from these scoped actions; its private database was not read
or independently hashed.

Initial transaction used Start capture → Copy authorization → WeChat File Transfer
Assistant self-chat → open the new authorize link. Transactions 2 and 3 began by
clicking the application's `重新授权`; the app generated and copied a new entry.
No other recipient was messaged. The screen advanced 1/3 → 2/3 → 3/3 in the same
logical import flow. Authorize/callback secrets were never included in this report.

| Transaction | Fresh authorize generated | Callback request/response | Home decision | Product result |
| --- | --- | --- | --- | --- |
| 1/3 | 00:57:43.679 | 01:03:35.045; exactly one request, 404 | 01:03:37.646; HTTP 200, unauthenticated, typed auth rejection | AUTH_RETRY_AVAILABLE; capture stopped; fresh retry button |
| 2/3 | 01:04:57.811 | 01:05:52.885; exactly one request, 404 | 01:05:55.233; HTTP 200, unauthenticated, typed auth rejection | AUTH_RETRY_AVAILABLE; capture stopped; fresh retry button |
| 3/3 | 01:06:43.708 | 01:07:34.829; exactly one request, 404 | 01:07:37.961; HTTP 200, unauthenticated, typed auth rejection | FAILED; no fresh retry button; ordinary manual restart available |

Times are 2026-10-05, UTC+08:00, from existing product logs, not added observers.
After 3/3 the screen said `已尝试 3 次授权，仍未登录成功。请稍后重新开始导入。`.
All validation-package services had ended (`dumpsys activity services`: nothing).
Existing validation scores stayed at 1,730 and quarantine at 2 in the product's
before/terminal refresh logs; this is auth-abort preservation, not PARTIAL acceptance.

**Authenticated Home reached: NO, 0/3.** Recent records, BASIC, ADVANCED, EXPERT,
MASTER, RE_MASTER, supplemental/rating target, PC, score-page retry and new import
persistence were all **UNREACHED** in this device run. There was no naturally
observed score timeout/EOF recovery or required-source exhaustion after login.

Awarded:

- `BOUNDED_FRESH_AUTH_RETRY_FLOW_VALIDATED`
- `AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED`

Not awarded:

- `AUTH_NET_COMPLETE_IMPORT_VALIDATED`
- `REAL_DEVICE_RETRY_RECOVERY_VALIDATED`
- `REAL_DEVICE_PARTIAL_PERSISTENCE_VALIDATED`

### Final-build provenance limit

Final code review after the three transactions identified the capture window while
authorize generation had not yet registered transaction identity. The final gate
rejects captures throughout that window; deterministic tests cover this plus
simultaneous generation. This gate was **added after** the measured three-transaction
flow. Serial callback dispatch also prevents a concurrent stale callback from
suppressing a valid callback. The final targeted and full suites above were rerun
after these changes.

The final validation APK SHA-256 is
`cebb50dd116fb94ebfa166fe4c64054816e9b2fcadb7684f5cfcdb05aabf6ceb`.
It was built, installed on the same tablet and independently hash-checked against
the installed APK. Launch returned to idle with no active import/capture service,
demonstrating no credential replay on process replacement. No fourth OAuth was
created. The generation-window gate has deterministic evidence and installation
smoke evidence, **not an additional real-OAuth run on the final APK**. The measured
flow marker belongs to the initial APK; it is not promoted to authenticated-import
acceptance or identical-final-binary OAuth evidence.

## AUTH-NET-3B exact final APK session — acceptance incomplete

2026-10-05, UTC+08:00. This separately authorized session used the exact final
implementation at source SHA `9dd6b2c56ec8bfafa765e77fe6b62cac7334fd29`.
`git fetch origin` confirmed the remote feature branch at that SHA before device
work. The existing detached 3A checkout matched it and was clean. Production
sources and build configuration were not changed.

The installed `dev.fluentmai.android.validation` APK was read back before and after
the session. Both SHA-256 values matched the required final binary:
`cebb50dd116fb94ebfa166fe4c64054816e9b2fcadb7684f5cfcdb05aabf6ceb`.
No build or installation was needed. Version remained 0.3.0-beta / 14;
first installation 2026-10-03 11:15:16 and last update 2026-10-05 01:24:07
were unchanged. All device commands selected only the verified 24018RPACC tablet.
The Owner phone was not accessed. PR #7 was OPEN and Draft at preflight and
post-session readback.

### Actual attempt sequence

There was **one logical import session and two generated fresh OAuth transactions**.
The first authorization was copied using the product UI, sent only to WeChat's
File Transfer Assistant self-chat, and opened there. The second was generated by
the product's `重新授权` action. It was not opened after the capture wait ended.
Existing product logs established stages; no observer, debugger, synthetic callback,
fault injection, header/Cookie differential or renewed root-cause work was used.

| Attempt | Fresh authorize generated | Callback replay count and result | Home decision | Product state |
| --- | --- | --- | --- | --- |
| 1/3 | 01:46:29.679 | **1**; request 01:51:21.607, response 01:51:22.414, HTTP 404, attempt 1/1, willRetry=false | 01:51:25.606; HTTP 200, `WahlapAuthorizationRetryRequiredException`, unauthenticated | AUTH_RETRY_AVAILABLE; screen 1/3, `重新授权` offered; capture stopped and no validation services remained |
| 2/3 | 01:52:10.334, following retry click at 01:52:07.632 | **0**; no captured callback or replay observed | UNREACHED | AUTHORIZING at 2/3, then FAILED with `等待微信授权超时，请重新启动捕获` |
| 3/3 | NOT GENERATED | NOT ATTEMPTED | UNREACHED | Not reached in this session |

An execution delay between handling the second authorize entry and completing the
WeChat interaction exceeded the existing **10-minute capture wait**. The next device
interaction was at approximately 02:08; by the 02:09 readback the service had ended
and the UI reported capture timeout. The exact timeout-transition timestamp was not
logged. The error matches the existing waiting timeout in
`ImportForegroundService`; this is an interrupted acceptance run, **not a confirmed
final-binary defect**. No OAuth interaction was started in the 04:00–07:00 maintenance
window. The unused second entry was removed from the self-chat input without sending
or opening it after timeout.

No stale/concurrent event issue was observed during the completed first rejection
or the normal start of attempt 2. Deliberate races and stale callbacks were not
manufactured. This incomplete sequence does **not** provide hardware qualification
of the final 3/3 ceiling, old-service overlap, or contested concurrent callbacks.
No third or fourth transaction was generated; the no-automatic-fourth boundary was
not exercised at 3/3. Starting again would create another logical session, outside
the original one-session authorization; it was not done.

### Import and hardware disposition

**Authenticated Home reached: NO.** One Home decision was observed and rejected;
attempt 2 never reached Home. Recent records, BASIC, ADVANCED, EXPERT, MASTER,
RE_MASTER, supplemental/rating target, PC, parsing, new import persistence and final
COMPLETE/PARTIAL outcome were all **UNREACHED**. Score retry recovery and PARTIAL
persistence were not observed.

Retained hardware disposition:

- `AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED`

Not awarded in 3B:

- `FINAL_BINARY_BOUNDED_FRESH_AUTH_RETRY_VALIDATED`
- `AUTH_NET_COMPLETE_IMPORT_VALIDATED`
- `REAL_DEVICE_RETRY_RECOVERY_VALIDATED`
- `REAL_DEVICE_PARTIAL_PERSISTENCE_VALIDATED`

The older initial-APK 3A flow marker remains historical evidence only. The exact
final-APK acceptance gap remains open because 3B did not complete three auth
rejections or an authenticated import.

### Data preservation and cleanup

The control `dev.fluentmai.android` was not launched, installed over, uninstalled or
cleared. Before/after APK readbacks both had SHA-256
`d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`;
version 0.2.9-beta / 13 and first-install/last-update time
2026-09-22 11:46:51 were unchanged. Its private database was not read or hashed;
byte-identical private control data is **not claimed**.

Read-only copies of the validation Room database and its available WAL files were
taken before the first fresh authorize and after terminal failure. Aggregate results
were **1,730 score records, 1 import batch, 2 quarantine records** both times.
The sorted complete import-batch rows had the same SHA-256 digest in both local
snapshots. Thus no new or duplicate import batch was created by this auth retry.
These measurements do not claim byte-identical state for the entire database.
No package data clearing or uninstall was performed.

After the first rejection and terminal timeout, `dumpsys activity services` for the
validation package reported `(nothing)`. The terminal UI reported `Capture stopped.`;
VPN management reported active VPN type -1 with null session/underlying networks.
Listener state is UNOBSERVED: the optional socket listing was denied by Android
(`Cannot open netlink socket: Permission denied`). Service, UI and VPN-state checks
above establish the observed cleanup; no force-stop was used to produce them.

**Disposition: acceptance incomplete due to execution delay/capture timeout.**
Neither `READY_FOR_NEXT_PRODUCT_PHASE` nor
`BLOCKED_BY_SPECIFIC_FINAL_BINARY_DEFECT` is supported by this run. No production
follow-up implementation is warranted by the measured timeout. A replacement
acceptance session requires an explicit Owner change to the session/budget limits;
it cannot be silently substituted for this run. Keep PR #7 **Draft / WIP / NOT
ACCEPTED / NOT FOR RELEASE**. Report delivery is Owner manual only; no ChatGPT or
CTO relay was opened. Private APKs, logs, database copies, UI XML, screenshots,
device serials, OAuth values and local paths are excluded from Git.

## Changed files and delivery

Production app files under `app/src/main/kotlin/dev/fluentmai/android/`:

- `ImportForegroundService.kt`, `ImportTaskState.kt`, `MainActivity.kt`
- `WahlapAuthorizationRetry.kt`, `WahlapAuthCaptureStore.kt`
- `WahlapHookBridge.kt`, `WahlapHookHttpService.kt`
- `WahlapHttpScorePageClient.kt`, `WahlapImportHttpClient.kt`
- `WahlapImportRunner.kt`, `WahlapWechatAuthUrlClient.kt`

UI: `feature/import/src/main/kotlin/dev/fluentmai/android/feature/importflow/ImportScreen.kt`.
Tests under `app/src/test/kotlin/dev/fluentmai/android/`:
`BoundedFreshAuthServiceTest.kt`, `WahlapOAuthAttemptTest.kt`, `ImportForegroundServiceTest.kt`,
`WahlapHookBridgeTest.kt`.
Documentation in this directory: `AUTH-NET-2L-R2.md`, `INDEX.md`, `AUTH-NET-3A.md`.

`git diff --check` passed. Gitleaks 8.30.1 working-tree scans found zero leaks;
the final commit-range scan and push receipt are recorded in the Owner delivery
report. Raw logs, UI XML, screenshots, device identifiers, local
paths, private init scripts and APKs are excluded from Git.

Recommendation: keep PR #7 **Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE**.
Next acceptance work should be one separately authorized bounded session on the
exact final APK in a future usable authentication window, continuing the entire
real import if Home authenticates. Do not resume broad client-side root-cause
forensics or spend a fourth transaction in this session.

## Evidence boundary

No additional ART/JDI observer, header/Cookie differential, synthetic callback-route
probe or nginx/cache investigation was performed. No Owner-phone operations or CTO
relay are part of this task. Private runtime artifacts are excluded from Git.
