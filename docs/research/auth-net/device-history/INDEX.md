# AUTH-NET device investigation history

Curated 2026-10-04. All windows below use Asia/Shanghai (UTC+08:00).
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE. PR #7 remains Draft.**

This is a historical evidence curation, based on fetched `origin/auth-net-1-v030`
at `663cca0ac658877d09e1976289052b59056794c3`. No new device access, ADB,
OAuth request, server probe, production experiment or CTO relay was performed.
The original local reports and artifacts were retained. Missing observations
remain `UNOBSERVED`; synthetic checks and Owner reports are identified separately.

## Current reading

The strongest measured differential is [2K](AUTH-NET-2K.md): immediately adjacent
phone FAIL/SUCCESS attempts changed the callback **first hop from 404 to 302**.
Observed browser Cookie names and both compared values were the same; normalized
UA and observed request header-name sets were the same; each pre-replay jar was
empty before browser-cookie seeding. These observations weaken simple
Cookie-presence and process-global-jar priming explanations for that pair.

The leading investigation area is **server-side transaction state, OAuth parameter
integrity, backend affinity, and remaining unmeasured metadata**. These are
candidate explanations, not established causes. Non-UA/non-Cookie header-value
equality and fresh OAuth query-value integrity were not measured; timing causality
was not established. A first-hop 302 followed by authenticated Home proves that
the callback endpoint can serve a successful transaction. It supersedes the
global route-unavailable interpretation of 2H/2I, without erasing their observed 404s.

[Phone build forensics](AUTH-NET-2J-PRECHECK.md) also corrects a control assumption:
the phone's installed APK is probably from the early AUTH-NET line near `919ea16`,
not the assumed stock `d734660` global-client build. Exact source SHA is unknown.
Phone success does not establish acceptance of the reviewed `07eb6ee` candidate.

## Chronology

Statuses apply to interpretations; correctly recorded historical measurements
remain evidence even when an interpretation is superseded. Windows from separate
reports are not pooled into a controlled experiment.

| Phase | Date/time window | Device/build | Key observation | Original classification | Current interpretation | Status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Initial AUTH-NET-1 matrix | Oct 3, 00:49–01:31 | Model 23116PN5BC; exact 919ea16 APK | Eight imports: callback 404, unauthenticated Home; no score fetch | FAILED 8; clean-install validation only | Historical auth-abort evidence; not score retry/PARTIAL acceptance | STILL_VALID | [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| Post-maintenance pre-capture failure | Oct 3, 07:36–07:43 | Same early report/build | Three WebView proxy-connect failures; no callback/import | POST_MAINTENANCE_AUTH_FAILURE_CHANGED | Capture/VPN/browser failure is distinct from callback rejection | STILL_VALID | [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2A / Clash revalidation | Oct 3, 08:40–08:55 | 23116PN5BC report series; 919ea16 vs d734660 | Both captured/replayed 404, 0/3 authenticated on each build | BASELINE_ALSO_FAILS_IDENTICALLY | Real ownership difference, but no measured lost authorize cookie or version-only regression | STILL_VALID | [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2B | Oct 3, morning; direct browser opened 09:50 | Early 919ea16 subject; temporary diagnostics restored | Six complete cookie-bearing captures/replays failed; direct HTTPS page visibly 404 | OWNER_AUTH_CONTINUITY_BLOCKED | Framing/cookie presence did not explain these failures; browser wire status unobserved | STILL_VALID | [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2C | Oct 3, 11:20–11:45 | 24018RPACC tablet; 0.2.9 control vs 919ea16 | Six interleaved attempts: 0/3 vs 0/3; callback 404 | TABLET_SHARED_AUTH_FAILURE | Same-tablet shared failure in this window; cause unknown | STILL_VALID | [2C/2D](AUTH-NET-2C-2D.md) |
| AUTH-NET-2D | Oct 3, 14:48–15:13 | Same tablet; direct HTTPS diagnostics vs 0.2.9 | Three RAW + one alternate-Wi-Fi RAW visible 404s; three app 404s | RAW_OAUTH_TRANSACTION_REJECTED_OUTSIDE_FLUENTMAI | User-visible failure outside capture/replay; RAW protocol status/query unobserved | STILL_VALID | [2C/2D](AUTH-NET-2C-2D.md) |
| Pre-2F acceptance | Oct 3, 15:39–16:26 | Tablet; exact 919ea16 candidate vs retained-session 0.2.9 | Candidate 0/5; one control transaction replayed four times and added PC rows | AUTH_INTERMITTENCY_BLOCKED_THIS_VALIDATION | Confounded control cannot be used as clean fresh-OAuth success/failure | STILL_VALID | [Acceptance](AUTH-NET-1-PRE-2F-ACCEPTANCE.md) |
| AUTH-NET-2F | Oct 3, 16:51–17:02 | Tablet; Owner-operated 919ea16 candidate | Five callbacks 404, Home 0/5; score stages unreached | OWNER_DRIVEN_AUTH_STILL_BLOCKED | Human operation did not recover this candidate; original measurement stands | STILL_VALID | [Existing full report](../../../AUTH-NET-1-HUMAN-AUTH-ACCEPTANCE.md) |
| AUTH-NET review fix | Oct 3, before 2G | No new device run; reviewed 07eb6ee | Complete-attempt ownership, cancellation and result repair; deterministic tests | Code review ready; device acceptance blocked | Internal correctness evidence, not device OAuth acceptance | STILL_VALID | [Existing review](../../../AUTH-NET-1-REVIEW-FIX.md) |
| AUTH-NET-2G | Oct 3, 19:18–19:40 | Tablet; 0.2.9 control vs reviewed 07eb6ee | A 0/6, B 0/6; capture succeeded 12/12 | CASE 3 / SHARED_CURRENT_AUTH_FAILURE | No evidence of a v0.3.0-specific auth regression under these shared conditions | STILL_VALID | [Existing 2G/2H](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md) |
| AUTH-NET-2H | Oct 3, evening; authorize observation 20:27 | Tablet/host; reference-compatible replay | One fresh R1 first hop 404; R0 and dummy probes also 404 | Callback route dead / shape-independent failure, historical hypothesis | Measurements stand; parameter non-evaluation and global unavailability were not proven | PARTIALLY_SUPERSEDED | [Existing 2G/2H, annotated](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md) |
| AUTH-NET-2I | Oct 3, retained 20:48:51–23:54:33 | Host synthetic probes; branch 663cca0 | Ten callback probes 404; two authorize checks 302 | Route-unavailable monitor premise; six-cycle plan | Dummy probes cannot establish real-OAuth unavailability; full six-hour run unsubstantiated | PARTIALLY_SUPERSEDED | [2I](AUTH-NET-2I.md) |
| R1 recovery window | Oct 4, Owner report 03:08; tablet 03:19–03:49 | Phone report vs reviewed 07eb6ee tablet | Owner-reported phone COMPLETE; two tablet transactions 404 | Phone/tablet divergence; cookieless/session rejection hypothesis | Phone build assumption corrected; Cookie-causal explanation not established | PARTIALLY_SUPERSEDED | [Recovery](AUTH-NET-RECOVERY-WINDOW-20261004.md) |
| Initial AUTH-NET-2J | Oct 4, before observer stop 16:08:45 | Existing phone APK, hash 39854f19… | Stock-class observer did not match APK; no observed OAuth attempts | NO_STABLE_PHONE_DIFFERENTIAL / baseline mismatch | Blocked experiment, not proof that the reported differential was absent | INCONCLUSIVE | [2J/precheck](AUTH-NET-2J-PRECHECK.md) |
| AUTH-NET-2J-PRECHECK | Oct 4, 16:21–16:27 | 23116PN5BC phone; 0.3.0-beta / 14 | 19/20 DEX match 919ea16 reference; auth-class disassemblies match | PHONE_BUILD_IDENTIFIED_PROBABLY | Early AUTH-NET lineage likely; stock/global-client assumption excluded; exact SHA unknown | STILL_VALID | [Forensics](AUTH-NET-2J-PRECHECK.md) |
| AUTH-NET-2J-R2 | Oct 4, 16:47–16:58 | Same phone APK; #3/#4 same replacement process | FAIL→SUCCESS; final callback 404→200; first hop unobserved | INCONCLUSIVE_CALLBACK_METADATA_INCOMPLETE | Differential exists; fuller 2K metadata is new evidence, not recovered R2 data | PARTIALLY_SUPERSEDED | [R2](AUTH-NET-2J-R2.md) |
| AUTH-NET-2K | Oct 4, 18:36–18:37 starts | Same phone APK; same live process/attachment | First hop 404→302; compared Cookie values SAME, normalized UA same, pre-replay jars empty | FIRST_HOP_AUTH_DIFFERENTIAL_CONFIRMED; SERVER_SIDE_STATE_OR_TIMING_DIFFERENTIAL_SUPPORTED | Measured differential; server state/parameter integrity/affinity/unmeasured metadata remain candidates | STILL_VALID | [2K](AUTH-NET-2K.md) |

## Failure categories and acceptance boundary

| Category | Evidence needed / historical example | What it does not establish |
| --- | --- | --- |
| Capture/VPN/browser transport | Proxy-connect failures with no captured callback | No callback response or server-auth rejection was measured in those runs |
| Callback/authenticated-Home | Captured callback response plus authenticated-Home check | HTTP transport `success` with status 404 is not OAuth success; Home HTTP 200 can be an error page |
| Score-fetch/import | Requests after authenticated Home, retries, parsed/persisted results | Auth-abort data preservation is not PARTIAL persistence or score retry recovery |

An Owner-reported score import is preserved as a report, not promoted into
independently measured candidate acceptance. 2K's `complete` attempt flag means
its auth observations are complete; it is not a COMPLETE score import. Reviewed
candidate retry recovery, PARTIAL persistence and successful background full import
remain unvalidated by this history.

## Local inventory and import decisions

The inventory covered 15 pre-existing AUTH-NET directories containing 1,037 files
across the local diagnostics and FluentMai Validation stores, plus seven local
Markdown reports. The curation's own audit directory is excluded from this count.
Directory names below are provenance labels, not working links to private artifacts.

| Local source directory | Import decision / durable destination |
| --- | --- |
| AUTH-NET-1-2026-10-02 | Eight-run matrix summarized in [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2A-2026-10-03 | Pre-capture proxy failure summarized in [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md); directory label differs from later continuity report |
| AUTH-NET-2-CLASH-2026-10-03 | Capture recovery and 2A baseline comparison summarized in [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2B-2026-10-03 | Framing, Cookie-bearing failures and direct-browser limit summarized in [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md) |
| AUTH-NET-2C-20261003-104138 | Missing same-tablet evidence added in [2C/2D](AUTH-NET-2C-2D.md) |
| AUTH-NET-2D-20261003 | Missing direct HTTPS/network differential added in [2C/2D](AUTH-NET-2C-2D.md) |
| AUTH-NET-1-ACCEPTANCE-2026-10-03 | Missing confounded-control round added in [Acceptance](AUTH-NET-1-PRE-2F-ACCEPTANCE.md) |
| AUTH-NET-2F-20261003 | Not re-imported: already adequately preserved in [human acceptance](../../../AUTH-NET-1-HUMAN-AUTH-ACCEPTANCE.md) |
| AUTH-NET-2G-20261003 | 2G/2H not duplicated: [existing findings](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md) annotated; previously unpreserved monitor evidence added in [2I](AUTH-NET-2I.md) |
| AUTH-NET-2H-20261003 | Relay/prompt/screenshot material not imported; instruction text is not run evidence; 2H measurements already in existing findings |
| AUTH-NET-R1-RECOVERY-20261004 | Missing measured failures and superseded hypotheses added in [Recovery](AUTH-NET-RECOVERY-WINDOW-20261004.md) |
| AUTH-NET-2J-20261004 | Initial baseline blockage summarized alongside [Precheck](AUTH-NET-2J-PRECHECK.md) |
| AUTH-NET-2J-PRECHECK-20261004-162157 | Missing phone build identity correction added in [Precheck](AUTH-NET-2J-PRECHECK.md) |
| AUTH-NET-2J-R2-20261004 | Missing differential and observation gaps added in [R2](AUTH-NET-2J-R2.md) |
| AUTH-NET-2K-20261004 | Full critical caveats and measured adjacent pair curated in [2K](AUTH-NET-2K.md) |

No separate 2I directory or final 2I results report was found. Its monitor evidence
is embedded in the 2G directory. No new acceptance test was run to fill gaps.
APKs, DEX/JDI fixtures, raw logs, UI dumps, screenshots, request dumps, private
device identifiers, OAuth/Cookie values and ephemeral fingerprints are excluded.
Existing implementation/port/baseline-rescue reports are linked rather than duplicated.

## CTO review map

**MUST READ:** this index, [2K](AUTH-NET-2K.md),
[Phone forensics](AUTH-NET-2J-PRECHECK.md), [R2](AUTH-NET-2J-R2.md),
and [Recovery interpretation corrections](AUTH-NET-RECOVERY-WINDOW-20261004.md).

**OPTIONAL HISTORICAL:** [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md),
[2C/2D](AUTH-NET-2C-2D.md), [Pre-2F acceptance](AUTH-NET-1-PRE-2F-ACCEPTANCE.md),
[2I](AUTH-NET-2I.md), [2F](../../../AUTH-NET-1-HUMAN-AUTH-ACCEPTANCE.md),
[2G/2H](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md), and
[implementation review](../../../AUTH-NET-1-REVIEW-FIX.md).
