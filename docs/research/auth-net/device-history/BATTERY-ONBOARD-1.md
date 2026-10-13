# BATTERY-ONBOARD-1 — optional, one-time battery onboarding

Baseline: `origin/master` at `1990ed8d6c8d7490815e93a3882c7a79218cacae`,
fetched and verified on October 13, 2026. Work is isolated on
`codex/battery-onboard-1`. PR #7 and its branch are outside this task.

## Symptom and source audit

The Owner's earlier Xiaomi/HyperOS observation was a repeated battery-details
redirect when exemption remained absent. Prior UX-BG-1 findings belong to their
historical APK; they are not acceptance of this implementation. Another device's
reported screen-off interruption motivates an optional protection entry, without
establishing a guarantee of background reliability.

Current master still contained `rememberRequestImportBackgroundAccess()`.
Each call checked `PowerManager.isIgnoringBatteryOptimizations(packageName)`;
false launched `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The result callback
continued the original operation regardless of grant, but did not record a
decision. Therefore returning without granting led to another request next time.

The complete caller audit found two wrappers in `MainActivity`:

| Path | Current master | New behavior |
| --- | --- | --- |
| 立即微信授权 | Battery wrapper, then notifications and `requestAuthorization(true)` | Fresh user click after independent onboarding; notifications and VPN gate retained |
| 重新授权并打开微信 | Same `onQuickAuthorization` callback | Same new entry; no second introduction or battery request |
| Cookie import | Starts existing service, then battery-wrapped notifications | Existing service and direct notification request; no battery settings |
| Cancellation / retry | Cancellation has no battery request; retry shares Quick Auth | No new battery entry or continuation |
| First relevant use | No persistent battery introduction | First idle visit to Import consumes and displays the app introduction |
| Settings | No optional entry | 后台导入保护 with read-only current state and 前往系统设置 |

Audited `ImportBackgroundAccess`, `ImportNotificationAccess`, `MainActivity`,
`ImportForegroundService`, `QuickAuthCoordinator`, `QuickAuthRuntime`,
`ImportTaskState`, Manifest, Import screen and existing tests against this baseline.
No automatic battery settings caller remains. There is no separate legacy capture
button in this baseline's Import screen; its fresh and retry buttons share the
Quick Auth callback.

## Product behavior and durable state

The app introduction uses the agreed text:

> 部分手机可能会限制熄屏后的成绩导入。可以选择调整后台运行设置，或直接继续使用。

Both choices are available: **前往设置** and **暂不设置**. Back/outside dismissal
acts as declining. Battery exemption is optional; no setting change is required
for import. An active transaction defers the introduction until it is idle.

`ImportBatteryOnboardingStore` stores `decision` in app-private
`import_battery_onboarding` SharedPreferences. A synchronized claim commits
`introduced` **before displaying** the automatic introduction. This deliberately
prevents a second automatic display even if the Activity/process is destroyed
before a choice. The user can always use the explicit Settings entry later.

Choosing either action commits `declined` or `settings` on `Dispatchers.IO`.
The settings Activity is launched only after that commit succeeds. A failed write
or unavailable settings Activity produces a short nonblocking explanation and
permits normal use. No Room schema or migration changes were made. General
"恢复默认设置" does not clear this separate preference file; app-data clearing or a
new installation may reset it. Exemption grants and revocations never reset it.

Checking the preference, displaying the introduction, saving the choice and
opening system settings all gate user import starts. No import/OAuth continuation
is retained. Returning from settings only refreshes the read-only state and
releases the gate; a new user click is needed. The activity-result callback and
pause/resume handling cover ordinary Back and Home -> app returns. Pending
settings state is saveable across recreation; durable acknowledgment prevents
reintroducing after process death.

## Android boundaries and compatibility

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` remains in the unchanged Manifest.
The explicit entry tries the public per-package request when exemption is absent,
then the public battery-optimization settings list if the first launch fails.
Already-exempt users or a missing PowerManager use the public settings list.
No Xiaomi internal Activity names or battery-setting mutation were added.
The OS controls whether a request opens a dialog or an OEM details page.

Current status is refreshed on resume. Missing PowerManager is reported as an
unknown state, rather than pretending exemption is granted. Rapid settings taps
are guarded before the disk write starts and while external settings are pending.
The explicit entry is disabled during an active import/authorization.

Notification permission, notification-channel creation, `VpnService.prepare`,
VPN result handling, foreground services and WakeLock ownership retain their
existing behavior. These required operational permissions may be requested again
when Android requires them; battery acknowledgment does not grant or bypass them.

Quick Auth implementation is unchanged:
capture ready -> fresh authorize -> clipboard -> WeChat launcher dispatch.
No fresh URL is generated by onboarding, settings launch or settings return.
Execution identity, three-attempt budget, callback/Home classification, HTTP
retry, import outcome/persistence, diagnostic JSON and timing are unchanged.

Platform references: [battery settings actions](https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
[synchronous preference commit](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()).
Commit is run off the main thread to finish persistence before external launch.

## Deterministic validation

Regression coverage uses Robolectric, Compose UI tests, local fixtures and existing
Quick Auth/diagnostic suites. It performs no real Wahlap/WeChat network transaction.

| Requirement | Evidence |
| --- | --- |
| At most once, including interruption before a choice | Durable claim and composition restoration tests |
| Decline / absent exemption / three revisits / retry | Store, entry gate and Compose tests |
| Settings choice and commit before launch | Compose Activity result-launch inspection and durable preference test |
| Back return with/without exemption; no automatic continuation | Real ActivityResultRegistry dispatch tests |
| Activity recreation | Robolectric Activity recreation and Compose save/restore |
| Process restart semantics | Read committed XML, evict Android preference cache, reload from disk; hardware process restart remains pending |
| Explicit app setting / later revocation | Production SettingsScreen callback, grant/revoke and no-repeat tests |
| No OAuth while onboarding/settings pending | Production import gate plus unchanged AUTH-NET-4A coordinator regressions |
| VPN / notification flows | Existing permission-required, grant/denial tests; actual notification launcher test |
| Cookie / retry wiring | Production entry-point contract and existing Cookie/authorization UI suites |
| Duplicate taps / unavailable intents / missing power service | Compose pending/rapid taps, fallback and nonblocking failure tests |
| Diagnostic JSON and timing | Existing IMPORT-OBS-1 suites |

The targeted suite passed **123 tests / 15 suites**, zero failures, errors or
skips. It covers `ImportBattery*`, `ImportBackgroundAccessTest`, `QuickAuth*`,
`BoundedFreshAuth*`, `ImportObservabilityTest`, `ImportDiagnostic*`,
`WahlapCookieImportCredentialsTest` and the full Import feature Debug suite.

The full Gradle invocation passed:

```text
gradlew.bat --init-script <private-test-temp-init> test :core:model:jvmTest :app:assembleDebug --continue --console=plain
```

Retained JUnit XML: **820 entries / 141 suites**, **819 passed**, zero failures or
errors, one existing optional Japanese local-fixture skip. Counts include Debug
and Release variants, not 820 independent test scenarios. AUTH-NET-4A and
IMPORT-OBS-1 regressions remain green. Debug APK assembly also passed.

An initial full run encountered the unchanged Windows SQLite migration test's
`SQLITE_CANTOPEN`; setting only that module's test temporary directory to the
short private `D:/fmb1tmp` resolved it. Neither database nor migration tests were
modified. The Compose host is registered in Robolectric's PackageManager by a
test rule for both build variants, with new UI tests in the target API-34 sandbox
separate from the existing API-33 bare-Activity diagnostics. This avoids test
host resolution and shared-recomposer interference without altering those
diagnostic tests or production permission behavior.

## Initial delivery: phone acceptance deferred

The target phone was not reachable as `2923ae26` during the read-only ADB preflight.
The Owner then explicitly selected completion of code, tests and Draft PR in this
round, with phone acceptance deferred. No application was installed, started,
updated, uninstalled or cleared on any device; no phone taps, OAuth, score import,
VPN change or battery-setting change were performed.

Original APK identity, installed hash and data identity could not be refreshed.
They remain **unverified this round**, rather than inherited from historical
UX-BG-1 evidence. Future installation must first inventory this phone's packages,
verify the independent validation ID is absent and record the original APK/data
identity. Any package/data conflict requires stopping for Owner approval.

The validation build uses only private build overrides for
`dev.fluentmai.android.batteryonboardvalidation`, label `FluentMai Battery Validation`,
version `0.3.1-beta` / code `15`. Its exact committed source SHA and built APK hash
belong to the delivery receipt. No production application ID or version changed.
Installed validation APK SHA-256 is pending because this round installs nothing.

Owner phone Test A (both choices / decline), Test B (three revisits), Test C
(Activity recreation and normal restart), Test D (explicit system screen and Back)
and non-OAuth Test E remain pending. The actual HyperOS screen, lifecycle return
behavior and physical phone UI are not validated by the deterministic suite.

Verdict: **BATTERY_ONBOARD_1_CODE_READY_DEVICE_PENDING**.
**BATTERY_ONBOARD_1_PHONE_UX_VALIDATED** is not awarded in this round.
**SCREEN_OFF_IMPORT_RELIABILITY_NOT_REVALIDATED**. Exemption does not guarantee
successful screen-off import; that needs a separately authorized experiment.

## Delivery checks

`git diff --check`, the protected-source byte comparison and Gitleaks 8.30.1
changed-file scan passed. Only battery onboarding, its UI/entry points, test
support, corresponding regressions and this documentation/index are changed.
Manifest, version, notification helper, Quick Auth coordinator/runtime, import
service/task state and core modules match baseline bytes.

The exact final source SHA, APK identity/hash, committed-range secret scan and
Draft PR URL are recorded in the Owner delivery receipt. Only
`origin/codex/battery-onboard-1` is pushed; no master push, merge, release, version
bump or PR #7 modification. Report delivery is Owner manual only; no CTO relay
or ChatGPT interaction is part of this task.

## BATTERY-ONBOARD-1-ACCEPT — Xiaomi phone acceptance, October 13, 2026

Disposition: **BATTERY_ONBOARD_1_PHONE_ACCEPTANCE_PARTIAL**. The Owner confirmed
Test A, all three Test B visits, Test C and Test D passed. A later, additional
Owner-initiated authorization generated a fresh OAuth URL, so this session cannot
confirm the requested no-OAuth boundary. The phone then disconnected before a
final post-authorization installation/service check. This is not evidence of an
automatic-onboarding product defect. The unrestricted final acceptance marker
**BATTERY_ONBOARD_1_PHONE_UX_VALIDATED** is withheld for this bounded task.

### Preflight and exact binary

Starting implementation/source HEAD was
`2bc5152dbe6720ff322e4e9992a6d2985e8aea9f`. Fetch, local HEAD, remote branch HEAD
and PR #8 head all matched. PR #8 was open and Draft. The feature worktree was
clean. No production source, test or build work was repeated in this session.

Only the explicitly named Owner phone was accessed with ADB serial `2923ae26`:
Xiaomi `23116PN5BC`, Android `16`, API `36`, HyperOS `OS3.0`, build
`OS3.0.306.0.WNBCNXM`. It initially reported ADB state `device`; by the final
cleanup check the same target reported `device not found`. No tablet was accessed.

The pre-install inventory contained `dev.fluentmai.android` and the existing
`dev.fluentmai.android.uxbgvalidation`. Neither was replaced or modified.
`dev.fluentmai.android.batteryonboardvalidation` was absent, and was installed
side-by-side using ordinary ADB installation without a replacement flag. The
Agent did not launch it; the Owner opened it after read-only monitoring was ready.

| Identity | Original app | Independent battery validation app |
| --- | --- | --- |
| Package | `dev.fluentmai.android` | `dev.fluentmai.android.batteryonboardvalidation` |
| Version | `0.3.0-beta` / code `14` | `0.3.1-beta` / code `15` |
| APK SHA-256 | `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da` | `8bf45a955a4e55a75caa4c3b505418e8b6b5cd135892483ea95c20642625bd46` |
| Primary-user first installation | `2026-10-03 00:15:13` | `2026-10-13 19:05:11` |
| Last package update | `2026-10-03 09:52:34` | `2026-10-13 19:05:11` |

The supplied APK, existing build output and device-installed validation APK all
matched the expected hash. Fresh `aapt` badging verified the embedded application
ID, version and `FluentMai Battery Validation` label; `apksigner verify` passed.
Source provenance is the existing delivery receipt naming the exact starting
commit, the unchanged clean feature worktree and matching original build output;
the APK does not independently embed a source-commit attestation. No rebuild was
needed. No private app data or authentication material was used for this check.

### Owner-operated Test A/B/C/D observations

All screen interaction was manual. Read-only observation retained only selected
Activity components, validation service components and Quick Auth event names.
It stored no intent URLs, Cookie/token values, screen contents or scores.
There were no Agent taps, swipes, UIAutomator input, Activity launches, screenshots,
battery-setting changes or WeChat operations. Times below are host observation
times in Asia/Shanghai; device log time was approximately two seconds ahead and
is not used for latency conclusions.

| Test | Owner observation | Independent corroboration | Result |
| --- | --- | --- | --- |
| A — first introduction, decline | One 后台导入保护 introduction with 前往设置 and 暂不设置; declined successfully; Import page usable; no system redirect | Validation MainActivity opened at 19:06:12; no system battery Activity or validation services observed; whitelist unchanged at 19:06:42 | PASS |
| B1 — first revisit | No introduction, battery page or permission loop; responsive | No battery Activity or validation service start in the monitored revisit interval; in-app navigation itself was Owner-reported | PASS |
| B2 — second revisit | Independently confirmed the same expected behavior | Same read-only monitor; no automatic battery Activity | PASS |
| B3 — third revisit | Independently confirmed the same expected behavior | Same read-only monitor; no automatic battery Activity | PASS |
| C — normal exit and recreation | Owner dismissed only the validation task from Recents, reopened its icon and returned to Import; no introduction or system redirect | Old MainActivity destroyed/finished at 19:07:59; a new MainActivity created/resumed at 19:08:00 | PASS |
| D — optional Settings entry and return | Current status and 前往系统设置 entry existed; explicit click opened the system page; returned without changing exemption; Import did not replay the introduction or redirect again | One PowerDetailActivity created/resumed at 19:09:26; it finished at 19:09:30 and validation MainActivity resumed; no second battery Activity observed | PASS for battery UI; later extra authorization recorded below |

Actual HyperOS destination:
`com.miui.securitycenter/com.miui.powercenter.legacypowerrank.PowerDetailActivity`.
This was observed after the Owner's explicit Settings click, not inferred from
historical UX-BG-1 evidence or an Agent-launched intent. Activity recreation was
observed; process death itself was not independently measured. No preferences
were cleared, no first-run replay was forced and no crash was simulated.

### Battery state and original application preservation

Read-only `dumpsys deviceidle whitelist` inspection found neither original nor
validation package in any exemption list before installation, after installation,
after A, or at the last complete snapshot at **19:09:41**, after the D return.
The observed state remained **not exempt -> not exempt** for both packages.
The Owner followed D without granting or revoking exemption. The Agent performed
no battery-state mutation.

The original APK hash, code/resource paths, version, primary-user installation
time, update time, installer metadata and package flags matched exactly between
the pre-install and last complete snapshots. No original-package installation,
update, downgrade, uninstall, data clearing, UI launch or app-private file access
was performed by the Agent. The original database was not accessed or exported;
original data preservation is supported by the absence of task mutations, not a
private-database byte comparison. The independent validation installation remains
separate. The final snapshot preceded the extra authorization below; the later
ADB disconnect prevented a post-authorization refresh.

### Non-OAuth source audit and later scope deviation

Existing retained JUnit XML and the earlier 123-test targeted / 819-pass full-suite
reports were reviewed, not rerun. Production entry points and onboarding source
were inspected. Manifest, `QuickAuthCoordinator`, `QuickAuthRuntime`, notification
helper, import service and import task state were byte-identical to the baseline.

| Safety property | Existing deterministic / source evidence |
| --- | --- |
| Quick Auth requires a new explicit action | `MainActivity` wires the Import button to the gated `requestWechatAuthorization`; entry-point and Quick Auth regressions remain in retained passing XML |
| Onboarding generates no OAuth URL | Introduction/choice code only commits battery preferences and optionally launches public settings intents; Compose introduction and gate tests |
| Settings return does not start authorization | Result and resume handlers only refresh exemption and release pending state; return-without-grant and return-with-grant tests; no retained import continuation |
| VPN permission flow retained | Unchanged `VpnService.prepare` and permission-result handling; existing permission-required and grant/denial regressions |
| Notification flow retained | Byte-identical notification helper and the actual notification-launcher regression |
| Cookie import does not open battery settings | Existing entry-point test and audited `startManualCookieImport`: import gate, service start and notification request, with no settings launch |

Up to and including the D return, no Quick Auth events or validation services
were observed. **After that return**, the monitor recorded:

- `19:09:46.096`: `quick_auth_requested`; a permission-controller Activity appeared.
- `19:09:49.872`: validation `ImportForegroundService`, `WahlapHookHttpService`
  and `LocalVpnService` records appeared.
- `19:09:50.191`: `capture_ready`.
- `19:09:50.980`: `authorize_generated`.
- `19:09:50.995` / `19:09:51.015`: `clipboard_ready` / `wechat_launch_dispatched`.

The Agent paused acceptance on discovering this sequence. The Owner clarified
that they performed an additional manual authorization check, observed no battery
redirect, and explicitly confirmed **主动点击，已经停止／取消捕获**. The extra request
was therefore Owner-initiated, not established as an automatic settings-return
defect. It does not support a claim that this whole session had no fresh OAuth.
No authorization URL or clipboard contents were read, opened or retained by the
Agent; no callback, Cookie, score contents or import batches were inspected.
No complete OAuth exchange or score import is claimed, and zero new scores/batches
cannot be independently certified from this observation.

The phone disconnected during final monitoring/cleanup. Monitoring is stopped.
Cancellation is Owner-confirmed; final service/VPN cleanup and installation state
after that extra check could not be independently re-read. No Agent VPN switch,
forced stop, destructive recovery or retry was performed. No production patch was
made: this session establishes no product defect and does not widen development.

### Evidence limits and delivery

The introduction, three revisits, Activity recreation, actual system destination
and normal battery-settings return have direct Owner plus read-only phone evidence.
The UI text, each in-app revisit and post-return absence of the introduction are
Owner observations; no screenshots or UI dumps were taken. Fresh authorization
generation occurred during the Owner's extra check, and final cleanup is only
Owner-confirmed after the device disconnected. The requested strict no-OAuth and
final connected-device safety closure remain unfulfilled for this session.

Final task verdict: **BATTERY_ONBOARD_1_PHONE_ACCEPTANCE_PARTIAL**.
**BATTERY_ONBOARD_1_PHONE_UX_VALIDATED** is not awarded by this session.
**SCREEN_OFF_IMPORT_RELIABILITY_NOT_REVALIDATED**. No screen-off import, reliability
experiment, score import acceptance, release or merge was performed.

Only this documentation and the device-history index are updated on
`origin/codex/battery-onboard-1`; PR #8 remains open Draft and PR #7 is unchanged.
Private observation files and the Owner report remain outside Git. Report delivery
is Owner manual only; no CTO relay or ChatGPT interaction occurred.
