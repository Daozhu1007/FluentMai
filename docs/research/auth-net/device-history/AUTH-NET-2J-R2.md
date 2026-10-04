# AUTH-NET-2J-R2 — phone FAIL/SUCCESS with metadata gaps, 2026-10-04

Window 16:47–16:58, UTC+08:00.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Observe a real adjacent failure/success differential on the existing early
AUTH-NET phone build without assuming stock/global-jar ownership.

## Environment

Xiaomi 23116PN5BC phone, existing `dev.fluentmai.android`, 0.3.0-beta / 14;
APK SHA-256
`39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da`,
verified before/after. [Forensics](AUTH-NET-2J-PRECHECK.md) supports likely
`919ea16` architecture, exact source SHA unknown. No replacement, global-jar
implementation, Cookie Import, injection or data clearing occurred.

## Procedure

The Owner operated fresh authorizations while a host observer/filtered collector
read selected boundaries. All **4/4 fresh authorizations** counted, including
one abandoned entry before an Owner process restart. #3/#4 were in the same
replacement process. No further attempt was made after FAIL→SUCCESS.

## Observations

| # | Authorize start | Entry generated | Callback emitted | Outcome |
| --- | --- | --- | --- | --- |
| 1 | 16:47:49.686 | 16:47:51.125 | UNOBSERVED | Owner-reported page failed/could not open; no captured replay |
| 2 | 16:51:51.393 | 16:51:52.736 | UNOBSERVED | Abandoned/unobserved; Owner restarted app |
| 3 | UNOBSERVED | 16:52:34.396 | 16:52:43.735 | FAIL; callback final 404, unauthenticated Home |
| 4 | 16:58:40.473 | 16:58:41.773 | 16:58:48.133 | SUCCESS; app Home-success log plus Owner confirmation |

Host method-entry times and device generated/emitted logs use different clock
bases. No exact authorize-start→captured-request interval was measured.

| Compared field | #3 FAIL | #4 SUCCESS |
| --- | --- | --- |
| Same-device entry-generation→callback-emission proxy | 9,339 ms | 6,360 ms |
| Captured Cookie presence | YES, inferred from pendingAuthCookies=1 | YES, same inference |
| Captured Cookie names/values | UNOBSERVED | UNOBSERVED |
| Captured header names / normalized UA | UNOBSERVED / UNOBSERVED | UNOBSERVED / UNOBSERVED |
| Pre-replay import jar | Count 0, empty names | Count 0, empty names |
| Replay-header count | 6 | 6 |
| Callback first-hop status/Location/body fingerprint | UNOBSERVED | UNOBSERVED |
| App followed callback | 404; 555 decoded characters; 916 ms | 200; 34,438 decoded characters; 8,691 ms |
| Jar after callback | 2: _ga, _ga_HMWCCH9Y0H | 5: _ga, _ga_HMWCCH9Y0H, _t, friendCodeList, userId |
| Home | 200; 7,366 decoded characters; unauthenticated | 200; 34,438 decoded characters; authenticated |

The installed client follows redirects. **Final callback 200 is not a measured
first-hop 302.** Query `code/state` presence was retained for #4's replay summary;
full captured key sets and `r/t` presence were unobserved. Post-success session
cookies are possible response outputs, not a proven pre-request cause.

## Verdict at the time

`INCONCLUSIVE_CALLBACK_METADATA_INCOMPLETE`. The measured FAIL→SUCCESS exists;
`NO_STABLE_PHONE_DIFFERENTIAL` would misdescribe this round. Callback equivalence
was insufficient to award `SERVER_SIDE_OR_TIMING_DIFFERENTIAL_SUPPORTED`, and
`TIMING_SENSITIVITY_SUPPORTED` was not awarded. Both attempts had observed Cookie
presence, weakening a simple absent/present explanation for this pair.

## Evidence limitations

Owner restart severed observation for #2/#3; #3 logs were recovered afterward.
ART argument/debug-info exceptions prevented full extraction on #4. Header-name
counts are not full name/value equivalence. The latency proxy was 2,979 ms shorter
on success but does not isolate timing. Host event handling peaked around 24 ms;
debugger/JIT effects outside measured handling remain unquantified. No complete
score-import acceptance follows from authenticated Home.

## Later status

**PARTIALLY_SUPERSEDED.** 2K repaired and independently verified observer readiness
and supplied a new, more complete first-hop pair. R2's missing metadata remains
missing; no later measurements are substituted into this round.

## Superseded by

[2K](AUTH-NET-2K.md) for the current measured differential and narrower hypotheses.
R2's own original inconclusive verdict and missing first-hop evidence remain intact.

## Raw evidence provenance

Directory name: `AUTH-NET-2J-R2-20261004`.
Primary artifacts: `OWNER-REPORT.md`, `result.json`, selected session/safe-log
events, recovered process events, field snapshots and Owner-event records.
Observer/collector were stopped and forwarding cleared; installed APK and production
diff were unchanged. No JSONL, debugger source/artifact, raw value, screen or body
is committed.
