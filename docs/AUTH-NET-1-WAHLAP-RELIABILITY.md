# AUTH-NET-1 — Wahlap Import Reliability Hardening (v0.3.0 port)

Status: implemented on branch `auth-net-1-v030` (worktree `D:\Code\FluentMai-authnet-v030`), awaiting
owner real-device validation (Xiaomi 23116PN5BC / Android 36).
Baseline: canonical `d734660719f94db523af1053bbc915bdc76d7c19` ("Restore complete scores screen for
v0.3.0 Beta", versionCode 14). Scope: captured-auth import and manual-cookie import Wahlap
networking, import result semantics, and their integration with the v0.3.0 background import
(`WahlapImportRunner` / `ImportForegroundService` / `ImportTaskState`). No product/UI behavior
beyond the explicitly required partial-success reporting was changed.

Provenance: this is a manual port of the legacy AUTH-NET-1 implementation `c8efad91`
(branch `auth-net-1-oldbase`), which was built on the stale base `6aa6baf` and is **reference
material only** — it was NOT cherry-picked and must not be pushed or built.

## 0. Correction of record — OAuth callback topology

The original AUTH-NET-1 report (committed with `c8efad91`) claimed:

> "The WeChat browser's own callback request reaches Wahlap first, and the app then replays the
> same URL."

**That claim is retracted permanently. It was incorrect.** The verified topology is:

```
authorize flow rewrites redirect_uri to http
→ browser requests tgk-wcaime.wahlap.com:80          (plaintext HTTP)
→ LocalVpnService / TunnelFactory intercepts Wahlap port 80
→ HttpCapturerTunnel upstream is 127.0.0.1:9457      (local redirect service)
→ local redirect service returns 202                 ("登录信息已捕获…")
→ WahlapHookBridge captures the callback URL/request headers
→ FluentMai performs the FIRST real HTTPS callback request to Wahlap
```

The intercepted HTTP callback therefore **terminates at the local capture server and never reaches
Wahlap**; the app's HTTPS replay is the first (and only) real callback request. Wahlap cannot have
seen the code before the replay. The corrected consequences:

- The app's replay carries full responsibility for presenting the single-use `code` with a
  coherent session context; there is no "browser got there first" failure mode.
- The legacy doc's §1.2 finding 1 (server-side replay-tolerance inference) is withdrawn; see
  §2.2 for the corrected account of what is proven vs. hypothesized.

(Also corrected from the legacy doc: the "baseline note" claiming `d734660` / background-import
infrastructure "do not exist in this repository" was an artifact of the local clone being unable
to fetch. The canonical v0.3.0 baseline exists, was fetched during the baseline rescue, and is the
base of this port. See `AUTH-NET-1-BASELINE-RESCUE.md` and `AUTH-NET-1-V030-PORT.md`.)

## 1. Root cause findings

### 1.1 Evidence #1 — EXPERT/MASTER score pages time out at 30s (proven)

Observed on a real device (2026-10-02): auth and home OK, BASIC/ADVANCED/RE_MASTER score pages
OK, EXPERT and MASTER failed with `HttpRequestTimeoutException` at `request_timeout=30000 ms`;
afterwards rating pages and all five PC mybest requests succeeded; 294 records parsed from the
three successful difficulties, 0 persisted.

Root cause: a single global 30s request timeout applied to every request. The score pages
(`/maimai-mobile/record/musicSort/search/?search=A&sort=1&playCheck=on&diff=N`) return the full
HTML listing of every played chart at that difficulty (~100 song cards ≈ hundreds of KB for
EXPERT/MASTER). Wahlap's server latency for those pages on real mobile networks can exceed 30s
while every smaller page succeeds — exactly the observed pattern. With no retry and with the
adapter's all-or-nothing persistence, two slow pages discarded the whole run.

Contributing architecture defect: `RealWahlapImportAdapter.importFetchedPages` aborted persistence
for *all* parsed records when even one difficulty fetch failed (`inserted/updated: 0` despite 294
parsed records).

### 1.2 Evidence #2 — intermittent auth failure (callback 404, home error 100001)

Observed across multiple releases: auth → HTTP 404 on
`tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx`, home → 200 but
`/maimai-mobile/error/?errorCode=100001`, then "home page is not authenticated".

**Proven (architecture):** `WahlapKtorClient` was a process-global object with a process-global
`AcceptAllCookiesStorage` that nothing ever cleared. Session cookies from an earlier import (or
authorize-URL generation) stayed in the jar for the lifetime of the process and were attached to
the *next* run's authorize request and callback replay. This cross-import state contamination is
real, reproducible from the code alone, and is a credible mechanism for intermittent
code/session conflicts.

**Hypothesis (unproven):** whether this contamination is what causes the observed 404 → 100001
pattern, and whether eliminating it fixes owner-device occurrences. The 404/100001 root cause
remains **unproven**; other candidate mechanisms include the single-use `code`/`state` expiring
between capture (WeChat browser hits the local redirect service) and the app's replay (e.g. long
delays inside WeChat), and genuine server-side flakiness. Per-import session isolation below is
accurately described as **eliminating a proven contamination source and testing a leading
hypothesis** — not as a guaranteed fix.

### 1.3 Ktor CIO connection handling after a request timeout (verified, unchanged from legacy)

Verified against Ktor 3.0.1 sources: `pipelining` defaults to false and FluentMai never enables
it; with pipelining off every request takes a dedicated connection; sockets are closed on call
completion including timeout-triggered cancellation; `HttpRequestTimeoutException` extends
`IOException` (not `CancellationException`) in Ktor 3.0.1. Conclusion: no connection-level retry
handling is needed; each retry attempt opens a fresh TCP+TLS connection; the retry loop re-checks
`coroutineContext.ensureActive()` after every caught error so real caller cancellation is never
swallowed.

## 2. Architecture changes (on v0.3.0)

| Before (v0.3.0 `d734660`) | After (this port) |
| --- | --- |
| `WahlapKtorClient` process-global object with global `AcceptAllCookiesStorage`, `pendingAuthCookies`, `authReplayHeaders` | Deleted. Replaced by per-import `WahlapImportHttpClient` (own `AcceptAllCookiesStorage` + own captured-auth state) and a process-wide `WahlapAuthCaptureStore` handoff that is **consumed once per import** |
| One global client shared by auth-url generation, login, import, player-home enrichment, activity/PC captures | `WahlapWechatAuthUrlClient` uses its own throwaway client per call; each import run builds one client and closes it in `finally`; all page fetches (including `enrichWahlapPlayerHome`, activity pages, music details) go through the run's client |
| `REQUEST_TIMEOUT_MS = 30_000` for everything | Per-category timeout profiles (`WahlapRequestCatalog`) applied per request via Ktor's per-request `timeout {}` |
| No retry for anything | `WahlapResilientFetcher` in `core:importer`: bounded, classified, backoff+jitter retries for idempotent categories; auth categories are single-attempt |
| Adapter aborts all persistence if any difficulty fails | Partial-safe persistence with explicit `WahlapImportOutcome` (`COMPLETE`/`PARTIAL`/`FAILED`) |
| `ImportTaskState.succeeded` required `failedDifficultyCount == 0` | Outcome-based: a PARTIAL import (records persisted, some difficulties failed) reports as succeeded → the existing `ImportRunStatus.PartialSuccess` UI state; `complete` additionally requires `outcome == COMPLETE` |

Preserved v0.3.0 behavior (deliberately kept, verified by the existing suites):

- Background import lifecycle: `ImportForegroundService` → `WahlapImportRunner` →
  `RealWahlapImportAdapter`, progress reporting via `ImportPageReporter`, wake lock, notifications.
- `pendingAuthCookies` seeding (captured browser `Cookie` header seeded into the import client's
  cookie storage when the callback URL is requested) — now sourced from the consumed
  `WahlapAuthCaptureStore` state instead of a global map.
- WeChat UA handling: default `WX_ANDROID_UA`; once a callback is captured, the captured browser
  UA presents for all requests of that import run (v0.3.0's global replay map behaved the same
  way, scoped here to the run instead of the process); the manual-cookie client keeps its
  credentials-header override rules. (The unused `WX_WINDOWS_UA` constant of the old global
  client was not carried over.)
- Activity page rules: `Accept-Encoding: identity`, `Referer` (home or music-detail referer),
  `Sec-Fetch-Site: same-origin` for activity/detail URLs.
- PC synchronization: efficient-PC and full-PC paths, play-count index, refresh rules.
- JP constants, B50 player capture (`B50PlayerStore`), favorites, in-app updates, the restored
  Scores screen, diagnostics capture (`ImportDiagnostics`), versionCode/versionName (untouched).

New/changed modules: `core:importer` stays free of Ktor; the resilience engine is pure Kotlin
(coroutines only) and fully unit-testable. `core:importer` gained
`implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")`.

## 3. Timeout policy

Per-request timeouts (`WahlapRequestCatalog.timeoutProfile`), applied via Ktor per-request
`timeout {}` so the largest pages get a larger budget without loosening anything else:

| Category | Request timeout | Connect timeout | Rationale |
| --- | --- | --- | --- |
| `AUTH_AUTHORIZE` | 30s | 15s | Small redirect chain; single attempt |
| `AUTH_CALLBACK` | 30s | 15s | Small response; single attempt (single-use code) |
| `LOGIN_HOME` | 30s | 15s | Small page |
| `SCORE_PAGE` | **60s** | 15s | Doubled from 30s: EXPERT/MASTER listings exceeded 30s on real devices; 60s gives 2x headroom, retries cover worse stalls |
| `SUPPLEMENTAL_PAGE` | 45s | 15s | Medium rating pages; also used for activity pages, music details, and player-home enrichment fetches |

Connect timeout was tightened from 30s to 15s: connect either succeeds quickly or the network
path is unusable; failing fast feeds the bounded retry loop sooner. The request timeout spans the
whole call (send + await response + read body), which is what the old 30s policy controlled.

## 4. Retry policy

Implemented in `WahlapResilientFetcher` (`core:importer`), selected by
`WahlapRequestCatalog.retryPolicy`:

| Category | Attempts (total) | Backoff |
| --- | --- | --- |
| `AUTH_AUTHORIZE` | 1 | none — deliberately never retried |
| `AUTH_CALLBACK` | **1** | **none — deliberately never retried** (single-use OAuth code) |
| `LOGIN_HOME` | 3 | 1s, 2s (±20% jitter) |
| `SCORE_PAGE` | 3 | 1s, 2s (±20% jitter) |
| `SUPPLEMENTAL_PAGE` | 2 | 1s (±20% jitter) |

Classification (`WahlapRetryClassifier`, walks the exception cause chain to depth 5 so context
wrappers do not hide markers):

- Retryable: `IOException` (socket timeouts, connect failures, Ktor
  `HttpRequestTimeoutException`), `WahlapHttpStatusException` with status in
  {408, 429, 500, 502, 503, 504}.
- Non-retryable: `WahlapNonRetryableException` (auth-failure page markers, unexpected content
  type), `WahlapHttpStatusException` with any other status (e.g. 404), all non-IO exceptions.
- A score page that is 2xx but fails the score-page shape check is retried (bounded): a
  transiently truncated/garbled response is plausible, while an auth-failure page throws
  `WahlapAuthFailurePageException` and is not retried.
- Activity/music-detail content validation (`validateActivityResponse`) stays **outside** the
  retry loop (as in v0.3.0): only HTTP-status and IO failures are retried for those pages, and
  error-page diagnostics surface unchanged.
- Cancellation: `CancellationException` is always rethrown; after every caught error the fetcher
  verifies `coroutineContext.ensureActive()` before scheduling a retry.
- The OAuth authorize/callback requests are intentionally excluded from every retry path: the
  callback carries a single-use `code`, and the authorize request starts an authorization that a
  human can simply re-trigger by tapping the hook link again.

## 5. Partial persistence semantics

`RealWahlapImportAdapter.importFetchedPages`:

- Fetch all five difficulties first (unchanged order); parse failures and fetch failures are
  recorded per difficulty.
- If at least one record was parsed (successful difficulties and/or supplemental pages), the
  pipeline persists **exactly the parsed records** and writes an `ImportBatch`. The pipeline only
  upserts fetched records and never deletes, so a difficulty whose fetch failed keeps its
  pre-existing local scores untouched — a partial network failure can no longer invalidate
  existing local data.
- If nothing at all could be fetched/parsed while failures exist, the run aborts with
  `rejected = failure count` and writes nothing (pre-existing behavior for total failure).
- A failure-free run with zero parsed records (empty account) still completes and writes its
  batch (preserves the v0.2.5-beta.3 "accept empty score pages" fix).
- `RealWahlapImportResult.outcome`:
  - `COMPLETE` — every difficulty and supplemental page fetched, all records persisted.
  - `PARTIAL` — records persisted but at least one difficulty or supplemental page failed.
  - `FAILED` — nothing fetched/parsed; nothing persisted.
- Integration with the v0.3.0 background import (no competing UI state machinery):
  `ImportTaskState.succeeded` is now outcome-based, so a PARTIAL result drives the existing
  `ImportRunStatus.PartialSuccess` UI state and the "成绩已导入，部分数据未完整同步" completion
  title; `complete` requires `outcome == COMPLETE` (plus the existing pageFailed/warning checks).
  The failure list remains visible, and the automatic rating-snapshot refresh runs for COMPLETE
  and PARTIAL (a partial import still changed local scores). The outcome is deliberately **not**
  persisted into `ImportBatch`/Room (would require a schema migration).

## 6. Auth/session lifetime — before and after

Before (v0.3.0):

- One process-global Ktor client + `AcceptAllCookiesStorage` for everything (authorize URL
  generation, callback replay, login, import pages, player-home enrichment, activity/PC pages).
- Cookie jar never cleared across imports; stale sessions from run N contaminated run N+1's
  authorize request and callback replay.
- `authReplayHeaders`/`pendingAuthCookies` were process-global and cleared only inside
  `getAuthUrl()`; captured credentials could in principle outlive their import.

After:

- `WahlapWechatAuthUrlClient.maimaiDxAuthUrl()` — clears any stale captured state, then uses a
  fresh throwaway `WahlapImportHttpClient` per call (closed after); the authorize request starts
  from an empty jar and is attempted once.
- Each import run (`WahlapImportRunner.runRealImport`) creates one `WahlapHttpScorePageClient`,
  which **consumes** the captured replay state from `WahlapAuthCaptureStore` at construction —
  headers + pending auth cookies belong to exactly one captured authorization attempt, and no
  credentials remain available to a later import. The client builds one `WahlapImportHttpClient`
  (empty jar) and closes it in `finally` when the run completes or fails. All requests of the run
  share that one session: the callback replay establishes the session (captured replay headers
  attached, captured cookies seeded into the jar), and the subsequent home/score/supplemental/
  activity/detail requests use only cookies set during *this* run.
- `WahlapAuthCaptureStore` (process-wide handoff from the VPN tunnel thread) is cleared-and-
  refilled on every capture and cleared again on consumption. It never logs; header and cookie
  metadata appear in logs as counts only.
- The manual-cookie import path already built its own per-instance client; it now also uses the
  same timeout profiles and retry policies (its cookies come from user input and are unaffected
  by session isolation).
- Verified by tests: two clients share no cookie state; a fresh client reports `count=0`; captured
  cookies/headers are attached only to callback-shaped requests; consumption leaves the store
  empty.

## 7. Privacy impact

- New attempt diagnostics are **structurally credential-free**:
  `category=… attempt=n/m elapsedMs=… outcome=… willRetry=… [status=…] [bytes=…] [error=SimpleClassName]`.
  They are built only from primitive fields; no exception messages, no URLs, no headers, no
  cookie values are ever passed into `WahlapAttemptLog.toSafeLogLine()`. They are written to both
  logcat and the per-import diagnostics report (after `sanitizeImportDiagnostic`).
- Exception messages that may embed URLs still pass through `PrivacyRedactor.redact()` exactly as
  before when they surface in failures/UI.
- Cookie summaries remain names-only (`count=N names=domain:name|…`); captured replay headers and
  pending cookies are logged as counts only.
- `WahlapAuthCaptureStore` never logs; consume returns a plain value object used only by the
  client replay path. No raw URL query, OAuth code, state, cookie value, Authorization header, or
  page body is ever logged by the new code.
- No new data is persisted; Room schema unchanged; no credentials anywhere in the new tests.

## 8. Tests and results

See `AUTH-NET-1-V030-PORT.md` §7 for the run record. New regression coverage (mapped to the
required scenarios):

1. Score GET succeeds first attempt — `WahlapResilientFetcherTest.scoreGetSucceedsOnFirstAttemptWithoutDelays`
2. Score GET times out then succeeds on retry — `scoreGetTimesOutThenSucceedsOnRetry` (+ backoff ≈1s assertion)
3. Bounded retry exhaustion — `repeatedTransientFailuresStopAfterBoundedRetries` (3 attempts)
4. Non-retryable responses are requested once — `nonRetryableHttpStatusIsRequestedExactlyOnce`,
   `authFailurePageMarkerIsNotRetriedEvenThoughItIsAnIoException`, `nonIoUnexpectedFailuresAreNotRetried`
5. OAuth callback cannot enter generic retry — `oauthCallbackCategoryIsNeverRetriedEvenOnTransientErrors`
   (also asserts the catalog policy itself is single-attempt)
6. One difficulty fails while successful difficulties persist — `RealWahlapImportAdapterTest.oneDifficultyFailurePersistsTheSuccessfulDifficulties`
7. Old local data for the failed difficulty remains — `failedDifficultyKeepsItsPreviousLocalRecords`
8. Result becomes PARTIAL — `outcome` assertions in both tests above; `supplementalFailureAloneYieldsPartialWithPersistedDifficulties`
9. All failed → FAILED without destructive writes — `allDifficultiesFailedProducesFailedOutcomeWithoutDestructiveWrites`
10. Legitimate empty account remains COMPLETE — `legitimateEmptyAccountImportsAsComplete` (+ pre-existing `unplayedBasicAndAdvancedDoNotBlockOtherDifficulties`)
11. Diagnostics show timing/retry metadata without secrets — `attemptLogLineCarriesMetadataWithoutFreeText`, `cookieSummaryExposesNamesOnlyAndNeverValues`
12. Two sequential imports do not share cookie/session state — `WahlapImportHttpClientTest.repeatedImportSessionsDoNotReuseStaleCookieState`
    (+ `freshImportSessionStartsWithAnEmptyCookieJar`, and loopback end-to-end tests
    `authCallbackSeedsCapturedCookiesAndReplaysCapturedHeaders` /
    `ordinaryPageRequestsNeverReplayCapturedAuthState`)
13. v0.3.0 background `ImportForegroundService` integration remains valid — existing
    `ImportForegroundServiceTest` suites pass unchanged, plus new
    `partialImportWithPersistedDifficultiesCountsAsSucceeded` and
    `failedOutcomeNeverCountsAsSucceededEvenWhenSomePagesWereFetched`
14–16. Activity capture, efficient PC capture, full-PC path — existing suites
    (`WahlapActivityResponseTest`, `WahlapEfficientPcCaptureTest`, `WahlapPlayCountCaptureTest`,
    `WahlapPlayerHomeCaptureTest`, `WahlapSupplementalPagesTest`) run unchanged and pass
17. Cancellation propagates through retry/backoff — `realCancellationIsNeverSwallowedByTheRetryLoop`,
    `cancellationRaisedDuringBackoffStopsTheLoopWithoutFurtherAttempts`

## 9. Files changed

See `AUTH-NET-1-V030-PORT.md` §8 for the complete list with roles.

## 10. Remaining uncertainty

1. **404 / error 100001 root cause** — unproven. Per-import session isolation eliminates the
   proven cross-import contamination, and tests that leading hypothesis, but the owner-device
   validation is the actual verdict. If 404/100001 still occurs on a fresh session, next
   suspects are code/state expiry between capture and replay and server-side flakiness; the
   attempt diagnostics added here (timing, per-attempt outcomes) make one run sufficient to
   distinguish these.
2. **Whether 60s is enough for the worst score pages.** Evidence only proves >30s. If the owner
   still times out at 60s, the number is one constant in `WahlapRequestCatalog` — but the retries
   and partial persistence already bound the damage.
3. **PARTIAL outcome is not persisted to Room** (would need a schema migration); it lives in
   result state/UI.
4. **Pre-existing Robolectric SQLite failure on this machine** — `core:database` migration tests
   (`PlayActivityPersistenceTest.sevenToEightMigrationRetainsExistingExactPc`,
   `sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables`) fail with
   `SQLiteCantOpenDatabaseException` (code 14, during `PRAGMA journal_mode=WAL`) on this Windows
   machine, deterministically and in isolation, on the pristine `d734660` baseline as well as on
   this port (verified on a detached baseline worktree). Environmental/Windows-specific, not
   introduced here; every other suite passes.
