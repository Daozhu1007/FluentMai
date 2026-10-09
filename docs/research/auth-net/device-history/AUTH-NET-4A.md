# AUTH-NET-4A — low-latency WeChat handoff

Engineering: 2026-10-06. Owner acceptance: 2026-10-08.
Asia/Shanghai (UTC+08:00).
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE. PR #7 remains Draft.**

Current disposition: **LOW_LATENCY_HANDOFF_VALIDATED**;
**AUTH_NET_COMPLETE_IMPORT_VALIDATED**, scoped to the exact installed 4A binary
and the one Owner-operated real transaction recorded below.

The original October 6 engineering disposition was
**READY_FOR_OWNER_QUICK_HANDOFF_VALIDATION_AFTER_0705**. That engineering task
generated zero real OAuth transactions and awarded no device acceptance marker.
Its pre-07:05 smoke remains separate from the October 8 acceptance.

## Canonical source and scope

Fetched `origin/auth-net-1-v030` was exactly
`c7cd4c48a78a362972ce363b2bc835d4fcd3c366`. Work used a clean independent worktree
`D:/Code/FluentMai-authnet-4a`, branch `codex/auth-net-4a`, based directly on that
remote. The original workspace and its untracked diagnostics were preserved.
The final commit is pushed to `origin/auth-net-1-v030` without force-push.
No merge, release, version bump, ChatGPT opening or CTO relay is authorized or performed.

[TIMING-1](AUTH-NET-TIMING-1.md) motivated reducing avoidable transaction age.
It establishes neither an exact TTL nor `t` semantics nor a proven expiry mechanism.
No age threshold, automatic expiration or new root-cause experiment was added.

## Capability spike: tablet only, zero OAuth

Verified Xiaomi **24018RPACC**, Android API **36**, WeChat **8.0.78 / 3180**.
All ADB commands explicitly selected this tablet; the Owner phone was not accessed.

| Tier | Current observation | Decision |
| --- | --- | --- |
| Ordinary launcher | MAIN + LAUNCHER resolves to an installed launcher. Launching the dynamically resolved component brought `com.tencent.mm` to foreground; HOT shell launch reported **237 ms**. | Adopt Android's public `PackageManager.getLaunchIntentForPackage("com.tencent.mm")`. |
| Package-scoped text SEND | Resolves two targets, observed as `ShareImgUI` and `AddFavoriteUI`. No share intent was launched and no text was sent. | Resolution proves available targets only; it does not prove the desired conversation or privacy/reliability. Reject as default. |
| Direct File Transfer Assistant | No stable documented public mechanism was proven. Restricted searches of official WeChat developer documentation did not establish one. | Feasibility remains **UNPROVEN**. No private component, deep-link guess or reverse-engineered activity is used. |
| Manual fallback | Copied link plus ordinary app launch, then Owner chooses conversation and pastes/sends/opens. | Retained. |

The first package-only shell MAIN/LAUNCHER start did not resolve for launch;
resolving first and launching the returned component succeeded. Production obtains
that explicit intent dynamically from PackageManager. The observed component names
above are evidence, not production constants. The HOT launch resumed WeChat's
existing webview task; ordinary launch does not promise the chat list or File
Transfer Assistant. No task clearing, force-stop, WeChat modification, logout,
message automation or link clicking was performed.

Android documents the [public launcher API](https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String))
and [package visibility declaration](https://developer.android.com/training/package-visibility/declaring).
The manifest declares only a package query for `com.tencent.mm`.
The shell's 237 ms is a non-OAuth launcher smoke measurement, **not**
`authorize_generated_to_wechat_launch_dispatch_ms` and not product handoff acceptance.

## Final order and readiness evidence

1. Explicit user action: **立即微信授权**, or **重新授权并打开微信**.
2. Coordinator reserves one request identity. If needed, Activity requests VPN permission.
3. Approval automatically starts the foreground import/capture execution. Denial generates nothing.
4. Wait for matching task/execution ownership, VPN tunnel readiness and both HTTP listeners.
5. With a resumed Activity host, generate exactly one fresh authorize transaction through the existing handoff.
6. Immediately write the clipboard; on Android 13+, mark its preview sensitive.
7. Immediately dispatch the ordinary WeChat launch from that Activity.
8. Owner manually pastes, sends and opens. Matched callback continues the existing import service.

The old `vpnRunning=true` was published before TCP/DNS proxy initialization and
TUN establishment, so it was insufficient. It now publishes only after TUN,
input/output streams and bound TCP/DNS proxies exist and are not stopped.
`captureReadiness` separately requires successful binding of ports 8284 and 9457.
Each readiness update carries its execution identity; stale readiness/teardown
updates cannot open the current gate. `ImportForegroundService` prepares the gate
and owns the corresponding Waiting execution. No sleep is used as a readiness signal.

Manual copy and the local hook authorize handler also require the gate. During
quick authorization the hook handler cannot initiate competing authorize generation.
The previous manual **启动捕获 → 复制授权** path remains secondary, with explicit
**手动重新启动捕获** available at retry. A manual start alone generates nothing.

## Coordinator, lifecycle and failure behavior

`QuickAuthCoordinator` is a process-scoped, main-thread command owner with injected
capture, generation, clipboard and launcher ports. Its phases are Idle,
RequestingVpnPermission, StartingCapture/WaitingCaptureReady, GeneratingAuthorize,
CopyingClipboard, LaunchingWechat, AwaitingCallback, ManualWaiting, Importing,
AuthRetryAvailable and Terminal. State stores request/execution identifiers and
safe status text, never the authorize URL.

Activity registers the permission callback outside Compose and saves only its
request identifier across recreation. At most one permission flow can be pending.
Only the matching approval can continue the coordinator. A recreated Activity
attaches its resumed launcher host; an older Activity's pause cannot detach that
replacement. If the app is backgrounded before readiness, generation waits for a
resumed host. If backgrounding occurs during generation, clipboard still completes
and missing launch availability selects the manual fallback, without a delayed
automatic launch on return.

The coordinator claims its one-shot handoff before writing clipboard or launching.
Recomposition, state re-delivery, return to FluentMai and rapid double taps cannot
repeat it. Service admission requires the still-pending matching request command;
cancelled queued starts are rejected. Cancellation during preparation or generation
invalidates the coordinator and cancels only the matching Waiting execution.
Late results and authoritative execution replacement cannot hand off a stale URL.
Terminal state cancels generation; import-owned sessions retain existing natural
completion/cleanup. Cancellation controls are disabled during import.

Existing `WahlapOAuthAttempt` ownership, serialized authorize generation,
`r/t/state` identity matching, callback single claim, stale rejection, isolated
execution/client lifetime and **three-attempt logical-task budget** remain intact.
Retry starts a new execution and a distinct OAuth attempt. No fourth transaction
is generated automatically. Incompatible manual, Cookie and upload controls are
disabled during quick authorization, and command/service checks enforce admission.

WeChat missing/unresolvable/throwing is **not** OAuth rejection. After successful
clipboard write, keep the current transaction and capture waiting; display:

> 授权链接已复制，请立即打开微信，粘贴发送并点开。

No regeneration occurs solely because launch failed. Clipboard failure likewise
retains the pending transaction and enables the manual copy path. Generation and
capture-start failures perform no stale clipboard write or WeChat launch. New
failure messages are fixed safe text; launcher/clipboard exception payloads are
never logged.

## Privacy-safe telemetry

Tag: `FluentMaiQuickAuth`; prefix: `QUICK_AUTH`. Each request records fixed events
and monotonic elapsed durations only, once per event:

`quick_auth_requested`, `capture_ready`, `authorize_generated`, `clipboard_ready`,
`wechat_launch_dispatched`, `callback_captured`, `authenticated_home`, `auth_rejected`.

Metrics: `elapsed_ms`, `request_to_capture_ready_ms`,
`capture_ready_to_authorize_generated_ms`, `authorize_generated_to_clipboard_ms`,
`clipboard_to_wechat_launch_dispatch_ms`,
`authorize_generated_to_wechat_launch_dispatch_ms`, and
`authorize_generated_to_callback_ms`.

Callback timing is recorded when the foreground service accepts the matched
captured callback; Home timing follows the existing authenticated-Home decision.
Launch timing measures intent dispatch, not WeChat's first rendered frame.
Missing/failed launch has no successful-dispatch marker. No URL, `r/t/state/code`,
Cookie value, message content or wall-clock timestamp enters the new timing logger.
No diagnostic-export product or new credential persistence was added.

## Verification

All OAuth inputs in deterministic tests are synthetic and use
existing loopback HTTP fixtures, never WeChat/Wahlap authentication.

Added coverage includes readiness ordering, grant/denial, double tap, recreation,
stale permission/readiness/execution events, foreground host replacement, background
transition, startup/generation/clipboard/launch failure, cancellation including
uncancellable late completion, terminal cleanup, fresh retry and the unchanged
three-attempt budget, safe timing grammar, manual/quick exclusion, public launcher
flags and sensitive clipboard preview. Service integration exercises actual
request admission, callback identity, authenticated synthetic Home, importer and
Room persistence: duplicate callback produces one complete import/batch, and three
synthetic rejections retain the existing retry/retry/FAILED sequence.

Private evidence directory:
`C:/Users/Daozh/.codex/diagnostics/AUTH-NET-4A-20261006`.
Build-only init scripts keep test-worker temporary paths short and set the
validation applicationId without changing tracked production configuration.

- Targeted command: `:app:testDebugUnitTest --tests '*QuickAuth*' --tests '*BoundedFreshAuthServiceTest' --tests '*WahlapOAuthAttemptTest' --tests '*ImportForegroundServiceTest' --tests '*WahlapImportRunnerTest' --tests '*WahlapHookBridgeTest'`.
  **68 tests / 7 suites, zero failures/errors/skips; BUILD SUCCESSFUL.**
- Final full command: `test :core:model:jvmTest :app:assembleDebug`.
  **684 JUnit entries / 127 suites: 683 passed, 1 skipped, zero failures/errors;
  BUILD SUCCESSFUL.** Entries include debug/release variants, not 684 unique methods.
  The existing optional `JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`
  skipped because its live fixture was unset. **30 new methods** were added; no
  existing AUTH-NET assertion was removed or weakened.
- Independent validation build: `:app:assembleDebug` with the private applicationId
  init script; **BUILD SUCCESSFUL**. Tracked app version remains **0.3.0-beta / 14**.
- Tablet installation at **04:09 UTC+08** succeeded with `install -r` into
  `dev.fluentmai.android.validation`. Cold launch opened the import screen in
  **1,141 ms**; screenshot verified the primary action and secondary manual controls.
  The real quick-auth button was **not tapped**. No capture was started.
- The installed APK was pulled back and matched the built validation APK SHA-256:
  `e8757de42f41fbb10934f12d00f52313302b18b29b52148136ff01c6f761f0bb`.
- After process stop/cold start, filtered startup markers counted **0** quick-auth
  requests, **0** authorize generations and **0** captured callbacks; validation
  `dumpsys activity services` reported **(nothing)**. This is startup/source smoke
  evidence of no credential replay, not a packet-level network audit.
- Control package `dev.fluentmai.android` remained **0.2.9-beta / 13**. Its SHA-256
  `d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`
  and installation/update metadata matched before and after. It was not installed
  over, cleared or launched. Validation data was not cleared; its database contents
  were not independently compared in this task.
- `git diff --check`: passed. Gitleaks 8.30.1 scans of the final tracked-source
  snapshot and commit range: zero findings. Generated Gradle files/locked caches
  are excluded from the tracked-source snapshot.

### Changed files

Production: `app/src/main/AndroidManifest.xml`; `app/src/main/java/dev/fluentmai/android/vpn/core/LocalVpnService.java`;
`app/src/main/kotlin/dev/fluentmai/android/{MainActivity,ImportForegroundService,ImportTaskState,WahlapHookBridge,WahlapHookHttpService,QuickAuthCoordinator,QuickAuthRuntime}.kt`;
`feature/import/src/main/kotlin/dev/fluentmai/android/feature/importflow/ImportScreen.kt`.

Tests: `app/src/test/kotlin/dev/fluentmai/android/{QuickAuthCoordinatorTest,QuickAuthAndroidTest,BoundedFreshAuthServiceTest}.kt`.
Documentation: `docs/research/auth-net/device-history/{AUTH-NET-4A,INDEX}.md`.

## Original engineering deferral — October 6

**NOT RUN / DEFERRED BEFORE 07:05.** Engineering/smoke ran before 07:05,
overlapping the 04:00–07:00 maintenance window. This task spent **zero** real
OAuth transactions. Owner-assisted acceptance requires a separate explicit
continuation after 07:05; the agent does not wait idle until that time.

At October 6 engineering closure, this binary's app-side authorize-to-launch and
authorize-to-callback latencies, real Home authentication and complete import
remained **UNOBSERVED**. Historical
TIMING-1 complete-import evidence belongs to its previous exact APK and is not
promoted into confirmation of this build. Deterministic tests and ordinary launcher
smoke do not award **LOW_LATENCY_HANDOFF_VALIDATED**.

The deferred acceptance plan was: the Owner taps the primary action and performs all WeChat
paste/send/open steps. Maximum **three fresh transactions**, no deliberate delay
experiment. Measure the two authorize-to-launch/callback metrics and actual Home
YES/NO; allow any authenticated import to finish naturally. Server rejection alone
does not fail a correctly measured handoff. No acceptance claim should imply
automatic navigation to File Transfer Assistant: that capability is unproven.

## AUTH-NET-4A-ACCEPT — real Owner quick handoff, October 8

**LOW_LATENCY_HANDOFF_VALIDATED. AUTH_NET_COMPLETE_IMPORT_VALIDATED.**
One fresh transaction was used; the successful handoff and COMPLETE import met
the task's early-stop rule. Attempts 2 and 3 were not generated. No product defect
was observed, and no production source was changed.

### Exact-binary preflight and authorization

- `git fetch origin` verified `origin/auth-net-1-v030` at
  `50084517247df3fcc778fb564588ba3ff7f6d85f`; the clean 4A worktree was at that
  same source HEAD. The original workspace and its untracked diagnostics remained
  untouched. PR #7 was OPEN and Draft before validation.
- ADB read operations selected only the tablet and verified model **24018RPACC**,
  API **36**, using the existing tablet serial binding. The Owner phone was not
  accessed.
- Installed `dev.fluentmai.android.validation`, **0.3.0-beta / 14**, matched the
  required SHA-256 before and after:
  `e8757de42f41fbb10934f12d00f52313302b18b29b52148136ff01c6f761f0bb`.
  No rebuild, reinstall, uninstall or data clear was performed.
- Control `dev.fluentmai.android`, **0.2.9-beta / 13**, retained SHA-256
  `d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`.
  Its first-install and last-update metadata remained **2026-09-22 11:46:51**,
  matching both this run's preflight and the October 6 control baseline.
  Validation first-install **2026-10-03 11:15:16** and last-update
  **2026-10-06 04:09:21** likewise remained unchanged.
- The Owner confirmed WeChat was logged in and prepared in a public-account
  conversation, classified **OTHER_CHAT**, rather than File Transfer Assistant.
  The preparation prompt preceded any generation. The Owner later explicitly
  permitted testing before **04:00** or after **07:05**; the original >=07:05
  restriction was therefore relaxed for this pre-04:00 run. The **04:00–07:00**
  maintenance exclusion was retained. The real transaction ran at
  **02:21:16–02:22:08**, outside maintenance. The earlier time waiver in TIMING-1
  was not used as authorization for this session.
- A validation-UID-scoped, privacy-filtered logcat observer was active before the
  Owner was instructed to tap **立即微信授权**. Only fixed QUICK_AUTH events,
  numeric durations, fixed request labels, status/outcome primitives and a broad
  foreground-package classification were retained. No URL, OAuth value, Cookie
  value, clipboard content, chat text, screenshot or UI hierarchy was recorded.
  Synthetic parser checks confirmed unrecognized/secret-bearing timing fields
  were dropped; these checks generated no OAuth transaction.

### Attempt and actual event ordering

| Attempt | Requested, UTC+08 | Fresh generations / clipboard-ready / launch-dispatched | Callback final status | Home authenticated | WeChat foreground / destination | Final import |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 02:21:16.466 | 1 / 1 / 1 | 200 | YES | ADB: WECHAT; Owner: OTHER_CHAT, prepared public-account conversation | COMPLETE, Owner-confirmed result page |

| Event | Monotonic elapsed ms from quick-auth request | Device log timestamp, UTC+08 | Count |
| --- | ---: | --- | ---: |
| quick_auth_requested | 0 | 02:21:16.466 | 1 |
| capture_ready | 279 | 02:21:16.745 | 1 |
| authorize_generated | 966 | 02:21:17.432 | 1 |
| clipboard_ready | 970 | 02:21:17.435 | 1 |
| wechat_launch_dispatched | 983 | 02:21:17.448 | 1 |
| callback_captured | 8,107 | 02:21:24.573 | 1 |
| authenticated_home | 15,923 | 02:21:32.388 | 1 |

Core assertion passed at the telemetry's millisecond resolution:
**279 < 966 < 970 < 983**. The independent existing fresh-generation log witness
occurred once at **02:21:17.430**, also after capture readiness. The existing
callback bridge and replay-start witnesses each occurred once. No duplicate
generation, clipboard-ready or launch-dispatched event was observed, and no
automatic additional transaction appeared through cleanup.

`capture_ready` is the coordinator's source-linked matching-execution gate:
Waiting execution ownership, ready VPN tunnel and both ready HTTP listeners must
precede generation. Those readiness internals were not independently instrumented
on-device. Log ordinal 1 groups the sequential request stream; execution/request
identifiers are not exposed by this telemetry. One matched callback acceptance,
one replay and one resulting batch corroborate success-path ownership. No auth
rejection occurred, so a fresh retry was neither needed nor exercised in this run.
Existing retry/three-attempt code and its deterministic coverage remain unchanged;
this observation does not award a new hardware retry marker.

### App-side timing

| Required metric | ms |
| --- | ---: |
| request_to_capture_ready_ms | 279 |
| capture_ready_to_authorize_generated_ms | 687 |
| authorize_generated_to_clipboard_ms | 4 |
| clipboard_to_wechat_launch_dispatch_ms | 13 |
| authorize_generated_to_wechat_launch_dispatch_ms | **17** |
| authorize_generated_to_callback_ms | **7,141** |

These are the product's monotonic duration fields. Device log timestamps are
separate wall-clock samples and can differ by a millisecond from their differences.
Launch latency ends at successful intent dispatch, not WeChat's first rendered
frame. Callback latency ends at foreground-service acceptance of the matched
capture, before callback HTTP response/Home authentication. No hard millisecond
threshold, OAuth TTL, server-expiry cause or population success rate is inferred.

### Actual WeChat destination and manual work

The passive post-dispatch package sample found **com.tencent.mm** in the resumed
foreground activity. The Owner independently reported that WeChat automatically
came forward and landed directly in the prepared public-account conversation:
**OTHER_CHAT**. This establishes that no manual app-switch/search was needed to
bring WeChat forward or find that already-prepared destination in this attempt.

Pasting, sending and opening the authorization link remained Owner work, following
the displayed instructions. The agent made no tablet UI input, operated no chat,
and used no UIAutomator, debugger, race injection or deliberate transaction delay.
The launcher resumed a prepared conversation in this one observation. It proves
neither reliable repeated landing behavior nor a public direct-conversation/File
Transfer Assistant navigation API. Launch-unavailable fallback was not exercised.

### Authenticated import and completion

Callback replay completed once with final HTTP **200** at **02:21:27.537**.
The Home request completed HTTP **200** at **02:21:28.498**; the coordinator's
separate **authenticated_home** marker subsequently confirmed actual authenticated
Home. All listed stage requests succeeded on their first request attempt:

| Stage | Request completion, UTC+08 | Result / evidence basis |
| --- | --- | --- |
| recent | 02:21:37.118 | HTTP 200 / success; play-records label before difficulties |
| BASIC | 02:21:37.555 | HTTP 200 / success; explicit difficulty label |
| ADVANCED | 02:21:38.205 | HTTP 200 / success; explicit difficulty label |
| EXPERT | 02:21:56.120 | HTTP 200 / success; explicit difficulty label |
| MASTER | 02:22:03.701 | HTTP 200 / success; explicit difficulty label |
| RE_MASTER | 02:22:06.359 | HTTP 200 / success; explicit difficulty label |
| supplemental / rating target | 02:22:06.803 | HTTP 200 / success; rating-target-music label |
| PC, five page requests | 02:22:07.518–02:22:08.691 | All HTTP 200 / success; source-order inference from five subsequent play-records requests |
| final outcome | COMPLETE | Owner explicitly confirmed **导入完成**, without a partial-data warning; batch addition and natural shutdown independently corroborated |

As in TIMING-1, efficient PC requests share the play-records label. Their placement
after supplemental processing and `runRealImport`/`captureEfficientPc` ordering
support the PC assignment; no independent PC-start transition was logged. Listed
times are request completions, not stage starts, parser finishes or persistence
timestamps. The existing logs do not directly expose the terminal result enum.
The COMPLETE determination therefore includes explicitly identified Owner UI
evidence. No score failure was injected; score retry recovery and PARTIAL
persistence remain unvalidated.

### Preservation and cleanup

Read-only validation DB/WAL snapshots were taken with no validation service active.
Only aggregate counts and import-batch digests were retained; temporary database
copies were removed. No control private data was read.

| Aggregate | Before, 02:15:00 | After, 02:26:02 | Change |
| --- | ---: | ---: | ---: |
| Score records | 1,731 | 1,734 | +3 |
| Import batches | 3 | 4 | **+1** |
| Quarantine records | 6 | 8 | +2 |
| Recent play records | 50 | 89 | +39 |
| Chart play-count rows | 5,498 | 5,498 | 0 |

The previous three complete import-batch rows retain their exact aggregate digest.
Exactly one new batch was created, with **1,821 parsed**, **3 inserted**,
**1,729 updated**, **87 duplicate skips**, **2 quarantined**, **0 rejected**.
This is expected import persistence, not whole-database byte identity or independent
per-record semantic validation. There were zero rejected OAuth attempts in this
session; rejection/no-batch behavior is therefore **NOT EXERCISED**, not newly proved.

After natural completion, read-only checks found **0 validation ServiceRecord
entries**, **0 LISTEN entries on 8284/9457** in `/proc/net/tcp` and `tcp6`, and
**0 tun-number interfaces** in `/sys/class/net`. The `ss`/`ip link` netlink
queries lacked permission; the readable proc/sys checks above supplied the socket
and tunnel observations. No device force-stop or service-disconnect command was
issued. VPN authorization still lists the validation package; that retained
permission is distinct from an active capture tunnel.

The host-only observer was stopped after evidence collection, and both its Python
and task-owned ADB logcat PIDs were verified absent. No debugger or ADB forward
was created. Installed validation/control APK hashes and installation metadata
were rechecked after the import and matched preflight. No uninstall/data clear,
phone access, ChatGPT opening or CTO relay occurred.

Private evidence:
`C:/Users/Daozh/.codex/diagnostics/AUTH-NET-4A-ACCEPT-20261008`.
Only this sanitized report and `INDEX.md` are committed. Source/build/test results
from engineering are not rerun or elevated into independent hardware evidence.
PR #7 remains **Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE**. The two awarded
markers close this bounded exact-binary handoff/full-import observation, not
general release acceptance or root-cause forensics. Owner report delivery is manual.
