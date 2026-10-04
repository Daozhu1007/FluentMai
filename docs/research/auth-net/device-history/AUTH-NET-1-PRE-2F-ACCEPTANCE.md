# AUTH-NET-1 pre-2F acceptance — 2026-10-03

Window 15:39–16:26, UTC+08:00. **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Preserve the exact-candidate validation following the Owner's natural auth-success
and score-timeout/EOF report, including the confounded control and resulting data
change that later preservation claims use as their starting point.

## Environment

- Xiaomi 24018RPACC tablet, Android 16 / API 36; primary WeChat 8.0.72.
- Candidate: exact `919ea16`, temporary build-only validation applicationId,
  0.3.0-beta / 14; installed APK SHA-256
  `98dd3d7e386d3b6802748dd31d94c0eda87cbccb896f3d15f86b169e4bf943f5`.
- Installed 0.2.9-beta / 13 control APK SHA-256
  `d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`;
  its source SHA was unknown. Wi-Fi; no active competing VPN at preflight.

## Procedure

Five fresh candidate transactions were followed through callback and Home.
One contemporaneous control authorization was attempted. The candidate was built
in an exact-source detached checkout, excluding existing experiments; temporary
build configuration was restored. Read-only data comparisons separated candidate
validation from the control fallback.

## Observations

- Candidate: callbacks captured 5/5, callback 404 in every attempt,
  authenticated Home 0/5, COMPLETE/PARTIAL/persisted imports all zero.
- Control: one fresh callback reached four retained Activity collectors and
  produced four app replays, all 404. These are four replays of one transaction,
  not four fresh authorizations. The control also retained three session cookies.
- Control PC records increased from 1,732 to 5,498 (**3,766 additions**).
  Existing PC rows, 1,732 score rows, 100 recent plays and the prior
  batch/rating/quarantine rows were unchanged. No database rollback erased the additions.
- Continued control authentication is a strong inference from PC upserts,
  not a directly observed fresh-OAuth Home success. The candidate reached no score source.
- The Owner's preceding witness reported a first auth failure then success,
  natural 30-second timeouts/chunked EOF, 140 parsed scores, three failed
  difficulties and 98 PC charts. This is Owner-supplied evidence, not a new
  candidate outcome in this round.

## Verdict at the time

`AUTH_INTERMITTENCY_BLOCKED_THIS_VALIDATION`; accepted NO, pushed NO at that time.
Neither a fresh candidate success nor a new AUTH-NET-specific regression was
proven. The control cannot be classified as a clean unauthenticated abort because
of retained sessions, repeated collectors and later PC additions.

## Evidence limitations

The source identity of the installed control is unknown. The repeated control
callback is operator-confounded. Score retry, PARTIAL persistence and successful
background full import were unreached. The original package was preserved, but
its data was not byte-identical to its pre-validation state after the PC additions.
Later 2F preservation is relative to its own start, after those additions.

## Later status

**STILL_VALID.** This round's confounds and historical data delta remain necessary
context. Later publication of the implementation does not turn this run into acceptance.

## Superseded by

No observation superseded. [2F](../../../AUTH-NET-1-HUMAN-AUTH-ACCEPTANCE.md)
provides the separately Owner-operated matrix; [2G](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md)
provides a clean interleaved shared-failure comparison after the review repair.

## Raw evidence provenance

Directory name: `AUTH-NET-1-ACCEPTANCE-2026-10-03`.
Primary artifacts: `verified-acceptance-summary.json`, per-run records/safe events,
`control-new-pc-verification.json`, preservation comparisons and cleanup records.
Local narrative: `AUTH-NET-1-REAL-DEVICE-ACCEPTANCE.md`.
No raw database, personal screen or secret artifact is committed.
