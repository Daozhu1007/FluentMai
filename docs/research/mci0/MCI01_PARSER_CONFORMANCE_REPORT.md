# MCI-0.1 — Parser conformance and minimal Canonical IR spike

Date: 2026-09-11. Recommendation: **READY_FOR_MCI02**, limited to six-chart
development intake, coverage measurement and independent structural auditing.
Real-chart acceptance is unknown. No real chart, model call, semantic Skill,
24-chart benchmark, product UI/storage change or production dependency was used.

## Git record and boundaries

| Item | Result |
| --- | --- |
| Starting branch | `master` |
| Starting commit | `6aa6bafd5421fa2826dc3effccf40592a91d1f29` |
| Starting status | Exactly four untracked MCI-0 Markdown outputs; tracked/staged diffs empty; no unrelated changes |
| Documentation commit | `c12a4f70551fa8ef5918d62c1b7e23ca344ffcdc` — `docs: add MCI-0 research architecture and benchmark plan` |
| Ending implementation commit | The local commit containing this report and `research/mci0/`, subject `research: add MCI-0.1 strict parser and conformance spike`; resolve its exact immutable hash with `git log -1 --format=%H -- docs/research/mci0/MCI01_PARSER_CONFORMANCE_REPORT.md` |
| Product modifications | None; all implementation files are under `research/mci0/` |
| Publication | No push |
| Final status contract | Clean after the focused implementation commit; actual final HEAD and `git status --short` are also supplied in the task's final response |

The report identifies its containing commit by a resolvable Git path rather than
embedding its own hash (which would change that hash). Tests passed before the
implementation was committed. The four prior documents were read in full, matched
the previously recorded deliverable names/scope/baseline and were committed as
found, without editorial changes. No earlier byte-hash manifest was available to
prove historical byte identity; the precommit SHA-256 values recorded here make
that limitation explicit:

| Prior document | SHA-256 of precommit workspace bytes |
| --- | --- |
| MCI0_RESEARCH_REPORT.md | `fab67087f0e121473214d08a10868d14392a1307b00f9315b4cc70a960a1edee` |
| MCI0_CANONICAL_IR_PROPOSAL.md | `dd388e3ec8a3b1f8c5f4d45abe3a48fa2dc99e76726d025e7ff14d9f81e5587b` |
| MCI0_BENCHMARK_PROTOCOL.md | `3c319d701c917fb20e84999efe1c9ffed708af54c8c49a3b46baee62ae374f9f` |
| MCI0_IMPLEMENTATION_PLAN.md | `af4cdafc917d64a8dc2daea4a5de719c2cc1edfae0c825940cbd373570ad1c7e` |

## Frozen support profile

The normative, exhaustive admission table is
[research/mci0/PROFILE.md](../../../research/mci0/PROFILE.md): **mci-simai-0.1**.
It was authored before implementation, together with all 40 fixed fixtures.
Two defensive resource bounds were added during review, as disclosed in the
profile; no fixture expectation was changed to accommodate parser behavior.

Supported: explicit maidata slot selection and offset precedence; required initial
BPM/grid; decimal BPM changes and numeric or absolute-second comma grids; slash
simultaneity and plain Tap shorthand; button Tap/Hold, all declared Touch sensors
and Touch Holds; valid Break/EX/firework attachment; short Hold as implicit
1280th-note duration; ordinary Slide heads; restricted straight `-` and short-arc
`^` tracks with validated endpoint pairs; independent head/track Break; head EX;
shared-head `*` tracks; default/explicit waits and the enumerated duration forms;
standalone END; UTF-8/BOM/CRLF source tracing; exact crossing-interval inspection.

Unsupported: other shapes, connected chains, fan branches, headless/visual
variants, comments/editor commands and pseudo-EACH. Unsupported note-only atoms
are preserved atomically; unknown or dialect-dependent timing invalidates suffix
timing. The profile labels SUPPORTED, UNSUPPORTED, INVALID and DIALECT-SPECIFIC
explicitly. Other simulators' short-Hold/pseudo-EACH behavior is not adopted.

The maintainer's [notation reference](https://w.atwiki.jp/simai/pages/1003.html)
and [container reference](https://w.atwiki.jp/simai/pages/510.html) were revisited
on 2026-09-11. The MCI-0 proposal supplies the architectural contract. Reference
pages are mutable; this profile is the reproducible admission contract, not a
certification of universal Simai or original-game semantics.

## Empirical strategy selection

**Chosen: B — small independent Python parser.** Python 3.10.11 was used here;
runtime requirement is Python 3.10+, standard library only.

Strategy A was bounded to a verified pinned PySimaiParser public-API probe and a
terminal-E prefilter. The prefilter is a realistic minimal wrapper, not a full
adapter. Fourteen of the same independently authored fixtures were used. The
unwrapped path passed 3/24 applicable bounded checks; the prefilter passed 25/38.
These denominators differ because further checks require matching cardinality;
they are **not comparable aggregate accuracy estimates**. Any failed mandatory
field disqualifies that wrapper as the IR front end.

| Bounded comparison after terminal-E prefilter | Observation |
| --- | --- |
| Tap, simultaneous Tap, rest BPM change | Checked cardinalities/onsets pass |
| Frozen long Hold | Checked onset/duration pass |
| Short Hold (08) | Duration fails; upstream supplies zero |
| E versus E1/E2 (12) | Prefilter fixes this terminal-E case |
| Endpoint 4 versus 5 (13–14) | No structured endpoint/shape fields; distinction survives only in raw text |
| Explicit wait plus beat duration (16) | Movement duration fails; topology fields absent |
| Shared head (19) | Hit-record count differs from the one-head oracle; topology fields absent |
| Tiny absolute grid (22), absolute grid plus BPM change (40) | Onsets fail |
| Invalid locations (25) | Lane 9 remains accepted |
| Pseudo-EACH (29) | Returns dependent downstream notes without the required coverage restriction |

A reliable wrapper would have to reparse raw locations/modifiers, duration
expressions, shared heads, endpoints/path families and timing extensions; it
would also need a fresh loss/source ledger. That duplicates most of the selected
language's front end while retaining upstream float timing and warnings. The
independent implementation is the smaller trustworthy ownership boundary.

Upstream evidence pin: `Choimoe/PySimaiParser` at
`1268523ebc77f697afc97b1c5469cc0ccd4470fc`. The existing external source snapshot
had no repository-local Git metadata, so a parent-directory `git rev-parse` was
not used as its provenance. Its four package files and MIT license file were
instead compared byte-for-byte with raw files at the exact pinned GitHub commit;
all matched. File hashes are in
[upstream_pin.json](../../../research/mci0/reports/upstream_pin.json), and full
original synthetic observations are in
[upstream_observations.json](../../../research/mci0/reports/upstream_observations.json).

No upstream implementation was copied or imported into the parser/IR/CLI. The
optional offline comparison script alone imports the external snapshot after
hash verification. No third-party runtime dependency, downloaded commercial
chart, geometry data or model artifact is included in Git.

## Implemented IR and loss behavior

The minimal dataclass schema is `mci-ir-spike/0.1`, deliberately distinct from the
unimplemented full `mci-ir/0.1` proposal. It implements Chart, Timeline,
TimingEvent, NoteEvent, SlideEvent, SlideSegment, Diagnostic and Coverage, plus
small Location, DurationSpec, source-ledger and exact-group records. See the
[package README](../../../research/mci0/README.md) for field boundaries and CLI.

Authoritative timing uses `Fraction`, including decimal source literals, frozen
duration BPM, absolute spacing and offsets. JSON uses reduced `n/d` strings.
Source ordering and expansions survive simultaneity. A Slide head is a single
TAP with role SLIDE_HEAD; tracks reference it and preserve separate modifiers,
endpoint, symbolic family/direction, wait, movement start, duration and tail.
Two slash-separated identical heads remain two heads. END is a timing event.

Every byte is covered exactly once by NORMALIZED, PRESERVED_WITH_DIAGNOSTIC or
REJECTED ledger entries, including commas, separators, trivia, BOM, metadata and
unselected bodies. Event spans retain original UTF-8 byte offsets, decoded line/
column and raw source. Normalization is transactional per atom: a failing shared
track cannot leave a fabricated head or a surviving subset of its tracks.

| Condition | Diagnostic/coverage behavior |
| --- | --- |
| Unknown metadata / unselected body | INFO and raw preservation; selected-body structure remains COMPLETE |
| Missing offset | OFFSET_UNKNOWN; audio_alignment PARTIAL, chart-time structure can remain COMPLETE |
| Unknown syntax / pseudo-EACH / editor timing | PARTIAL structure and timing, raw retained, dependent times null; later BPM/grid does not reset absolute time |
| Known unsupported note/chain/visual atom | PARTIAL structure; whole atom retained, no guessed notes/flags; comma cursor remains exact |
| Invalid lane/modifier/duration | INVALID structure; atom rejected with source span; unaffected comma cursor may remain exact |
| Missing/invalid initial timing | INVALID; dependent times null |
| Invalid/ambiguous container | INVALID; no arbitrary selected body; source rejected as an envelope |
| Missing END / content after END | INVALID; no fabricated END and no suffix note ingestion |
| Tails beyond END | TAIL_AFTER_END warning with affected event ID; END and full tail both retained |
| Encoding/profile/whole-input resource failure | INVALID rejection envelope, original source SHA and rejected byte range; no chart inferred |

`structure` is the consumer's admission gate. `timing` describes cursor/retained
event timing prerequisites and cannot override omitted note structure.
`last_event_end_seconds` is null if omitted structure prevents a trustworthy
global maximum. No parser semantic fields are emitted. Raw source/metadata are
an archive attachment and will require a separate sanitized projection before
future model experiments.

## Independent conformance gates

All **40/40 fixed fixtures pass**, consisting of 25 expected COMPLETE results,
3 expected PARTIAL results and 12 expected INVALID results. Rejection fixtures
passing means the specified rejection and preservation facts were observed.
Each fixture contains independently authored exhaustive note/track rows, exact
END, diagnostic-code sets and selected extra field facts. Expected fractions were
hand calculated before the parser existed; no expected value calls the parser.

Fixture SHA-256:
`c78296cc274bfd2b4216bb28457df541c88370d859d6e86c0e9863030b6db269`.
See [cases.json](../../../research/mci0/fixtures/cases.json) and its
[oracle description](../../../research/mci0/fixtures/README.md).

| Mandatory fact family | Fixture IDs / checks | Result |
| --- | --- | --- |
| Tap/type/location/source order | 01–02, 09, 11–12 | PASS |
| BPM/grid/comma timing, including rests | 03–05, 22, 40 | PASS |
| Frozen Hold duration/end, seconds and explicit BPM | 06–08, 40 | PASS |
| Touch/Touch Hold/center aliases/firework | 08–10, 12 | PASS |
| Break/EX attachment and invalid combinations | 11, 19, 27 | PASS |
| Terminal E versus Touch E1/etc | 12, 36–37 | PASS |
| Slide head/start/endpoint/topology/wait/duration/end | 13–20, 26 | PASS |
| Same-time/start different endpoints remain distinguishable | 13–14, 20; structural field comparison | PASS |
| Distinct shape families/directions | 13–15; same-endpoint straight-versus-arc comparison | PASS |
| Shared-head cardinality and atomic rejection | 19–20; valid/invalid multiple-track checks | PASS |
| Invalid lane/sensor and unknown syntax | 25–30 | PASS |
| Malformed timing/duration and missing prerequisites | 31–35 | PASS |
| Exact simultaneity below display precision | 02, 22; group equality/integrity | PASS |
| Crossing intervals, boundary exclusion, zero duration | 21, 40; explicit half-open slice checks | PASS |
| Event tails beyond END | 21 | PASS |
| No silent drops, source byte coverage and references | All 40; ledger and ID invariants | PASS |
| Unsupported syntax restricts coverage/suffix timing | 28–30; editor/comment probes | PASS |
| Explicit selected body and offset precedence | 23–24, 38–39; envelope edge checks | PASS |
| Deterministic serialization | All 40, twice per process and in two independent processes | PASS |

The complete suite passed **53/53 test methods**, with no skipped tests:

```powershell
python -m research.mci0.tests --report research/mci0/reports/conformance.json
```

The additional checks include two distinct hash seeds (`1` and `8675309`), CLI
bare/file/selected-body/output/exit-code behavior, shared-head movement groups with
different waits, invalid UTF-8/profile, numeric and expansion limits, nonfinite
timing literals, duplicate metadata, superseded invalid offsets and atomic
multi-track rejection. A subprocess with a 15-second timeout exercises 1,200
seeded random malformed strings plus five large adversarial tokens. It completes
normally; this is bounded robustness evidence, not a proof about all inputs.

Serialization is byte-identical across the two fresh-process runs for every
fixed fixture. Machine-readable results are in
[conformance.json](../../../research/mci0/reports/conformance.json). No assertion
uses rounded floats for this parser; approximate comparisons exist only in the
separate upstream observation script because its output uses floats.

Initial defensive checks found and corrected a movement-group ID collision for
same-head tracks with different waits, and overly permissive recognition of a
duration-less repeated path as an unsupported chain. Neither fix changed the
independent oracle. Review also added bounded expansion and rational growth.

## Files changed

The documentation commit added only the four preexisting MCI-0 research outputs.
The implementation commit adds this report and the following isolated files:

| Files under research/mci0/ | Purpose |
| --- | --- |
| `.gitignore`, `.gitattributes` | Ignore private files/bytecode; normalize research source line endings |
| `PROFILE.md`, `README.md` | Declared grammar, loss contract, CLI/API instructions |
| `__init__.py`, `__main__.py` | Version constants and module entry point |
| `parser/__init__.py`, `parser/source.py`, `parser/duration.py` | Strict scanner/container, source mapping, exact timing grammar |
| `ir/__init__.py` | Minimal typed records, canonical JSON and interval references |
| `cli/__init__.py` | Local input selection and JSON/coverage output |
| `fixtures/cases.json`, `fixtures/README.md` | 40 original independent fact oracles |
| `tests/__init__.py`, `tests/__main__.py`, `tests/test_conformance.py` | Synthetic gate runner and structural/defensive assertions |
| `tests/probe_upstream.py` | Optional pinned external-parser experiment |
| `reports/conformance.json`, `reports/upstream_pin.json`, `reports/upstream_observations.json` | Reproducible gate and strategy evidence |

## Remaining risks and six-chart readiness

It is safe to begin **private six-chart development intake** using the exact
profile, explicit source slot and coverage gate, followed by independently
verified facts. It is not established that any particular real chart will be
COMPLETE, nor that the future 90% real-source coverage threshold can be reached.
No real chart was opened or smoke parsed in this phase.

The narrow two-shape profile, absent chains/headless paths and rejected
pseudo-EACH/comments may exclude substantial real material. Log every attempted
chart and failure; do not silently replace exclusions. Primary downstream facts
must require COMPLETE structure, and audio-dependent questions also need a known
offset. Unsupported cases remain challenge/intake evidence until separately
specified and independently tested profile extensions are available.

Other unresolved limits: no original-game replay validation; source-reference
interpretation and independently authored oracles still need external human
review; no geometry or internal chain timing; no verified catalog identity or
revision mapping; source-containing archive JSON is unsuitable as a blinded
model input; resource caps may reject unusually large but otherwise valid files.
These are explicit research boundaries, not passing benchmark claims.

Final recommendation: **READY_FOR_MCI02** for gated development coverage and
structural validation. The research question has a positive answer only for the
declared synthetic subset: trustworthy, traceable structural evidence is now
available there. Real-chart coverage and any benefit to later LLM experiments
remain unmeasured.
