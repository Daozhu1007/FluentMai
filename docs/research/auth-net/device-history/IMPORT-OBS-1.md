# IMPORT-OBS-1 — Import timing and sanitized diagnostic export

Date: 2026-10-09. Repository: `Daozhu1007/FluentMai`.
Delivery branch: `auth-net-1-v030`. PR #7 remains **Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE**.
Starting remote HEAD was fetched and matched
`349f14e598c02d32809c9671a73aa109ae01ef10` exactly. Implementation used a clean,
independent worktree. The final delivery SHA is provided in the Owner report and
Git history; this document does not contain a self-referential commit hash.

## Feature and architecture

The existing Import screen now includes **最近一次导入诊断**, with the terminal
outcome, import/execution durations, separate OAuth wait, stage timings, longest
observed stage, request retry/failure summaries, aggregate parsed/saved/quarantined
counts, **导出诊断报告**, and **复制诊断摘要**. Current progress and the preceding
report have separate labels, including while quick-auth is preparing permission.
Unobserved requests and counts are displayed as unavailable, never fake zeroes.

```text
ImportForegroundService (existing execution owner)
  -> ImportDiagnosticCollector (one execution, monotonic clock, bounded aggregates)
     <- WahlapImportRunner stage scopes (both OAuth and Cookie modes)
     <- categorized HTTP attempt callbacks (both clients, no log parsing)
     <- narrow parser / database-call observer hooks
     <- read-only QuickAuthTiming milestone snapshot
     <- existing terminal ImportTaskState and RealWahlapImportResult
  -> explicit ImportDiagnosticJson allowlist codec
  -> ImportDiagnosticStore latest app-private snapshot
  -> existing ImportScreen / ImportDiagnosticPanel
  -> user action -> pinned snapshot -> Android CreateDocument(application/json)
     -> ContentResolver output stream
```

`core:model/ImportDiagnosticReport.kt` is the typed public aggregate model. It has
no credentials, ownership identifiers, account IDs, source/batch strings, record
objects, diagnostic log text, throwable messages, URLs, or filesystem paths.
`core:importer/ImportTimingObserver.kt` supplies only stage enter/exit and existing
outer-recovery notifications. `ImportDiagnosticCollector`, the codec, private
store, and document exporter live in the Android app. The presentation stays in
the existing import feature and styling. No dashboard, analytics, upload service,
Room migration, version bump, or extra import batch was introduced.

## Instrumentation and timing semantics

Elapsed timing uses `SystemClock.elapsedRealtimeNanos()` through an injected
`ImportMonotonicClock`. The real clients' existing retry engine receives the same
platform monotonic clock converted to milliseconds. Tests inject fake clocks.
The existing quick-auth clock remains monotonic `elapsedRealtime`; its previously
recorded fixed milestones are copied, not reconstructed from log timestamps.
Wall time is used only for report creation and the suggested filename.

| Stage / evidence | Exact boundary |
| --- | --- |
| LOGIN_HOME | Existing `client.login` or `validateLogin`, including Home validation and optional player enrichment |
| CALLBACK_PROCESSING | Existing OAuth callback request/validation block nested inside LOGIN_HOME; unavailable for Cookie |
| SONG_CATALOG | Existing catalog fetch, including decoding; fallback remains unchanged and failed observation is retained |
| RECENT_RECORDS | Existing recent-history capture, including its waits, parsing, saves, and recovery loops |
| BASIC, ADVANCED, EXPERT, MASTER, RE_MASTER | Each existing difficulty fetch/validation plus PC-index preparation; retries/backoff included |
| SUPPLEMENTAL | Existing supplemental fetch loop and validation; returned source failures mark this family failed |
| PC_CAPTURE | Existing efficient/full-PC capture, including pagination/detail recovery, waits, parsing, saves, and target refresh |
| PARSING | Actual score/mixed-page parser calls, recent records, PC index/target parsing, and PC count/ranking parser calls |
| DATABASE_PERSISTENCE | Existing persistence lookup, score/quarantine/batch writes, recent-record and PC writes; the same operations and ordering |
| Quick-auth milestones | Requested, capture ready, authorize generated, clipboard ready, public WeChat dispatch, callback captured, authenticated Home, auth rejected; fixed offsets from Requested |

These measure operation boundaries, not isolated server latency, wire bytes,
CPU time, or time inferred by subtracting unrelated log completions. Parsing is
the sum of the hooked parser calls; auxiliary shape checks/pagination, catalog
decoding, validation/deduplication, repository reads outside those calls, and
other orchestration remain in their enclosing family or unattributed overhead.
The catalog's own HTTP attempt/status details are **unobserved**; its complete
preparation scope is timed. Authorize HTTP attempt details are also unobserved;
existing quick-auth milestones still identify generation latency. Manual OAuth
handoff has no quick-auth milestone snapshot. Unreached milestones/stages and
unknown response/status facts have `null` measurements rather than zero.

`total_duration_ms` starts when callback processing / Cookie execution is
dispatched and ends at terminal report capture. It includes login and client/DB
cleanup, but excludes the OAuth waiting period and the diagnostic file write and
subsequent service/notification teardown. `authorization_wait_duration_ms` is
service admission to callback dispatch (or termination before callback).
`execution_duration_ms` is service admission to terminal capture. Permission UI
before service admission is excluded; quick-auth offsets include that period
when its original Requested event was observed. These are deliberately separate
timelines. A failed login can have a measured total with no score-import outcome.

Stage `duration_ms` is inclusive. `exclusive_duration_ms` excludes measured child
scopes; only exclusive durations can be summed. Unattributed overhead is total
minus the sum of exclusive milliseconds, including sub-millisecond rounding
residue. Request-attempt time is nested inside stages and must never be added to
them. Timing observation is COMPLETE, FAILED, INCOMPLETE, or UNOBSERVED; the
stage's observation is distinct from the import outcome. Cancellation closes open
scopes at one common endpoint as incomplete and ignores late worker events.

## Request accounting and authoritative outcomes

The clients forward `WahlapResilientFetcher.onAttempt` with a typed request
category and exact-match allowlisted label. Its legacy `category` and `errorType`
strings are ignored. Each completed attempt increments attempts. An actually
executed attempt numbered greater than one increments `retry_count`; a scheduled
retry that is cancelled before execution is not counted. Successful requests and
final failed requests are counted independently; HTTP status frequencies and
decoded character counts are numeric aggregates. Callback transport success with
a non-2xx status remains transport success; its status and the authoritative Home
decision are separate evidence. Character counts sum observed decoded responses,
not wire bytes or necessarily every response. A final attempt without an observed
status has `last_http_status: null`.

Existing `retryActivityFetch` and detail-link refresh are a separate recovery
layer. They increment stage `recovery_retry_count`, while each restarted HTTP
fetch keeps its own existing attempt numbering. Recovery and HTTP retry counts
are not combined. PC ranking/detail requests aggregate by fixed stage/category/
label, with no per-song identifiers or event stream.

The report reads `ImportTaskState.complete` / `succeeded`, including page failure,
recent/PC warnings, and the adapter's authoritative outcome. It does not replace
completion criteria. COMPLETE requires the existing product checks; useful
persisted results with missing sources produce PARTIAL; no current useful import
result produces FAILED. Old database records cannot turn a failed current run
into PARTIAL. Existing upserts retain failed difficulties' preceding records.

An OAuth rejection before score import has `termination: AUTH_REJECTED` and
`outcome: null`; retry availability is not an import outcome. A typed rejection
from Cookie Home validation is also identified separately, while existing task
state, error handling, and retry policy stay unchanged. Cancellation and process
interruption use CANCELLED / INTERRUPTED with no success outcome. Counts are
available only after the relevant result was observed. If an exception interrupts
a multi-call save before a result returns, diagnostic counts remain unknown;
already-written data is not guessed or modified.

## Privacy, bounds, retention, and export

The serializer enumerates every export key. All exported textual values are
fixed enums/identifiers or the exact build-owned app version; a supplied version
string is replaced with UNAVAILABLE unless it exactly matches. Report timestamps
are numeric. No field is populated by joining logs, exception strings, raw
`ImportDiagnostics`, reflection, or object dumps. Existing redaction and legacy
in-memory error display remain separate; the new export does not rely on them.
No URLs (even query-free ones), headers, bodies, HTML, OAuth values, Cookie/token
input, WeChat content, personal records, device identifiers, or private paths
cross the schema boundary.

Bounds: fixed stage/event sets; at most 64 stage/category/label request aggregates;
at most eight status counters per aggregate; numeric counters at most 10^12;
durations at most seven days; each JSON file at most **48 KiB UTF-8**. Oversized
detail is truncated with `metrics_bounded: true` and incomplete request coverage.
Oversized/corrupt/unsupported stored files cannot become an exportable report.

The app-private, no-backup directory holds one latest terminal report, one minimal
active/interruption marker, and one report pinned only for a requested document
save. This is not a history. Writes sync a bounded sibling `.new` file and use
same-directory `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (minimum API 26), without
falling back to a torn replacement. Incomplete pending writes are discarded at
startup. Runtime ownership stays internal; stale executions cannot replace a
newer report. Activity recreation does not reset progress or the report.

On process restart, a durable active marker becomes INTERRUPTED with unknown
durations and counts, superseding stale success. There is intentionally no
attempt to resume credentials or rebuild COMPLETE. A crash between final file
replacement and marker removal can conservatively report interruption; it cannot
manufacture success. Storage failures are surfaced in the UI; an unsuccessful
terminal replacement retains the interruption marker for a later restart.

The export button is disabled with an explanation when no snapshot exists. A
user click pins the chosen sanitized report and opens the standard Android
document-save flow, using `application/json` and
`FluentMai-import-diagnostic-YYYYMMDD-HHMMSS.json`. It needs no external-storage
permission. The result callback loads the pinned snapshot, including after
recreation; a newer import cannot change the selected file's contents. Bounded
output writes run on IO and survive Activity disposal. Provider/write failures
show a fixed error message and never claim success. Nothing is uploaded.

## Synthetic sample

[IMPORT-OBS-1.synthetic.json](IMPORT-OBS-1.synthetic.json) is a **generated,
synthetic documentation fixture**, validated by the production codec. Its
durations/counts and PARTIAL outcome are invented examples, not device evidence.
No fixture seeds a production report or fabricates hardware COMPLETE acceptance.
The sample has 3,100 ms total, 2,900 ms exclusive stages, and 200 ms unattributed
overhead; it illustrates a recovered MASTER request and failed rating target.

```json
{
  "schema_version": 1,
  "app_version": "0.3.0-beta",
  "import_mode": "MANUAL_COOKIE",
  "outcome": "PARTIAL",
  "termination": "FINISHED",
  "total_duration_ms": 3100,
  "authorization_wait_duration_ms": null,
  "unattributed_duration_ms": 200
}
```

This excerpt is abbreviated; the linked file contains the complete schema.

## Deterministic verification

No test calls Wahlap or WeChat services. HTTP integration uses only a local
loopback server; importing uses existing synthetic fixtures, fake monotonic
clocks, and test-only persistence. The sample is decoded by the production codec.

| Required evidence | Test coverage |
| --- | --- |
| Stage clock, multiple difficulties, nesting, overhead | ImportObservabilityTest fake clock and real synthetic adapter/pipeline |
| First-attempt success, HTTP retry timing/count, exhausted retries | Injected existing retry engine; both real client observer paths against loopback |
| Supplemental failure, large PC request sets, parser/DB timing | Synthetic adapter, 10,000-page aggregate, actual observer and persistence boundaries |
| COMPLETE / PARTIAL / FAILED and no deletion / no extra batch | Existing task criteria plus synthetic upsert preservation and failed-run fixtures |
| OAuth/Cookie rejection before score import; cancellation | Typed rejection snapshots and incomplete scopes; existing bounded auth service suites |
| Recreation, stale execution, process interruption, atomic write | ImportDiagnosticAndroidTest store recovery, late ownership, unfinished replacement |
| Export all outcomes, stable JSON, recreation during picker | Production exporter/ContentResolver, actual Compose launcher callback after Activity destruction and saved-state recreation |
| Sensitive injection, oversized inputs, no raw secrets | Every exported string value (43 positions × eight attacks), legacy attempt/exception/result injections, 100,000 input records, worst-case bounded aggregates |
| Screen state/actions | ImportDiagnosticPanelTest runtime Compose semantics in Debug and Release |
| AUTH-NET-4A and UX-BG-1 | Existing QuickAuth*, BoundedFreshAuthService, ImportBatteryGateRegression, ImportBackgroundAccess, ImportForegroundService, and runner suites |

Final commands and results are recorded in the delivery checks below. The initial
full run exposed a Release-only missing Compose test-host manifest; adding the
manifest to test dependencies covers both variants and changes no production
request behavior. The final complete suite is the acceptance evidence.

## Device and lifecycle boundary

Device smoke was **omitted**. No ADB/device access, install, uninstall, data clear,
APK replacement, real OAuth, or settings change was performed. The production
APK builds and the document contract/picker recreation path have deterministic
Android/Robolectric evidence. Hardware UI/save-provider acceptance remains
**pending**, and no historical exact-APK marker is promoted to this binary.

The [UX-BG-1](UX-BG-1.md) residual non-foreground ImportForegroundService record
and terminal notification remain an existing, separate lifecycle issue. This
task adds report capture at existing termination points and deliberately leaves
capture cancellation, stopSelf, notification and service lifecycle semantics
unchanged. It introduces no wake lock, service, retry, or import ownership path.
The three-attempt fresh-auth budget, generation/callback semantics, immediate
4A clipboard/public-launch handoff, HTTP timeout/retry policy, parser decisions,
and persistence behavior remain intact. No real OAuth acceptance was repeated.

No merge, release, version bump, ChatGPT opening, CTO message, or auto relay.
The Owner receives and manually relays this report.

## Changed file manifest

Production Kotlin (19):

```text
app/src/main/kotlin/dev/fluentmai/android/ImportDiagnosticCollector.kt
app/src/main/kotlin/dev/fluentmai/android/ImportDiagnosticExport.kt
app/src/main/kotlin/dev/fluentmai/android/ImportDiagnosticJson.kt
app/src/main/kotlin/dev/fluentmai/android/ImportDiagnosticStore.kt
app/src/main/kotlin/dev/fluentmai/android/ImportForegroundService.kt
app/src/main/kotlin/dev/fluentmai/android/MainActivity.kt
app/src/main/kotlin/dev/fluentmai/android/QuickAuthCoordinator.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapActivityCapture.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapEfficientPcCapture.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapHttpScorePageClient.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapImportRunner.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapManualCookieScorePageClient.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapPlayCountCapture.kt
core/database/src/main/kotlin/dev/fluentmai/android/core/database/FluentMaiRepository.kt
core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/ImportTimingObserver.kt
core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/RealWahlapImportAdapter.kt
core/model/src/main/kotlin/dev/fluentmai/android/core/model/ImportDiagnosticReport.kt
feature/import/src/main/kotlin/dev/fluentmai/android/feature/importflow/ImportDiagnosticPanel.kt
feature/import/src/main/kotlin/dev/fluentmai/android/feature/importflow/ImportScreen.kt
```

Test configuration (1):

```text
feature/import/build.gradle.kts
```

Deterministic tests (3):

```text
app/src/test/kotlin/dev/fluentmai/android/ImportDiagnosticAndroidTest.kt
app/src/test/kotlin/dev/fluentmai/android/ImportObservabilityTest.kt
feature/import/src/test/kotlin/dev/fluentmai/android/feature/importflow/ImportDiagnosticPanelTest.kt
```

Documentation and synthetic sample (4):

```text
docs/PRIVACY_MODEL.md
docs/research/auth-net/device-history/IMPORT-OBS-1.md
docs/research/auth-net/device-history/IMPORT-OBS-1.synthetic.json
docs/research/auth-net/device-history/INDEX.md
```


## Delivery checks

Verdict: **IMPORT_OBS_1_IMPLEMENTED_READY_FOR_OWNER_REVIEW**.
Hardware UI/save-provider acceptance remains pending. This is engineering
implementation readiness, not release or PR acceptance.

Targeted app command (a private init script supplies the short Windows test temp
root; no tracked Gradle runtime settings were changed):

```text
:app:testDebugUnitTest --tests '*ImportObservabilityTest' --tests '*ImportDiagnosticAndroidTest' --tests '*ImportForegroundServiceTest' --tests '*QuickAuth*' --tests '*BoundedFreshAuthServiceTest' --tests '*ImportBatteryGateRegressionTest' --tests '*ImportBackgroundAccessTest' --tests '*WahlapImportRunnerTest'
```

**102 passed / 0 failed / 0 errors / 0 skipped, nine suites; BUILD SUCCESSFUL.**
Panel-targeted command: `:feature:import:testDebugUnitTest --tests '*ImportDiagnosticPanelTest'`:
**3 passed / 0 failed / 0 errors / 0 skipped; BUILD SUCCESSFUL.** The full suite
also passes those panel methods in Release.

Full command: `test :core:model:jvmTest :app:assembleDebug --continue`:
**775 passed / 0 failed / 0 errors / 1 skipped; 776 entries in 135 suites;
BUILD SUCCESSFUL.** These are retained Debug/Release and JVM executions, not 776
unique methods. The sole skip is the existing optional
`JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`,
whose live snapshot fixture was unset. Debug APK assembly passed; no APK was
installed. There are 37 new deterministic methods (24 collector/schema/privacy,
ten Android storage/client/export, three Compose presentation methods).

Privacy injection: **344 exported-string mutations rejected or safely dropped**,
plus legacy attempt category/error-type, arbitrary exception/result diagnostics,
URLs, OAuth parameters, Cookie/Authorization, HTML, token sentinels, and private
path payloads. No injected sensitive content appears in exported JSON. Oversized
100,000-record/list inputs, 10,000 PC request aggregates, corrupt/oversized stored
files, and the maximum aggregate/status-detail combination all pass bounds checks.
The documentation sample decodes with the production schema and balances its
exclusive-stage totals and unattributed overhead.

Scope preservation compared against the exact starting SHA: 13 critical source
files remain unchanged, including QuickAuthRuntime, OAuth generation/attempt/
capture storage, ImportTaskState, retry/timeouts, RoomImportPersistence,
FakeImportPipeline, battery-access code, app build/version, and manifest. Eight
critical admission/handoff/cancellation/notification method bodies also match.
No Room schema, deletion, batch construction, retry policy, auth budget, battery
exemption fix, launch behavior, wake-lock ownership, or notification policy changed.

`git diff --check` and staged diff checks pass. Gitleaks **8.30.1** scoped source
and delivery-commit scans pass with **zero findings**. The source scan covers the
27 changed production/configuration/test/documentation/sample files and excludes
build caches. All delivery changes are confined to the feature, its narrow
observers, regression tests, privacy documentation, this report/sample and index.
Git delivery is a normal fast-forward push to `origin/auth-net-1-v030`; PR #7
remains open Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE. The exact delivery SHA
and final remote/PR verification are returned to the Owner manually.
