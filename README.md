# FluentMai

[English](README.md) | [简体中文](README.zh-CN.md)

| Platform | Status | Download | Notes |
| --- | --- | --- | --- |
| Android | Beta | [Android APK](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.5-android-beta.3) | Primary maintained platform; debug-signed test build |
| iOS | Experimental Beta | [unsigned IPA](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.4-beta) | User self-signing required; iOS 17+ |
| Windows | Alpha / Development | Source only | Independent desktop implementation under `windows/` |

FluentMai is an unofficial, local-first score companion for players of the mainland China version of maimai DX. This product repository carries three independent platform implementations. Android is currently the most mature platform; iOS and Windows remain experimental.

The platforms do not promise identical features, storage formats, or release dates. Android and iOS share selected Kotlin Multiplatform domain code in `core/model`; Windows remains a separate Python/PyQt6 application. Android data migration and Room schema guarantees do not apply to iOS or Windows.

## Platform guides

- [Android](docs/platforms/android.md) — Kotlin, Jetpack Compose, Room, and the current public Beta.
- [iOS](docs/platforms/ios.md) — SwiftUI with the KMP domain layer and a real unsigned `iphoneos arm64` IPA for self-signing.
- [Windows](docs/platforms/windows.md) — the independent PyQt6 desktop codebase, currently source-only.

## Product scope

Across the product, FluentMai focuses on importing, validating, browsing, and analyzing score data locally. Platform capabilities currently differ:

- Android provides the broadest feature set: Wahlap import, Room persistence, quarantine, B35/B15 and Rating analysis, chart search, plate progress, trends, recommendations, and optional user-triggered uploads.
- iOS is an experimental SwiftUI client backed by the shared KMP score/Rating domain layer. Its unsigned IPA is a real device build, but it must be signed by the user before installation.
- Windows provides an independent local SQLite database, manual import flows, score and chart browsing, catalog caches, and a PyQt6 desktop UI. It does not share Android's database or migration path.

See [Product Scope](docs/PRODUCT_SCOPE.md), [Privacy Model](docs/PRIVACY_MODEL.md), and [Third-Party Notices](THIRD_PARTY_NOTICES.md) for the detailed boundaries.

## Releases

Current mobile releases:

- `v0.2.5-android-beta.3` — Android debug-signed test APK.
- `v0.2.4-beta` — iOS unsigned `iphoneos arm64` IPA (unchanged).
- Windows does not yet have a public release. CI artifacts are development evidence, not a Windows release.

Future platform-specific tags use these forms:

- `vX.Y.Z-android-beta.N`
- `vX.Y.Z-ios-alpha.N`
- `vX.Y.Z-windows-alpha.N`

Platforms follow independent release cycles; the earlier `v0.2.4-beta` synchronized Android and iOS test artifacts under one tag.

## Repository layout

- `app/`, `feature/`, and most `core/` modules — Android application and its Kotlin domain/data layers.
- `core/model/` — Kotlin Multiplatform domain code shared by Android and iOS.
- `iosApp/` — experimental SwiftUI application.
- `windows/` — independent Python/PyQt6 Windows application.
- `scripts/ios/` and `scripts/windows/` — platform build and validation helpers.
- `docs/platforms/` — per-platform status, setup, build, and limitation notes.
- `.github/workflows/` — path-isolated Android, iOS, and Windows validation.

## Build and test

Android on Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
```

iOS requires macOS, Xcode, JDK 17, and XcodeGen. See [the iOS platform guide](docs/platforms/ios.md).

Windows:

```powershell
python -m pip install -r windows/requirements.txt
python -m pytest -q windows/tests
.\scripts\windows\smoke_test.ps1 -Mode source
.\scripts\windows\build_portable.ps1
```

Detailed Windows commands and known limitations are in [windows/README.md](windows/README.md).

## Screenshots

The current screenshots show the Android Beta and local synthetic/test data.

| Home B50 | Unified chart browser | Chart detail |
| --- | --- | --- |
| <img src="docs/screenshots/home-b50.png" width="260" alt="FluentMai Android home B50"> | <img src="docs/screenshots/chart-browser.png" width="260" alt="FluentMai Android chart browser"> | <img src="docs/screenshots/chart-detail.png" width="260" alt="FluentMai Android chart detail"> |

Authentication URLs, tokens, cookies, raw imported pages, databases, logs, and user score data are deliberately excluded from the repository.

## Privacy and security

- Never commit real Cookies, Authorization headers, Import Tokens, LXNS tokens, full authentication URLs, raw Wahlap HTML, user databases, or diagnostic dumps.
- Android and Windows use separate local databases; no cross-platform migration guarantee currently exists.
- Uploads to supported community services are optional and user-triggered.
- Release signing credentials, certificates, and passwords are not stored in this repository.
- Unlicensed game artwork is not bundled with the Windows source or CI artifact; covers are resolved from user cache or runtime providers.

## Disclaimer

FluentMai is an independent, unofficial fan-made tool. It is not affiliated with, authorized by, or endorsed by SEGA, Wahlap, Diving Fish, LXNS, or their operators. Game names, artwork, music metadata, and trademarks belong to their respective owners. Upstream services can change without notice; keep independent backups of important data.
