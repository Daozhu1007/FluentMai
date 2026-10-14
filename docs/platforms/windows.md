# Windows platform

## Status

Windows is an independent Alpha / Development implementation under `windows/`. Its source is part of the product repository, but there is currently no public Windows Release.

- Technology: Python 3.10+, PyQt6, PyQt6-Fluent-Widgets, requests, Beautiful Soup, and SQLite
- UI: independent desktop navigation and pages
- Storage: independent AppData SQLite database with non-destructive legacy migration
- Packaging: PyInstaller one-directory portable build plus a separate one-file loopback capture helper

See [windows/README.md](../../windows/README.md) for setup, tests, packaging, smoke tests, features, and limitations.

## Current validation baseline

```powershell
python -m pip install -r windows/requirements.txt
python -m pytest -q windows/tests
python -m compileall -q -f windows scripts/windows
.\scripts\windows\smoke_test.ps1 -Mode source
.\scripts\windows\build_portable.ps1
.\scripts\windows\smoke_test.ps1 -Mode package
python scripts/windows/smoke_capture_helper.py --helper build/windows/dist/FluentMai/FluentMaiCaptureProxy.exe
```

The Windows workflow runs only for changes under `windows/`, `scripts/windows/`, or its own workflow file. It uploads a development artifact after tests, compilation, source startup, packaging, packaged startup, packaged-helper IPC startup, and content checks succeed.

The capture foundation is local-only: a random authenticated loopback helper returns Wahlap pages in memory, the main process parses and atomically imports them, and a DPAPI-protected journal restores the exact WinINET/WinHTTP baseline after success, cancellation, failure, close, or the next startup. The authenticated WeChat WebView fetches all five difficulty pages from a no-store same-origin prompt and returns them to a nonce-protected path that the helper short-circuits locally; Cookies and raw pages are never persisted. Real-account and packaged-helper validation imported 1,632 local score rows, produced a 35/15 B35/B15 snapshot, and repeated twice without creating duplicate scores.

The desktop shell is taskbar-, monitor-, and high-DPI-aware. Long import/settings documents have one primary scroll area; first-run and restored geometry is kept inside the active screen work area; removed-monitor restores return to the primary screen; and maximized state is stored in the per-user AppData settings file. Automated scale coverage runs at 100%, 125%, 150%, 175%, and 200%.

Windows now implements the Android core product semantics through a desktop information architecture: local Overview, Import, one virtualized chart browser, Tools, Settings, and a standalone About destination. Rating/B35/B15, filter-dependent player statistics, editable Rating Trend, version plates, deterministic recommendations, selected-chart Note loss, version reference, and the Kaleid×Scope audited unavailable boundary are local-first. See [the Android–Windows feature matrix](../windows/android-windows-feature-matrix.md).

## Data, artwork, and release limits

- Windows data is not the Android Room database and has no documented cross-platform migration path.
- User databases, raw HTML captures, tokens, Cookies, logs, build directories, and local configuration are excluded from source control.
- Game jacket images are not bundled because their redistribution rights are not established. The app uses user cache or runtime providers.
- A public Windows pre-release remains blocked until the project license and the complete runtime third-party distribution obligations are resolved and the packaged UI is manually reviewed.
