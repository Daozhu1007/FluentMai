# MCI-0 implementation plan

This plan proposes future research work. This round creates documentation only; none of the stages below has been implemented. Read the [research report](MCI0_RESEARCH_REPORT.md), [IR contract](MCI0_CANONICAL_IR_PROPOSAL.md) and [benchmark protocol](MCI0_BENCHMARK_PROTOCOL.md) together.

The next investment should be a small offline experiment with a strict format profile, verified structural evidence and four controlled prompts. Product integration is contingent on results.

## 1. Proposed placement and dependency direction

| Component | Initial research home | Possible later home | Boundary |
| --- | --- | --- | --- |
| Container/Simai parser | Independent Python package under proposed `research/mci0/`, or a separate research repository | A dedicated `chart-parser` package; KMP adapter only if justified | Takes bytes plus declared dialect; no catalog network, UI, database or account access |
| Canonical IR | JSON contract and pure data types in the research package | Optional separate `core:chart-ir` KMP module for Android/iOS; version-matched Python DTOs for Windows | No dependency on Compose, SwiftUI, PyQt, Room, SQLite or provider APIs |
| Deterministic analyzer | Pure functions over IR | Separate `chart-analysis` package/module | Versioned formulas, units, evidence IDs and coverage; never imports product UI |
| maimai semantic Skill | Small versioned text/reference bundle beside the harness | Independent domain-content package loaded by whichever LLM adapter exists later | Definitions and inference discipline; no parser arithmetic or network credential logic |
| Benchmark/research harness | CLI, local manifests and private corpus paths | Remain outside production application modules | Model invocation, run records and human scoring; no player-score collection |
| Future product identity adapter | Not built in MCI-0 | Thin platform/domain adapter | Explicitly maps provider ID/type/difficulty/revision to IR artifact; no title-only join |

Do **not** extend existing `core:model` with full chart event graphs now. That module already provides a KMP framework consumed by iOS; adding experimental parser/geometry dependencies would broaden its stability and interop obligations. Its existing catalog types, scoring counts and tolerance formulas have different purposes.

If the experiment succeeds, stabilize JSON schema and conformance fixtures first. Then evaluate whether a pure KMP `core:chart-ir` is worthwhile for Android/iOS. Windows remains an independent Python application; sharing a contract and fixtures is sufficient initially. A portable parser implementation is an optional later decision, not a reason to rewrite Windows or force Swift through Android import code.

## 2. Stage 0 — freeze evidence and corpus rules

Inputs: the four research documents; pinned source register; a consenting human curator able to supply local chart files and verify their revisions.

Work:

1. Define `mci-simai-0.1` support table. Decide pseudo-EACH policy, short-Hold defaults, offset precedence and allowed chain forms explicitly.
2. Record source hashes, parser/profile versions, dependency licenses and the distinction between original chart data, transcription and conversion.
3. Select 24 candidate charts and the 6/18 development/held-out split, grouped by song. Preserve rejected candidates in the ledger.
4. Prepare an independent factual oracle using notation references, manually calculated micro-examples and a second parser where it supports the same dialect. No single upstream parser is ground truth.

Exit: support profile and corpus manifest frozen; no commercial content added to Git. If real inputs are unavailable, complete synthetic conformance work and leave the real-chart evaluation explicitly pending.

## 3. Stage 1 — disposable parser/IR spike

Prefer Python for the initial offline package because the inspected parser and a lightweight JSON harness can run without changing a mobile build. This is a research-language choice, not a Windows product integration choice.

Evaluate two bounded options against the same conformance cases: a strict adapter around a pinned existing parser, and a small independently implemented parser for the declared subset. PySimaiParser's main output is insufficient as-is: a wrapper must not merely rename keys while leaving `E`, durations and path topology incorrect. MaiLib/MuConvert can provide independent format evidence and differential results without becoming FluentMai runtime dependencies. Select an option only on measured support, provenance and manageable dependency terms; do not copy converter internals blindly.

Required microfixture families, all original/synthetic:

- Single and simultaneous Tap; empty commas; initial offset; multiple difficulties with explicit mapping.
- BPM/grid changes, including rests and noninteger BPM; frozen-duration versus tempo-map distinction; supported absolute time expressions.
- Hold and Touch Hold, short forms, Touch C versus numbered sensor zones; invalid lane/sensor rejection.
- Slide head versus track modifiers; EX attachment; headless visual variants; explicit wait and duration.
- Straight/arc/other admitted shape families with legal/illegal endpoints; fan endpoint representation; same-head tracks and connected segments.
- Shared-duration chain with unresolved internal geometry: preserve topology and total time, mark internal segment times unavailable.
- Pseudo-EACH dialect rules, terminal `E` versus `E1` Touch, unknown directives, malformed brackets, duplicate metadata and missing BPM.
- Cross-boundary slices, tails past END, exact simultaneity versus rounded display times, deterministic IDs and repeated serialization.

Start with roughly 30–40 small cases covering these families; number alone is not a correctness target. Require 100% on mandatory facts and no silent loss. Capture negative-test diagnostics as seriously as positive parsing. Check that unsupported timing invalidates dependent results instead of returning a plausible partial chart as complete.

Exit: one file/selected difficulty can produce the proposed IR, source mapping and coverage report. All mandatory microfacts pass. No renderer, MA2 production adapter or LLM calls needed yet.

## 4. Stage 2 — minimum deterministic evidence

Implement only hit-onset counts, exact simultaneity groups, BPM/time queries, 1/2/4-second density windows, timing gaps, circular button distance/span, Slide/Hold temporal overlap and exact repeated motifs. Keep counts and rates distinct. Support evidence queries by time range/event ID with crossing sustained events included.

Emit conservative temporal interaction candidates; do not label them “抢手” or assign hands. Full sensor geometry, trajectory simulation and two-hand optimization remain outside the initial prototype. Shared-duration chains retain unknown internal times when geometry is missing.

Exit: independent arithmetic examples agree exactly; event/metric references validate; missing geometry suppresses dependent claims. The same IR/analyzer versions generate byte-identical C and D chart evidence. A and B share byte-identical raw evidence with matching scope and state.

## 5. Stage 3 — small Skill and human development set

Create only the five-part Skill structure proposed in the report: entry instructions, reviewed concepts, terminology registry, evidence rules and a few development/synthetic examples. Cover 交互, 纵连, 换手, 大跨度, Slide interference, reading/execution differences and sustained activity through evidence cues and explicit counterexamples. Unknown slang stays unresolved until a human supplies a definition/source.

Annotate the six development charts with 2–4 segments each, build 20 questions, and calibrate two raters. Populate factual values independently of the evaluated parser. Test for accidental inclusion of titles, constants, author names, community answers or Gold segment labels in prompts.

Exit: all four prompts, answer shape and scoring keys are usable; rater calibration reaches the protocol target; context fits for every arm. Freeze Skill and analyzer parameters before held-out evaluation.

## 6. Stage 4 — run and decide

Run the preregistered four arms twice on the frozen corpus, using one exact general-purpose model revision and fresh conversations. Log costs, context lengths, failures and model settings. Keep user-private chart sources local. Score blind, adjudicate novel localization candidates symmetrically, and compute paired chart-family uncertainty.

Apply the gates in the benchmark protocol without changing thresholds after seeing answers. If parser facts fail, do not try to compensate through prompt wording. If C matches D, simplify the Skill. If strong raw prompting matches D, report that rather than manufacturing a justification for IR. If uncertainty is too large, permit only a small preregistered replication before further investment.

Exit: a results report with observed scores, raw counts, exclusions, uncertainty, failure examples and a continuation decision. This is the first point where MCI's chart-understanding hypothesis can be assessed experimentally.

## 7. Risks, dependencies and work deferred

| Risk/dependency | Mitigation or explicit limit |
| --- | --- |
| Dialect ambiguity | Versioned profiles and conformance examples; unsupported constructs stay visible |
| Sparse human Gold | Candidate adjudication, disagreement fields, scoped questions and multiple acceptable answers |
| Incorrect independent oracle | Cross-check source, timing math and second implementation; record disagreements rather than voting blindly |
| Geometry not available | Symbolic topology plus temporal metrics only; abstain from physical certainty |
| Corpus/model familiarity | Blind identities, remove constants and player statistics, split song families |
| Annotation cost | Short form and structured responses; estimate and measure time on development data |
| Token/context cost | Preflight every arm; no truncation hidden from the scorer |
| Library suitability | Pin source and review actual output/terms before reuse; no transitive dependency added to FluentMai now |
| Product boundary creep | No modifications to Gradle modules, Swift interfaces, Python product imports or storage schemas during MCI-0 |

Do not build yet: automated corpus acquisition, a large grammar framework, all-format conversion, a faithful gameplay simulator, personalized hand strategy, chart embeddings/search, author-style prediction, difficulty regression, a model-training pipeline, a production LLM service, UI integration or shared database migrations.

## 8. This round's change and validation record

- Starting branch: `master`.
- Starting commit: `6aa6bafd5421fa2826dc3effccf40592a91d1f29`.
- Starting status: clean.
- Ending commit: `6aa6bafd5421fa2826dc3effccf40592a91d1f29`; no commit or push.
- Created: `docs/research/mci0/MCI0_RESEARCH_REPORT.md`, `MCI0_CANONICAL_IR_PROPOSAL.md`, `MCI0_BENCHMARK_PROTOCOL.md`, `MCI0_IMPLEMENTATION_PLAN.md`.
- Modified existing product files: none.
- Executed upstream tests outside the repository: PySimaiParser `python -m unittest discover -s tests -v`, 18 passed.
- Disposable experiments outside the repository: nine synthetic parser probes, documented in the research report; they revealed conformance/representation gaps and are not a passing MCI benchmark.
- Document checks passed: all 3 fenced JSON examples parse; all 31 local Markdown links resolve; external citation references resolve within the documents; benchmark allocation arithmetic and new-file whitespace checks pass. Field tables and cross-document requirements were manually reviewed.
- Product builds/tests: not run because no product code changed. No Android/iOS/Windows capability claim is made.
- Final Git status: the four requested Markdown documents are untracked; existing tracked files remain clean. No downloaded upstream code, commercial charts or generated model artifacts are inside the repository.

## 9. Small proposed MCI-0 prototype

Deliver one offline CLI/package with **one declared Simai profile**, a `maidata` selector, JSON IR/diagnostics, a small set of deterministic analyzers, the short semantic Skill, and a four-arm harness. Start with synthetic conformance cases and the six-chart development set; only then execute the 24-chart/80-question protocol if its prerequisites pass.

Its user flow is only: select a local chart → parse/inspect evidence → generate frozen experiment inputs → collect model answers → record human scores → produce a paired results report. The initial usable slice requires no full chart renderer, geometry engine, server, database or FluentMai UI. Keep source files outside Git and use private manifests to reference them.

Success means a reproducible answer to the research question with honest limits. It does not mean a shipped FluentMai feature. Recommendation: **GO_WITH_CHANGES**, limited to this prototype and its conformance/evaluation gates.
