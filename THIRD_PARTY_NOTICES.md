# Third Party Notices

This product repository uses open source dependencies across its Android, iOS/KMP, and Windows implementations. Each packaged platform must satisfy the licenses and notices for the exact dependencies included in that artifact.

## Android Gradle Plugin

Used for Android project builds. Licensed by Google under Android SDK and related license terms.

## AndroidX Activity, Compose, Lifecycle, and Room

Used for the Android UI, lifecycle integration, and local persistence. AndroidX libraries are licensed under the Apache License 2.0.

## Kotlin and Kotlin Coroutines

Used for application and test code. Kotlin components are licensed under the Apache License 2.0.

## KSP

Used for Room annotation processing. KSP is licensed under the Apache License 2.0.

## Gradle

Used as the build system. Gradle is licensed under the Apache License 2.0.

## JUnit

Used for unit tests. JUnit 4 is licensed under the Eclipse Public License 1.0.

## JSON-java

Used by the fixture parser in JVM tests and importer code. JSON-java is distributed under its project license.

## opencc4j

Version 1.14.0 supplies the offline Traditional/Simplified Chinese character and phrase dictionaries used by chart search. opencc4j is licensed under the Apache License 2.0.

## MaiproberPlus / MPP-Lab

MaiproberPlus was inspected only as a read-only technical validation reference for this phase. No large MaiproberPlus modules were copied into FluentMai Android Phase 0.

## maimai game UI artwork

The Android B50 renderer includes the 11 `rating_base_*.png` colour frames and 5
`trophy_*.png` backgrounds from `https://maimaidx.jp/maimai-mobile/img/`, retrieved
2026-09-15. Their local names are `b50_rating_*.png` and `b50_trophy_*.png` under
`feature/scores/src/main/res/drawable-nodpi`. The rating renderer displays the complete
unmodified source frames, including the left-hand logo. These game artworks belong
to SEGA and their respective rights holders; they are not covered by the project's
source-code or dependency licenses. Bundling avoids runtime requests to the Japanese
NET site. Player collections and jackets remain separately loaded public artwork.

## Windows dependencies

The independent Windows implementation uses PyQt6, Qt 6, PyQt6-Fluent-Widgets, requests, Beautiful Soup, PyInstaller, Python, and transitive runtime packages. See [windows/THIRD_PARTY_NOTICES.md](windows/THIRD_PARTY_NOTICES.md).

The repository does not bundle Windows jacket artwork. A public Windows release remains blocked until a project-level license is declared and the complete packaged dependency/license inventory is reviewed.
