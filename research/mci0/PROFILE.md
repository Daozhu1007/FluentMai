# mci-simai-0.1 (frozen before parser implementation)

This is a deliberately narrow research language, not universal Simai support.
Profile version: `mci-simai-0.1`; minimal schema: `mci-ir-spike/0.1`.
The schema implements only structural objects from the MCI-0 proposal, with a
source-accounting ledger; it does not claim to implement the full proposed IR.

## Admission table

| Construct | Classification | Exact policy |
| --- | --- | --- |
| Input | SUPPORTED | Strict UTF-8 bytes, optional UTF-8 BOM. Bare body or line-oriented maidata; explicit format and profile required. |
| Container | SUPPORTED | Explicit `inote_1` through `inote_7` selection; never choose first/nonempty automatically. `&key=value` starts a record at a line's beginning (ASCII indentation allowed). Multiline values end at the next record. |
| Metadata | SUPPORTED / preserved | Ordered raw records retained. Unknown metadata and unselected bodies are preserved with INFO diagnostics, not interpreted. Duplicate keys are INVALID, including unselected bodies and superseded offsets. |
| Offset | SUPPORTED | For maidata: `first_N` overrides `first`; bare body accepts an explicit decimal offset argument. No offset means null plus INFO, never implicit zero. All supplied offset records must be valid signed decimals. A CLI offset with maidata is INVALID. Chart time excludes offset. |
| Initial timing | SUPPORTED | Explicit positive BPM before the first grid; explicit positive grid before any note, comma or END. No fallback BPM/grid. |
| BPM / grid | SUPPORTED | `(B)` and `{D}` with finite positive decimal literals; comma advances `240/(B*D)` seconds and `4/D` quarters. Directives occur before the notes of a comma cell, in source order, including during rests. |
| Absolute grid | SUPPORTED | `{#S}` with positive decimal seconds; comma advances S seconds and `S*B/60` quarters. Initial BPM is still required. Later BPM changes leave S unchanged. |
| Whitespace | SUPPORTED | ASCII space, tab, CR and LF are ignored even within constructs, but original byte spans remain authoritative. |
| Tap / simultaneity | SUPPORTED | Lanes 1–8; `/` separates simultaneous atoms; multi-digit shorthand contains only unmodified button Taps. Source ordering retained; no deduplication of identical hits. Empty slash members INVALID. |
| Hold | SUPPORTED | Button plus `h`, duration below. Short `h` uses implicit `[1280:1]`, frozen at onset BPM. Zero explicit duration is admitted as a point. |
| Touch | SUPPORTED | A/B/D/E with index 1–8; C, C1 and C2 map to center C with null lane. |
| Touch Hold | SUPPORTED | Any admitted sensor plus `h` and duration (including short form). This is notation support, not a claim about official chart placement. |
| Modifiers | SUPPORTED | Button `b`, `x`, `h` in any order, each at most once. Touch allows `f`, `h` in either order. Touch Break/EX and button firework INVALID. |
| Slide head | SUPPORTED | A button with optional `b`/`x` before its path produces one TAP with role SLIDE_HEAD and STAR visual. Head flags do not propagate to track. |
| Slide paths | SUPPORTED | One segment per track: `-` STRAIGHT with clockwise lane delta 2–6; `^` ARC with delta 1–3 or 5–7, selecting the shorter arc (CW for 1–3, CCW for 5–7). All endpoints are lanes 1–8. Other deltas INVALID in this profile. No geometry. |
| Track modifier | SUPPORTED | One `b` immediately after that track's closing duration bracket. EX on track INVALID. |
| Shared heads | SUPPORTED | `1-4[4:1]*^3[4:2]`: each `*` introduces another complete track without repeating the head lane. One head, multiple linked tracks, each with its own wait and duration. Distinct slash-separated heads are never merged. |
| Hold duration | SUPPORTED | `[D:N]`, `[#S]`, `[B#D:N]`: nonnegative count/seconds; positive divisor/BPM. Numeric literals are decimals, not fractions/exponents. Ordinary beat durations freeze note-onset BPM. |
| Slide timing | SUPPORTED | `[D:N]` default wait one quarter at onset BPM; `[B#D:N]` and `[B#S]` use one-quarter wait at explicit B; `[W##S]`, `[W##D:N]`, `[W##B#D:N]` use explicit second wait W. Duration begins at movement start. Later BPM changes do not stretch either interval. |
| END | SUPPORTED | Standalone E must occupy the final cell, optionally after directives; E1–E8 remain Touch. No implicit END; any nontrivia after E (including comma) INVALID. Hold/Slide tails past END are preserved with WARNING. |
| Short Hold alternatives | DIALECT-SPECIFIC | Other simulator defaults are not selected; this profile uses 1280th-note duration. |
| Pseudo-EACH backtick | DIALECT-SPECIFIC / UNSUPPORTED | No timing interpretation in this spike. Preserve entire affected atom and invalidate exact timing of the suffix; do not apply a BPM-dependent or 1 ms guess. |
| Other paths/chains | UNSUPPORTED | `< > v V p q pp qq s z w`, connected chains and fan expansion. Preserve the entire atom with PARTIAL structure coverage. Known note-only omissions do not change comma timing. |
| Visual/headless syntax | UNSUPPORTED | `$`, `$$`, `@`, `?`, `!`; preserve atom with diagnostic. No fabricated head or false flags. |
| Extensions/comments | DIALECT-SPECIFIC / UNSUPPORTED | Editor speed directives, comments, unknown commands/tokens. Conservatively make suffix timing unknown; no recovery by guessing even after later BPM/grid directives. |
| Malformed | INVALID | Bad encoding, location, delimiter nesting, missing prerequisites, duplicate modifier, unsupported modifier attachment, negative duration, nonpositive timing basis, dangling slash/track, missing END. |
| Resource bounds | INVALID | Input >1 MiB, compact atom >4096 characters, numeric literal >24 characters, >20000 note/track objects, or accumulated cursor rational >2048 bits: RESOURCE_LIMIT, no parsing of a truncated input. The last two defensive bounds were added during implementation review, before the final gate run. |

## Loss and scope contract

Every source byte belongs to exactly one ordered ledger entry: NORMALIZED,
PRESERVED_WITH_DIAGNOSTIC, or REJECTED. Trivia is normalized as trivia. Omitted
objects always have a diagnostic and a preserved raw span; an empty output list
on PARTIAL/INVALID input is not evidence that the chart contains no notes.
Aggregate and capability coverage are relative to the selected body only.
INFO preservation of metadata does not reduce structural coverage. Unknown audio
offset restricts audio_alignment independently. Geometry is explicitly unavailable
on slide segments and is outside the declared coverage contract.

Timing-affecting omissions poison both seconds and quarter positions from the
construct onward. Valid later note structure can remain with null times; exact
groups exclude unknown times, and interval slicing refuses non-COMPLETE structure.
No malformed construct yields partially committed heads/tracks.

IDs include source SHA-256, selected body, byte span, expansion identity and
parser/profile versions. Serialization uses reduced `n/d` rational strings,
sorted keys, source-stable arrays, UTF-8, and no paths or runtime timestamps.
Slices use half-open intervals, retain full crossing Holds and moving Slides,
and include the source head of each selected Slide as explicit context. Waiting
alone does not count as movement occupancy; zero-duration events are points.

## Reference basis and deliberate restrictions

The selected rules were checked against the maintainer's
[notation reference](https://w.atwiki.jp/simai/pages/1003.html) and
[container reference](https://w.atwiki.jp/simai/pages/510.html), accessed 2026-09-11,
and the pinned source register in the MCI-0 report. These mutable pages are format
evidence, not original-game conformance certification. The table above is the
normative versioned contract for this prototype. Endpoint admission, strict END,
resource limits and conservative rejection are explicit research restrictions.
