# AUTH-NET-4A — low-latency WeChat handoff

2026-10-06, Asia/Shanghai (UTC+08:00).
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE. PR #7 remains Draft.**

Disposition: **READY_FOR_OWNER_QUICK_HANDOFF_VALIDATION_AFTER_0705**.
No real OAuth transaction was generated in this task. No device handoff or
authenticated-import acceptance marker is awarded to the new binary.

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

## Real OAuth acceptance

**NOT RUN / DEFERRED BEFORE 07:05.** Engineering/smoke ran before 07:05,
overlapping the 04:00–07:00 maintenance window. This task spent **zero** real
OAuth transactions. Owner-assisted acceptance requires a separate explicit
continuation after 07:05; the agent does not wait idle until that time.

The new binary's app-side authorize-to-launch and authorize-to-callback latencies,
real Home authentication and complete import remain **UNOBSERVED**. Historical
TIMING-1 complete-import evidence belongs to its previous exact APK and is not
promoted into confirmation of this build. Deterministic tests and ordinary launcher
smoke do not award **LOW_LATENCY_HANDOFF_VALIDATED**.

For later acceptance, the Owner taps the primary action and performs all WeChat
paste/send/open steps. Maximum **three fresh transactions**, no deliberate delay
experiment. Measure the two authorize-to-launch/callback metrics and actual Home
YES/NO; allow any authenticated import to finish naturally. Server rejection alone
does not fail a correctly measured handoff. No acceptance claim should imply
automatic navigation to File Transfer Assistant: that capability is unproven.
