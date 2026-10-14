# FluentMai for Windows

FluentMai for Windows is the independent desktop implementation in the FluentMai product repository. It is Alpha / Development software. It now targets Android-equivalent core product semantics, while its SQLite format and release lifecycle remain platform-specific.

## Technology stack

- Python 3.10 or later
- PyQt6 and PyQt6-Fluent-Widgets
- SQLite under `%LOCALAPPDATA%\FluentMai` for local catalog, scores, import batches, quarantine, Rating snapshots, and provider cache
- requests and Beautiful Soup for supported import/catalog flows
- pytest for tests
- PyInstaller for the portable one-directory build

## Setup and run

From the repository root:

```powershell
python -m pip install -r windows/requirements.txt
Set-Location windows
python main.py
```

Runtime data is local. Set `FLUENTMAI_DB_PATH` to choose an explicit database path for tests or experiments. Do not use a real user database in CI.

Window geometry, maximized state, and future user preferences share `%LOCALAPPDATA%\FluentMai\settings.ini`; portable and installed builds therefore use the same settings location. Restored geometry is constrained to a visible monitor work area.

## Tests and static compilation

From the repository root:

```powershell
python -m pytest -q windows/tests
python -m compileall -q -f windows scripts/windows
```

The test suite uses synthetic payloads and temporary databases. It must not use real Cookies, tokens, auth URLs, raw HTML, or player data.

## Startup smoke test

The source and packaged application support a CI-only `--smoke-test` argument. It constructs and shows the main window, including the core pages, and exits automatically.

```powershell
.\scripts\windows\smoke_test.ps1 -Mode source
```

The helper forces Qt's offscreen backend and a temporary SQLite path so smoke tests do not touch user data.

## Packaging

Build the portable directory and ZIP from the repository root:

```powershell
.\scripts\windows\build_portable.ps1
```

Outputs are written under `build/windows/` and are ignored by Git. Validate the packaged executable with:

```powershell
.\scripts\windows\smoke_test.ps1 -Mode package
```

The build includes application code, `assets/`, `locales/`, the default non-secret `config.json`, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). It must not contain databases, raw HTML, logs, credentials, caches, source-only agent instructions, or unlicensed jacket images.

## Current features

- Manual Wahlap import from an auth URL, Cookie, or Reqable request headers
- Diving-Fish full-record import by Import Token
- LXNS full-record import by personal API token
- Local score database, validation, deduplication, quarantine, and import-batch summaries
- Android-equivalent single-chart Rating plus local B35/B15 bucket calculation and automatic Rating history deduplication
- A FluentMai-owned loopback Wahlap capture helper with authenticated IPC, finite retries, protected exact-network recovery, and no default third-party upload
- Diving-Fish and LXNS song/chart catalog caches
- Local score browsing and song/chart search
- A local Overview with Rating, B35/B15, player statistics, Trend and quick product routes
- One virtualized chart browser with alias/Traditional Chinese search, complete score filters, sibling difficulty switching and selected-chart loss entry
- Android-equivalent version plates, editable Rating Trend, deterministic B35/B15 recommendations, single-chart Rating and automatic/manual Note loss tools
- Runtime-only LXNS/Yuzu community aliases with atomic replacement and regression protection
- Cover resolution through user cache, runtime providers, and optional local assets
- Redaction of Token, Cookie, Authorization, and auth-URL material in application messages

## Known limitations

- The automatic capture backend, packaged helper, one-click import worker, cancellation path, startup crash recovery, and close-time recovery wait are implemented and real-account validated. The helper injects a no-store same-origin page that lets the authenticated WeChat WebView fetch five difficulty pages and return them only to a nonce-protected local interception path; no Cookie replay or raw-page persistence is used.
- Before capture, WeChat's `Settings > General > Open third-party web pages with the system default browser` option must be off so the official-account menu opens inside WeChat. Restore the preference after import if it was previously on.
- The manual Wahlap flow still needs broader real-account validation.
- Upload remains an explicit optional operation and is never the default result of local import.
- No game jackets are distributed in the repository or portable package; missing covers are expected until resolved from user cache or a runtime provider.
- The product repository currently has no declared project-level `LICENSE`. PyQt6 and PyQt6-Fluent-Widgets also require an explicit GPL/commercial-license compliance decision. These issues block a public Windows Release.

## Differences from Android and iOS

- Windows uses PyQt6 rather than Compose or SwiftUI.
- Windows uses its own SQLite schema and release cycle.
- It does not consume Android Room migrations and does not currently share the KMP domain module.
- Core product semantics are tracked in [the Android–Windows feature matrix](../docs/windows/android-windows-feature-matrix.md); UI composition and persistence formats intentionally differ by platform.

## Privacy

Never commit or package real Cookies, Authorization headers, Import Tokens, LXNS tokens, full auth URLs, raw Wahlap HTML, user databases, logs, or score exports. The settings UI does not persist tokens, and Credential Manager/keyring integration remains pending.
