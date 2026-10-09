# UX-BG-1 — remove intrusive battery settings requests

Date: 2026-10-09, Asia/Shanghai (UTC+08).
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE. PR #7 remains Draft.**

Disposition: **UX_BATTERY_GATE_FIXED**, scoped to the battery-settings UX and
two ordinary phone capture starts. Service-record cleanup has the separate
limitation below. No real OAuth was performed or authorized for this task.

## Canonical source and implementation

Fetched `origin/auth-net-1-v030` at the expected starting SHA
`b2759309a3a8c1a61274e14220df94d811d3d1f5`; no remote advancement was present.
A clean independent worktree at `D:/Code/FluentMai-ux-bg-1`, local branch
`codex/ux-bg-1`, preserved all existing workspaces and diagnostic artifacts.
Implementation commit: `a3940b55920d1515debf31a32458f00d2b7c3678`.

The Owner reported that legacy **启动捕获** repeatedly opened HyperOS **电量详情**,
even after returning without changing settings. Source confirms the mechanism:
`rememberRequestImportBackgroundAccess()` rechecked
`PowerManager.isIgnoringBatteryOptimizations(packageName)` on each invocation.
Without exemption, it launched `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
with the package URI. Returning reset its in-flight flag and requested notifications,
but did not grant exemption; a later invocation could launch the same request again.

The complete caller trace at the starting SHA was:

| Entry | Original ordering | Fixed ordering |
| --- | --- | --- |
| Legacy capture | Battery request, then `requestAuthorization(false)`; notification request after battery Activity returns | Direct notification request, then unchanged VPN/coordinator path |
| Manual fresh-auth retry | Same `startHookCapture()` callback and ordering as legacy capture | Same direct notification/VPN path; existing retry identity/budget preserved |
| Cookie import | Start import foreground service, then battery wrapper; notifications deferred until return | Same foreground service, then direct notification request; no capture VPN |
| Quick Auth / quick reauthorization | `requestAuthorization(true)` directly; no battery wrapper or UI notification request | Unchanged |

The wrapper was an intrusive detour, not a synchronous service admission gate:
startup could continue while battery settings were foreground. This was also
observed on the original phone. No collaborator/commit attribution is asserted.

Removed the unused requesting helper and its exemption-needed predicate.
`ImportBackgroundAccess.kt` retains only the read-only background diagnostic.
After a complete production-reference audit across `app`, `feature` and `core`,
removed `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from the manifest: no requesting
caller or explicit optional settings action remains. The installed validation
manifest also omits it. No vendor-specific replacement or optional settings UI
was added.

`ImportNotificationAccess.kt`, `QuickAuthCoordinator`, `QuickAuthRuntime`,
`ImportForegroundService`, `WahlapOAuthAttempt`, `WahlapHookBridge`, `core`, and
`app/build.gradle.kts` are unchanged from the starting SHA. MainActivity changes
only the shared notification-function binding. Capture readiness, OAuth generation,
clipboard/launcher handoff, execution isolation, three-attempt fresh-auth budget,
callback/Home classification, score retries, COMPLETE/PARTIAL persistence and
foreground-service/WakeLock lifecycle retain their original production code.
Production version and applicationId are unchanged.

## Phone baseline and APK provenance

Only serial `2923ae26`, model **23116PN5BC**, Android **16 / API 36** was accessed.
No tablet operations occurred. Device identity was verified before device access.

| Artifact | Package / version | SHA-256 |
| --- | --- | --- |
| Original installed phone APK | `dev.fluentmai.android`, `0.3.0-beta / 14` | `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da` |
| Built validation APK | `dev.fluentmai.android.uxbgvalidation`, `0.3.0-beta / 14` | `057732829131946615ec3ccd83b598d816e927f8240090784744d800870f2743` |
| Installed validation APK, pulled back | Same separate package/version | `057732829131946615ec3ccd83b598d816e927f8240090784744d800870f2743` |

Original installation: **2026-10-03 00:15:13**; last update:
**2026-10-03 09:52:34**. Its APK contains the battery-request helper/action and
`WahlapImportHttpClient`, but lacks `QuickAuthCoordinator` and `WahlapKtorClient`.
It is a historical build, not a source-equivalent witness for the current branch;
the exact original source commit remains unknown.

The validation package was absent before installation. Private Gradle init scripts
set only the build applicationId and a debug-manifest label **FluentMai UX-BG-1**.
Production configuration was not edited for validation. Manifest inspection
verified the original fully qualified Activity/services, VPN binding permission
and the unique `${applicationId}.updates` FileProvider authority. The first
installation was rejected by HyperOS with `INSTALL_FAILED_USER_RESTRICTED`;
after the Owner unlocked the phone and confirmed readiness, a new-package
`adb install` succeeded at **13:40:03**, without `-r` or an existing-package update.
The APK was then pulled back and independently hashed; build/install match.

## Original defect observation and device-state boundaries

At **13:22:26**, the Owner reported the current phone screen was **电量详情**.
Read-only ADB independently observed resumed
`com.miui.securitycenter/com.miui.powercenter.legacypowerrank.PowerDetailActivity`
above original `dev.fluentmai.android/.MainActivity`. A screenshot confirmed the
FluentMai battery page and selected **智能限制后台运行（推荐）** policy.
Read-only Intent resolution returned the same destination Activity for the
platform battery-exemption request and original package URI. Device idle whitelist
contained no FluentMai exemption. Original import, HTTP hook and VPN services
were all running, with the original package owning the active VPN (type 1).

This is an Owner-triggered original reproduction observed in its redirected state,
not an Agent-recorded click-to-launch trace or proof of the original source SHA.
The APK action marker, unchanged exemption, resolver and foreground destination
corroborate the source-supported mechanism. No real OAuth or score import was needed.

Clash Meta is installed. The Owner explicitly confirmed no other VPN was active
and authorized temporary FluentMai capture. A previously retained Clash package
in `vpn_management` had VPN type **-1**, not an active tunnel. The Agent neither
disconnected nor operated Clash. At the Owner's manual stop, **13:24:28** observation
found no original service records and VPN type **-1**. The Owner confirmed battery
settings were not changed. Both packages must never capture simultaneously.

At **13:40:22** preflight, both packages were absent from the battery exemption
whitelist, the new package lacked notification runtime permission, and neither
package had running services. The original package had `START_FOREGROUND` and
`RUN_ANY_IN_BACKGROUND` AppOps allowed. These read-only eligibility diagnostics
do not promise unrestricted background operation under every later device state.

## Fixed-phone acceptance

The preparation gate was presented after installation/preflight. The Owner
confirmed the separate validation app's import page was ready, other VPNs remained
inactive, and the already authorized capture was safe. The Owner manually operated
only **启动捕获 / 停止捕获**. No **立即微信授权**, **复制授权** or WeChat operation occurred.

A read-only observer collected privacy-filtered activity components,
service/VPN states and fixed OAuth event counters. It discards raw credential text.
No ADB input, automated taps, UIAutomator input, Computer Use, process forcing,
or diagnostic hooks are used.

| Acceptance step | Result |
| --- | --- |
| Original-phone reproduction | Observed redirected state, as described above |
| Fixed capture A | At 13:43:16.968, three foreground services and active validation VPN; Owner confirmed no battery screen/prompt; screenshot showed `Capture started` and enabled copy control |
| Natural cleanup A | At 13:44:11.507, HTTP/VPN services ended, no foreground service state, VPN type -1; subsequent exact import-tag WakeLock check was absent; one non-foreground ImportForegroundService record remained |
| Fixed capture B | At 13:47:17.838, three foreground services and active validation VPN again; Owner confirmed no battery screen/prompt; screenshot showed `Capture started`; no stale VPN/startup conflict |
| Natural cleanup B | At 13:48:12.215, same capture-resource teardown; 13:49:30 check found no foreground/ongoing capture notifications or import WakeLock; non-foreground service record remained |
| Battery screen during repaired attempts | Neither attempt opened battery details or requested exemption; Owner confirmation and zero matching activity events |
| VPN / notification behavior on repaired phone | First start showed normal ConfirmDialog and GrantPermissionsActivity; POST_NOTIFICATIONS changed false → true by Owner grant; second start reused grants; exemption stayed absent |

The observer ran **13:40:30–13:49:36** and recorded **0** quick-auth requests,
**0** authorize-generation markers, **0** captured-callback markers and **0** battery
details activity events. Original service count stayed zero throughout validation.
This is fixed-marker/source/Owner evidence of no OAuth generation, not a packet-level
network audit. The observer's host logcat processes were ended naturally after
the final snapshot; no phone process was forced to stop.

**Cleanup limitation:** both manual stops left `ImportForegroundService` with
`startRequested=true` but no foreground state, active VPN, HTTP service or import
WakeLock. A terminal AUTO_CANCEL notification and its group summary remained;
there was no ongoing/foreground capture notification. Complete service destruction
is therefore **not validated**. Starting-branch source has two cancellation paths
in `stopHookCapture()` (coordinator cancellation and direct cancellation), and
the service's non-Waiting CANCEL branch does not stop itself; a duplicate-cancel
explanation is source-supported but not causally proven on the starting binary.
These exact code paths were preserved as required; no lifecycle fix or force-stop
was used. Socket enumeration was denied by the phone, so direct port/listener
closure is unverified; HTTP-service disappearance is observed. This limitation
does not obstruct either capture start or change the scoped battery-gate verdict.

## Deterministic checks

Nine new regression methods use an explicitly non-exempt PowerManager state and
the real coordinator-to-foreground-service admission path: legacy and quick capture,
manual and quick fresh retry, Cookie foreground admission without VPN, repeated
capture, VPN grant/denial, plus actual Compose-callback wiring and production
source/manifest request-absence checks. Cookie execution is a no-network/no-write
fixture. The obsolete exemption-request expectation is replaced by repeated
read-only diagnostic checks with and without exemption. Existing notification
grant rechecks and pre-Android-33 coverage remain. Runtime tests bypass Compose
button interaction; source wiring checks and Owner phone acceptance cover that boundary.

Targeted command (private init script supplies the test temporary path):

```text
:app:testDebugUnitTest --tests '*ImportBackgroundAccessTest' --tests '*ImportBatteryGateRegressionTest' --tests '*QuickAuth*' --tests '*BoundedFreshAuthServiceTest' --tests '*WahlapOAuthAttemptTest' --tests '*ImportForegroundServiceTest' --tests '*WahlapImportRunnerTest' --tests '*WahlapHookBridgeTest'
```

**80 passed / 0 failed / 0 errors / 0 skipped, 9 suites; BUILD SUCCESSFUL.**
This includes all seven existing AUTH-NET-4A targeted suites.

Full command: `test :core:model:jvmTest :app:assembleDebug --continue`.
Final retained JUnit results: **701 passed / 0 failed / 0 errors / 1 skipped,
702 entries in 129 suites; BUILD SUCCESSFUL.** These include Debug/Release
executions, not 702 unique methods. The sole skip is the existing optional
`JapaneseConstantCatalogTest.optionalLiveSnapshotParsesAndReportsCoverage`, whose
live fixture was unset. No external OAuth is used.

The first full invocation recorded **699 passed / 2 failed / 1 skipped**:
unchanged `PlayActivityPersistenceTest.sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables`
failed with `SQLITE_CANTOPEN` in Debug and Release using `D:/fmuxbgtmp`.
Changing only the private test-worker temporary root to shorter `D:/ux1` and
rerunning the full command passed both failed tasks; the other tasks were up to date.
No migration implementation, test assertion or tracked build setting changed.
This is consistent with the previously recorded Windows temporary-path limitation.
The separate validation `:app:assembleDebug` also succeeded.

## Preservation, delivery and limitations

Baseline, post-original-stop, post-validation-install and final **13:49:31**
read-only checks showed
the original APK/version, installation/update timestamps and all three database
file hashes unchanged: database `3e14b91b…`, WAL `e2fdb456…`, SHM `c08dfe1c…`.
No original installation/update/uninstall, data clear, database reset, score deletion,
account logout or battery-policy change occurred. No pre-existing validation
package was present or overwritten. This verifies these recorded files, not a
byte-level audit of every other preference/cache file on the phone.

Implementation file and commit scans with **Gitleaks 8.30.1** passed with zero
findings; `git diff --check` and staged diff checks passed. Delivery targets only
`origin/auth-net-1-v030`, with this report and the history index. PR #7's refreshed
description distinguishes previously validated exact-binary hardware findings
from still-unvalidated score retry recovery/PARTIAL persistence and this task's
new battery-only acceptance. Existing hardware acceptance markers remain
scoped to their historical exact binaries in [INDEX.md](INDEX.md); none is promoted
to the new UX-BG-1 binary. Score retry recovery and PARTIAL persistence remain
unvalidated on real hardware. No merge, release, ChatGPT opening or CTO relay.

Private evidence: `C:/Users/Daozh/.codex/diagnostics/UX-BG-1-20261009/`, including
original/acceptance phone snapshots, original battery screenshot, original DEX
markers, build/installed identities, filtered observations, critical-source blob
comparison, Gradle logs/JUnit summaries and Gitleaks reports.
