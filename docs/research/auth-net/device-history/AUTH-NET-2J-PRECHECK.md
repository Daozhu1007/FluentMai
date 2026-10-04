# AUTH-NET-2J / PRECHECK — phone build forensics, 2026-10-04

Initial observer stopped by 16:08:45; forensic window 16:21–16:27, UTC+08:00.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Verify the phone build before attributing failure-first/success-second behavior to
stock v0.3.0's process-global Cookie client. Preserve why the initial differential
was blocked and how installed-bytecode evidence corrected the assumed control.

## Environment

- Owner phone: Xiaomi 23116PN5BC / shennong, Android API 36; WeChat 8.0.72 / 3085.
- Existing `dev.fluentmai.android`, 0.3.0-beta / 14, debuggable;
  installed APK 26,678,317 bytes, SHA-256
  `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da`.
- Package first-install time 2026-10-03 00:15:13 and last-update time 09:52:34
  are device metadata, not source-SHA evidence. Android Debug signing certificate
  and shared version labels do not prove build identity.
- Comparison sources: stock `d734660`, early `919ea16`, reviewed `07eb6ee`.
  Installed APK was retained; no replacement or data clearing occurred.

## Procedure

Initial 2J attached a host observer expecting stock `WahlapKtorClient`, then
checked installed classes before any observed fresh OAuth transaction. The request
for Owner W1→W2 was withdrawn when that class did not match the APK.

PRECHECK inspected all 20 DEX files with an independent parser and SDK `dexdump`,
compared preserved APKs/disassemblies, and verified installed hash/metadata again.
It was read-only: no app launch, capture, OAuth or import in the forensic round.

## Observations

### Initial 2J baseline blockage

No fresh OAuth attempt, W1/W2 result or warm FAIL→SUCCESS pair was observed.
The stock lookup's `initialized=false, count=0` meant the requested class/storage
was unavailable; **it was not a valid empty-jar measurement of the installed client**.
Neither confirmation nor falsification of priming follows from that result.

The Owner reported temporary unresponsive taps, black notification shade and a
white reopened screen, followed by recovery. Debugger attachment/EVENT_THREAD
suspension could perturb execution; no cause was established. Absence of selected
fatal/ANR markers did not rule out a stall or another-process/system fault.
Observer/forward were removed, while normal app capture services remained active.

### PHONE_BUILD_FORENSICS

| Installed symbol | Result |
| --- | --- |
| WahlapKtorClient | ABSENT |
| WahlapOAuthAttempt | ABSENT |
| WahlapAuthCaptureHandoff | ABSENT |
| WahlapImportHttpClient | PRESENT, owns per-instance Cookie storage |
| WahlapWechatAuthUrlClient | Creates/closes a temporary authorize client and clears captured state |
| WahlapHttpScorePageClient | Creates a separate import client |

The independent parser and SDK inventory agreed on 30,012 class definitions.
Against the preserved `919ea16` validation APK, **19/20 DEX files were byte-identical**.
In the remaining DEX, **431/433 normalized class disassemblies matched**, including
all four inspected auth classes. The two differing normalized classes were
`MainActivity$onCreate$1$1$1` and `MainActivityKt`; other ZIP-entry differences were
manifest/resources, with the reference using the validation applicationId.
Normalization removed offsets, encoded instruction words and resolved-reference
indexes while retaining resolved instructions/operands/fields/debug positions.

| Candidate line | Disposition |
| --- | --- |
| Unmodified known stock d734660 | Excluded: stock has WahlapKtorClient; phone does not |
| Early 919ea16 | Most likely implementation line; strong DEX/auth-class agreement |
| Unmodified known reviewed 07eb6ee | Excluded: reviewed attempt/handoff owner classes absent on phone |
| Intermediate/local build retaining early architecture | Cannot exclude |
| Exact source SHA | UNKNOWN |

No source-linked reference APK had the full phone hash. An earlier phone readback
matched that hash, establishing artifact continuity rather than an exact source
commit. `919ea16` and documentation-only successor `c1b5a40` share app/core source.
Installed hash/path/version/timestamps and inspected worktree states were unchanged.

## Verdict at the time

Initial 2J: `NO_STABLE_PHONE_DIFFERENTIAL` with **baseline mismatch / not executed**;
this was not evidence that a warm differential was absent.
PRECHECK: `PHONE_BUILD_IDENTIFIED_PROBABLY`; likely early AUTH-NET near `919ea16`,
exact SHA unknown. No global-jar investigation or speculative production fix was justified.

## Evidence limitations

DEX similarity establishes implementation lineage, not reproducible full-build
identity. Generated-class differences are unexplained. Static lifetime differences
do not establish the cause of the Owner's prior phone success or tablet failures.
The earlier COMPLETE import remains Owner-reported. Initial observer records of
the nonexistent stock jar must not be used as measured installed-client state.

## Later status

**STILL_VALID** for the forensic correction. The initial blocked experiment remains
**INCONCLUSIVE**; its missing measurements are not filled retrospectively.
This corrects the stock/global-client assumption in the
[recovery-window report](AUTH-NET-RECOVERY-WINDOW-20261004.md).

## Superseded by

No newer evidence supersedes the forensic identity in this inventory.
[R2](AUTH-NET-2J-R2.md) and [2K](AUTH-NET-2K.md) observed new transactions on
this same APK; they do not establish an exact source SHA or turn it into a stock build.

## Raw evidence provenance

Directory names: `AUTH-NET-2J-20261004`, `AUTH-NET-2J-PRECHECK-20261004-162157`.
Primary artifacts: APK/class inventories, installed-auth disassembly,
`919ea16-dex-comparison.json`, normalized comparison, source references and
`final-verification.json`. Local narratives: `AUTH-NET-2J-SESSION-PRIMING-DIFFERENTIAL.md`
and PRECHECK `OWNER-REPORT.md`. APK/DEX files, package dumps, installed paths,
serials and debugger artifacts are not imported.
