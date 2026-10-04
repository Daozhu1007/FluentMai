# AUTH-NET-2K — measured adjacent first-hop FAIL/SUCCESS, 2026-10-04

Authorize starts 18:36:11.558 and 18:37:34.312, UTC+08:00.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Repair the R2 observer's ART argument-reading/first-hop gaps, verify readiness
without real OAuth, then compare fresh adjacent FAIL/SUCCESS transactions on the
same installed phone build. Preserve the complete evidence and its causal limits.

## Environment

- Xiaomi 23116PN5BC phone; existing `dev.fluentmai.android`, 0.3.0-beta / 14,
  APK SHA-256
  `39854f19c07b35f0da0d500bd59267d3f5f6cc4bb2bee9cf2a25690c1768b3da`.
- Likely early `919ea16` architecture; exact source SHA unknown. This is neither
  the assumed stock global-client build nor reviewed complete-attempt `07eb6ee`.
- Both live attempts were in the same process and observer attachment. The earlier
  process disappeared before final readiness checks; cause unknown. The observer
  did not stop/restart it. The existing app was then launched normally for the pair.
- No APK rebuild/patch/replacement, account/app data clearing, Cookie injection,
  redirect change, deliberate delay or extra callback replay.

## Procedure

### Observer readiness mechanism

`OBSERVER_READY` was awarded **before real authorizations** from host Ktor tests
and a separate temporary phone ART fixture. The ART fixture loaded authentication
and Ktor classes directly from the unchanged installed APK and used synthetic
loopback requests only. SDK `dexdump` independently cross-checked DEX parameter
register maps. Installed Home classification and host reproduction agreed across
the same 12 synthetic cases.

Modern ART exposes DEX register slots; standard JDI/debug tables had interpreted
low slots/inline temporaries as JVM arguments. The observer instead read exact
APK code-item parameter registers at instruction zero. It read first-hop
`DefaultHttpResponse` fields at the header getter **before redirects**, and observed
body buffers at instance-filtered flush/close boundaries. It did not consume a
stream, invoke target methods, write fields or send another callback.

Synthetic checks covered complete header names (including punctuation), Cookie
names/count, normalized UA, query keys/timestamps, replay/seeded names and jar
snapshots; 404 and 302 before followed 200; Location classes/Set-Cookie names;
fixed-length, redirect, chunked and 77,000-byte bodies with expected lengths/hashes.
These establish observer readiness, **not real login success**.

The Owner then operated **two of the maximum four fresh authorizations**. Earlier
R2 outcomes were not counted as new attempts.

## Observations

### Adjacent live pair

| Field | FAIL #1 | SUCCESS #2 |
| --- | --- | --- |
| Authorize start | 18:36:11.558 | 18:37:34.312 |
| Captured browser request timestamp | 18:36:23.428 | 18:37:41.559 |
| Authorize→callback interval, host observer | 11,871 ms | 7,249 ms |
| Browser Cookie names | _ga, _ga_HMWCCH9Y0H | Same two names |
| Browser Cookie count | 2 | 2 |
| Compared observed browser Cookie values | _ga: SAME; _ga_HMWCCH9Y0H: SAME | Same comparison |
| Normalized browser UA | ANDROID_WECHAT | Same normalized value |
| Browser query key set | code, r, state, t | Same keys |
| Browser request header-name set | H-browser, below | Same set |
| Replay header-name set | H-replay, below | Same set |
| Seeded browser Cookie names | _ga, _ga_HMWCCH9Y0H | Same set |
| Jar before replay/browser-cookie seeding | Observed count 0, empty names | Observed count 0, empty names |
| First-hop actual request header-name set | H-request, below | Same set |
| First-hop actual request Cookie names | _ga, _ga_HMWCCH9Y0H | Same set |
| **Callback first-hop HTTP** | **404** | **302** |
| First-hop Location | Absent | Present; maimai.wahlap.com / OTHER_PATH class |
| First-hop Set-Cookie names | Empty | Empty |
| First-hop Server | nginx/1.29.5 | nginx/1.29.5 |
| First-hop Content-Type | text/html; charset=UTF-8 | Absent |
| Observed first-hop body bytes | 555 | 0 |
| Callback followed HTTP/path | 404 / CALLBACK | 200 / HOME |
| Jar after followed callback | 2: analytics names above | 5: analytics names plus _t, friendCodeList, userId |
| Home followed HTTP/path class | 200 / OTHER_PATH | 200 / HOME |
| Jar after Home | 4: analytics names plus _t, userId | 5: analytics names plus _t, friendCodeList, userId |
| Authenticated Home | NO | YES |

The failed first-hop body SHA-256 was
`e32d0010b841a289f489aa6a7e6deef59a000799c53497d96171bdaf070ca370`;
the successful 302's empty body SHA-256 was
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
These are response-body hashes, not OAuth/Cookie fingerprints. The normalized UA
comparison was equal at browser capture and observed first-hop request; its digest
is unnecessary to publish and is omitted.

| Name set | Complete observed names |
| --- | --- |
| H-browser | accept, accept-encoding, accept-language, connection, cookie, host, upgrade-insecure-requests, user-agent, x-requested-with |
| H-replay | accept, accept-encoding, accept-language, upgrade-insecure-requests, user-agent, x-requested-with |
| H-request | accept, accept-charset, accept-encoding, accept-language, connection, cookie, sec-fetch-dest, sec-fetch-mode, sec-fetch-site, sec-fetch-user, upgrade-insecure-requests, user-agent, x-requested-with |

Cookie equality was compared locally with an in-memory per-run keyed mechanism.
Only SAME/DIFFERENT outcomes were retained; no values, key or digest bytes are
included here. The run key/fingerprints were cleared before observer exit.
Actual-request Cookie **names** were observed; this is not a claim of decrypted
wire equality or equality of every other request field.

The Owner independently reported failure for #1 and successful login for #2.
The installed app's successful-Home log also corroborates #2. The filtered app
collector started after #1, so #1 has no retained app-log corroboration; its Home
classification used the observed body and classifier parity plus Owner report.
Both attempt records were complete for the auth observations; **no COMPLETE
score import was separately verified**.

## Verdict at the time

`FIRST_HOP_AUTH_DIFFERENTIAL_CONFIRMED` and
`SERVER_SIDE_STATE_OR_TIMING_DIFFERENTIAL_SUPPORTED` were recorded. The second
label supports a candidate explanation given equality of the specified observed
fields; it is not proof of byte-identical requests, a timing effect or server causality.

`TIMING_SENSITIVITY_SUPPORTED` was **not awarded**. One pair does not establish a
latency relationship. `BROWSER_SESSION_VALUE_DIFFERENTIAL_SUPPORTED` was **not
awarded** because both compared browser Cookie values were SAME.

Post-success `_t`, `friendCodeList`, `userId` are **response/session outputs**,
not proven causes of first-hop success. Failure Home also set `_t`/`userId`;
their presence alone does not establish authentication.

## Evidence limitations

- **Non-UA/non-Cookie header VALUE equality was not measured.** Identical names
  do not mean identical values.
- **Fresh OAuth query-value integrity was not measured.** Matching key sets do
  not prove `r/t/code/state` integrity, linkage or equivalence across stages.
- **Timing causality was NOT established.** Response snapshots briefly suspended
  all app threads; other selected boundaries suspended the event thread.
  Maximum synthetic event handling was 401.8 ms; maximum live handling 483.4 ms.
  Host monotonic breakpoint-receipt intervals are not uninstrumented wire timings.
- A single pair cannot distinguish server-side transaction/session state,
  token-specific handling, backend affinity, timing or unmeasured request metadata.
  Backend/cache metadata values were not isolated as a cause.
- No agent network-setting change was made; transient network behavior was not
  independently held constant. No all-stage score/persistence/background acceptance.

## Later status

**STILL_VALID.** This is the most complete retained real first-hop pair in the
inventory. It supersedes the global route-unavailable interpretation and supplies
new evidence beyond R2's metadata gaps. Simple Cookie-presence/global-jar priming
does not explain this measured pair: both requests had the same compared browser
Cookie values and empty pre-seeding import jars. This does not exclude every
possible cookie contribution in other transactions.

Leading investigation area: **server-side transaction state / OAuth parameter
integrity / backend affinity / remaining unmeasured metadata**. These remain
hypotheses; this history authorizes no new experiment or speculative production fix.

## Superseded by

None found in the local inventory. Context corrected by this round:
[2H interpretation](../../../AUTH-NET-2G-2H-DEVICE-FINDINGS.md),
[2I monitor premise](AUTH-NET-2I.md),
[recovery Cookie/global-client hypothesis](AUTH-NET-RECOVERY-WINDOW-20261004.md),
and [R2 observation gaps](AUTH-NET-2J-R2.md).

## Raw evidence provenance

Directory name: `AUTH-NET-2K-20261004`.
Primary sources: `OWNER-REPORT.md`, `readiness.json`, host/ART fixture results,
`result.json`, live/Owner-event records, `live-state.json`, `cleanup.json`.
Report and structured result agree on the pair. Cleanup recorded unchanged APK,
same live process across the pair, observer/collector stopped, forwarding/reverse
entries cleared, temporary phone DEX removed, ephemeral material cleared and zero
unfinished bodies. Ordinary app capture/import services remained under app control.
No JSONL, debugger/ART artifact, body, secret value or temporary fingerprint is committed.
