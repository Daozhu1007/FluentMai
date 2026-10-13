# Import Pipeline

## Android WeChat authorization (v0.3.2 Beta)

The user starts authorization or clicks **重新授权并打开微信** after rejection. Each explicit retry retains the logical import id while assigning a fresh execution, OAuth attempt, HTTP client and authorization link. There is no cumulative authorization limit. Attempt numbers are internal diagnostics only. Capture readiness, callback transaction identity and one-shot handoff prevent concurrent or repeated imports; retry and cancellation commands are tied to their execution. Cancellation, timeout and rejected authorization close their resources. Permission, readiness and Activity callbacks never trigger a retry after failure without another user click.

Background-permission onboarding is optional, skippable and persisted once. A later Settings entry remains available; ordinary authorization does not automatically open battery settings. VPN and notification permissions still apply. Existing bounded retries for idempotent page requests, COMPLETE/PARTIAL/FAILED outcomes and Room persistence remain unchanged.

## Original fixture pipeline

Phase 0 uses a fake local fixture pipeline.

1. Read JSON from `fixtures/`.
2. Parse fixture records with `FixtureImportParser`.
3. Validate records with `ScoreRecordValidator`.
4. Route invalid records to `QuarantineRecord`.
5. Deduplicate valid records with `ImportDeduplicator`.
6. Write accepted scores to Room.
7. Write quarantine entries to Room.
8. Write an `ImportBatch`.
9. Return an `ImportResult`.

## Validation Rules

- `title` must not be blank.
- `achievement` must be within `0.0..101.0`.
- `levelIndex` must be `0..4`.
- `difficulty` must map to the same `levelIndex` when both are present.
- Duplicate imports must not create duplicate score rows.
- Invalid records must not contaminate the score table.

## Deduplication

Score ids are deterministic from normalized title and level index. The importer also checks existing Room score ids before insert. Room uses conflict-ignore inserts and a unique index on title plus level index as an additional guard.

## Quarantine

Quarantine records store reason, optional difficulty, fingerprint, batch id, and creation time. They do not store raw HTML, credentials, full URLs, or raw fixture payloads.

