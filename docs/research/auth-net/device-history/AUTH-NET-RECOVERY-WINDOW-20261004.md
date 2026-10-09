# AUTH-NET R1 recovery-window validation — 2026-10-04

Tablet window 03:19–03:49, UTC+08:00.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Attempt reviewed-candidate tablet acceptance immediately after the Owner reported
a phone COMPLETE import at approximately 03:08. Preserve the difference between
that reported witness, the measured tablet failures and the hypotheses proposed
in the local report.

## Environment

- Xiaomi 24018RPACC tablet; reviewed `07eb6ee` candidate from 2G,
  `dev.fluentmai.android.validation`, 0.3.0-beta / 14.
- Local report identifies candidate APK only by SHA-256 prefix `56ba50e1…`;
  the full hash is not invented here.
- Installed 0.2.9 control was stopped to isolate shared capture ports.
  Each real transaction used a cold candidate process. No Owner phone access occurred.
- Phone implementation was assumed stock v0.3.0 at the time. Later build
  forensics corrected that assumption; see below.

## Procedure

Synthetic dummy callback probes were recorded before/between tablet transactions.
Fresh entries were opened in WeChat and observed through callback/Home and stage
failure. Three retained mints correspond to two executed transactions; mint #2
was never opened after an operator navigation slip and is not a third OAuth test.
No callback auto-retry or score-source fault injection was performed.

## Observations

| Transaction | Fresh mint | Captured callback | Callback replay | Home request | Result |
| --- | --- | --- | --- | --- | --- |
| 1 | 03:30:15.3 | 03:31:34.942 | 404; 555 reported chars; 815 ms | 200; 7,366 reported chars; 3,817 ms; unauthenticated | Failed at login stage 1/6 |
| 2 | 03:37:11 | 03:38:22.088 | 404; 555 reported chars; 820 ms | 200; 7,366 reported chars; 2,555 ms; unauthenticated | Failed at login stage 1/6 |

The original report's `B` labels for app body lengths are not independent wire-byte
measurements; its underlying reviewed app events use decoded-character diagnostics.
Both callbacks contained `r`, `t`, `code`, `state` presence metadata; pre-replay jar
and captured-cookie summaries were empty. Callback HTTP transport `success` only
meant a response arrived, not that authentication succeeded.

Dummy pinned/normal-host probes at approximately 03:19 and 03:34 returned the known
555-byte nginx 404 response fingerprint. They were invalid-parameter probes, not
real OAuth tests. Recent/difficulty/supplemental/PC stages and persistence were
unreached in both real transactions. Pre-existing candidate B50 display was not
newly imported data from this round.

The phone COMPLETE witness (six stages, 1,819 parsed, supplemental 90, recent 50,
PC 98) is **Owner-reported**. No phone callback first-hop metadata, Cookie state
or exact executing source was measured in this window.

## Verdict at the time

No COMPLETE-import, real retry-recovery or PARTIAL-persistence marker was awarded.
The local classification was
`CALLBACK_REJECTION_PERSISTS_ON_TABLET_X2__PHONE_TABLET_DIVERGENCE__COOKIELESS_REPLAY_FINDING`.
Here "cookieless finding" preserves the observed empty metadata, not a causal conclusion.

The original report proposed the **historical hypothesis** that session-less/
Cookie-free callbacks were rejected while a stock phone's global jar/browser
cookies explained success. It also recognized that dummy 404s were insufficient
to detect real-flow health. Route flapping was an alternative, not excluded.

## Evidence limitations

The reported phone success and later dummy failures were not simultaneous controlled
transactions. Device, browser, network and transaction state differed. Empty
tablet Cookie metadata correlates with these failures but does not establish a
Cookie requirement. The second run was a fresh auth attempt, not recovery from a
natural score retry. Source stages were never reached; no acceptance marker follows.

Cleanup evidence reports capture still armed and the control stopped; a later
timeline entry records host log collection stopped at 03:49. A capture-stopped
final state is not claimed. The Owner required manual report delivery; this
curation performs no CTO communication.

## Later status

**PARTIALLY_SUPERSEDED.** Two tablet auth failures and unreached acceptance stages
remain valid. The **stock/global-client phone assumption is superseded** by
forensics showing likely early `919ea16` AUTH-NET lineage. The simple
Cookie-presence/global-jar priming explanation is unsupported: 2K observed
FAIL/SUCCESS with the same measured browser Cookie values and empty pre-replay
jars in both. This does not prove all cookies irrelevant to all transactions.

## Superseded by

[Phone build forensics](AUTH-NET-2J-PRECHECK.md), [R2](AUTH-NET-2J-R2.md),
[2K](AUTH-NET-2K.md). R2 proves later authenticated-Home success; 2K independently
measures first-hop 302. Neither retroactively establishes a first hop for the
03:08 Owner witness or reviewed-candidate tablet acceptance.

## Raw evidence provenance

Directory name: `AUTH-NET-R1-RECOVERY-20261004`.
Primary narratives: `RESULTS.md`, `timeline.txt`; historical app log events
support callback/Home timings. Raw `run.logcat`, UI dumps, screenshots,
probe bodies/headers and `cto_relay*.txt` are not committed. Relay drafts are
not technical evidence and were not sent by this curation.
