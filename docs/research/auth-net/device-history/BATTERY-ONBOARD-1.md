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

## Phone acceptance and remaining evidence boundary

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
