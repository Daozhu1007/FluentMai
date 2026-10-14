# FluentMai Windows 2.0 progress

Updated: 2026-07-16 (Asia/Shanghai)

This is the single working report for the Windows 2.0 product update. It must not contain Cookies, tokens, raw Wahlap HTML, complete authentication URLs, private player identities, or user database contents.

## Status

- Phase: P2 Android core-feature parity complete; next checkpoint is the FluentMai Windows design system and desktop settings/about/update experience
- Branch: `feat/windows-product-parity`
- Starting commit: `6fff45d5b7f85b358e2e05a249632010417ec55b`
- Current release decision: development may continue; public Windows release is blocked pending a project-license/Qt binding decision and complete packaged-license verification
- P0 status: complete. The current account produced five real Wahlap difficulty pages in memory, 1,632 local score rows, a 35/15 B35/B15 snapshot, Rating 14,633, and two subsequent zero-duplicate real imports (including one with the packaged helper).
- System network result: every real attempt restored the exact preflight WinINET and WinHTTP snapshots. Final proxy hash is `c95f9dd3f866b4d21e5fc3ed6de790229eb01ed3b735e00fe85825c5e8d79104`, final WinHTTP fingerprint is `5b07a5d64e5af355c0d92253ce43abf88f6197a0aaa5ea221b0269ba6533fd70`, no recovery journal exists, and no helper/certutil process remains.
- WeChat preference result: `Use the system default browser to open third-party web pages` was temporarily disabled for capture and verified restored to its original enabled state after packaged-helper validation.
- Third-party uploads made by this task so far: none
- P1 status: complete. Long pages own their scroll areas; default/restored geometry is constrained to the active work area; maximized state and per-user geometry persist under AppData; missing-monitor restores return to the primary screen.
- P2 status: complete. Windows now uses the six-destination product IA, local Overview, one virtualized chart browser, exact filter-dependent statistics, version plates, editable Trend, deterministic recommendations, automatic/manual Note loss tools, shared cross-platform fixtures, and canonical difficulty visuals.

## Git baseline

- Local `master` and `origin/master` were both `6fff45d5b7f85b358e2e05a249632010417ec55b` after `git fetch --prune --tags origin`.
- Ahead/behind at start: `0/0`.
- Worktree at start: clean.
- Remote: `origin` -> `https://github.com/Daozhu1007/FluentMai.git`.
- Windows history entered the unified repository through `7c92be5` and `a0cb20b`; PR #1 was merged as `6fff45d`.
- Existing tags at start: `v0.1.0-beta.1`, `v0.2.0-beta`, `v0.2.0-ios-alpha.1`.
- Existing releases at start: three Android/iOS pre-releases; no Windows release.
- Existing Android/iOS tags and releases have not been changed.

## Specification and reference audit

Read in full:

- `D:/Code/Prompts/FluentMai/长任务3/FluentMai_Windows_2.0_Prompt.md` (2,218 UTF-8 lines)
- root `README.md` and `README.zh-CN.md`
- `docs/platforms/windows.md`
- `windows/README.md`, `windows/PROXY_SETUP.md`, and both third-party notice files
- all 10 existing files under `windows/tests/`
- `.github/workflows/windows-build.yml`
- the relevant RhythmAlign app shell, settings, about, diagnostics, updater, PyInstaller, Inno Setup, tests, license, and repository screenshots
- the maimaidx-prober Windows proxy entry point, configuration, CA handling, system-proxy manager, domain interception, CookieJar/WAF handling, progress channel, difficulty/version fetching, retries, and license

The task message referred to additional attached FluentMai/RhythmAlign screenshots, but no separate image attachment was exposed as a readable local file. The audit therefore used the checked-in RhythmAlign screenshots plus fresh, ignored screenshots of the current FluentMai Windows build. This line should be updated if the task attachment later becomes available.

## Windows stack baseline

- Python: 3.10.11
- SQLite runtime: 3.40.1
- Binding: PyQt6 6.10.2 / Qt 6.10.0
- Fluent widget package: PyQt6-Fluent-Widgets 1.11.1
- requests: 2.32.5
- beautifulsoup4: 4.14.2
- pytest: 9.1.0
- PyInstaller: 6.18.0
- Inno Setup compiler: not installed or not on `PATH`
- Go toolchain: not installed or not on `PATH`
- mitmproxy command: installed locally, but it is not an approved long-term helper architecture
- Packaging: PyInstaller one-directory portable ZIP only; no installer implementation yet
- Updater: none in FluentMai at baseline
- Theme: hard-coded dark shell mixed with page-local styles
- Localization: JSON-backed Chinese/English strings, but coverage is incomplete and configuration is stored next to the application source/bundle

## Current product capability audit

### Present

- Manual Wahlap auth URL/Cookie/Reqable input, five-page fetch, parse, validation, deduplication, quarantine, and SQLite transaction
- Diving Fish and LXNS imports into the local score table
- Local song/chart catalog, score table, quarantine and import-batch tables
- Real WeChat/Wahlap capture through a FluentMai-owned current-user CA, exact recoverable system proxy, authenticated IPC, and nonce-protected same-origin browser-memory collection with no default upload
- Local Rating/B35/B15 snapshots derived from `score_records`, deduplicated Rating Trend history, and aggregate player statistics derived from the local database
- Local Overview and one virtualized `QAbstractListModel`/delegate chart browser with filter-dependent player statistics
- Runtime LXNS/Yuzu aliases, Simplified/Traditional search, complete Android score filters, chart detail/sibling switching, and canonical difficulty visuals
- Version plates, editable Rating Trend, deterministic recommendations, automatic/manual Note loss, version reference, and Kaleid×Scope unavailable boundary
- Catalog refresh from Diving Fish/LXNS
- Runtime/user-cache jacket resolution
- Basic settings and about pages
- Source/package smoke-test mechanism and portable content audit

### Missing or materially incomplete

- Complete settings/privacy/diagnostics/update experience
- Installer, update channels, and Windows-only release filtering

### Retired legacy paths

- `windows/interceptor.py`, `windows/sync_core.py`, and `windows/PROXY_SETUP.md` were removed after the authenticated local capture path passed real validation.
- The external-player `ui_dashboard.py` / `fetch_profile.py` cache path and duplicate `ui_scores.py` page were removed after their user-facing capabilities moved to local Overview and the unified chart browser.
- Existing database tables/rows are preserved; no migration deletes legacy user data.

## Data baseline and backup

No `FLUENTMAI_DB_PATH` override was active and the unified checkout contained no database at start. Two legacy databases were found in the read-only former Windows tree.

The primary legacy source database:

- Integrity: `ok`
- File SHA-256: `c77c50360d3b44c438f6900c1fd3643062938e001bb52a9a451e1ae9ec94582f`
- Songs: 1,293
- Charts: 5,372
- Legacy `music_data`: 1,326
- Local `score_records`: 0
- Quarantine: 0
- Import batches: 0
- Provider cache rows: 1
- Legacy B35 cache: 35 entries, aggregate 10,430
- Legacy B15 cache: 15 entries, aggregate 4,225
- Legacy B50 semantic SHA-256: `55501a05b1002cb5ead885ac30a0e0866650101c142f7b1fea644c41c4154932`

The packaged legacy database contained 50 cached B50 entries but no catalog or local score records.

Ignored, local-only backup copies were created under:

```text
build/windows-v2-backup/baseline-6fff45d/
```

The originals remain untouched. No database is tracked or uploaded.

Data conclusions:

- The baseline cannot satisfy P0: local `score_records` is empty.
- The current default path (`database.py` beside source/executable) is unsuitable for Installer/Portable parity and can create a second empty database after moving the app.
- Windows 2.0 needs an AppData-based path and an atomic, non-destructive legacy discovery/copy/migration flow.
- `ensure_schema()` has no schema-version table or explicit migration transaction/rollback protocol at baseline.

## Performance baseline

Measured with the ignored copy of the 1,293-song / 5,372-chart legacy database and jacket network disabled:

- Existing test suite: 38 tests in 6.06 s
- Source smoke process: 4,716.5 ms end-to-end (includes the fixed 3 s smoke dwell)
- Python/UI module import: 736.2 ms
- Main window construction: 171.3 ms
- First event processing: 833.1 ms
- Descendant QWidget count after construction: 1,149
- Navigation event processing: home 14.2 ms; dashboard 36.0 ms; scores 8.6 ms; library 7.7 ms; settings 7.9 ms; about 8.1 ms
- Load/filter all charts: 294.6 ms (5,372 matched, 500 displayed)
- Level `13+`: 107.0 ms (355 results)
- Constant `13.3`: 74.2 ms (101 results)

Baseline query code reloads and maps the full chart set for each filter. It is below the 300 ms target for these local probes but leaves little UI-thread budget and lacks aliases/Chinese normalization required by the acceptance queries.

## Window and visual baseline

Current physical desktop baseline:

- Primary display: 1,600 x 1,000
- Available work area: 1,600 x 952
- System DPI: 96 (100%)
- Single active physical display plus a WSL virtual adapter/display environment

Although `MainWindow` calls `resize(1180, 760)`, the real Qt window was forced to `1180 x 1175` by child size constraints. It exceeds the available work area by 223 px, reproducing the invisible-bottom defect. This proves that increasing or merely changing the default height is not a sufficient fix.

P1 result:

- The import page's baseline minimum-size hint was 692 x 1,002 before global control styling and drove the top-level defect. Import and settings are now explicit single-scroll pages instead of contributing their full document height to the window stack.
- Default geometry is centered and clamped inside the current `availableGeometry`; the minimum size adapts when high-DPI logical work areas are smaller than the normal 760 x 520 desktop minimum.
- Saved geometry and maximized state use `%LOCALAPPDATA%\\FluentMai\\settings.ini` for both portable and installed layouts. Restored windows are clamped fully on-screen when a monitor is removed, and screen changes recompute the safe minimum.
- A real Windows-QPA run reported Qt logical DPI 96, device-pixel ratio 2.0, available work area 1,600 x 952, and a 1,166 x 753 frame at (217, 96). Its bottom edge was 849 and the import page exposed a 706 px vertical scroll range, so the title bar and bottom remained reachable on the current high-DPI desktop.
- Subprocess coverage passes at Qt scale factors 100%, 125%, 150%, 175%, and 200%. Synthetic work-area tests cover 1280 x 720-class taskbar geometry and a 683 x 384 high-DPI logical work area.
- Smoke/UI tests set `FLUENTMAI_DATA_DIR` to temporary storage, so geometry validation cannot create or overwrite a real user setting. The one test-only settings file found during implementation was removed without touching the score database.

Ignored baseline screenshots are under:

```text
build/windows-v2-backup/baseline-6fff45d/screenshots/
```

Visual findings:

- Home and settings content render as a light surface inside a dark shell.
- Selected navigation items can become blank white pills with invisible text.
- Long import/settings layouts drive the top-level minimum height instead of scrolling.
- Import credentials are all flattened into the primary page.
- Dashboard and chart cards contain reusable FluentMai-specific visual work, but the old navigation duplicates concepts.
- The current about page shows stale version/author/contact content and lacks the required third-party details and Windows disclaimer.
- RhythmAlign provides useful patterns for theme-following settings, independent About navigation, centralized version metadata, diagnostic copying, background update checks, SHA-256 verification, PyInstaller spec, and Inno Setup structure. Its media-specific content and exact branding/layout are not to be copied.

## Proxy, WeChat, CA, and recovery baseline

Read-only system state at task start:

- WeChat/Weixin is running and logged in according to the user; the process tree is present.
- FluentMai and proxy helper were not running.
- User WinINET proxy was disabled but retained a prior proxy-server value and bypass list.
- No PAC URL was active.
- WinHTTP was direct.
- Target ports 8033/8080 and common local proxy ports checked were free at the snapshot time.
- A Clash service exists, although the WinINET proxy switch was off.
- Existing mitmproxy and maimaidx-prober root certificates are present; they must not be deleted by FluentMai.

Only hashes/presence flags are recorded in this report. Exact values will be captured immediately before each test into an ignored, current-user-protected recovery journal and restored byte-for-byte/field-for-field.

Official reference:

- Source commit: `7a8bf1302fa569ebc036c908f8c6662c007b8b3f`
- Source license: MIT
- Binary SHA-256: `0bdaf93fda06b866fe1be517be5a351ec52c0e7ac840a0bd861ea31df29685db`
- Default proxy: port 8033; source initially permits an empty bind host and then normalizes display to loopback
- TLS MITM is restricted to maimai/chunithm Wahlap hosts
- CookieJar preserves request and response cookies, including EdgeOne/TencentEdge challenge state
- Browser fingerprint headers are forwarded for subsequent difficulty requests
- It supports difficulty and optional version slicing plus an in-page authenticated progress path
- Windows CA installation targets `LocalMachine\\Root` and may trigger UAC
- It writes Import Token to JSON and defaults to uploading to Diving Fish
- Its fetch loops retry without a finite bound
- Its registry rollback does not cover all required fields/WinHTTP/crash-after-process-death cases

Therefore the official EXE is a behavior baseline only. FluentMai must ship its own MIT-attributed helper or equivalent, bind loopback only, use a random session secret and finite retries, keep raw pages in memory, return them to FluentMai over authenticated local IPC, default to local import, and implement independent exact recovery.

## License and distribution baseline

This section is an engineering audit, not legal advice.

- The unified repository has no project-level `LICENSE`.
- PyQt6 is dual-licensed under GPLv3 or a Riverbank commercial license and is not available under LGPL: <https://www.riverbankcomputing.com/software/pyqt/intro/>
- Riverbank states that a non-GPL-compatible distribution requires a commercial PyQt license: <https://riverbankcomputing.com/commercial/license-faq>
- PyQt6-Fluent-Widgets declares GPLv3 for the open-source package and a commercial path for commercial use: <https://github.com/zhiyiYo/PyQt-Fluent-Widgets>
- Qt itself is available under commercial or open-source licenses; an LGPL route has dynamic-linking, notice, relinking, and corresponding-source obligations: <https://www.qt.io/development/open-source-lgpl-obligations>
- PyInstaller uses GPL-2.0-or-later with an exception allowing distribution of bundled applications: <https://pyinstaller.org/en/stable/license.html>
- maimaidx-prober is MIT licensed and requires preservation of its copyright/license notice: <https://github.com/Diving-Fish/maimaidx-prober/blob/main/LICENSE>
- requests is Apache-2.0; Beautiful Soup is MIT according to their upstream package/project metadata.
- RhythmAlign is the same author's read-only design/code reference under PolyForm Noncommercial 1.0.0; reuse is limited to the rights and author authorization stated by this task, with its notice retained where code is actually reused.
- Live alias endpoints do not document a separate dataset redistribution license; the existing runtime-cache-only policy remains appropriate.
- Game jacket redistribution rights are not established. The 74 untracked reference jackets are not to be copied; runtime/user cache remains the release-safe default.

Current conclusion: engineering work and private development artifacts may continue. A public Windows release must not be created until the user explicitly chooses and documents a valid project/PyQt/Fluent-Widgets licensing path and the final artifact contains the required license texts, source offer/instructions where applicable, and a complete dependency inventory.

## Existing CI baseline

The Windows workflow currently installs dependencies, runs 38 tests, compiles Python, runs source smoke, builds a PyInstaller portable directory, runs package smoke, checks a small forbidden-file pattern, hashes the ZIP, and uploads a 14-day development artifact.

Gaps against Windows 2.0:

- feature branch push trigger is absent
- `test-fixtures/**` and Windows-related root configuration paths are absent
- no explicit integration/migration/golden/search/plate/rating groups beyond existing unit tests
- no helper build, helper mock/IPC tests, restore-script tests, installer build, or installer smoke
- no SBOM/license inventory, broad credential scan, or release asset naming
- no Windows-only updater/channel tests

## Test ledger

| Command | Exit | Result |
| --- | ---: | --- |
| `python -m pytest -q windows/tests` | 0 | 38 passed, 0 failed, 0 errors, 0 skipped; 6.06 s |
| `python -m compileall -q -f windows scripts/windows` | 0 | completed |
| `.\\scripts\\windows\\smoke_test.ps1 -Mode source` | 0 | source window constructed/shown and exited; 4,716.5 ms measured wrapper time |
| `python -m pytest -q windows/tests` | 0 | 70 passed; capture/helper/recovery/rating/UI lifecycle additions included |
| `python -m compileall -q windows` | 0 | completed after P0 foundation changes |
| `.\\scripts\\windows\\build_portable.ps1` | 0 | main app and `FluentMaiCaptureProxy.exe` built; forbidden artifact count 0 |
| `.\\scripts\\windows\\smoke_test.ps1 -Mode package` | 0 | packaged main window constructed/shown and exited |
| packaged helper IPC smoke | 0 | helper reached ready state without CA installation, timed out finitely, and restored the simulated proxy exactly |
| three real WeChat imports | 0 | first import inserted 1,632 scores; two repeats inserted/updated 0 and skipped 1,632 duplicates; packaged helper used on the final run |
| `python -m pytest -q windows/tests` | 0 | 71 passed after browser-memory capture and migration-sidecar cleanup |
| `python -m compileall -q -f windows scripts/windows` | 0 | completed after final P0 changes |
| `.\scripts\windows\smoke_test.ps1 -Mode source` | 0 | final source smoke passed |
| `.\scripts\windows\build_portable.ps1` | 0 | final portable build passed; ZIP SHA-256 `9340bf497c32ef0b9101c9b0212c0619b9b4f31b8c884bb21a252a6f611f6135` |
| final packaged main/helper smoke and artifact audit | 0 | both smokes passed; forbidden files 0; jacket directory absent; declared ZIP checksum matched |
| `python -m pytest -q windows/tests` | 0 | 76 passed after P1 window, scroll, monitor-loss, and 100%-200% scale coverage |
| real Windows-QPA P1 geometry probe | 0 | DPR 2.0; 1,166 x 753 frame stayed inside 1,600 x 952 available work area; import bottom reachable by scrolling |
| `python -m pytest -q windows/tests` | 0 | 95 passed after P2 domain, database, alias, unified UI, tools, navigation reservation and cross-fixture coverage |
| `:core:model:jvmTest :feature:scores:testDebugUnitTest` | 0 | Kotlin consumed the shared Rating, B50, player, alias, plate, loss and search fixtures |
| real schema 2 -> 3 migration | 0 | backup count 15 -> 16; integrity `ok`; 1,632 scores and semantic hash `2c9f13d…bcbcb` unchanged |
| real public alias refresh | 0 | 1,324 songs / 10,488 aliases cached locally without credentials or upload |
| real Windows-QPA P2 render at DPR 2.0 | 0 | Overview, Tools and Library passed; a first render exposed the navigation overlay and the corrected render confirmed reserved 207px sidebar space |
| `.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon` | 0 | all Android unit tasks and debug assembly passed after adding shared-fixture consumers; Android product code/behavior unchanged |

## Checkpoints

- [x] `chore: establish Windows v2 baseline`
- [x] `feat: integrate local Wahlap capture pipeline`
- [x] `fix: make Windows shell responsive and DPI safe`
- [x] `feat: reach Android feature parity on Windows`
- [ ] `ui: adopt FluentMai Windows design system`
- [ ] `feat: add Windows settings about and updater`
- [ ] `perf: optimize Windows chart browsing`
- [ ] `build: prepare FluentMai Windows alpha`
- [ ] `test: validate FluentMai Windows v2`

## Immediate next work

1. Apply the FluentMai Windows design system consistently to the remaining import, settings and about surfaces without changing the validated product semantics.
2. Complete privacy/diagnostics/update settings and the standalone About page using RhythmAlign's desktop patterns.
3. Keep public release blocked until the project/PyQt/Fluent-Widgets license path is explicitly selected and packaged-license verification is complete.

## P0 foundation checkpoint evidence

- AppData migration copied (not moved) the legacy database to `%LOCALAPPDATA%\\FluentMai\\data\\maimai_data.db`; source SHA-256 remained `c77c50360d3b44c438f6900c1fd3643062938e001bb52a9a451e1ae9ec94582f` and the migrated database passed `integrity_check` at schema 2.
- The public LXNS catalog was refreshed to 1,304 songs, 5,414 charts, and 20 explicit major versions; operating version resolved to 25,500 without creating local scores.
- The packaged helper binds loopback, authenticates each IPC session with a random token passed through stdin, only intercepts the exact Wahlap host, and never uploads by default. After the authenticated home response, it injects a no-store same-origin page; the WeChat WebView fetches five difficulties and POSTs each page to a random-nonce path that is short-circuited locally before any upstream request.
- Success, authentication failure, helper error, helper crash, cancellation, stale-journal recovery, and packaged-helper finite-timeout paths are covered without changing the real system proxy.
- Android Rating boundary values and B35/B15 future-version fail-closed semantics are covered by golden tests. Repeated imports do not duplicate automatic Rating history.
- Real WeChat UI navigation reached the official `舞萌 | 中二` service-account `我的记录 -> 舞萌DX` entry using window-relative coordinates after accessibility discovery showed the Qt canvas exposes no actionable UIA controls.
- The Windows trust dialog was verified against both the exact subject `FluentMai Local Capture CA` and SHA-1 `AF9D656F6F6E5359794F5047A151E8D1498C1BD2` before acceptance. Exactly one matching current-user Root certificate remains installed.
- The first successful real import captured 2,498,486 bytes across five difficulty pages, parsed 1,634 records, inserted 1,632, and quarantined two `blank_title` records. SQLite integrity is `ok`; score semantic SHA-256 is `7d9f6761146bfd1765cd3a4ed8fea99c7b24bc0b0ffac70c04f83984292df43a`.
- Local Rating is 14,633: B35 has exactly 35 items / 10,419 Rating and B15 has exactly 15 items / 4,214 Rating. Local aggregate player statistics are 1,632 chart scores across 1,067 songs, including 535 SSS+ and 75 AP/AP+ results.
- Two additional real imports, the last using the packaged helper EXE, each parsed the same 1,634 records with `inserted=0`, `updated=0`, and `skipped_duplicate=1,632`; the score count and semantic hash did not change. Three rating snapshots exist, while unchanged repeats correctly leave only one Trend point.
- The task temporarily disabled WeChat's system-default-browser option so the official-account menu opened in WeChat, then verified the original enabled state was restored. Final helper/certutil counts are zero and the recovery journal is absent.
- Final WinINET and WinHTTP fingerprints match preflight after every successful import. Twenty-five task-generated WeChat image artifacts were zeroed; AppData/evidence sensitive-pattern matches are zero; the database has no Cookie/Token/session/auth-URL/HTML columns and quarantine stores only a fingerprint, not raw input.
- The import center now exposes one high-contrast primary action plus a fixed cancel/recover action, requires explicit CA/proxy consent, runs capture off the UI thread, reports only safe stage metadata, and distinguishes verified restoration from an unverified restoration error.
- Application startup consumes a protected pending journal before creating the main window; close waits for capture cancellation and restoration. The portable archive exposes both double-click recovery scripts beside the application.
- A read-only UI render confirmed the P0 controls are visible. It also measured the still-unfixed top-level height at 1,344 px, so the separate window/DPI checkpoint remains mandatory after real P0.

## P1 responsive shell checkpoint evidence

- The root cause was removed rather than hidden by a larger default: the 1,002 px import-page minimum hint is now isolated behind one vertical scroll area, and the main shell minimum-size hint no longer inherits the long form.
- Window placement is based on each screen's taskbar-aware available geometry. First launch is centered; restored and cross-screen windows are resized/moved inside a safe inset; a missing prior display falls back to the primary screen.
- Geometry and maximized state persist in the shared per-user AppData location, not beside the source tree or portable executable.
- Scale-factor subprocesses at 1.0, 1.25, 1.5, 1.75, and 2.0 all constructed and showed the shell inside the reported work area with the import scroll bar active.
- The current desktop was also measured through the real Windows platform plugin at DPR 2.0. The resulting 1,166 x 753 frame fit in the 1,600 x 952 work area with 103 logical pixels below it.
- Tests include default centering, adaptive minimum sizing, missing-monitor recovery, explicit scroll ownership, settings/import bottom reachability, and temporary-storage isolation. The full Windows suite is 76/76 passing.

## P2 Android parity checkpoint evidence

- The left navigation is exactly `首页 / 导入 / 谱面 / 工具 / 设置 / 关于`. The local Overview replaces the former third-party player dashboard and exposes Rating, score/chart counts, B35/B15, filtered statistics, Trend, plates, recommendations and quick routes.
- The chart browser is the only catalog/player-score surface. It searches runtime LXNS/Yuzu aliases, Simplified/Traditional forms, full-/half-width text, IDs, BPM, artists and designers; filters all Android score/status dimensions; and keeps the virtualized delegate implementation.
- Schema 3 additively adds alias cache, manual Trend metadata and recommendation exclusions. Before the real migration, the database was schema 2 / integrity `ok` / 1,632 scores. After migration and alias refresh it is schema 3 / integrity `ok` / 1,632 scores; the task-specific score semantic hash stayed `2c9f13d355580ea943e16538ead15dec331942cbb1c156063a311453ad8bcbcb`. A new integrity-checked backup was created.
- The real public runtime alias refresh merged usable LXNS/Yuzu data into 1,324 song identities and 10,488 aliases. The app rejects empty updates and any refresh retaining under 80% of the prior song or alias count.
- Shared fixtures under `test-fixtures/` are read by Kotlin and Python for Rating, B50 future batches, player statistics, aliases, search, FiNALE/PANDORA plate membership, Note loss and version boundaries. Recommendations also read their common cutoff/candidate fixture.
- Trend supports automatic deduplication plus manual add/edit/delete, three time ranges and restart persistence. Automatic rows are protected from editing/deletion.
- Recommendation output is a deterministic B35/B15 simulation with actual cutoff replacement gain, closed-set explanations, filters and persistent “不想练” identities. It does not claim player-skill prediction.
- The selected-chart loss flow fills five Note types from catalog data, keeps manual mode, and uses the same weighted/BREAK formula as Android. Kaleid×Scope remains an explicit audited unavailable state on both platforms.
- Real DPR 2.0 PrintWindow review showed the first forced-expanded navigation incorrectly covered content. Expansion was moved after final geometry selection and made adaptive; the corrected Overview reserves 207 logical px, while narrow shells use a 48px rail. Tools and Library renders confirmed reachable scroll, visible filters/cards, and distinct MASTER/Re:MASTER treatments.
- Detailed status, test ownership and intentional platform differences are recorded in `docs/windows/android-windows-feature-matrix.md`.
