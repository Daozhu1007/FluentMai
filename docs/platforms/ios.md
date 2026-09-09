# iOS platform

## Status

iOS is an experimental Beta implemented in SwiftUI with selected shared Kotlin Multiplatform domain code from `core/model`.

- Minimum deployment target: iOS 17.0
- Device artifact: unsigned `iphoneos arm64` IPA
- Current release: `v0.2.4-beta`
- Signing: users must sign the IPA with their own Apple identity before installation

The existing IPA is a real generic-device build, not a Simulator archive. The current source mirrors Android's four primary destinations (Home, Import, Charts, and Tools), keeps Settings under Tools, and shares the Rating and achievement/tolerance formulas through Kotlin Multiplatform. Experimental status still means platform integrations and storage are not interchangeable with Android.

## Installation

Download the unsigned IPA from the [v0.2.4 Beta release](https://github.com/Daozhu1007/FluentMai/releases/tag/v0.2.4-beta), then follow [IOS_SIDELOAD_GUIDE.zh-CN.md](../../IOS_SIDELOAD_GUIDE.zh-CN.md). FluentMai does not distribute signing certificates, provisioning profiles, Apple account credentials, or passwords.

## Build and validation

iOS builds require macOS, Xcode, JDK 17, and XcodeGen. The repository maintains two independent validation workflows:

- `iOS Level 1` compiles and tests the shared KMP domain layer, runs `iosSimulatorArm64Test`, builds the SwiftUI app for an iOS Simulator, launches it, and captures evidence.
- `iOS Device unsigned IPA` builds the KMP `iosArm64` framework and an unsigned `iphoneos arm64` app, packages a standard Payload IPA, and statically validates its architecture and contents.

Generated Xcode projects and build products are not committed.

## Shared scope and limitations

Only selected domain behavior in `core/model` is shared with Android. The iOS navigation, chart query/detail, SSS+ tolerance sorting, player progress/recommendation views, calculators, and settings follow the Android information architecture. iOS cannot run Android's local VPN Hook from an ordinary application, and the Wahlap/cloud networking adapters remain platform-specific. iOS JSON backup/restore is available, but it is not an Android database migration format.
