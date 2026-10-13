# Android platform

## Status

Android is FluentMai's primary maintained platform and is published as Beta. The current release is [v0.3.2-beta](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.3.2-beta).

- Minimum Android version: Android 8.0 (`minSdk 26`)
- Target SDK: 34
- Current application version: `0.3.2-beta` (`versionCode 16`)
- Technology: Kotlin, Jetpack Compose, Room, coroutines, Ktor, and modular Gradle

## Build and test

Requirements are JDK 17, an Android SDK capable of API 34 builds, and either `local.properties` with `sdk.dir` or a configured `ANDROID_HOME` / `ANDROID_SDK_ROOT`.

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
```

Unix/macOS:

```sh
./gradlew test
./gradlew :app:assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## Data and privacy

Android stores validated score data in its local Room database. Import validation, deduplication, and quarantine are Android product features. Diving Fish and LXNS upload tokens are encrypted with Android Keystore-backed AES-GCM before being saved in app-private preferences, while raw import pages, Wahlap authorization URLs, and Cookie import values are not persisted.

Android Room migrations apply only to Android. They do not migrate data to iOS or Windows.

## Release notes

The current Beta APK is debug-signed because trusted release signing material is intentionally absent from the repository. Keep an external backup before upgrading and do not uninstall the previous compatible debug-signed build if local data must be retained.

Download [FluentMai-v0.3.2-beta.apk](https://github.com/Daozhu1007/FluentMai/releases/download/v0.3.2-beta/FluentMai-v0.3.2-beta.apk) and open it with Android's package installer, or accept the in-app update prompt. The package remains `dev.fluentmai.android`; local Room data and preferences are preserved by a compatible in-place upgrade. The Release includes a SHA-256 checksum and signing-certificate fingerprint for comparison with v0.3.1 Beta.

WeChat authorization has no cumulative retry limit. After an authorization rejection, click **重新授权并打开微信** to create a new transaction and link. The app never retries authorization without a user click, allows only one active transaction, rejects old callbacks and cancellation commands, and cleans up capture resources on failure, cancellation or timeout. Idempotent score-page HTTP requests retain their existing bounded retry policy.

The optional background-permission introduction is shown once and can be skipped. Its selection persists; the background-access entry remains available in Settings. It does not open battery details on every authorization. VPN and notification permissions and the capture-ready handoff remain in place.

This release is verified with local fixture HTTP, Robolectric/Compose tests, persistence regressions, APK assembly and signature checks. Owner's phone was not modified or used for real WeChat authorization or an actual upgrade; device-specific installation, handoff latency and live OAuth behavior remain unverified for these exact release bytes.
