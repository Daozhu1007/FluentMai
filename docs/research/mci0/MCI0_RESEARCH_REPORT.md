# MCI-0 — Maimai Chart Intelligence: research report

Research date: 2026-09-10. Status: architecture proposal; no LLM benchmark has been run and no chart-understanding capability has been demonstrated yet.

Recommendation: **GO_WITH_CHANGES**. Proceed with a small offline, Simai-first experiment, subject to parser conformance gates. Do not treat an existing parser's JSON as the canonical representation, conflate notation with hand technique, or use difficulty prediction as evidence of understanding.

The decisive question is whether reliable factual structure plus a small, sourced semantic Skill improves a general-purpose LLM's chart explanations without training. This is plausible enough to test. The largest immediate obstacle is trustworthy input representation, not model selection.

Companion documents:

- [Canonical IR proposal](MCI0_CANONICAL_IR_PROPOSAL.md): field contract, timing semantics, loss handling, analyzer boundaries.
- [Benchmark protocol](MCI0_BENCHMARK_PROTOCOL.md): four arms, 80 questions, human annotation, scoring and decision gates.
- [Implementation plan](MCI0_IMPLEMENTATION_PLAN.md): isolated stages ending in a disposable research prototype.

## 1. Scope, method and Git baseline

| Item | Recorded result |
| --- | --- |
| Workspace | `D:\Code\FluentMai` |
| Starting branch | `master` |
| Starting HEAD | `6aa6bafd5421fa2826dc3effccf40592a91d1f29` |
| Starting status | Clean; `git status --porcelain=v1` returned no entries |
| Applicable project guidance | No `AGENTS.md` found in the workspace or checked ancestor directories |
| Product changes | None |
| Ending HEAD | `6aa6bafd5421fa2826dc3effccf40592a91d1f29`; no commit or push |
| Final Git status | Four untracked requested Markdown files in `docs/research/mci0/`; tracked and staged diffs empty |

Method: inspect FluentMai source at the recorded commit; inspect upstream documentation and source pinned below; run upstream PySimaiParser tests and nine tiny synthetic probes outside FluentMai. External source snapshots were placed in `%TEMP%\fluentmai-mci0-20260910`. No commercial chart corpus, trained weights, player statistics dataset, audio, or video was downloaded. Upstream `data` and `info` submodules were not fetched. The source-only `tools` submodule was inspected at its pinned commit.

Evidence levels used throughout: **observed** means source inspection or a reported probe; **documented** means a maintainer/specification statement; **inference** means an explanation consistent with that evidence; **proposed** means an MCI design choice requiring validation. Existing open-source format implementations are useful primary evidence, not a guarantee of original-game equivalence.

## 2. FluentMai today

| Files inspected | Finding and architectural implication |
| --- | --- |
| [ChartRecord.kt](../../../core/model/src/main/kotlin/dev/fluentmai/android/core/model/ChartRecord.kt) | Identity, catalog BPM, versions, difficulty, designer and nullable `ChartNotes`. Counts are `total`, `tap`, `hold`, `slide`, `touch`, `breakCount`; no event timeline, lane sequence, paths or durations. A nullable catalog BPM integer cannot represent a tempo map. |
| [MaimaiTools.kt](../../../core/model/src/main/kotlin/dev/fluentmai/android/core/model/MaimaiTools.kt) | Achievement and SSS+ Tap-Great tolerance use aggregate weights and judgement loss. These answer score-budget questions; they cannot localize a section. Their scoring categories should not become the IR's structural note taxonomy. |
| [RatingRecommendations.kt](../../../core/model/src/main/kotlin/dev/fluentmai/android/core/model/RatingRecommendations.kt) | Simulates target achievement and B35/B15 gain from catalog and scores. It explicitly avoids estimating player skill. No pattern or execution analyzer is present here. |
| [MaimaiSongCatalog.kt](../../../core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/MaimaiSongCatalog.kt) | Parses LXNS song-list JSON into catalog models; imports note-count objects, not raw chart notation. |
| [SongCatalogStore.kt](../../../app/src/main/kotlin/dev/fluentmai/android/SongCatalogStore.kt), [LxnsMaimaiSongCatalogClient.kt](../../../app/src/main/kotlin/dev/fluentmai/android/LxnsMaimaiSongCatalogClient.kt) | Android owns network fetch, fallback/cache and refresh guards. `notes=true` requests aggregate catalog notes; it is not a chart-file acquisition route. |
| [RealWahlapImportAdapter.kt](../../../core/importer/src/main/kotlin/dev/fluentmai/android/core/importer/RealWahlapImportAdapter.kt), [IMPORT_PIPELINE.md](../../IMPORT_PIPELINE.md), [DATA_CONTRACT.md](../../DATA_CONTRACT.md) | Score-page import, validation, deduplication and quarantine are separate from chart parsing. The Phase 0 documents describe an earlier fixture pipeline; current adapter source takes precedence. |
| [core/model/build.gradle.kts](../../../core/model/build.gradle.kts), [IosDomainBridge.kt](../../../core/model/src/main/kotlin/dev/fluentmai/android/core/model/IosDomainBridge.kt), [settings.gradle.kts](../../../settings.gradle.kts) | `core:model` is already KMP with JVM and iOS targets, despite its historical `android` package name. Selected formulas reach Swift through a small bridge. It is not an appropriate home for experimental parser dependencies. |
| [CatalogModels.swift](../../../iosApp/FluentMaiIOS/Models/CatalogModels.swift), [iOS boundaries](../../platforms/ios.md) | Swift has native catalog DTOs and native platform integration; only selected domain behavior is shared. |
| [catalog.py](../../../windows/fluentmai_core/catalog.py), [chart_browser.py](../../../windows/fluentmai_core/chart_browser.py), [import_pipeline.py](../../../windows/fluentmai_core/import_pipeline.py), [Windows boundaries](../../platforms/windows.md) | Windows has independent Python catalog/import logic, SQLite and PyQt UI. It does not consume the Kotlin module. |
| [Android boundaries](../../platforms/android.md) | Android storage, UI and migrations remain Android-specific. MCI must not turn chart research into a cross-platform storage migration. |

Conclusion from this inspection: introduce an independent research package and JSON boundary first. If later justified, a small `core:chart-ir` KMP module could serve Android/iOS, with an independent Python implementation for Windows sharing contract fixtures. Nothing requires changing `core:model` now.

## 3. Input language: choose a declared Simai profile

`maidata.txt` is a container convention: metadata keys and multiple `&inote_N` chart bodies. Simai is the notation inside those bodies and can also be supplied as a standalone string. MA2 is a separate tabular chart representation; its music identity is typically external. A filename alone does not identify a dialect or trustworthy chart revision. [Container reference][S-container], [MaiLib format overview][L-readme]

| Input | Practical advantages | Costs and losses to manage | MCI-0 decision |
| --- | --- | --- | --- |
| Selected Simai body in `maidata.txt` | Human-readable, small local segments, metadata and multiple difficulties available together; accessible for player annotation | Stateful BPM/grid/offset; dialect extensions; slide chains; container difficulty indices differ from FluentMai | First input |
| Bare Simai body | Convenient for synthetic tests and segment questions | Needs explicit dialect, selected difficulty identity and offset supplied separately | Same parser entry point, explicit envelope |
| MA2 | Explicit bar/tick events and slide wait/length records; valuable independent conformance reference | Version-specific commands; external metadata; resolution/meter distinctions; conversion may alter timing and visual details | Inspect now; implement adapter only after Simai experiment warrants it |

Use the name **`mci-simai-0.1`** for an MCI-supported subset, not a claim to support every simulator. Pin the notation reference and enumerate every extension. Require an explicit initial BPM and grid in accepted primary-benchmark inputs; support global/per-difficulty offset precedence in the container adapter. Preserve unknown metadata. Unknown note/timing constructs make affected results incomplete; do not silently skip them.

The conventional container mapping is EASY=1, BASIC=2, ADVANCED=3, EXPERT=4, MASTER=5, Re:MASTER=6, ORIGINAL=7. FluentMai BASIC=0 through Re:MASTER=4 therefore requires a deliberate adapter, not copying an `inote` number. Do not infer STANDARD versus DX from that number; attach verified chart identity separately. [S-container]

### Critical timing distinctions

The reference defines comma spacing as `240 / BPM / divider` seconds, ordinary slide waiting as one quarter-note beat, and explicit second-based timing forms. The English reference specifies 1 ms pseudo-EACH offsets and a special short-Hold duration; inspected implementations do not all follow these rules. Treat profile differences as observable compatibility issues. [S-notation]

For a Simai duration expressed at the note's BPM, freeze that BPM when resolving the duration; later tempo changes still affect subsequent comma positions. For MA2 beat/tick durations, traverse the tempo map over the duration. MuConvert models these as different duration modes. An IR that stores only a beat length and always integrates BPM would change Simai meaning. [U-duration]

Do not hard-code every MA2 file to 384 ticks per format bar: MaiLib's inspected parser uses 384, whereas MuConvert reads `RESOLUTION` with 384 as default. Also preserve `MET` records: MuConvert explicitly ignores them, while MaiLib has measure-change objects. Their agreement is insufficient to certify unusual meter handling. [L-ma2], [U-ma2]

### Capability matrix

The table describes what a conforming deterministic parser/normalizer **can** provide, not a certification of any inspected library. RAW/NORMALIZED are syntax facts; DERIVED values are reproducible calculations; SEMANTIC outputs need interpretation.

| Capability | Syntax facts to preserve | Deterministic result | Semantic limit / first-profile policy |
| --- | --- | --- | --- |
| BPM changes | Simai BPM directives; MA2 BPM records | Ordered tempo map and active BPM queries | A tempo change does not prove a musical section boundary |
| Timing/grid | Commas, divisors, absolute-step forms; MA2 bar/tick/resolution | Exact positions and onset times | Simai subdivision is not a musical time signature |
| Tap | Button location and modifiers | One hit object at an onset | Hand assignment absent |
| Hold | Location and duration expression | Press/release interval; declared duration mode | Release difficulty needs a gameplay/technique model |
| Slide | Head syntax, path expression, timings | Separate head event and track object; chain/branch links | Track presence does not establish actual hand motion |
| Touch / Touch Hold | Area and index; duration/firework flags | Sensor location distinct from outer button | Center C is not lane 8; sensor geometry needs its own profile |
| Break | Modifier and its attachment | Head and track Break states independently | Do not turn every Break into a base note kind |
| EX | Applicable note modifier | Typed flag with profile validity checks | Do not propagate a head EX flag onto its track |
| Simultaneous notes | Slash, tap shorthand, shared-head syntax | Exact-onset groups; independent slide-movement-start groups | Source order is not hand order; pseudo-EACH stays separate |
| Slide start/end lanes | Start, terminal and any waypoint tokens | Validated locations, one endpoint selector or fan branches | Never discard endpoints into raw text alone |
| Slide shape/path type | `-`, arc forms, `v`, `V`, `p/q`, `pp/qq`, `s/z`, `w` and ordered segments | Symbolic path family, direction where validated, waypoints | Full trajectory/judgement geometry is a further contract |
| Slide wait | Default or explicitly stated timing expression | Head/reference onset to movement-start interval | Waiting is not itself a required continuous hand contact |
| Slide duration | Whole-path or per-segment expressions | Track end; segment times only when resolvable | Uniform-speed chain allocation needs trusted path lengths |
| Tempo versus visual speed | BPM directives versus editor high-speed extensions | Separate tempo map and supported display-speed events | Do not interpret visual speed as audio tempo; preserve unsupported extensions |
| Chart sections | Source markers, explicit selected range, chart end | Fixed windows, pauses and structural change candidates | Verse/chorus/climax and hardest-section names are semantic |

Sources for the matrix: [PySimai note parsing][P-timing], [MaiLib note and slide objects][L-slide], [MuConvert slide graph][U-slide], [MA2 parser][U-ma2], [Simai Japanese notation][S-jp]. Support for a token family does not imply support for every combination. Validate legal endpoints, chain forms and modifier attachment using small fixtures before admitting them to the benchmark.

## 4. MaiDiffPredictor: actual serialization and feature paths

Repository pin: `ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51`. Its `tools` submodule is **Choimoe/open-mdp at `b4d33dfbe994115eb400a4c2d410808336e57c8e`**. Inspecting `serializer.py` alone would miss the actual representation. [M-modules], [M-serializer]

### Serialized representation

The Python driver finds `maidata.txt` directories and invokes a C# serializer. C# emits one `{id}_{level}.json` and a CSV identity/level row. JSON is an array of `{ "Time": seconds, "Notes": [...] }` populated from note-bearing timing points. Each note carries exactly these 14 fields:

`holdTime`, `isBreak`, `isEx`, `isFakeRotate`, `isForceStar`, `isHanabi`, `isSlideBreak`, `isSlideNoHead`, `noteContent`, `noteType`, `slideStartTime`, `slideTime`, `startPosition`, `touchArea`.

There is no dedicated endpoint, path-type or segment graph field. Those details survive, if at all, in `noteContent`. BPM/grid events, source spans and empty comma positions are not exported as explicit records. [O-program]

`SimaiProcess.Serialize` starts at `first`; `slideStartTime` initially equals timing-point time plus wait. Its parser stores `inote_j` at array slot `j-1`; the driver visits slots 1–5, corresponding to conventional BASIC through Re:MASTER and outputs 1–5. This is another index namespace, not FluentMai's. The parser uses a BPM-dependent pseudo-EACH gap and maps center Touch to numeric position 8 with a separate area field. [O-process]

`fixSlideTime.py` later subtracts `Time` when `slideStartTime >= Time`, changing an absolute time to a relative wait without a schema-version change. This condition is not generally idempotent: at `Time=0.1`, `0.6 → 0.5 → 0.4` on successive runs still passes the condition. This is a source-derived counterexample, not a claim that all existing datasets are corrupted. [M-fix]

### Feature and target paths

| Path | Actual consumed information | Loss or qualification |
| --- | --- | --- |
| README command `train/ori_train.py` | An LSTM with attention; 18 scalars per note: hold time; seven flags; note-type code; slide wait/start; slide duration; start position; touch-area code; absolute time; density; sweep flag; simultaneous count; displacement | Does not consume `noteContent`, endpoint, path shape or segment graph. Simultaneity survives repeated timestamps/counts, but serialized tie order becomes sequence order. Label is `combined_diff`. [M-ori] |
| `train/dataset.py` | 10 values per time point: eight starting-lane counts, Break count, Slide count; sorts by `Time` | Time is used for ordering then discarded. Hold lengths, Touch areas, EX, slide duration/wait/end/shape and inter-onset gaps disappear. Padding and truncation are applied. [M-dataset] |
| `train/augmenter.py` → `EnhancedChartDataset` | Four statistical inputs: `fit_diff`, `std_dev`, log count, and `sum(fc_dist[-2:])/sum(fc_dist)`; missing values filled from difficulty-group statistics or defaults | This is the exact implemented ratio; do not rename it a general FC/clear rate without validating bin definitions. Fallback `fit_diff` uses catalog level; group imputation also depends on level. [M-augmenter] |
| `train/model.py` → `LatentDifficultyTransformer` | Instantiates EnhancedChartDataset without overriding its length; labels are parsed catalog `Level`; generates track/type indices via argmax over count features | Actual default is 1000 time points here, despite `config/train.json` listing 1280 for other defaults. Argmax loses chord multiplicity for positional embedding even though count channels remain. [M-model], [M-config] |
| `preprocess/genInfo.py` | Builds `ds`, adjusted `fit_diff`, and their mean `combined_diff`; falls back to `ds` when adjusted fit is nonpositive | The LSTM target already contains population performance-derived information. Target construction is not evidence of local semantic understanding. [M-info] |

The enhancer's `density` is the number of preceding entries in a 1.5-second lookback, not notes/second; equal-time notes can get different values because they are visited sequentially. `multiPressCount` counts equal-time entries. `displacement` is circular lane distance to the previous time/position-sorted note, including ties. `sweepAllowed` marks a heuristic chain of three consistently adjacent lanes within a 0.5-second lookback; it is not a proof of a playable sweep. [O-enhance]

### Why the documented limitations may exist

The maintainer reports excessive statistical influence and weak sensitivity to local patterns in Issue #1. This is an observation about the Enhanced/Latent models, with suggested causes, not a controlled ablation result. [M-issue]

Our inferences from code:

1. Different rhythms can produce identical 10-channel sequences once timestamps disappear. A model cannot recover information absent from its input.
2. The 18-channel path retains timing but still cannot distinguish slides sharing start/duration/flags when only their endpoints or shapes differ.
3. Statistics offer a strong shortcut to level prediction; level-derived imputation makes that shortcut stronger. Both sequential and statistical inputs are standardized in the inspected implementation, so “no normalization” is not an adequate diagnosis. [M-dataset], [M-augmenter]
4. Statistics are injected into every Transformer position and also influence latent weighting. No padding mask is supplied in the inspected forward paths. These are plausible sources of shortcut/length effects, not proven explanations. [M-transformer]
5. Neither inspected training loop provides a held-out chart-understanding evaluation. Six latent outputs are not automatically six validated player skill dimensions. [M-model], [M-ori]

Reusable ideas: timestamped event extraction, explicit feature computation, local windows, separation of sequence and aggregate views. Do not reuse the lossy tensor schema as an IR, the heuristic labels as factual technique, or the training architecture for MCI-0.

## 5. Parser assessment and small experiments

| Implementation | Useful evidence / reusable idea | Limits for MCI |
| --- | --- | --- |
| PySimaiParser `1268523...` | Small Python reader; metadata, timing and note flags; raw text retained; tests easy to run | Main JSON lacks structured endpoints/shapes/chains. Warnings can leave apparently valid output. Requires a conformance wrapper or a different front end. [P-core], [P-note] |
| MaiLib `c76e94e...` | Tokenizer/parser separation, Chart abstraction, explicit slide end/wait/length, MA2 command knowledge | Conversion-oriented mutable classes are not an LLM evidence contract; parser hard-codes resolution. Do not assume all source information survives conversion. [L-readme], [L-ma2], [L-slide] |
| MuConvert `73022f2...` | Separate container and chart parsers; rational duration modes; explicit chains/shared heads; warnings | Useful independent reference. Its MA2 reader ignores some records, so its “lossless” design goal is not blanket proof of preservation. Geometry and dialect behavior still require fixtures. [U-duration], [U-slide], [U-ma2] |

License files inspected: PySimaiParser MIT; MaiLib GPL v2 text; open-mdp GPL v3 text; MuConvert LGPL v3-or-later declaration. MaiDiffPredictor's README license section is a placeholder. These are recorded file contents, not a legal compatibility determination. This round copies no implementation into FluentMai; future dependency selection must record the applicable terms. [P-license], [L-license], [O-license], [U-license], [M-readme]

### Executed observations

On the pinned PySimaiParser source using Python 3.10.11, `python -m unittest discover -s tests -v` passed **18/18 tests**. Then nine original synthetic inputs were parsed through the public `SimaiChart.load_from_text` entry point; no real chart was used. Each input had `&first=0` and `&inote_5=` before the body below.

| Probe body | Observed output / relevance |
| --- | --- |
| `(120){4}1/2,3h[4:1],1-4[4:1],E` | Tap pair at 0, Hold at 0.5 lasting 0.5, Slide at 1 with wait/duration 0.5; additionally emits terminal `E` as Touch with null lane. A terminator must not be a note. |
| `(120){4}1` + backtick + `2,E` | Second Tap at 0.015625, versus reference 0.001-second gap. |
| `(120){#0.35}1,2,E` | Warning, second Tap at 0.5 rather than 0.35. |
| `(120){4}3h,E` | Hold duration 0, versus reference short-Hold rule. |
| `(120){4}1-4[3##8:3],E` | Wait 3, duration 0, warnings; supported-reference combination does not resolve correctly here. |
| `(120){4}9,E` | Accepts Tap lane 9 without warning. |
| `(120){4}1h[2:1],(240)2,3,E` | Hold duration 1; later onsets 0.5 and 0.75. Confirms frozen-onset BPM duration behavior for this case. |
| `(120){4}1-4[4:1],E` | No structured end-lane or shape field. |
| `(120){4}1-5[4:1],E` | Same structured note values as prior probe apart from raw note text. |

All nine also emitted the terminal `E` Touch. These are observed behaviors at one pin; they neither invalidate every parser feature nor certify every unsupported form. Upstream tests encode that implementation's expectations, so passing them is not equivalent to reference conformance. Reproduction bodies and expected distinctions are retained here; disposable probe scripts/output were not added to product code. [P-core], [P-timing], [P-tests], [S-notation]

## 6. Analyzer versus Skill

The parser owns tokens, attachment, positions, duration modes, diagnostics and source mapping. The normalizer resolves exact times and relations. Deterministic analyzers own counts, timing gaps, window density, interval overlap, symbolic repetition and explicitly named movement/contact proxies. Each metric must identify its formula, units, window, counting policy and limitations.

The Skill owns contextual interpretation: whether evidence resembles a community pattern, which alternative handings are plausible, why reading or execution may be demanding, and whether a statement requires video, a player profile or geometry unavailable in the input. “High density” is measurable; “hard for this player” is not implied by it. A temporal overlap is not proof of Slide 抢手.

The [IR proposal](MCI0_CANONICAL_IR_PROPOSAL.md) specifies formulas and evidence records. No parser object gets `hard`, `cross_hand`, `stamina` or an author-style score. Analyzer candidates are hypotheses under declared rules and must remain separate from semantic claims.

### First Skill structure, not a finished Skill

Propose five small files, supplied identically by version in benchmark arm D:

1. `SKILL.md`: read the evidence envelope; distinguish facts, proxies and hypotheses; cite event IDs/ranges; expose uncertainty; abstain narrowly.
2. `references/concepts.md`: reviewed definitions, operational cues and counterexamples for the terms below.
3. `references/terminology.json`: term ID, original spelling, aliases, language/community, source URL or consenting annotator ID, definition status, scope, disagreements and review date.
4. `references/evidence_rules.md`: which metric supports which claim, and what it cannot establish.
5. `examples/`: a few synthetic or development-only positive/negative examples with alternative interpretations. No held-out chart facts.

| Concept requested | Candidate evidence to teach | Required restraint |
| --- | --- | --- |
| 交互 | Alternating location/group motifs and timing regularity | A button pattern alone does not prove alternating hands; preserve the community's definition and alternatives |
| 纵连 | Repeated onsets at one location, run length and gaps | Distinguish repeated Tap hits from sustained Hold occupancy |
| 换手 | Annotated alternative hand assignments or geometry-supported candidate transitions | Cannot state an actual switch from notation alone |
| 大跨度 | Circular distance/chord width under an explicit threshold | Say the measured span; hand burden depends on assignment and body position |
| Slide 抢手 / interference | Slide activity plus competing hit/hold timing; optional spatial conflict candidate | Require more than time overlap; early judgement and contact mechanics can change the outcome |
| Reading difficulty | Timing irregularity, concurrent paths, symbolic branching; visual context if supplied | Note speed, visibility and familiarity are often missing |
| Execution difficulty | Tight gaps, holds plus other contacts, travel proxies | State player/technique assumptions; offer alternatives |
| Stamina pressure | Sustained activity windows and limited recovery | Do not equate a density peak with endurance demand |
| Author-specific patterns/slang | Source-backed examples with chart revision and community context | An author's name never proves a pattern; unsupported slang stays quoted and undefined |

These are proposed teaching boundaries, not newly asserted universal definitions of Chinese maimai slang. A term enters the accepted registry only after a knowledgeable human verifies its definition and positive/negative examples. If two communities disagree, keep separate senses. Unknown nicknames should produce a neutral structural description and a request for the intended definition, rather than an invented expansion.

## 7. Assumptions, risks and unresolved questions

| Issue | Status and consequence |
| --- | --- |
| Access to 20–30 real charts | Assumed for a later round. Humans must supply/select lawfully usable local files and verify identity/revision. Converted community files are not automatically original-game ground truth. |
| Format profile | Must resolve terminal `E` versus `E1` Touch, pseudo-EACH dialect, short Holds, offsets, chain timings and modifier attachment before corpus freeze. |
| Slide geometry | Symbolic topology is feasible; precise judgement-area traversal is not certified here. Shared-duration chain segment allocation and fan contacts require evidence-backed geometry or explicit unknowns. |
| Coverage | A narrow profile can select away challenging charts. Publish all attempted chart exclusions and parser failures; do not hide them by replacement. |
| Semantics | Hand assignment, interference, reading burden and author style are partly contextual. Require disagreement fields and alternative valid answers. |
| Benchmark contamination | Familiar chart names/constants can invite recall. Blind identities and remove level constants, player statistics, annotator prose and known-pattern tags from model inputs. |
| Representation confound | IR plus metrics is more than formatting. Primary C/D include the same factual metric view; a small metrics-off sensitivity check identifies whether the gain is mostly arithmetic assistance. |
| Statistical confidence | 18 held-out charts in the proposed default design establish only pilot-level evidence. Cluster by chart/song, report uncertainty, and replicate before product claims. |
| Runtime/token cost | Long IR can exceed context. Preflight every arm; use the same source interval and justified context without answer-dependent retrieval. |
| Reuse/licensing | Record dependency/source terms; do not copy an entire converter or commercial corpus into FluentMai. |

Still unknown: which real charts/dialects the humans will supply; how often difficult chains occur; which community definitions raters agree on; whether the chosen LLM benefits after factual reliability is controlled; whether timing-only interference candidates are useful. No training, real-chart semantic evaluation, original-game replay comparison or quantitative model improvement was performed in this round.

## 8. What should not be built yet

No production chart browser/viewer, mobile or Windows UI changes, database migrations, chart crawler, bundled commercial corpus, difficulty regressor, embeddings service, similarity index, personalized practice engine, universal hand solver, author classifier, full gameplay simulator or cross-platform parser rewrite. A chart understanding benchmark must precede these investments.

The [protocol](MCI0_BENCHMARK_PROTOCOL.md) makes continuation conditional on parser correctness, factual accuracy, measurable gains over raw prompting, low hallucination and useful abstention. Factual extraction should be deterministic in the underlying data; subjective technique explanations can retain disagreement.

## 9. Source register

All repository links below are pinned to inspected revisions. Web documentation was accessed on 2026-09-10; it is not an immutable game specification. Only the files actually supporting findings are listed; no claim is made to have reviewed every file in an upstream repository.

[S-container]: https://w.atwiki.jp/simai/pages/510.html
[S-notation]: https://w.atwiki.jp/simai/pages/1003.html
[S-jp]: https://w.atwiki.jp/simai/pages/1002.html
[M-readme]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/README.md
[M-modules]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/.gitmodules
[M-serializer]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/serializer.py
[M-fix]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/preprocess/fixSlideTime.py
[M-dataset]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/train/dataset.py
[M-ori]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/train/ori_train.py
[M-augmenter]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/train/augmenter.py
[M-model]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/train/model.py
[M-transformer]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/train/transformer.py
[M-config]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/config/train.json
[M-info]: https://github.com/Choimoe/MaiDiffPredictor/blob/ece16bf6a6ef0492b9c82e38ddc9c08c983e8e51/preprocess/genInfo.py
[M-issue]: https://github.com/Choimoe/MaiDiffPredictor/issues/1
[O-program]: https://github.com/Choimoe/open-mdp/blob/b4d33dfbe994115eb400a4c2d410808336e57c8e/serializer/src/Program.cs
[O-process]: https://github.com/Choimoe/open-mdp/blob/b4d33dfbe994115eb400a4c2d410808336e57c8e/serializer/src/SimaiProcess.cs
[O-enhance]: https://github.com/Choimoe/open-mdp/blob/b4d33dfbe994115eb400a4c2d410808336e57c8e/inference/src/enhance_json_with_features.py
[O-license]: https://github.com/Choimoe/open-mdp/blob/b4d33dfbe994115eb400a4c2d410808336e57c8e/LICENSE
[P-core]: https://github.com/Choimoe/PySimaiParser/blob/1268523ebc77f697afc97b1c5469cc0ccd4470fc/SimaiParser/core.py
[P-note]: https://github.com/Choimoe/PySimaiParser/blob/1268523ebc77f697afc97b1c5469cc0ccd4470fc/SimaiParser/note.py
[P-timing]: https://github.com/Choimoe/PySimaiParser/blob/1268523ebc77f697afc97b1c5469cc0ccd4470fc/SimaiParser/timing.py
[P-tests]: https://github.com/Choimoe/PySimaiParser/blob/1268523ebc77f697afc97b1c5469cc0ccd4470fc/tests/test_core.py
[P-license]: https://github.com/Choimoe/PySimaiParser/blob/1268523ebc77f697afc97b1c5469cc0ccd4470fc/LICENSE.txt
[L-readme]: https://github.com/Neskol/MaiLib/blob/c76e94e16592db4510e6f3b5f86cd2b7b360a350/README.md
[L-ma2]: https://github.com/Neskol/MaiLib/blob/c76e94e16592db4510e6f3b5f86cd2b7b360a350/Parser/Ma2parser.cs
[L-slide]: https://github.com/Neskol/MaiLib/blob/c76e94e16592db4510e6f3b5f86cd2b7b360a350/Note/Slide.cs
[L-license]: https://github.com/Neskol/MaiLib/blob/c76e94e16592db4510e6f3b5f86cd2b7b360a350/LICENSE.txt
[U-duration]: https://github.com/MuNET-OSS/MuConvert/blob/73022f2e2bd931b6296c3182c4a1b4307d525e02/chart/mai/Duration.cs
[U-slide]: https://github.com/MuNET-OSS/MuConvert/blob/73022f2e2bd931b6296c3182c4a1b4307d525e02/chart/mai/Slide.cs
[U-ma2]: https://github.com/MuNET-OSS/MuConvert/blob/73022f2e2bd931b6296c3182c4a1b4307d525e02/parser/mai/MA2Parser.cs
[U-license]: https://github.com/MuNET-OSS/MuConvert/blob/73022f2e2bd931b6296c3182c4a1b4307d525e02/LICENSE

## 10. Research decision

**GO_WITH_CHANGES** — build only a conformance-gated offline prototype. Preserve source timing semantics and slide structure, use independently checked facts, and test the Skill with blinded, paired human evaluation. The evidence supports testing this architecture; it does not yet support a FluentMai feature or a claim that an LLM understands maimai charts.
