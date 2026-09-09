# Android platform

## Status

Android is FluentMai's primary maintained platform and is published as Beta. The current release is `v0.2.5-android-beta.3`.

- Minimum Android version: Android 8.0 (`minSdk 26`)
- Target SDK: 34
- Current application version: `0.2.5-android-beta.3` (`versionCode 9`)
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
