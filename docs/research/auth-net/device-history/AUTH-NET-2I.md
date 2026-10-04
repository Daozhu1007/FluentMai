# AUTH-NET-2I — callback monitor history, 2026-10-03

Retained window 20:48:51–23:54:33, UTC+08:00.
**DRAFT / WIP / NOT ACCEPTED / NOT FOR RELEASE.**

## Purpose

Characterize current callback responses across resolved/normal-host targets and
monitor them using synthetic dummy parameters without spending real OAuth codes.
The original plan assumed these probes could detect recovery of a globally
unavailable route. That assumption was later invalidated as an acceptance oracle.

## Environment

Host-side monitor; no real authorizations in the retained monitor records.
Branch context `auth-net-1-v030` at `663cca0`. Source records are inside the 2G
diagnostics directory; no separate 2I results directory/report was found.
This curation did not run the monitor or contact the endpoints.

## Procedure

The instruction specified a backend/DNS audit and up to six hourly monitor cycles,
with authorize health/public-signal checks on even cycles. Instruction text is a
plan, not proof that every requested step ran. The retained JSONL was inventoried
and summarized without importing scripts, callback bodies or raw artifacts.

## Observations

There are **14 retained records**: ten callback probes, two authorize-health checks
and two public-watch records. The ten callback records comprise five timestamp
pairs labelled cycles **1, 3, 4, 5, 6**. Each pair covers a pinned target and the
normal hostname; target IPs are omitted here.

| Retained cycle label | Timestamp | Callback probes | Authorize check | Public watch |
| --- | --- | --- | --- | --- |
| 1 | 20:48:51 | Two identical 404s | Not retained | Not retained |
| 3 | 20:53:59 | Two identical 404s | Not retained | Not retained |
| 4 | 21:53:40 | Two identical 404s | 302 to open.weixin.qq.com | NO_PUBLIC_SIGNAL |
| 5 | 22:54:04 | Two identical 404s | Not retained | Not retained |
| 6 | 23:54:33 | Two identical 404s | 302 to open.weixin.qq.com | NO_PUBLIC_SIGNAL |

Every callback record has `Server: nginx/1.29.5`,
`Content-Type: text/html; charset=UTF-8`, 555 observed bytes and 555 characters,
no Location, and response body SHA-256
`e32d0010b841a289f489aa6a7e6deef59a000799c53497d96171bdaf070ca370`.
Authorize checks retained server-minted parameter presence only. The public-watch
records retained no related public issue signal; this is not evidence of service health.

## Verdict at the time

The task's route-outage premise proposed
`CALLBACK_ROUTE_UNAVAILABLE_ACROSS_CURRENT_RESOLVED_BACKENDS` and, after a full
six-hour unchanged run, `CALLBACK_ROUTE_OUTAGE_PERSISTS_6H`. The retained probes
support identical synthetic responses on the tested targets and timestamps.
No located final report substantiates completion of a six-hour run, and neither
global real-OAuth unavailability nor parameter non-evaluation follows from 404 alone.

## Evidence limitations

- Cycle 2 is absent; the first-to-last retained span is **3h 05m 42s**, not six hours.
  Cycle 1→3 is approximately five minutes, so labels are not evidence of hourly duration.
- No complete historical DNS A/AAAA inventory was found in these monitor records.
  Coverage of every currently resolved backend cannot be independently reconstructed.
- Synthetic invalid parameters do not test acceptance of a real fresh transaction.
  An unchanged dummy 404 cannot prove that valid OAuth is unavailable or prevent
  successful OAuth from occurring between probes.
- No real first-hop recovery transaction or authenticated Home was observed by
  this monitor. Public-watch absence is only a limited historical search result.

## Later status

**PARTIALLY_SUPERSEDED.** The synthetic 404 measurements remain valid; the
global-outage/recovery-oracle interpretation is superseded. The recovery-window
report already showed that dummy 404s could coexist with an Owner-reported phone
success. R2 later measured authenticated Home, and 2K directly measured the same
callback endpoint producing a successful first-hop 302.

## Superseded by

[Recovery window](AUTH-NET-RECOVERY-WINDOW-20261004.md),
[R2](AUTH-NET-2J-R2.md), [2K](AUTH-NET-2K.md).
These later observations do not fill missing monitor cycles or prove when/how
server behavior changed.

## Raw evidence provenance

Directory names: `AUTH-NET-2G-20261003`, `AUTH-NET-2H-20261003`.
Primary retained evidence: `monitor_2i.jsonl` in 2G.
`cto_2i_prompt.txt` in 2H records the requested plan only; relay/prompt text is
not promoted into an executed result. No JSONL, script, raw request/body or
relay screenshot is committed.
