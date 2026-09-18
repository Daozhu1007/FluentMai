# Android–Windows core feature matrix

Updated: 2026-07-16

Android remains the product-semantics reference. Windows uses a separate PyQt/SQLite implementation,
but the rows below share domain rules and cross-platform fixtures under `test-fixtures/`.

| Capability | Android status | Windows status | Windows implementation | Automated verification | Manual verification / intentional difference |
| --- | --- | --- | --- | --- | --- |
| Product information architecture | Complete adaptive navigation | Complete desktop navigation: 首页 / 导入 / 谱面 / 工具 / 设置 / 关于 | `windows/ui_main.py` | `test_product_ui.py` checks exact ordered destinations | Windows uses an expanded sidebar at >=960 logical px and a compact 48px rail below it. Real 200% DPI PrintWindow passed. |
| Local player identity | Local product state | Complete local display-name boundary | `ui_overview.py`, AppData `settings.ini` | Overview construction tests | Defaults to “本地玩家” until the user supplies a display name; score pages do not contain a reliable profile name, so Windows does not invent one. |
| Rating and B35/B15 | Complete | Complete | `rating.py`, `ui_overview.py` | Shared Rating and future-version B50 fixtures; Python and Kotlin tests | Real local account remains Rating 14,633, B35 10,419 / 35 and B15 4,214 / 15 after schema 3 migration. |
| Future catalog fail-closed | Complete | Complete | `resolve_current_version_id`, `compute_best_set`, chart current-version filter | `future-version.tsv`, Rating and chart-browser tests | Future batches are ineligible and cannot empty or redefine B15. |
| Home summary and quick actions | Complete | Complete | `ui_overview.py` | `test_product_ui.py` | Real Windows-QPA render checked cards, scrolling, quick actions, and reserved navigation width. |
| Filter-dependent player statistics | Complete | Complete | `player_records.py`, `chart_browser.py`, `ui_library.py` | Shared player-record fixture plus chart-filter tests | Counts exact rank / FC / FS states for the active filter; no cumulative status guessing. |
| Unified chart browser | Complete | Complete, virtualized | `chart_browser.py`, `ui_library.py` | Query, UI model/delegate, responsiveness and performance tests | Windows uses `QAbstractListModel` + delegate and caps displayed results at 500; it does not create 5,000 QWidget cards. |
| Search fields and normalization | Complete | Complete | `search_normalization.py`, `aliases.py`, `chart_browser.py` | Shared search fixture consumed by Python and Android; alias parser/retention tests | Android uses OpenCC dictionaries; Windows uses `LCMapStringEx` Simplified Chinese mapping. Both share NFKC, punctuation/space removal and golden queries. |
| Runtime community aliases | Complete two-source cache | Complete two-source SQLite cache | `aliases.py`, `song_aliases` schema 3 table | Parser, Yuzu ID normalization, merge and 80% regression guard tests | Dataset is fetched at runtime from LXNS/Yuzu and is not redistributed. Real cache: 1,324 songs / 10,488 aliases. |
| Chart filters and sorting | Complete | Complete | `ChartFilters`, `ui_library.py` | `test_chart_browser.py` | Title/alias/ID/BPM/artist/designer, level, constant range, difficulty, version, genre, SD/DX, played, achievement, rank, FC/AP, FS/FSD, Rating/ID/constant/title/version sorts covered. |
| Chart detail and sibling difficulty switch | Complete | Complete | `ChartDetailPanel`, targeted `load_chart_records(song_id=…)` | UI detail/cover tests | Right detail panel appears when width permits; same-song SD/DX/difficulties switch without changing global filters. |
| Detail loss/tolerance entry | Complete | Complete | detail `lossRequested` route into `ToolsInterface` | Product UI and tools domain tests | Only shown when all five Note counts are available. |
| Rating Trend automatic history | Complete | Complete | schema 3 `rating_history`, `rating.py` | repeat-import and database tests | Equal automatic Rating values remain deduplicated. |
| Rating Trend manual add/edit/delete | Complete | Complete | `database.py`, `ui_tools.py` | manual CRUD/protection tests | Automatic rows cannot be edited/deleted; manual note length is 200 and Rating range is 0–30,000. |
| Trend ranges and persistence | Complete | Complete | `ui_tools.py`, SQLite | UI persistence test | One month, three months and all; chronological storage survives restart. |
| Version plates | Complete | Complete | `version_catalog.py`, `player_records.py`, `ui_tools.py` | Shared FiNALE/PANDORA fixture, Kotlin/Python plate tests | FiNALE=輝, MiLK PLUS=雪; MASTER included, Re:MASTER excluded for version plates; official exclusions/ranges retained. |
| 霸者 | Complete | Complete | `calculate_plate_progress` | domain tests | SD BASIC–Re:MASTER at >=80%; DX excluded; empty data never claims completion. |
| Rating recommendations | Complete deterministic simulation | Complete | `recommendations.py`, `ui_tools.py` | shared candidate fixture and Android-equivalent replacement tests | Reports current/target single Rating, theoretical gain, actual B50 gain, cutoff and closed-set reason. No skill prediction. |
| Recommendation filters / “不想练” | Complete | Complete | schema 3 `recommendation_exclusions`, tools UI | persistence and exclusion tests | Excluded stable identities persist locally; disabled/locked/future charts are not offered. |
| Single-chart Rating tool | Complete | Complete | `tools.py`, `ui_tools.py` | shared Rating TSV consumed by Kotlin/Python | Formula output is independent of UI formatting. |
| Automatic and manual Note loss | Complete | Complete | `tools.py`, `ui_tools.py` | shared judgement-loss TSV consumed by Kotlin/Python | Default flow searches a chart and fills Note counts; manual mode remains explicit. Missing Note data is never fabricated. |
| Version name reference | Complete | Complete | `version_catalog.py`, tools UI | shared version fixture | One table supplies official name, related names, generation, plate prefixes and numeric ranges. |
| Kaleid×Scope | Audited unavailable boundary | Equivalent audited unavailable boundary | `tools.KALEID_SCOPE`, tools UI | domain constant/UI construction | Official public source lacks a structured auditable gate catalog; both platforms show unavailable state instead of placeholder gates. |
| Difficulty visuals | Complete | Complete | `ChartCardDelegate`, overview rows, canonical model labels | responsive UI tests and real render | MASTER is saturated purple/white. Re:MASTER is light lavender/deep-purple with lavender border. Canonical text is `Re:MASTER`. |
| Legacy pages and unsafe paths | N/A | Retired after migration | unified Overview/Library/Tools; deleted old dashboard/score/raw-capture code | import/compile/full Windows tests | Existing database tables and user rows are preserved; source-only third-party player cache, raw-HTML interceptor and upload-first sync path no longer ship. |

## Shared fixture coverage

The same checked-in fixtures are consumed by Kotlin and Python for Rating, future-version B50,
player statistics, search normalization, aliases, FiNALE/PANDORA plate membership, Note judgement
loss, and version boundaries. Recommendation tests take their cutoff/candidate inputs from the same
fixture. Platform UI tests then verify the corresponding interaction surfaces.
