# Product Scope

FluentMai Android is currently focused on one workflow:

1. Import maimai DX score data from the Wahlap WeChat account flow.
2. Persist valid scores locally.
3. Keep invalid or suspicious records out of the main score table.
4. Export local scores into upload-ready payloads.
5. Upload local scores to Diving Fish and LXNS.

## In Scope

- Local Room database ownership.
- Real Wahlap auth URL import.
- Fixture-based import tests.
- Score browsing.
- Quarantine for abnormal records.
- Deduplication across title, chart type, and difficulty.
- Song type and song ID handling for upload compatibility.
- Diving Fish and LXNS upload clients.
- Android Keystore-backed encrypted storage for Diving Fish and LXNS upload tokens.
- Privacy-safe logs and UI messages.

## Deferred

- Persistent account login.
- Cloud sync.
- AI recommendations.
- Community features.
- Multi-game support.
- Best 50 image generation.

## Reference

MaiproberPlus validated the local Wahlap import direction and remains a read-only reference. FluentMai Android should keep its own module boundaries, tests, and data model.
