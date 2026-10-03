# AUTH-NET-1 Review Fix

Date: 2026-10-03. **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

Verdict: **AUTH_NET_1_REVIEW_BLOCKERS_RESOLVED — GITHUB REVIEW READY**.
This is readiness for code review; device acceptance is **STILL BLOCKED / NOT RE-RUN IN THIS ROUND**.

## Revision and scope

| Field | Value |
| --- | --- |
| Repository | Daozhu1007/FluentMai |
| PR | [#7](https://github.com/Daozhu1007/FluentMai/pull/7) — remains Draft |
| Branch | auth-net-1-v030 |
| Starting SHA | c1b5a40f6883257f75c871a8c51dc494f1d89e2f |
| Prior implementation | 919ea16aac3ad7dffb0721f17fba67f3ce27cc63 |
| Canonical baseline | d734660719f94db523af1053bbc915bdc76d7c19 |
| Ending SHA (implementation and design docs) | 07eb6eea42c00ad4e2e8018678598b40c1fd2155 |
| Final publication | This report is a separate documentation-only child commit; its exact final branch SHA is recorded in the PR description/comment. |

Precheck fetched the target branch and read the current [CTO review](https://github.com/Daozhu1007/FluentMai/pull/7#issuecomment-5967619661).
The initial directory was the old-base checkout, and the existing target worktree contained
uncommitted device/OAuth experiments. Those files were preserved in place by detaching that
worktree at the same starting SHA. A clean worktree in the same repository at
`D:\Code\FluentMai-authnet-review-fix` now checks out the existing `auth-net-1-v030` branch.
No repository or unrelated branch was created; none of the experiments entered this repair.
No ADB, hardware testing, real Wahlap credentials, OAuth-404 investigation, merge, release,
version bump, schema migration or history rewrite was performed.

## Blocker 1: one complete OAuth attempt

Before `919ea16`: the canonical client was process-global, allowing cookie contamination between imports.
At `919ea16`: authorize used a fresh throwaway client, destroyed before a different import client
replayed callback. This isolated separate imports but lost same-attempt authorize cookies.

The repaired lifetime is:

```text
attempt A: fresh client/jar -> authorize -> capture -> callback -> home -> import -> close
attempt B: new client/jar   -> authorize -> capture -> callback -> home -> import -> close
```

`WahlapOAuthAttempt` owns the HTTP client, cookie jar and attached browser replay context.
The synchronized `WahlapAuthCaptureHandoff` owns only the unfinished attempt. Starting a new
attempt closes/replaces the previous pending attempt and clears its captured handoff state.
Authorize retains the client on success and discards only its own attempt on failure; a late
failure cannot discard a newer pending attempt.

`WahlapHookBridge` attaches callback context to that existing attempt. Consumption matches the
captured URL, transfers the same instance once to `WahlapImportRunner`, and clears only the
handoff copy. Authorize cookies survive. Browser cookies are seeded into the existing jar:
matching cookie names update appropriately, and other authorize cookies remain intact.
Callback, authenticated home, score, supplemental, activity, PC and enrichment requests share
that attempt. Capture-service shutdown closes only unconsumed attempts; it cannot close a session
already owned by the importer. The runner's outer `finally` now includes login and its shutdown
callback, fixing the earlier login-failure resource leak. Close is idempotent; closed clients
reject further requests. Manual Cookie imports retain their independent per-instance session.

## Blocker 2: cancellation propagation

Both supplemental loops explicitly rethrow `CancellationException` before constructing failure
results or continuing to another page, and check coroutine activity between sources.
The audit also repaired adapter score/provider/parser catch wrappers, hook authorize handling,
and the runner's catalog fallback. The adapter checks cancellation before persistence.
The existing retry engine's cancellation/backoff behavior remains unchanged and green.

Reviewed other AUTH-NET network boundaries: categorized request wrappers, optional enrichment,
activity capture, efficient/full PC, progress reporting, import diagnostics and the service's
worker catch already rethrow cancellation. Remaining broad catches cover local URI/parser,
socket/notification/lifecycle operations rather than suspend network work. No cancellation is
converted to a supplemental failure, PARTIAL result or continued fetch loop.

## Blocker 3: per-page failure reaches the importer

`WahlapSupplementalPageProvider` now returns `WahlapSupplementalFetchResult(pages, failures)`.
Both real HTTP clients retain every successful page and an explicit labeled, sanitized failure
for every failed required source. No correctness decision relies on logs or side effects.
The adapter merges these failures, parses/persists successful pages, and preserves failure
visibility in `RealWahlapImportResult.supplementalFailures`.

- COMPLETE: all required difficulty and supplemental sources succeeded, including a legitimately empty account.
- PARTIAL: a required source failed, but other sources yielded useful parsed data that was persisted.
- FAILED: required-source failures left no useful parsed data; no persistence writes or batch occur.

This also fixes supplemental-only failure with empty score pages incorrectly producing PARTIAL
and writing an empty batch. Useful supplemental data can yield PARTIAL even if every difficulty
failed; `ImportTaskState.succeeded` now recognizes this case. Runner progress checks explicit
failures as well as successful page count. Real per-page results were passed through the foreground
service to verify PARTIAL/FAILED task state and notification titles. Existing local score retention,
non-destructive upserts, failed-difficulty preservation and empty-account COMPLETE behavior stay green.

## Cleanup and static review

Production/test KDoc now describes cross-import contamination as an architecture defect, with
404/error-100001 causality explicitly unproven. The reliability document distinguishes the initial
split-session implementation from the current repair; the historical port record is marked
superseded for session lifetime. Historical device evidence and the baseline-rescue record remain
intact. No claim is made that this repair fixes device OAuth failures.

The decoded String-length metric is `responseChars`, emitted as `chars=` throughout production,
retry metadata, tests and current diagnostic documentation. UTF-8 regression coverage checks a
Chinese response whose encoded byte size differs from its String length. Production has no
inaccurate byte metric.

Pre-commit searches covered `runCatching`, `catch`, `getOrElse`, old byte-field/log labels and
causal wording. The catalog still has score/supplemental/auth-home request timeouts of 60/45/30s,
connect timeout 15s, score/home maximum 3 attempts, supplemental maximum 2, authorize/callback
maximum 1, and retry statuses 408/429/500/502/503/504. Transient IO/backoff tests remain green.
Version/applicationId/manifest/database files were not changed. `git diff --cached --check` passed.
Gitleaks 8.30.1 scanned the staged repair with no findings. All new auth-like values are explicitly
synthetic fixtures; no secret, raw real auth material or page body was added to logs.

## Regression coverage

39 new test cases: 37 Android cases execute in both Debug and Release, plus two importer JVM
cases, totaling 76 additional full-suite executions. Existing tests were neither deleted nor weakened.

| Suite | Added coverage |
| --- | --- |
| WahlapOAuthAttemptTest (6) | Actual authorize redirect cookie survives callback/home/score; browser cookies and UA attach; two complete imports have fresh jars; handoff consumption preserves cookies; pending replacement/close; wrong or duplicate consumption; rejected authorize closes; idempotent close; browser cookie collision |
| WahlapHookBridgeTest (2) | VPN capture transfers the existing attempt once; callback without an authorize attempt cannot begin import |
| WahlapImportRunnerTest (2) | Real runner closes after callback transport failure and after login-shutdown callback failure; callback transport attempted once |
| WahlapHttpScorePageClientTest (12) | Shared real loopback client/provider/adapter contract |
| WahlapManualCookieScorePageClientTest (12) | Same contract on manual Cookie transport |
| RealWahlapImportAdapterTest (+2) | Score and whole-supplemental provider cancellation propagate without writes |
| ImportForegroundServiceTest (+3) | Actual per-page failure gives PARTIAL/FAILED notifications/state; cancellation of authorization wait closes pending attempt |

The shared contract covers all successful sources, exactly one and multiple failed pages,
all supplemental pages failing with useful/empty difficulty data, useful supplemental-only data,
whole-provider failure with useful/empty score data, direct cancellation identity, cancellation
of suspended real IO without later requests or writes, sanitized failures, the actual configured
production source/progress result, and accurate decoded character diagnostics. Production still
requests only the single confirmed rating-target endpoint; multi-source tests use injected local
fixtures and do not introduce guessed endpoints.

## Test results

Windows commands:

```powershell
.\gradlew.bat :core:importer:test :app:testDebugUnitTest --tests '*Wahlap*' --tests '*RealWahlapImportAdapterTest*' --tests '*ImportForegroundServiceTest*' --continue --console=plain
.\gradlew.bat test --continue --console=plain
```

Counts are summed from JUnit XML, excluding no failed or skipped test.

| Run | Passed | Failed | Skipped | Total |
| --- | ---: | ---: | ---: | ---: |
| Final targeted run | 197 | 0 | 1 | 198 |
| Full requested run | 591 | 4 | 1 | 596 |
| Clean d734660 baseline reproduction of the four failing cases | 0 | 4 | 0 | 4 |

| Full-suite task | Passed | Failed | Skipped | Total |
| --- | ---: | ---: | ---: | ---: |
| app / testDebugUnitTest | 130 | 0 | 0 | 130 |
| app / testReleaseUnitTest | 130 | 0 | 0 | 130 |
| core/database / testDebugUnitTest | 13 | 2 | 0 | 15 |
| core/database / testReleaseUnitTest | 13 | 2 | 0 | 15 |
| core/exporter / test | 9 | 0 | 0 | 9 |
| core/importer / test | 111 | 0 | 1 | 112 |
| core/model / jvmTest | 47 | 0 | 0 | 47 |
| core/privacy / test | 2 | 0 | 0 | 2 |
| core/upload / test | 17 | 0 | 0 | 17 |
| feature/import / testDebugUnitTest | 5 | 0 | 0 | 5 |
| feature/import / testReleaseUnitTest | 1 | 0 | 0 | 1 |
| feature/scores / testDebugUnitTest | 60 | 0 | 0 | 60 |
| feature/scores / testReleaseUnitTest | 47 | 0 | 0 | 47 |
| feature/tools / testDebugUnitTest | 3 | 0 | 0 | 3 |
| feature/tools / testReleaseUnitTest | 3 | 0 | 0 | 3 |


Full Gradle command exited 1 solely because `:core:database:testDebugUnitTest` and
`:core:database:testReleaseUnitTest` each failed the following two existing migration tests:

| Test in PlayActivityPersistenceTest | Same baseline/candidate error in both variants |
| --- | --- |
| sevenToEightMigrationRetainsExistingExactPc | SQLiteCantOpenDatabaseException, code 14 SQLITE_CANTOPEN, `PRAGMA journal_mode=WAL`, unable to open database file |
| sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables | SQLiteCantOpenDatabaseException, code 14 SQLITE_CANTOPEN, unable to open database file |

The baseline worktree was clean at exact `d734660719f94db523af1053bbc915bdc76d7c19`.
Reproduction command, run again in this review-fix round:

```powershell
.\gradlew.bat :core:database:testDebugUnitTest :core:database:testReleaseUnitTest --tests '*sevenToEightMigrationRetainsExistingExactPc' --tests '*sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables' --continue --console=plain
```

All four exception types and messages match after normalizing only dynamic `DB[number]` handles;
there are no new failures. Database production and test sources are unchanged. The single skip is
`JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`, because the optional
`FLUENTMAI_JP_LIVE_FIXTURE` is unset, matching its existing explicit assumption.

Local logs and parsed results are retained outside the checkout under
`D:\Code\FluentMai-authnet-review-fix-{targeted,full,baseline-migrations}.log` and
`D:\Code\FluentMai-authnet-review-fix-{targeted,full}-results.json`. The full results JSON records
all four baseline comparisons. Routine generated JUnit XML remains under each module's
`build/test-results/` directory.

## Changed files

```text
app/src/main/kotlin/dev/fluentmai/android/ImportForegroundService.kt
app/src/main/kotlin/dev/fluentmai/android/ImportTaskState.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapAuthCaptureStore.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapHookBridge.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapHookHttpService.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapHttpScorePageClient.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapImportHttpClient.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapImportRunner.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapManualCookieScorePageClient.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapOAuthAttempt.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapSupplementalPages.kt
app/src/main/kotlin/dev/fluentmai/android/WahlapWechatAuthUrlClient.kt
app/src/test/kotlin/dev/fluentmai/android/ImportForegroundServiceTest.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapHookBridgeTest.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapImportHttpClientTest.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapImportRunnerTest.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapLoopbackServer.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapOAuthAttemptTest.kt
app/src/test/kotlin/dev/fluentmai/android/WahlapScorePageClientContractTest.kt
core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/RealWahlapImportAdapter.kt
core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/WahlapResilience.kt
core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/WahlapResilientFetcher.kt
core/importer/src/test/kotlin/dev/fluentmai/android/core/importer/RealWahlapImportAdapterTest.kt
core/importer/src/test/kotlin/dev/fluentmai/android/core/importer/WahlapResilientFetcherTest.kt
docs/AUTH-NET-1-V030-PORT.md
docs/AUTH-NET-1-WAHLAP-RELIABILITY.md
docs/AUTH-NET-1-REVIEW-FIX.md
```

## Remaining device uncertainty and publication boundary

Loopback tests establish internal session continuity and result/cancellation correctness. They
provide no device OAuth acceptance evidence and do not establish the cause of 404/error 100001.
The existing AUTH-NET-2F Owner-driven hardware outcome remains blocked; the next hardware round
must validate actual authorize/callback/home, score retry/recovery, PARTIAL persistence and
background behavior. No hardware run or ADB use occurred here.

The repair and this report are intended for normal fast-forward publication to
`origin auth-net-1-v030` with PR #7 kept Draft. Final push verification and the exact documentation
commit SHA are recorded in the PR update. Merged: NO. Released: NO. Device acceptance:
**STILL BLOCKED / NOT RE-RUN IN THIS ROUND**.
