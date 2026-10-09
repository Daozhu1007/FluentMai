# AUTH-NET-TIMING-1 — manual authorize-to-callback latency crossover

2026-10-06, Asia/Shanghai (UTC+08:00). **DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**
PR #7 remains Draft. This report reopens only the narrow transaction-age hypothesis.

**Classification: `LATENCY_HYPOTHESIS_STRONGLY_SUPPORTED` under the task's
predefined early-stop rule.** Two sub-10-second FAST transactions authenticated;
both DELAY-20 transactions failed authentication. The first sub-10-second FAST
transaction also failed. This is a small, ordered operational association;
neither a deterministic cutoff, a causal mechanism nor an exact TTL is established.

## Provenance and operation boundaries

Preflight completed on October 5. A successful `git fetch origin` verified
`origin/auth-net-1-v030` at `902b55390f9c0471e5f51fe64cd5ee62675f9af2`.
The same remote HEAD was fetched again before documentation delivery.
Production implementation remains `9dd6b2c56ec8bfafa765e77fe6b62cac7334fd29`;
the difference from that implementation contains documentation only.

Only the verified **24018RPACC tablet** was accessed. Validation package:
`dev.fluentmai.android.validation`, version 0.3.0-beta / 14. Installed APK
readbacks before and after the experiment both SHA-256 matched:
`cebb50dd116fb94ebfa166fe4c64054816e9b2fcadb7684f5cfcdb05aabf6ceb`.
No rebuild, reinstall, production edit, app-data clear or device reset occurred.
The control APK also remained unchanged; see data preservation below.

The Owner performed every UI action, using the existing capture, copy and fresh
retry flow and WeChat File Transfer Assistant. No agent UI input, UIAutomator,
Computer Use, accessibility automation, link opening or callback replay was used.
Each captured callback was requested **once by the ordinary product**; no agent
requested or replayed it. No phone access, instrumentation, JDI/ART attachment,
first-hop observer, synthetic endpoint probe, Cookie/header investigation or
backend/root-cause expansion occurred. No ChatGPT or CTO relay was opened.

The Owner confirmed that account/session, network and self-chat recipient stayed
the same, without data clearing, reboot or additional VPN/proxy changes. This
continuity is **Owner-confirmed**, not independently inspected inside WeChat.

### Start-time deviation and observer recovery

On resume, the agent issued the first FAST instruction before checking the new
day's time and observer health. It then withdrew the instruction because the
clock was before the original 07:05 start gate and the prior local collector had
stopped. The Owner had already completed transaction 1. Its six relevant product
events were recovered from the tablet's retained log buffer through the same
privacy allowlist; it was retained as FAST and counted, not discarded or repeated.

The Owner then explicitly instructed: `不用管时间，该测试测试。` The time restriction
was waived for continuation, and the live filtered observer was restored with the
existing transaction count. The first observation preceded that waiver. Actual
OAuth generation/capture windows were **03:12–03:20**, outside the stated
04:00–07:00 maintenance window, but before the original 07:05 start gate.
The prior preflight was retained; only time, observer continuity and connected
tablet availability required recovery checks.

## Measurement and actual order

Actual order: **FAST → DELAY-20 → FAST → DELAY-20 → FAST**, then early stop at
**5/6**. No sixth transaction was generated. Success 3 completed naturally before
starting the next logical session; success 5 also completed before cleanup.

Generation is the existing product's fresh-authorization log marker. Capture is
the existing bridge's callback-captured/emission marker. Intervals subtract these
two timestamps from the **same tablet log clock**, to nominal millisecond
precision. They do not measure an earlier internal server transaction creation.

For each DELAY-20, the host detected the live generation event and waited
**20,000 ms on its monotonic clock** before issuing `GO NOW`. Only then did the
Owner switch, paste, send and open. Tool/message delivery and manual completion
add to the measured total. No delay was inserted after opening the authorization
page. No raw URLs, OAuth fields, Cookie values, request bodies or screenshots
were retained in this report or the filtered event store.

All timestamps below are October 6, UTC+08:00. Product-attempt numbers in
parentheses follow the instructed flow and existing three-attempt source behavior;
they were **not directly exposed by the existing logs or independently read from
the UI**. Session 1 used planned attempts 1/3, 2/3, 3/3; session 2 used 1/3, 2/3.

| # | Condition | Session (planned product attempt) | Generated | Captured | Generation→callback ms | Callback final result | Home authenticated | Import result |
| --- | --- | --- | --- | --- | ---: | --- | --- | --- |
| 1 | FAST | 1 (1/3) | 03:12:18.650 | 03:12:28.117 | 9,467 | HTTP 404; callback path | NO | UNREACHED: auth rejected |
| 2 | DELAY-20 | 1 (2/3) | 03:14:25.443 | 03:15:03.776 | 38,333 | HTTP 404; callback path | NO | UNREACHED: auth rejected |
| 3 | FAST | 1 (3/3) | 03:15:43.968 | 03:15:52.411 | 8,443 | HTTP 200; final Home path | YES | COMPLETE, Owner-confirmed terminal UI |
| 4 | DELAY-20 | 2 (1/3) | 03:18:57.597 | 03:19:30.368 | 32,771 | HTTP 404; callback path | NO | UNREACHED: auth rejected |
| 5 | FAST | 2 (2/3) | 03:20:28.172 | 03:20:35.804 | 7,632 | HTTP 200; final Home path | YES | COMPLETE, Owner-confirmed terminal UI |

Both DELAY-20 intentional host waits were 20,000 ms. Home decision timestamps,
in order: 03:12:31.554, 03:15:07.092, 03:15:56.306, 03:19:33.810,
03:20:39.787. Every failed Home request had HTTP 200 but the product classified
its content with `WahlapAuthorizationRetryRequiredException`, without retrying it.
The successful Home-response marker is emitted only after the existing classifier
accepts the page. Callback HTTP transport `outcome=success` on a 404 is not an
authentication success. First-hop response status/redirects were **UNOBSERVED**.

There were exactly **five** generation markers, callback capture markers, callback
request starts and callback responses. Callback requests were each attempt 1/1,
`willRetry=false`. Primary outcomes were three unauthenticated Home decisions and
two authenticated Home decisions. There was no reused callback or automatic sixth
generation observed through collection shutdown.

## Successful imports and stage evidence

Both real imports ran without interruption. Each had successful logged requests
for recent records, all five difficulties, supplemental/rating target, and five
subsequent ranking pages. Times below are **request completion markers**, not
instrumented stage-start or parser-finish timestamps; every listed response was
HTTP 200 with a successful request outcome.

| Stage | Transaction 3 | Transaction 5 | Evidence basis |
| --- | --- | --- | --- |
| recent | 03:16:03.141 | 03:20:44.458 | Existing request label before difficulty pages |
| BASIC | 03:16:04.072 | 03:20:44.783 | Existing difficulty request label |
| ADVANCED | 03:16:08.240 | 03:20:46.236 | Existing difficulty request label |
| EXPERT | 03:16:12.068 | 03:21:06.803 | Existing difficulty request label |
| MASTER | 03:16:28.762 | 03:21:14.232 | Existing difficulty request label |
| RE_MASTER | 03:16:31.399 | 03:21:16.808 | Existing difficulty request label |
| supplemental / rating target | 03:16:33.234 | 03:21:17.327 | Existing supplemental request label |
| PC, five ranking-page requests | 03:16:33.896–03:16:36.391 | 03:21:18.034–03:21:20.189 | Source-order inference; shared request label |
| parser/persistence timestamp | UNOBSERVED | UNOBSERVED | Successful persistence confirmed by aggregate batch changes |
| final outcome | COMPLETE | COMPLETE | Owner-reported product terminal UI; natural service shutdown corroborated |

PC requests reuse `play-records` in `WahlapHttpScorePageClient`. Their placement
after supplemental completion, five-request count, and
`WahlapImportRunner.runRealImport` / `captureEfficientPc` ordering support the PC
assignment. They are not an independently logged PC transition or proof of every
parser result. Existing logs do not directly emit the complete task state,
product-attempt counter or terminal COMPLETE/PARTIAL/FAILED event. No
instrumentation or UI polling was added to fill those gaps. No PARTIAL or
score-request failure/recovery occurred in the retained observations.

## Data preservation and cleanup

Read-only validation DB/WAL snapshots retained only aggregate summaries and an
import-batch-row digest; temporary host copies were removed after summarization.
No control private data was read. The baseline was retained from the October 5
preflight; it was not re-snapshotted immediately before transaction 1 on resume.
The aggregate progression was:

| Snapshot | Score records | Import batches | Quarantine records |
| --- | ---: | ---: | ---: |
| Preflight baseline, Oct 5 17:52 | 1,730 | 1 | 2 |
| After successful import 3 | 1,731 | 2 | 4 |
| After rejected transaction 4 | 1,731 | 2 | 4 |
| After successful import 5 / final | 1,731 | 3 | 6 |

The after-3 and after-4 complete import-batch-row digests were identical. Final
batch count increased by exactly two, consistent with the two accepted imports;
no extra batch from a rejected transaction was observed. Separate before/after
DB snapshots were not taken for rejects 1 and 2. Their absence of import-stage
requests, combined with the aggregate progression, is the evidence boundary.
Expected success persistence, including quarantine rows, was allowed. Full DB
byte identity or per-record correctness is not claimed.

Control package `dev.fluentmai.android` remained 0.2.9-beta / 13. Its before/after
APK SHA-256 was identical:
`d58043448620ab470d2fb2e8c01ff3e874fe7339f112fbc147741cadf7e1ff17`.
Validation and control version/install/update metadata matched before and after.
The control was not launched, replaced, cleared or uninstalled; its data was
preserved by non-interference, not independently hashed.

Read-only service checks after successful imports showed no validation import,
hook HTTP or VPN service. Shutdown was natural; no device force-stop was used.
Independent socket/VPN-manager state was not measured. The host-only logcat
process was closed after final evidence collection, and the collector recorded
STOPPED with five transactions. No debugger or ADB forward was created.

## Disposition and scientific limits

- FAST: **3 attempts, 2 successes; 66.7%**. All three measured below 10 seconds.
- DELAY-20: **2 attempts, 0 successes; 0%**.
- Shortest FAIL: **9,467 ms**. Longest SUCCESS: **8,443 ms**.
- Classification: **`LATENCY_HYPOTHESIS_STRONGLY_SUPPORTED`**, using the task's
  predefined two-FAST-success / two-DELAY-failure rule. This is not a significance
  test. Ordered retries, logical-session differences, unobserved server state,
  temporal variation, early stopping and the five-transaction sample limit causal
  interpretation. Historical phone/automated-tablet windows were not pooled.
- Exact TTL established: **NO**. Do not infer an 8-second TTL, meaning of parameter
  `t`, ADB detection, or proven server-side expiry. A genuinely fast failure remains
  in the evidence; the FAST condition does not guarantee authentication.
- Authenticated real import acceptance obtained: **YES, twice**, under the
  unchanged exact validation APK and Owner-mediated terminal observation.
- Newly earned hardware marker: **`AUTH_NET_COMPLETE_IMPORT_VALIDATED`**, scoped
  to these two Owner-confirmed COMPLETE imports, direct authentication/request
  logs and independently checked batch persistence. Exact parser/terminal timing
  and every record's semantic correctness remain unverified.
- Do **not** award `REAL_DEVICE_RETRY_RECOVERY_VALIDATED` (score retry recovery)
  or `REAL_DEVICE_PARTIAL_PERSISTENCE_VALIDATED`; fresh authorization recovery is
  distinct from those gates. The historical final-binary bounded-retry marker is
  retained, not newly earned here.

Only this sanitized report and `INDEX.md` are committed for this task. Production
diff is empty. No APKs, local observer scripts, private DB/log files, device serials,
credentials or Owner-chat material are committed. PR #7 remains
**Draft / WIP / NOT ACCEPTED / NOT FOR RELEASE**. Report delivery is Owner manual
only; this experiment does not authorize a production timing fix or release.
