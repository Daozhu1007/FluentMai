# AUTH-NET-1 — v0.3.0 Port Record

Date: 2026-10-02. Branch: `auth-net-1-v030` (worktree `D:\Code\FluentMai-authnet-v030`).
This is the historical port record. The authorize/callback lifetime was subsequently corrected in
[AUTH-NET-1-REVIEW-FIX.md](AUTH-NET-1-REVIEW-FIX.md); the initial split-session design below is
superseded. Device acceptance is still BLOCKED.

Companion documents: `AUTH-NET-1-BASELINE-RESCUE.md` (audit, preserved verbatim),
`AUTH-NET-1-WAHLAP-RELIABILITY.md` (corrected design/behavior record).

## 1. Baselines

| Role | Commit | Note |
| --- | --- | --- |
| Canonical production baseline | `d734660719f94db523af1053bbc915bdc76d7c19` ("Restore complete scores screen for v0.3.0 Beta", versionCode 14) | Base of this port; verified locally with `git cat-file -e d734660…^{commit}` before any work |
| Legacy AUTH-NET-1 implementation | `c8efad91b5da3cec78a417d0c1afb8c2ff972c64` (based on stale `6aa6baf`, versionCode 9) | **Reference material only.** Preserved on local branch `auth-net-1-oldbase`. Not pushed, not built for owner validation, NOT cherry-picked |

`c8efad91` must never reach an owner device: it sits on the v0.2.5 line and Android refuses a
downgrade from v0.3.0 (versionCode 14 → 9). Everything of value in it was re-implemented onto the
v0.3.0 architecture by hand (§2).

## 2. Port strategy

Manual port, file by file — not a cherry-pick and not a wholesale diff application:

1. Inspected `git diff 6aa6baf..c8efad91` as the reference implementation (resilience engine,
   per-import session, capture store, partial persistence, attempt diagnostics).
2. Re-derived every change against the v0.3.0 architecture, which differs materially from
   `6aa6baf` (background `WahlapImportRunner` + `ImportForegroundService`, `ImportTaskState`
   tri-state UI, activity/PC captures, `pendingAuthCookies` seeding, activity-page header rules,
   WeChat Android UA default, per-import diagnostics).
3. Adapted the legacy concepts so v0.3.0 behavior is preserved exactly where the two differ
   (UA choice, activity rules, cookie seeding, integration into the existing tri-state UI model).

## 3. What was ported (and how it landed)

| AUTH-NET-1 concept | Landing on v0.3.0 |
| --- | --- |
| Request-category timeout profiles | `WahlapResilience.kt` ported verbatim into `core:importer` (pure Kotlin); applied per request by both page clients and the auth-url client |
| Bounded retry + backoff + jitter, cause-chain classifier, single-attempt auth | `WahlapResilientFetcher.kt` + tests ported verbatim; wired into `WahlapHttpScorePageClient`, `WahlapManualCookieScorePageClient` |
| Partial score persistence + `WahlapImportOutcome` | `RealWahlapImportAdapter` updated (abort only when nothing was parsed); integrated into the **existing** `ImportTaskState` model: outcome-based `succeeded`/`complete` drive the existing `ImportRunStatus.PartialSuccess` UI — no competing UI machinery |
| Per-import HTTP client / cookie session | `WahlapImportHttpClient` (own `AcceptAllCookiesStorage`, per-request timeouts) created per import run and closed in `finally` by `WahlapImportRunner.runRealImport`; process-global `WahlapKtorClient` deleted |
| Captured auth state handoff | `WahlapAuthCaptureStore` stores replay headers + pending auth cookies per capture; **consumed once** at client construction so no credentials remain for a later import; `WahlapHookBridge` feeds it |
| OAuth single-attempt policy | `AUTH_AUTHORIZE`/`AUTH_CALLBACK` use `WahlapRetryPolicy.NO_RETRY`; the auth-url client uses a fresh throwaway client per call and clears stale captured state first |
| Structured privacy-safe attempt diagnostics | `WahlapAttemptLog.toSafeLogLine()` written to logcat and to the v0.3.0 per-import diagnostics report (`ImportDiagnostics`) |

## 4. Timeout policy

`WahlapRequestCatalog.timeoutProfile` — auth authorize/callback and home: 30s; score pages: 60s;
supplemental pages (rating pages, activity pages, music details, player-home enrichment): 45s;
connect timeout: 15s. Rationale and the EXPERT/MASTER evidence: reliability doc §3.

## 5. Retry policy

`WahlapRequestCatalog.retryPolicy` — auth authorize/callback: 1 attempt, never retried; home and
score pages: max 3 attempts; supplemental: max 2. Retryable: transient `IOException`s and statuses
{408, 429, 500, 502, 503, 504}; bounded exponential backoff with ±20% jitter. Cancellation is
never swallowed. OAuth operations are excluded from every retry path. Details: reliability doc §4.

## 6. Partial-persistence semantics

Reliability doc §5. Summary: persist every successfully fetched/valid difficulty; never delete
scores because another difficulty failed; a failed difficulty keeps its previous local records;
`COMPLETE` / `PARTIAL` / `FAILED` outcomes; a legitimately empty account stays `COMPLETE` (the
v0.2.5-beta.3 empty-page fix preserved).

## 7. Tests and results

Command: `./gradlew test` (all modules, debug + release variants) on the port worktree; baseline
run on a pristine detached worktree at `d734660`.

**Results — see §10 of the final delivery report (updated at commit time).**

Note on pre-existing failure: `core:database` Robolectric migration tests
(`PlayActivityPersistenceTest.sevenToEightMigrationRetainsExistingExactPc`,
`sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables`) fail on this Windows machine
with `SQLiteCantOpenDatabaseException` (code 14, during `PRAGMA journal_mode=WAL`) —
deterministically, in isolation, and identically on a pristine detached worktree of `d734660`.
The failure is pre-existing on the canonical baseline and unrelated to this port; every other
suite passes.

## 8. Changed files

New:

- `core/importer/src/main/kotlin/…/importer/WahlapResilience.kt` — categories, timeout profiles,
  retry policies, catalog, status/non-retryable exceptions, attempt log + safe log line,
  classifier, backoff
- `core/importer/src/main/kotlin/…/importer/WahlapResilientFetcher.kt` — bounded retry engine
- `app/src/main/kotlin/dev/fluentmai/android/WahlapImportHttpClient.kt` — per-import Ktor client
  (own cookie storage, per-request timeouts, captured-cookie seeding, replay-header attach,
  activity/detail header rules, WeChat UA handling, names-only cookie summary)
- `app/src/main/kotlin/dev/fluentmai/android/WahlapAuthCaptureStore.kt` — captured replay-state
  handoff (headers + pending auth cookies) with consume-once semantics
- `core/importer/src/test/kotlin/…/importer/WahlapResilientFetcherTest.kt` (13 tests, ported verbatim)
- `app/src/test/kotlin/dev/fluentmai/android/WahlapImportHttpClientTest.kt` (session isolation +
  loopback end-to-end replay/seeding tests)
- `app/src/test/kotlin/dev/fluentmai/android/WahlapAuthCaptureStoreTest.kt` (replace-on-capture,
  consume-clears, cookie separation)
- `docs/AUTH-NET-1-V030-PORT.md` (this file)

Modified:

- `core/importer/src/main/kotlin/…/importer/RealWahlapImportAdapter.kt` — partial persistence,
  `WahlapImportOutcome`, `isCompleteSuccess` now outcome-based
- `core/importer/src/test/kotlin/…/importer/RealWahlapImportAdapterTest.kt` — partial-semantics
  tests (the old all-or-nothing test was replaced; nothing unrelated weakened)
- `app/src/main/kotlin/dev/fluentmai/android/WahlapHttpScorePageClient.kt` — per-import client +
  resilient fetch + per-request timeouts + typed failures + attempt logging + `AutoCloseable`;
  all request paths (login, score, supplemental, activity, music detail, player-home enrichment)
  now flow through the run's own client
- `app/src/main/kotlin/dev/fluentmai/android/WahlapManualCookieScorePageClient.kt` — same
  resilience treatment; per-request timeout profiles; activity/detail rules preserved
- `app/src/main/kotlin/dev/fluentmai/android/WahlapWechatAuthUrlClient.kt` — per-call throwaway
  client, stale capture-state clear, single attempt
- `app/src/main/kotlin/dev/fluentmai/android/WahlapHookBridge.kt` — capture store handoff
- `app/src/main/kotlin/dev/fluentmai/android/WahlapImportRunner.kt` — close the run's client in
  `finally` (real-device path; cookie path already closed its client)
- `app/src/main/kotlin/dev/fluentmai/android/ImportTaskState.kt` — outcome-based `succeeded` /
  `complete` (integrates PARTIAL into the existing tri-state UI)
- `app/src/test/kotlin/dev/fluentmai/android/ImportForegroundServiceTest.kt` — PARTIAL/FAILED
  service-level integration tests
- `core/importer/build.gradle.kts` — `kotlinx-coroutines-core` dependency
- `docs/AUTH-NET-1-WAHLAP-RELIABILITY.md` — corrected record (topology retraction + v0.3.0)
- `docs/AUTH-NET-1-BASELINE-RESCUE.md` — preserved verbatim (previously untracked)

Deleted:

- `app/src/main/kotlin/dev/fluentmai/android/WahlapKtorClient.kt` (process-global client)

## 9. Deliberately NOT ported from `c8efad91`

- **MainActivity changes** (`ImportRunStatus.Partial`, outcome-prefixed summary, rating refresh on
  partial): v0.3.0 already has the richer tri-state model (`ImportRunStatus.PartialSuccess`,
  `ImportTaskState`); the equivalent integration is done in `ImportTaskState` instead of the
  Activity, per the "integrate with the existing model" requirement.
- **`WX_WINDOWS_UA` default UA choice**: the legacy client presented `WX_WINDOWS_UA` on every
  request; v0.3.0 presents `WX_ANDROID_UA` and, after capture, the captured browser UA for the
  whole run. The v0.3.0 behavior is preserved; the (unused in v0.3.0) `WX_WINDOWS_UA` constant is
  not carried over.
- **Raw `Cookie` header replay on the callback**: the legacy client replayed the captured cookie
  as a request header; v0.3.0's mechanism (seed captured cookies into the import client's cookie
  storage so redirects carry them) is preserved instead.
- **`WahlapKtorClient.getAuthUrl()` shape**: superseded by
  `WahlapImportHttpClient.fetchAuthorizeRedirectFinalUrl` + explicit capture-store clear.
- Nothing else was dropped: all legacy reliability behaviors have an equivalent on v0.3.0.

## 10. Proven facts vs. hypotheses

| Claim | Status |
| --- | --- |
| 30s global timeout killed EXPERT/MASTER score pages on real devices | Proven (device evidence, 2026-10-02) |
| All-or-nothing persistence discarded 294 parsed records | Proven (code + device evidence) |
| Process-global cookie jar was never cleared across imports | Proven (code) |
| OAuth callback is consumed first by the WeChat browser | **Retracted** — the intercepted HTTP callback terminates at the local redirect service; the app's HTTPS replay is the first real callback request |
| Per-import session isolation fixes the 404/error-100001 auth failures | Plausible hypothesis — unproven; device acceptance remains blocked |
| Code/state expiry between capture and replay can cause auth failures | Hypothesis — unproven; attempt diagnostics now make it measurable |
| Ktor CIO connections cannot be poisoned by a request timeout | Verified against Ktor 3.0.1 sources |

## 11. Remaining uncertainty

Reliability doc §10, plus:

- The exact Wahlap server behavior on a semantically "first" callback request that still fails
  (404) is unknowable from the client; the new per-attempt diagnostics (timing, outcomes, cookie
  counts) exist precisely to turn one owner-device run into evidence.
- Partial persistence changes what "a failed page" means for local data freshness (failed
  difficulties now intentionally keep stale records); if the owner wants stale-data visibility
  later, a per-difficulty freshness marker in Room is the natural follow-up.
