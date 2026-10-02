# AUTH-NET-1 — Baseline Rescue / Audit

Date: 2026-10-02. Mode: read-only audit + port plan. **No production code changed, no commit, no push.**

Scope of this document: (1) exact repository state, (2) ancestry audit of the unpushed AUTH-NET-1
commit `c8efad91`, (3) re-audit of the OAuth callback topology (retracting one claim from the
previous report), (4) a migration plan for porting the valid portions of AUTH-NET-1 onto the
canonical v0.3.0 baseline `d734660`.

---

## 1. Task 1 — Exact repository state

Recorded verbatim at audit time (worktree `D:\Code\FluentMai`, shell Git Bash on win32):

```
$ git remote -v
origin  https://github.com/Daozhu1007/FluentMai.git (fetch)
origin  https://github.com/Daozhu1007/FluentMai.git (push)

$ git status --short --branch
## master...origin/master [ahead 1]

$ git rev-parse --show-toplevel
D:/Code/FluentMai

$ git rev-parse HEAD
c8efad91b5da3cec78a417d0c1afb8c2ff972c64

$ git branch -vv
  feat/windows-product-parity bfc24f7 [origin/feat/windows-product-parity: ahead 1] chore: checkpoint abandoned Windows installer/updater WIP
* master                      c8efad9 [origin/master: ahead 1] fix(android): harden Wahlap import networking (AUTH-NET-1)
  research/mci0               fb529b2 [origin/research/mci0] research: add MCI-0.1 strict parser and conformance spike
```

`git log --oneline --decorate --graph -30` (head): `c8efad9 (HEAD -> master)` →
`6aa6baf (tag: v0.2.5-beta, tag: v0.2.5-android-beta.3, origin/master, origin/HEAD)` → `b84df2e` →
`8936bf5` → … (full v0.2.x history; nothing above 6aa6baf is known locally).

### Object existence checks (before any fetch)

```
$ git cat-file -t d734660719f94db523af1053bbc915bdc76d7c19
fatal: git cat-file: could not get object info            (exit 128)

$ git cat-file -t af0d0ee68f736937c9195e8e3f92c5bbc44d70e4
fatal: git cat-file: could not get object info            (exit 128)

$ git merge-base --is-ancestor d734660... c8efad91...
fatal: Not a valid commit name d734660719f94db523af1053bbc915bdc76d7c19   (exit 128)
```

All six v0.2.6→v0.3.0 commits are **absent from the local object database**:
`2157681`, `a7a8fb7`, `81a5c62`, `a382e63`, `af0d0ee`, `d734660` — every one MISSING.

### Why the checkout did not contain v0.3.0

Verified via the GitHub REST API (api.github.com is reachable from this machine; github.com:443
is **not** — `git fetch origin` fails with `Recv failure: Connection was reset`, retried 3×, so
the local remote-tracking refs could not be refreshed during this audit):

```
$ gh api repos/Daozhu1007/FluentMai/commits/d734660...
  → "Restore complete scores screen for v0.3.0 Beta"      (exists)
$ gh api repos/Daozhu1007/FluentMai/commits/af0d0ee...
  → "Release v0.3.0 Beta: background import and JP constants"  (exists)
$ gh api repos/Daozhu1007/FluentMai/branches/master
  → d734660719f94db523af1053bbc915bdc76d7c19              (canonical master)
$ gh api repos/Daozhu1007/FluentMai/compare/6aa6baf...d734660
  → status=ahead, ahead_by=6, behind_by=0
    2157681 v0.2.6 Beta (B50 export, favorites, updates)
    a7a8fb7 v0.2.7 Beta (B50 artwork, annual rating versions)
    81a5c62 v0.2.8 Beta (theoretical B50, compact player header)
    a382e63 v0.2.9 Beta (PC import rules, custom components, value gradients)
    af0d0ee v0.3.0 Beta (background import, JP constants)
    d734660 Restore complete scores screen for v0.3.0 Beta
```

**Determination: remote not fetched — stale remote-tracking refs.** Concretely:

- Not a wrong worktree or wrong repository: toplevel is `D:\Code\FluentMai`, `origin` URL matches
  the canonical repo exactly.
- Not a stale local branch or detached history: `master` tracks `origin/master` normally and the
  worktree was clean before AUTH-NET-1.
- The canonical repository gained six linear commits (all descendants of `6aa6baf`) after the
  last successful fetch on this machine. The local clone's `origin/master` still pointed at
  `6aa6baf`.
- Process failure (audit author's): no `git fetch`/freshness check was run before implementing
  AUTH-NET-1, and the task-time statement "baseline = 6aa6baf" was accepted without verifying it
  against the remote. `6aa6baf` was correct *as far as the stale clone knew*, but wrong as the
  production baseline. Additionally, today github.com:443 is blocked from this network, so even a
  diligent fetch at implementation time would have failed loudly — which would have surfaced the
  problem. The API-only path used now was not attempted then.

---

## 2. Task 2 — Ancestry audit of c8efad91

```
$ git rev-parse c8efad91^
6aa6bafd5421fa2826dc3effccf40592a91d1f29          (parent)

$ git log --oneline 6aa6baf..c8efad91
c8efad9 fix(android): harden Wahlap import networking (AUTH-NET-1)   (exactly 1 commit)
```

- **Parent of `c8efad91`:** `6aa6baf` (v0.2.5-android-beta.3 / v0.2.5-beta tag).
- **Commits between `6aa6baf` and `c8efad91`:** only `c8efad91` itself.
- **Does `c8efad91` contain the v0.3.0 changes?** **No.** Its only parent is `6aa6baf`; the six
  commits `2157681…d734660` are not ancestors of it (they are not even present as objects
  locally), and the compare API shows `d734660` is a strict descendant of `6aa6baf`. Therefore
  `c8efad91` and `d734660` are siblings diverging at `6aa6baf`.
- **Would an APK built from `c8efad91` regress the owner?** **Yes, materially:**
  - `c8efad91` is `versionCode 9 / versionName 0.2.5-android-beta.3`; v0.3.0 is
    `versionCode 14 / versionName 0.3.0-beta`. Android blocks an in-place "update" to a lower
    versionCode; the owner would have to uninstall first, which destroys local Room data
    (scores, import batches, quarantine, rating history).
  - It lacks all v0.2.6–v0.3.0 functionality: B50 export/gallery/poster + bundled artwork,
    favorites, in-app updates (AppUpdateClient), annual rating versions, theoretical-B50 fix,
    compact player header, PC (play-count) import rules, custom components, value gradients,
    background import (`ImportForegroundService`/`WahlapImportRunner`), JP constants, restored
    complete scores screen, and the newer capture/diagnostics plumbing
    (`pendingAuthCookies`, `WX_ANDROID_UA`, activity/PC captures).
  - Its Wahlap capture behavior additionally predates v0.3.0's fixes, so networking would differ
    from the owner's current v0.3.0 experience in ways this audit did not validate.

**Conclusion: `c8efad91` must not be built, installed, or pushed. It is a v0.2.5-based commit.**

---

## 3. Task 3 — Re-audit of the OAuth callback topology (retraction)

### 3.1 Traced chain (from source, same on `6aa6baf` and `d734660` — TunnelFactory verified byte-for-byte on both)

1. **Authorize URL generation** — hook tap → `WahlapHookHttpService.serveMaimaiAuthRedirect()` →
   `WahlapWechatAuthUrlClient.maimaiDxAuthUrl()` → `WahlapKtorClient.getAuthUrl()`:
   the app's own client GETs `https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/maimai-dx`
   (port 443 → `RawTunnel`, not intercepted) and follows the redirect chain to the WeChat
   authorize URL; then **rewrites `redirect_uri=https` → `redirect_uri=http`**.
2. **redirect_uri scheme** — the callback URL handed to the WeChat browser is therefore
   **plaintext HTTP on port 80**.
3. **VPN routing** — the browser's connection to `tgk-wcaime.wahlap.com:80` is intercepted by the
   local VPN (`TcpProxyServer` → `TunnelFactory.createTunnelByConfig`).
4. **TunnelFactory** (verified locally and on `d734660` via API):

   ```java
   if (isWahlapHost && destAddress.getPort() == 80) {
       return new HttpCapturerTunnel(
           new InetSocketAddress("127.0.0.1", WahlapHookHttpService.REDIRECT_PORT), selector);
   }
   ```

5. **Actual socket destination** — `HttpCapturerTunnel`'s upstream is **`127.0.0.1:9457`**
   (`REDIRECT_PORT`), the local redirect HTTP service. `beforeSend` inspects a copy of the
   request bytes and calls `WahlapHookBridge.onAuthRequestCaptured(url, headers)`, but the bytes
   are then forwarded to the **local** server.
6. **Local redirect HTTP service** — port 9457 answers `202` with the canned page
   “登录信息已捕获，可以切回 FluentMai 等待成绩导入。” The browser renders that page. **The real
   Wahlap callback endpoint is never contacted by the browser.**
7. **WahlapHookBridge → app replay** — the bridge filters for
   `tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx?...code=`, stores the captured request
   headers, and emits the URL; `login()` then GETs the callback URL over **HTTPS** (port 443 →
   `RawTunnel`), so the app-side replay reaches the **real** Wahlap server. On v0.3.0 the
   captured browser `Cookie` header is additionally seeded into the (still global) Ktor cookie
   jar (`pendingAuthCookies` → `seedWahlapCookies`) before that replay.

### 3.2 Explicit answers

1. **Does the browser's captured HTTP callback actually reach the real Wahlap callback endpoint
   before FluentMai replay?** **No.** `*.wahlap.com:80` is hard-routed to `127.0.0.1:9457`; the
   browser receives the local 202 page. The only request that ever hits the real callback
   endpoint is FluentMai's own HTTPS replay.
2. **Retraction.** Yes — the previous report's claim that “the WeChat browser's own callback
   request reaches Wahlap first and consumes the single-use code, so successful runs depend on
   the server tolerating a replay” is **retracted**. The app-side replay is the **first and only
   consumption** of the OAuth code (until it expires server-side). The previous report's §1.3
   framing (“the browser completes the callback, consuming whatever needs consuming; the app
   replay is a second hit”) is wrong and must be corrected when the AUTH-NET-1 doc is revised on
   the new baseline. (The Ktor CIO connection-lifecycle findings in that section — verified
   against Ktor 3.0.1 sources — are unaffected by this retraction.)
3. **What remains proven for failure #2 (auth 404 / home error 100001):**
   - The login is a single HTTPS GET of the captured callback URL plus a home probe (code).
   - The capture path delivers URL + full request headers, and — on v0.3.0 — seeds the captured
     browser cookies into the global Ktor jar before replay (code).
   - The Ktor cookie jar is process-global, never cleared across imports, and its cookies are
     attached to the authorize request and the replay (code).
   - On failing runs the server rejected the callback (HTTP 404) and no session established
     (home → error 100001) (device evidence).
4. **What is hypothesis only (needs owner-device logs / server behavior):**
   - OAuth `code`/`state` **expiry** between the authorize visit and the app's replay (human
     delay in WeChat, slow handoff) — now the leading suspect given the retraction.
   - **Conflicting Cookie headers** at replay: stale jar cookies + freshly seeded browser cookies
     presenting two session generations to the server → state/session mismatch → 404/100001.
   - Stale jar cookies altering the **authorize** redirect chain itself.
   - Wahlap-side transient errors; WeChat-side auth refusals.
   - Note: since the code is *not* consumed by the browser, a one-shot retry of a failed
     *connect-level* callback attempt would in principle be server-safe — but a failure after the
     request reached the server still leaves consumption ambiguity, so **NO automatic callback
     retry remains the correct policy** (also matches objective C).
5. **Is stale process-global CookieStorage still a real architecture risk?** **Yes.** It is
   proven by code (both baselines) that stale cookies ride along on every authorize/callback/page
   request of every later import in the same process. Independent of which hypothesis explains
   the 404s, presenting conflicting session generations to an OAuth endpoint is never beneficial.
   On v0.3.0 the risk persists unchanged: `WahlapKtorClient` is still an `object`, its
   `AcceptAllCookiesStorage` is still never cleared.
6. **Does per-import session isolation remain justified independently?** **Yes.** (a) It removes
   the proven stale-cookie contamination; (b) each authorize+callback pairing is semantically one
   OAuth client session and should start clean; (c) it is testable without a device; (d) v0.3.0's
   own `pendingAuthCookies` seeding is *also* per-capture state — isolating the whole jar per
   import is the consistent completion of that direction. The suspected *causal* role in failure
   #2 is downgraded from "root cause" to "leading hypothesis", but the hardening itself stands.

---

## 4. Task 4 — Rescue plan: port AUTH-NET-1 onto v0.3.0 (`d734660`)

### 4.1 What v0.3.0 already provides (must be preserved, not regressed)

Verified on `d734660` (compare API + contents):

- **Background import ownership**: `ImportForegroundService` owns `WahlapImportRunner` (own
  database/persistence/redactor; `AutoCloseable`); `MainActivity` no longer runs imports — it
  starts the service and observes `ImportTaskStore.state`.
- **Tri-state UI already exists**: `ImportTaskState.succeeded` (= `failedDifficultyCount == 0 &&
  fetchedDifficultyCount > 0`), `complete` (adds no supplemental/activity/PC failures), and
  `MainActivity` maps `complete → ImportRunStatus.Success`, `succeeded → PartialSuccess`, else
  `Failed`; titles “导入完成 / 成绩已导入，部分数据未完整同步 / 导入未完成”.
- **Progress reporting**: `ImportProgress`/`ImportStage`/`ImportPageReporter` per difficulty page.
- **Diagnostics**: `ImportDiagnostics` (timestamped, capped, sanitized at the sink via
  `sanitizeImportDiagnostic`: headers/URLs/secret params redacted) + per-request `onDiagnostic`
  callbacks + `diagnosticException`.
- **Capture/session plumbing**: `pendingAuthCookies` (captured browser Cookie header seeded into
  the jar exactly once at replay), `WahlapSessionCookies.seedWahlapCookies` (RAW-encoded),
  `WX_ANDROID_UA` default, activity/play-count/player-home captures, `WahlapSupplementalPages`,
  `fetchActivityPage`/`fetchMusicDetail` with referer/identity-encoding rules.
- **Still present on v0.3.0 (i.e., AUTH-NET-1 goals remain unaddressed there)**: global
  `WahlapKtorClient` object with never-cleared cookie storage; single 30s timeout; no retries;
  adapter still aborts persistence when any difficulty fails (verified: v0.3.0 only added
  result fields, not logic); `ImportTaskState.succeeded` still equates “no difficulty failure”
  with success.

### 4.2 Port strategy

Mechanics (when network to github.com is available, e.g. via proxy — **not now**, per
instructions):

1. `git fetch origin --tags --prune` (until it succeeds; verify `origin/master == d734660`).
2. `git switch -c auth-net-1-v030 origin/master` (at `d734660`).
3. `git cherry-pick c8efad91` **expected to conflict** — resolve per the file map below.
   Alternative if conflicts are too tangled: re-apply by hand from the port map (the
   `core:importer` half ports mechanically; the app half is effectively a rewrite guided by both
   versions).
4. Leave `c8efad91` unpushed and unreferenced by the new branch; record its disposition
   (superseded) in the commit message of the port.

File-by-file map (AUTH-NET-1 change → action on v0.3.0):

| AUTH-NET-1 change | On v0.3.0 | Action |
| --- | --- | --- |
| `WahlapResilience.kt`, `WahlapResilientFetcher.kt` (new, core:importer) | file absent | **Apply as-is.** No conflicts. |
| `core/importer/build.gradle.kts` (coroutines-core dep) | unchanged upstream | **Apply as-is.** |
| `RealWahlapImportAdapter.kt` partial persistence + `WahlapImportOutcome` | modified upstream: result data class gained 5 defaulted fields (`fetchedPlayRecordCount`, `failedPlayPageCount`, `activityCaptureAttempted`, `fetchedPlayCountCharts`, `activityWarnings`, `diagnosticDetails`); method body unchanged | **Merge**: keep v0.3.0 fields; add `outcome`; replace the abort branch with the partial logic. `isCompleteSuccess` redefined via `outcome` (it is unused elsewhere upstream — verified on 6aa6baf; re-verify). |
| `RealWahlapImportAdapterTest.kt` | unchanged upstream | **Apply as-is** (ScoreRecord gained defaulted `playCount*` fields — named-arg construction unaffected). |
| `WahlapKtorClient.kt` deletion | **modified upstream, still the global object** with `pendingAuthCookies` seeding | **Do not delete.** Refactor instead: convert the object into (or wrap it with) a per-import instance class that keeps v0.3.0's `getWahlapPage(rawUrl, detailReferer)` signature, `pendingAuthCookies` semantics, both UAs, and activity-page header rules, but owns a private `AcceptAllCookiesStorage` created per import. Keep a thin factory for construction and close the client at import end. |
| `WahlapHttpScorePageClient.kt` rewrite | modified upstream (+`onPlayerHome`, `onDiagnostic`, `fetchActivityPage`, `fetchMusicDetail`, `WahlapSupplementalPages`, parser-based auth-failure detection) | **Hand-merge.** Keep every v0.3.0 capability; route all requests through the categorized resilient fetch; make `login`/`fetchScorePage` suspend or keep the `runBlocking` boundary — preserve the callers' contract with `WahlapImportRunner`. Typed failures (`WahlapHttpStatusException` etc.) go inside the fetch block exactly as in c8efad91; status validation must also cover the new `fetchActivityPage`/`fetchMusicDetail` (5xx retriable, 4xx not). |
| `WahlapManualCookieScorePageClient.kt` | modified upstream (seeded cookies via `HttpCookies default {}`, same new fetchers/callbacks) | **Hand-merge** same as above; keep the explicit `Cookie` header + seeded-storage behavior; per-request timeout/retry via the same engine. |
| `WahlapWechatAuthUrlClient.kt` per-call fresh client | unchanged upstream | **Apply as-is** (verify against d734660's file; the upstream `WahlapKtorClient.getAuthUrl()` moves onto the per-import instance). |
| `WahlapHookBridge.kt` → capture store | unchanged upstream, but upstream capture semantics changed (cookie → `pendingAuthCookies`) | **Adapt**: the per-import session object must consume both the replay headers and the pending cookie seed; keep the bridge's clear-and-refill contract. |
| `MainActivity.kt` (status mapping, client close, Partial) | heavily modified upstream; import flow moved out | **Do not apply.** Re-implement at the new ownership points: `ImportTaskState`/`ImportForegroundService` mapping driven by the new `outcome` (below), and runner-side client close. |
| `ImportRunStatus.Partial` + summary prefix | upstream already has `ImportRunStatus.PartialSuccess` and titles | **Reuse upstream's enum/titles.** Redefine `ImportTaskState`: `persisted = result.fetchedDifficultyCount > 0 || parsedRecordCount > 0`; `succeeded := outcome == PARTIAL || outcome == COMPLETE`; `complete := outcome == COMPLETE && !pageFailed && activityWarnings/PC empty`. This fixes the v0.3.0 gap where a difficulty failure still renders “导入未完成” even though (post-port) records were persisted. |
| Attempt diagnostics (`WahlapAttemptLog.toSafeLogLine`) | upstream has `onDiagnostic`/`ImportDiagnostics` with sink-side sanitization | **Compose**: emit attempt logs through `diagnostics.record(...)` (they are already credential-free by construction) and keep `Log.i`. Keep them orthogonal to `describeWahlapResponse`. |
| `WahlapImportHttpClient.kt`, `WahlapAuthCaptureStore.kt` + tests (new) | absent | **Apply, then integrate**: per-request `timeout{}` profiles preserved; `fetchPage` must accept v0.3.0's `detailReferer`/UA/activity-header rules; `fetchAuthorizeRedirectFinalUrl` replaces `getAuthUrl()`. |

Timeout/retry/outcome/persistence/session-isolation goals carry over **unmodified** (none are
disproved by the audit; §3 only changes the *narrative* for failure #2, not the policy: the
callback stays single-attempt).

### 4.3 Port test plan

1. Port all AUTH-NET-1 tests unchanged (`WahlapResilientFetcherTest`, adapter partial tests,
   `WahlapImportHttpClientTest`, `WahlapAuthCaptureStoreTest`) — the adapter test file is
   untouched upstream, so it merges cleanly.
2. Add one test for the redefined `ImportTaskState` semantics (PARTIAL when a difficulty failed
   but records persisted; COMPLETE only when nothing failed) — v0.3.0 has
   `ImportForegroundServiceTest`/`ImportTaskState` coverage to extend.
3. Re-run the full v0.3.0 suite (which now includes the v0.2.6–v0.3.0 test files listed in the
   compare output) plus ported tests; all green before any device build.
4. Build a debug APK **from the port branch only** (versionCode will exceed 14 or at least match
   via a version bump decision left to the owner) for owner validation on the Xiaomi device.

### 4.4 Required correction to the previous report

`docs/AUTH-NET-1-WAHLAP-RELIABILITY.md` (committed in `c8efad91`) contains the retracted
consumption claim (§1.2 item 1, §1.3 intro, §3 conclusion) and the wrong baseline claim
(“v0.3.0 does not exist in this repo”). When the port lands, that document must be revised in the
same change: replace the consumption narrative with the localhost-capture topology, restate
failure #2's proven vs hypothetical causes per §3 of this document, and fix the baseline note.

### 4.5 Explicitly out of scope / not done now

- No fetch-with-proxy attempts beyond the three retries recorded; no history rewriting; no
  rebase/cherry-pick executed; no APK built; `c8efad91` left in place unpushed; nothing pushed.

---

## 5. Verdict

**READY_TO_PORT_AUTH_NET_1_ONTO_V030**

Rationale: the baseline is *stale*, not corrupted — canonical history is intact and linear
(`6aa6baf` → … → `d734660` = canonical master), the worktree and remotes are healthy, and every
AUTH-NET-1 goal survives the re-audit (with the failure-#2 narrative corrected). The valid
portions map cleanly onto v0.3.0; the only genuine rework is the app-layer hand-merge where
v0.3.0 moved import ownership into `ImportForegroundService`/`WahlapImportRunner` and added
captures/diagnostics that the port must preserve. The blocking prerequisite is network-level:
a successful `git fetch origin` (github.com:443 is currently reset from this machine; api.github.com
works) so the six missing objects become available for the cherry-pick.
