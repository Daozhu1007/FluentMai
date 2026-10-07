# AUTH-NET device investigation history

Curated 2026-10-04; engineering transition, final-APK session and replacement
bounded-retry acceptance added 2026-10-05; manual timing crossover added 2026-10-06;
real 4A quick-handoff acceptance added 2026-10-08.
All windows below use Asia/Shanghai (UTC+08:00).
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE. PR #7 remains Draft.**

The original historical curation was based on fetched `origin/auth-net-1-v030`
at `663cca0ac658877d09e1976289052b59056794c3` and performed no new device access,
ADB, OAuth request, server probe, production experiment or CTO relay.
The later [3A engineering report](AUTH-NET-3A.md) separately records implementation
and three tablet-only product-flow transactions from the fetched `8abfaa95` baseline.
The original local reports and artifacts were retained. Missing observations
remain `UNOBSERVED`; synthetic checks and Owner reports are identified separately.

## Current reading

[AUTH-NET-4A](AUTH-NET-4A.md) adds a capture-ready-first quick authorization action,
immediate sensitive clipboard write and ordinary WeChat launcher dispatch, with
the existing manual fallback and three-attempt ownership preserved. The new
binary's October 6 engineering task had deterministic coverage and tablet
no-OAuth smoke only: **READY_FOR_OWNER_QUICK_HANDOFF_VALIDATION_AFTER_0705**.
The separately authorized October 8 real acceptance used fetched source
`5008451` and unchanged exact installed APK `e8757de4…`, without rebuilding or
reinstalling. One Owner-operated quick-auth request demonstrated capture ready
before generation, one generation/clipboard/launch each, **17 ms** from authorize
generation to WeChat dispatch and **7,141 ms** to matched callback acceptance.
ADB observed WeChat foreground; the Owner reported direct landing in the prepared
public-account conversation (**OTHER_CHAT**). Callback final HTTP 200 and the
authenticated-Home event preceded successful required-page requests. The Owner
explicitly confirmed **导入完成**, with no partial-data warning, and the validation
DB gained exactly one batch while preserving the three pre-existing batch rows.
Awarded **LOW_LATENCY_HANDOFF_VALIDATED** and **AUTH_NET_COMPLETE_IMPORT_VALIDATED**
on this exact binary; task-defined early stop at **1/3**. The Owner explicitly
allowed pre-04:00 testing, and the real run was outside maintenance. No deliberate
delay, tablet UI input, phone access or production edit occurred. Natural service,
capture-listener and tunnel cleanup was checked. This single resumed-chat landing
does not establish a navigation API or repeated-launch guarantee; PC stage
assignment uses source order and final COMPLETE includes Owner UI evidence.
Fresh auth retry, launch fallback, score retry recovery and PARTIAL persistence
were not exercised in this acceptance. PR #7 remains Draft / WIP / NOT ACCEPTED /
NOT FOR RELEASE.

**ROOT-CAUSE FORENSICS CLOSED → PRODUCT MITIGATION IMPLEMENTED.**

The separately authorized [AUTH-NET-TIMING-1 manual crossover](AUTH-NET-TIMING-1.md)
reopened only transaction-age association. On the unchanged exact final APK
`cebb50dd…` / production `9dd6b2c`, actual order was FAST → DELAY-20 → FAST →
DELAY-20 → FAST, then the task-defined early stop at 5/6. FAST authenticated 2/3
(successful latencies 8,443 and 7,632 ms; one 9,467 ms FAST failure); DELAY-20
authenticated 0/2 (38,333 and 32,771 ms, each with a 20,000 ms host wait).
**`LATENCY_HYPOTHESIS_STRONGLY_SUPPORTED`** applies to that small-sample rule,
not causal proof or an exact TTL. The Owner waived the original start-time gate;
all observations were before, rather than inside, the 04:00–07:00 maintenance window.
Both authenticated imports completed naturally, with Owner-confirmed COMPLETE
terminal UI, logged required-page requests and two independently measured batch
additions: **`AUTH_NET_COMPLETE_IMPORT_VALIDATED`**, under those observation limits.
Authenticated full import is now witnessed on this exact APK; score retry recovery
and PARTIAL persistence remain unvalidated. No production change was made; broader
Cookie/header/backend/JDI/ART forensics remain closed. PR #7 remains Draft.

[AUTH-NET-3A](AUTH-NET-3A.md) adds user-mediated fresh authorization retry with a
three-transaction budget and no old callback replay. Its tablet run reached bounded
FAILED after three callback-404/unauthenticated-Home decisions:
`BOUNDED_FRESH_AUTH_RETRY_FLOW_VALIDATED`;
`AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED`.
It mitigates intermittent authentication failure without repairing or explaining
Wahlap server behavior. The final pre-identity-registration capture gate was added
after that run and has deterministic coverage; no fourth OAuth was spent on it.

The separately authorized [3B exact-final-APK session](AUTH-NET-3A.md#auth-net-3b-exact-final-apk-session--acceptance-incomplete)
used source `9dd6b2c` and verified installed APK `cebb50dd…`. Attempt 1 rejected
after exactly one callback replay and offered fresh retry; attempt 2 generated a
new authorization but an execution delay exceeded the 10-minute capture wait.
It ended FAILED without capturing/replaying that callback. Attempt 3 was not
generated. That interrupted run did not award the final-binary retry marker and
did not establish a final-binary defect. Control APK/install metadata and
validation import-batch rows were preserved, and validation services ended.

The Owner-authorized [3B-R2 replacement session](AUTH-NET-3A.md#auth-net-3b-r2-replacement-exact-final-binary-acceptance)
then used fetched HEAD `1d389a0` (production implementation `9dd6b2c`) and the same
exact installed APK `cebb50dd…`, without rebuilding/reinstalling. All three fresh
transactions completed promptly: each callback replayed exactly once, returned
404, then reached unauthenticated Home. UI advanced 1/3 retry → 2/3 retry → 3/3
FAILED with no retry button or automatic fourth transaction. Natural capture/VPN
and service cleanup was measured after each rejection; validation import-batch
rows and control APK/install metadata were preserved. Awarded:
`FINAL_BINARY_BOUNDED_FRESH_AUTH_RETRY_VALIDATED`;
`AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED`.
**The exact-final-binary bounded retry gate is closed.** `READY_FOR_NEXT_PRODUCT_PHASE`
applies to that gate under the Owner's three-rejection rule; that historical run
did not validate authenticated full import, score retry recovery or PARTIAL persistence.
PR #7 remains Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE. No further OAuth session
was run or authorized in that 3B-R2 task; TIMING-1 has separate Owner authorization.

The strongest measured differential is [2K](AUTH-NET-2K.md): immediately adjacent
phone FAIL/SUCCESS attempts changed the callback **first hop from 404 to 302**.
Observed browser Cookie names and both compared values were the same; normalized
UA and observed request header-name sets were the same; each pre-replay jar was
empty before browser-cookie seeding. These observations weaken simple
Cookie-presence and process-global-jar priming explanations for that pair.

[2L-R2](AUTH-NET-2L-R2.md) (2026-10-04, after 2K) then spent the final four-attempt
budget on four fresh transactions that all failed first-hop 404, and measured the
fields 2K could not: intra-transaction `r/t/state/code` integrity passed in **4/4**
attempts; first-hop request header **values** were identical across all four; every
first hop was EO-Cache-Status MISS on the same `nginx/1.29.5` banner with the FAIL
body byte-identical to 2K's. These header values are equal within the four FAILs;
2L had no SUCCESS. 2K retains the first-hop FAIL→SUCCESS pair but did not measure
every value-level field added by 2L. Equality across outcomes is not established.
No explanatory client-side defect has been identified; server-side
per-transaction/per-window state is the leading residual explanation space,
not a proven cause.
**`STOP_CLIENT_SIDE_ROOT_CAUSE_FORENSICS` is recommended**; the next engineering
direction, now implemented by 3A, is `BOUNDED_FRESH_OAUTH_TRANSACTION_RETRY`.

The leading historical investigation area — server-side transaction state, OAuth
parameter integrity, backend affinity, and remaining unmeasured metadata — has been
narrowed by the additional integrity-perfect failures, without disproving every
parameter-integrity or metadata candidate across FAIL and SUCCESS. Further broad
client-side forensics have low expected value; those historical windows did not
establish timing. TIMING-1 later adds a bounded association, without proving a cause.
A first-hop 302 followed
by authenticated Home proves that the callback endpoint can serve a successful
transaction. It supersedes the global route-unavailable interpretation of 2H/2I,
without erasing their observed 404s.

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
| AUTH-NET-2L-R2 | Oct 4, 23:40–00:05 | Same phone APK; PID 1167 across both attachments | 4 fresh transactions all first-hop 404; r/t/state/code integrity 4/4 MATCH; first-hop header values identical; every hop EO-Cache-Status MISS | NO_COMPLETE_DIFFERENTIAL (CASE E); STOP_CLIENT_SIDE_ROOT_CAUSE_FORENSICS recommended | Header values equal within four FAILs; no SUCCESS or complete value-level comparison across outcomes; server-side per-transaction/per-window state leads residual explanations, not proven | STILL_VALID | [2L-R2](AUTH-NET-2L-R2.md) |
| AUTH-NET-3A | Oct 5, 00:57–01:07 | 24018RPACC tablet; separate validation 0.3.0-beta / 14 | Three fresh callback requests, each once; callback 404 followed by unauthenticated Home; retry UI 1/3 → 2/3 → bounded FAILED 3/3 | BOUNDED_FRESH_AUTH_RETRY_FLOW_VALIDATED; AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED | Product mitigation observed; final generation-window gate tested separately; score/import hardware acceptance remains blocked | STILL_VALID | [3A implementation/device report](AUTH-NET-3A.md) |
| AUTH-NET-3B | Oct 5, 01:46–02:09 | 24018RPACC tablet; exact final source 9dd6b2c / installed APK cebb50dd… | Attempt 1: callback replay once, 404, unauthenticated Home, retry at 1/3; attempt 2: fresh authorize at 2/3, execution delay, capture timeout, zero callback replay; no third transaction | Acceptance incomplete; AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED retained | The interrupted sequence does not close the exact-final-binary 3/3 gap or establish a production defect; no final retry/full import/recovery/PARTIAL hardware marker | INCONCLUSIVE | [3B exact-final-APK session](AUTH-NET-3A.md#auth-net-3b-exact-final-apk-session--acceptance-incomplete) |
| AUTH-NET-3B-R2 | Oct 5, 02:41–02:46 | 24018RPACC tablet; fetched HEAD 1d389a0, implementation 9dd6b2c / unchanged exact installed APK cebb50dd… | One replacement logical import, 3 fresh transactions, 1 callback replay each, all callback 404 → unauthenticated Home; 1/3 retry → 2/3 retry → 3/3 FAILED; natural cleanup and no new import batch | FINAL_BINARY_BOUNDED_FRESH_AUTH_RETRY_VALIDATED; AUTHENTICATED_IMPORT_ACCEPTANCE_STILL_BLOCKED | Exact-final-binary bounded retry gate closed; READY_FOR_NEXT_PRODUCT_PHASE is scoped to this gate, not authenticated import or release acceptance | STILL_VALID | [3B-R2 replacement acceptance](AUTH-NET-3A.md#auth-net-3b-r2-replacement-exact-final-binary-acceptance) |
| AUTH-NET-TIMING-1 | Oct 6, 03:12–03:22; Owner waived original start-time gate | Same 24018RPACC tablet; fetched HEAD 902b553, production 9dd6b2c / unchanged exact APK cebb50dd… | Manual FAST / DELAY-20 / FAST / DELAY-20 / FAST; FAST 2/3 Home success at 8,443 and 7,632 ms, one FAST failure at 9,467 ms; DELAY 0/2 at 38,333 and 32,771 ms; two Owner-confirmed COMPLETE imports, two batch additions; natural cleanup | LATENCY_HYPOTHESIS_STRONGLY_SUPPORTED; AUTH_NET_COMPLETE_IMPORT_VALIDATED | Task-defined early-stop criterion met at 5/6; short latency is associated with acceptance in this window, with a retained fast failure; no exact TTL, causal server expiry, score-retry or PARTIAL proof | STILL_VALID | [Manual timing crossover](AUTH-NET-TIMING-1.md) |
| AUTH-NET-4A engineering / smoke | Oct 6, before 07:05; validation installation 04:09 | 24018RPACC tablet; exact installed validation APK e8757de4…; source 5008451 at acceptance preflight | Capture-ready-first coordinator, immediate clipboard/public launcher, deterministic checks; zero real OAuth transactions | READY_FOR_OWNER_QUICK_HANDOFF_VALIDATION_AFTER_0705 | Engineering and ordinary-launch smoke alone awarded no handoff or full-import hardware marker | STILL_VALID | [4A engineering](AUTH-NET-4A.md) |
| AUTH-NET-4A-ACCEPT | Oct 8, 02:21:16–02:22:08; Owner explicitly allowed pre-04:00 testing | Same tablet; fetched source 5008451 / unchanged exact validation APK e8757de4… | One real quick-auth request: capture ready → one generation → one clipboard → one dispatch; 17 ms generation-to-dispatch, 7,141 ms generation-to-callback; WeChat foreground, OTHER_CHAT; callback 200, Home YES, Owner-confirmed COMPLETE, exactly one new batch; natural cleanup | LOW_LATENCY_HANDOFF_VALIDATED; AUTH_NET_COMPLETE_IMPORT_VALIDATED | Early stop at 1/3; success-path ownership witnessed; no new hardware retry/fallback/score-recovery/PARTIAL proof, direct-chat API, TTL or root-cause claim | STILL_VALID | [4A real acceptance](AUTH-NET-4A.md#auth-net-4a-accept--real-owner-quick-handoff-october-8) |

## Failure categories and acceptance boundary

| Category | Evidence needed / historical example | What it does not establish |
| --- | --- | --- |
| Capture/VPN/browser transport | Proxy-connect failures with no captured callback | No callback response or server-auth rejection was measured in those runs |
| Callback/authenticated-Home | Captured callback response plus authenticated-Home check | HTTP transport `success` with status 404 is not OAuth success; Home HTTP 200 can be an error page |
| Score-fetch/import | Requests after authenticated Home, retries, parsed/persisted results | Auth-abort data preservation is not PARTIAL persistence or score retry recovery |

An Owner-reported score import is preserved as a report, not promoted into
independently measured candidate acceptance. 2K's `complete` attempt flag means
its auth observations are complete; it is not a COMPLETE score import. Reviewed
candidate score retry recovery and PARTIAL persistence remain unvalidated.
The later TIMING-1 run adds Owner-mediated successful full-import evidence on the
exact final APK, with its terminal-observation and stage-timing limits stated above.

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
| AUTH-NET-2L-R2-20261004 | Final differential round (integrity/header-value/edge measurements, 4 FAILs) curated in [2L-R2](AUTH-NET-2L-R2.md) |

No separate 2I directory or final 2I results report was found. Its monitor evidence
is embedded in the 2G directory. No new acceptance test was run to fill gaps.
APKs, DEX/JDI fixtures, raw logs, UI dumps, screenshots, request dumps, private
device identifiers, OAuth/Cookie values and ephemeral fingerprints are excluded.
Existing implementation/port/baseline-rescue reports are linked rather than duplicated.

## CTO review map

**MUST READ:** this index, [Manual timing crossover](AUTH-NET-TIMING-1.md),
[2K](AUTH-NET-2K.md), [2L-R2](AUTH-NET-2L-R2.md),
[Phone forensics](AUTH-NET-2J-PRECHECK.md), [R2](AUTH-NET-2J-R2.md),
and [Recovery interpretation corrections](AUTH-NET-RECOVERY-WINDOW-20261004.md).

**OPTIONAL HISTORICAL:** [Early evidence](AUTH-NET-EARLY-CAPTURE-CONTINUITY.md),
[2C/2D](AUTH-NET-2C-2D.md), [Pre-2F acceptance](AUTH-NET-1-PRE-2F-ACCEPTANCE.md),
[2I](AUTH-NET-2I.md), [2F](../../../AUTH-NET-1-HUMAN-AUTH-ACCEPTANCE.md),
[2G/2H](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md), and
[implementation review](../../../AUTH-NET-1-REVIEW-FIX.md).
