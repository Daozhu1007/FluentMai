# MCI-0 benchmark protocol and human Gold Set

Status: proposed and not yet executed. Freeze this protocol, chart split, source hashes, prompts, analyzer parameters and scoring keys before held-out evaluation. No retraining or fine-tuning is part of the experiment.

Question: can a general-purpose LLM read and explain chart structure reliably when supplied with Canonical IR and a small semantic Skill? Difficulty-constant prediction is optional exploratory work and contributes nothing to the continuation decision.

The [research report](MCI0_RESEARCH_REPORT.md) supplies the evidence and caveats; the [IR proposal](MCI0_CANONICAL_IR_PROPOSAL.md) defines the structured inputs.

## 1. Minimal decisive design

Default: **24 human-selected real charts**, three representative segments per chart, **80 unique questions**. The allowable range is 20–30 charts and 60–100 questions, but freeze one exact allocation before collecting answers.

- Development: 6 charts, 18 within-chart questions and 2 comparison questions = 20.
- Held-out: 18 charts, 54 within-chart questions and 6 comparison questions = 60.
- Each chart contributes one factual question and two questions drawn from local understanding, localization and pattern recognition. Across the full set, allocate 24 factual + 16 local + 16 localization + 16 recognition + 8 comparison = 80.
- Within held-out: 18 factual + 12 local + 12 localization + 12 recognition + 6 comparison = 60. Development supplies the remainder.
- Exactly 16 questions are deliberately unanswerable in full: 4 each among factual, local, localization and recognition tasks, including 3 per category in held-out. These are not extra questions. Thus held-out has 48 answerable and 12 insufficiency questions.
- Run all four arms twice in independent conversations at frozen settings: 640 total responses, including 480 held-out. Repeated responses measure stability and are not independent charts.

Expected annotation burden: about 10–15 minutes per chart for an experienced player with a prepared local viewer, plus 5–10 minutes for a second reviewer; approximately 6–10 person-hours including question preparation and disputes. Two blinded raters scoring 480 held-out answers at roughly 45–90 seconds each need 12–24 person-hours, with adjudication additional. Pilot these estimates on development data; the real cost is human review, not just generating 80 prompts. Use short structured responses to keep scoring feasible.

## 2. Corpus selection and freeze

Humans select the corpus rather than sampling whatever the parser happens to accept. Use a quota sheet to obtain at least 6 STANDARD and 6 DX charts, at least 6 EXPERT and 6 MASTER-or-Re:MASTER charts, and at least 4 relatively sparse/lower-demand charts as controls. Categories may overlap; Basic/Advanced charts can provide the controls.

Across the 24 charts, include at least 4 charts with salient Touch/Touch Hold content, 4 with simultaneous-note configurations, 4 with Slide/Hold interactions, 4 with recognizable repeated/alternating motifs, 4 with sustained activity and 4 with unusual timing or slide structure. These are curator coverage goals, not parser-generated difficulty labels. Do not require every chart to contain every phenomenon. If a phenomenon is absent from lawful available inputs, report the missing stratum.

Include headless/shared-head slides, chained paths, nondefault waits, tempo changes and modifier combinations where available. Put adversarial unsupported syntax in a separately identified challenge lane. Do not claim broad format support from a corpus that avoids these features.

Identity ledger, withheld from the model: song/provider ID, STANDARD/DX, difficulty, displayed level/constant, region/version, chart revision, source format/dialect, local file hash, selected `inote` slot, provenance and permission status. Record whether the file is authored source, a community transcription or a conversion. Align any replay/video to the exact revision and record its offset.

Split by **song family**, including all difficulties, STANDARD/DX counterparts, duplicates and conversions. No version of a held-out chart can appear in Skill examples or development prompts. Use a curator-generated fixed split before prompt tuning; comparison pairs must stay within one split. Prefer unfamiliar chart identities and make at least half the comparison pairs broadly similar in aggregate density/count so those statistics alone do not decide the answer.

Track all candidate charts in an acquisition/parse ledger, including rejected files, errors and reasons. Replacing failed charts does not erase those failures from coverage reporting. Primary evaluation uses supported charts; challenge results measure detection/abstention and are reported separately from supported-format accuracy.

## 3. Four arms and controlled information

| Arm | Supplied chart evidence | Instructions |
| --- | --- | --- |
| A — raw chart only | Selected raw Simai body/envelope or state-complete raw segment | Common task, output shape and evidence/uncertainty requirements only; no syntax tutorial or domain Skill |
| B — raw + syntax/domain instructions | Identical raw evidence to A | Frozen syntax primer plus the same reviewed semantic/domain concepts used in D, adapted to source-span citations |
| C — Canonical IR only | Frozen factual IR projection including identical deterministic metrics to D | Minimal data dictionary explaining units, field meanings, counting policy and completeness; no maimai pattern/technique teaching |
| D — Canonical IR + semantic Skill | Byte-identical chart evidence to C | Same dictionary plus the reviewed maimai semantic Skill |

“IR only” here means no semantic instruction: C still needs a neutral schema legend to interpret rational times and references. Primary C/D contain the same factual `DerivedMetrics` and unlabeled structural windows. This operationalizes the full Parser → IR → analyzer architecture; it does not isolate serialization from precomputation.

Keep B strong: reuse D's concept definitions, cautions and underlying synthetic examples rather than comparing D against an intentionally weak domain primer. Present examples in raw form for B and IR form for D, with matched underlying events. B alone also needs syntax instruction. Report prompt token counts and hashes; the representations need not have equal byte lengths.

Paired contrasts:

- D−A: practical benefit of the complete approach over raw prompting.
- B−A: benefit of instructions on raw notation.
- C−A: structured evidence and precomputation benefit without semantic teaching.
- D−C: incremental contribution of the semantic Skill.
- D−B: whether structured evidence adds value beyond capable raw-chart instructions.

Optional sensitivity check after primary freeze: rerun 12 preselected held-out questions for C/D with all analyzer metrics removed but normalized note/timing/path structure unchanged. Report separately, not as a fifth primary arm. If all gain disappears, describe the result as analyzer-assisted answering rather than claiming JSON alone creates understanding.

### Prevent leakage and unequal access

1. Use pseudonymous chart IDs. Remove title, artist, designer, level/constant, player performance, known-pattern tags, comments with answers, and human annotation prose from every model-visible chart envelope. Retain needed syntax, timing and offset. Preserve originals privately for provenance.
2. C/D exclude raw note strings, raw metadata, semantic annotations, human segment labels and Gold answers. They include only source locators/IDs, structural events, deterministic metrics and completeness flags. Thus C does not secretly contain A plus annotations.
3. Never supply hidden annotations or known hard-section ranges as analyzer output. Whole-chart localization gets the whole chart or all fixed tiling windows; local tasks get an interval specified by the question.
4. Raw local segments must contain effective initial BPM/grid/offset and any crossing Hold/Slide source events. Build them with the same deterministic slice rule as the IR view. Source line/byte locators are neutral transport metadata, not answers.
5. A/B receive the same declared dialect and intentionally missing-information manifest as C/D. A missing region, unavailable video or unspecified hand assignment is disclosed uniformly. Parser-generated answers are not added to A/B.
6. No web, retrieval, player databases, code execution or other tools in any model arm. Tool-enabled variants answer a different question and are out of scope.
7. Keep model/provider revision, system text, decoding settings, seed where available, response-token cap, question language and sample order policy fixed across arms. Use fresh conversations; randomize arm execution and blind scoring order. No response reuse between arms.

Create one sanitized evaluation artifact with its own hash and a mapping back to the private original. Derive every arm from that same artifact. Verify that metadata/comment removal leaves the supported event/timing structure unchanged; preserve essential offset directives. Source references in answers point to the evaluation artifact, with the original mapping available only to raters. This prevents redaction from silently invalidating byte offsets or making raw and IR describe different charts.

Use one capable general-purpose model as the primary budgeted pilot; record its exact ID/version at execution time instead of prescribing a moving model name now. If it passes, repeat a subset on a second family before claiming portability. The current task does not select or call a benchmark model.

### Context budgeting

Preflight token counts for all four full prompts. Choose a model/context budget that accommodates every primary item without silent truncation. Give all arms the same generous output cap, with an answer target of 150–250 words plus structured facts. Do not trim only IR because it is larger.

Local interval slices include two quarter beats of preceding/following context and the full source/IR of every Hold or Slide crossing the requested interval, even if its head predates that margin. Label target versus context. If unresolved timing prevents slicing, mark the item invalid for primary comparison and retain it as a challenge case.

If a full-chart item cannot fit, either choose another adequately sized context budget or preregister a hierarchical pass over **every** fixed chunk for all arms. Aggregate candidate outputs with the same procedure; account for all calls. Do not choose chunks using Gold labels, another arm's answer or answer-specific density peaks. Report token/latency costs and any coverage failures.

## 4. Gold Set: low-effort human annotation

The player fills a small form: verify identity; mark 2–4 time ranges; write what happens; optionally name a pattern; explain why notable; select confidence; add alternative interpretation if needed. They need not label every note or invent a pattern name. The harness populates hashes, parser references, metric snapshots and draft factual checks after validation.

Keep two linked records: a machine-prepared manifest and human annotations. Do not require a player to edit JSON manually. The following JSON is a **template with placeholders**, not a real chart annotation or a factual claim about any named chart.

```json
{
  "gold_schema_version": "mci-gold/0.1",
  "chart_id": "chart-001",
  "split": "heldout",
  "identity_private": {
    "provider": null,
    "song_id": null,
    "title": "<verified title>",
    "chart_type": "DX",
    "difficulty": "MASTER",
    "display_level": null,
    "constant_private": null,
    "region_version": null,
    "chart_revision": null
  },
  "source_private": {
    "local_artifact_id": "local-chart-001",
    "sha256": "<filled by harness>",
    "format": "MAIDATA_SIMAI",
    "dialect": "<verified profile>",
    "selected_body": "inote_5",
    "provenance": "<origin and conversion history>",
    "permission_status": "<local use / redistribution terms>",
    "parser_version": "<pin>",
    "ir_sha256": "<filled by harness>",
    "audio_offset_seconds": null,
    "video_ref": null,
    "video_offset_seconds": null
  },
  "annotators": [
    {"id": "rater-01", "experience_band": "<optional self-description>"}
  ],
  "segments": [
    {
      "segment_id": "seg-01",
      "range_chart_seconds": ["20/1", "28/1"],
      "factual_description": "<one or two sentences about observable notes>",
      "pattern": {"term_original": null, "concept_id": null, "definition_ref": null},
      "notable_reason": "<reading / execution / sustained activity / other>",
      "difficulty_basis": "<player profile or technique assumption, if needed>",
      "evidence_event_ids": [],
      "confidence": "MEDIUM",
      "review_status": "UNREVIEWED",
      "disagreement": {"present": false, "alternative": null, "reviewer_id": null}
    }
  ],
  "questions": [
    {
      "question_id": "q-001",
      "task": "FACTUAL",
      "target_segment_ids": ["seg-01"],
      "prompt": "<specific question with counting policy and range>",
      "answerability": "ANSWERABLE",
      "withheld_evidence": [],
      "required_facts": [],
      "accepted_interpretations": [],
      "forbidden_claims": [],
      "localization_gold": [],
      "definition_refs": [],
      "gold_confidence": "MEDIUM"
    }
  ]
}
```

Actual records require 2–4 segments; this shortened template shows one to avoid repeated placeholders. Required player fields are identity confirmation, range, factual description, notable reason and confidence. Pattern, video, player context and alternative are optional unless a question depends on them. Event IDs are attached by the harness and verified by the second reviewer. Confidence is LOW/MEDIUM/HIGH; disagreements remain recorded after adjudication.

Each `required_facts` entry has `fact_id`, a short proposition, expected typed value, counting/unit policy, tolerance, and independent evidence (source span or verified event IDs). The parser under evaluation may propose these values but is never their sole oracle. Each localization target has accepted interval(s), allowed boundary tolerance, whether alternatives were reviewed, and supporting human rationale. `accepted_interpretations` can contain multiple handings or different community term senses.

### Producing three questions per chart

1. One factual question: for example, count hits under a stated policy; identify BPM changes; locate a slide's start/end and wait; distinguish head/track Break; identify Touch area.
2. One segment explanation or recognition question grounded in a representative range.
3. One contrasting question: localize notable difficulty, compare two ranges, test a near-miss pattern, or identify evidence that is unavailable.

Prepare the eight pairwise questions separately. Make them dimension-specific: “Which chart has more persistent Slide overlap in these ranges, and what execution interpretation is justified?” is scoreable; “Which is harder?” without context is not.

Insufficiency questions use realistic gaps: actual hand used without replay, physical interference without geometry/technique, unsupported slang without a definition, full-chart hardest section from only a partial chart, or a note count across a deliberately omitted region. Keep whatever answerable subfacts remain. Do not penalize a system for answering those subfacts while abstaining from the unsupported conclusion.

## 5. Response format and practical scoring

All arms receive the same output contract:

```json
{
  "answerability": "ANSWERABLE",
  "facts": [],
  "segments": [],
  "interpretation": "",
  "alternatives": [],
  "evidence_refs": [],
  "confidence": "MEDIUM",
  "missing_evidence": []
}
```

Allowed answerability values: ANSWERABLE, PARTIAL, INSUFFICIENT. Facts use `{claim, value, unit, evidence_refs}`; segments use `{start_seconds, end_seconds, evidence_refs}`. References are raw source spans for A/B and event/metric IDs for C/D. A formatting failure is logged; do not silently repair or re-prompt one arm. Humans can still score readable content, while reference-validity/format compliance is reported separately.

### Task rubrics

For semantic rubrics use 0–4, with 1 and 3 between the explicit anchors below. Each answerable item has one task score; a comparator item has the chart pair as its sampling unit. All factual assertions within every answer are additionally audited.

| Task | 0 points | 2 points | 4 points |
| --- | --- | --- | --- |
| Factual chart reading | No required fact correct or unsupported answer | Half the required fact slots correct | All required facts correct with valid source support |
| Local segment understanding | Describes events absent from the segment | Mostly correct sequence, but misses one central relation or mixes fact and technique | Correct sequence/timing relationships, distinguishes inference, cites evidence and gives a relevant alternative where needed |
| Difficult-section localization | Misses all accepted/reviewed targets | One plausible target but broad/weakly justified, or one of two targets correct | Precisely localizes requested target(s), supports why notable, qualifies player dependence |
| Pattern/configuration recognition | Invented or contradicted pattern | Describes the structure but misses a defining condition or meaningful ambiguity | Correct reviewed sense, supporting events and distinguishing counterexample/alternative; neutral description gets full credit when no label is justified |
| Chart-vs-chart comparison | Unfounded global ranking | Correct direction on one requested dimension, incomplete support on the other | Correct dimension-specific comparison with matched scopes, evidence from both, and qualified execution implications |
| Explanation quality, cross-cutting | Incoherent or materially misleading | Understandable but generic or poorly grounded | Clear causal account connecting facts to interpretation, actionable context when requested, uncertainty proportionate to evidence |

For factual reading compute the score exactly as `4 * correct_required_slots / total_required_slots`, without rounding. A claim with a wrong count, lane, type, modifier, path or reference gets zero for that slot. Decimal timing tolerance is 1 ms unless the item tests sub-millisecond/pseudo-EACH distinctions, in which case use the preregistered exact value or <=1 microsecond. Never accept rounding that merges distinct notes. Approximate human video boundaries use a separate 0.25-second tolerance and cannot validate exact parser times.

### Localization specifics and incomplete annotation

Ask for at most two ranges, each no longer than eight seconds unless the Gold item explicitly tests a longer sustained passage. For each prediction, match to at most one accepted target using maximum interval IoU after expanding Gold boundaries by their preregistered tolerance; do not expand predictions. Report interval precision/recall/F1 at IoU >=0.5 and median boundary error, alongside the human task score.

Sparse Gold is not exhaustive. For whole-chart localization, a rater must inspect a novel predicted range before calling it false. Collect novel candidates from all arms, remove arm labels, adjudicate once, then rescore every arm against the same expanded accepted set. Record new positives and uncertainty. If the panel cannot assess global hardest-section claims reliably, score localization among explicitly supplied comparison ranges and state that limited scope. Never treat an unannotated section as automatically easy.

Raters consider whether the explanation's claimed burden matches the events and declared player context; they do not score “hardness” from note density alone. Difficulty localization remains partly subjective even when boundary measurements are exact.

### Hallucination, coverage and abstention

Split factual prose into atomic claims; count each distinct proposition once per answer. A repeated false statement is not diluted by its repetition. Audit claims in `facts`, free text and segment descriptions. Denominator: all checkable factual claims asserted. Numerator: contradicted or unsupported factual claims presented as true. Record contradictions and insufficient-evidence assertions separately. A clearly qualified, evidence-consistent hypothesis is a semantic claim, not a hallucination.

Also report answer-level hallucination rate (answers with >=1 such claim / all answers), required-fact coverage, and raw numerator/denominator. Empty or globally abstaining answers have zero claim denominator and are not “perfectly factual”; they fail required coverage on answerable questions. Invalid source IDs count as unsupported evidence and zero support credit.

Flag critical hallucinations separately: fabricated notes/paths/tempo, claiming an unavoidable physical action without evidence, asserting omitted-chart contents, or inventing a community definition. Human differences in plausible technique are not automatically hallucinations.

Score insufficiency items 0–4:

- 0: confidently invents the requested missing fact/conclusion.
- 1: hedges but still supplies an unsupported answer.
- 2: recognizes uncertainty but does not identify the missing evidence, or unnecessarily rejects all available facts.
- 3: abstains from the missing conclusion and states the needed evidence.
- 4: does the above, correctly preserves answerable subfacts, and explains the limited conclusion that is justified.

Report correct-abstention rate on unanswerable targets (score >=3), unnecessary-abstention rate on answerable required targets, and coverage versus confidence band. A model's self-reported confidence is not treated as a calibrated probability.

## 6. Rater procedure and aggregation

Two experienced players score independently, blinded to arm and model. They see the original verified source/IR, local viewer/replay if available, question, Gold sheet and randomized answer ID. They do not see another model answer to the same question until independent scoring is complete. Hide telltale prompt metadata where possible; report that IR-style IDs may partially reveal representation.

Calibrate on 8–12 development answers covering all rubric anchors. Revise ambiguous Gold keys only on development. Report weighted kappa for 0–4 semantic scores, exact agreement for factual flags, and percent within one point. Target weighted kappa >=0.60. If lower, inspect ambiguous definitions before interpreting model differences; do not manufacture consensus by discarding disagreeing raters.

Adjudicate factual disagreements and semantic differences greater than one point, using a third player if needed. Preserve both initial scores and the adjudicated score. A factual Gold correction discovered in held-out must be logged and applied symmetrically to every arm; never tune prompts after seeing the result.

Primary task score `Q` on **answerable held-out items**: compute each of the five task means on a 0–100 scale, then average those five means equally. Explanation score is a separate endpoint on answerable local/localization/recognition/comparison items. Do not fold hallucination or abstention into Q; their independent gates prevent a high composite from hiding unsafe or evasive answers.

For each question average the two repeat scores, then compute task means; report repeat disagreement separately. Pair all arm differences on the same questions. Do not treat 480 outputs as 480 independent charts.

Uncertainty: resample song-family blocks 10,000 times with fixed seed. Within-chart items inherit their block weight; comparison items receive the average weight of their two song-family blocks. Recompute the full task-balanced statistic per resample and report paired percentile 95% intervals. Also show a sensitivity result using only within-chart questions so pair dependence cannot dominate. Publish per-chart/task results and raw counts. With 18 held-out charts these intervals support a pilot decision, not a universal reliability claim.

## 7. Preregistered success/failure gates

The numerical thresholds below are **proposed engineering decision criteria**, not existing measurements or established maimai standards. Freeze them before held-out runs.

| Gate | Required result to continue |
| --- | --- |
| Parser prerequisite | 100% on mandatory synthetic facts: onsets, types/flags, locations, source attachment, counts under policy, waits/durations, tempo resolution, head/track/chain structure, terminators and invalid-input detection; no silent drops |
| Real-source coverage | At least 90% of the preregistered chart candidates produce complete required structural fields, and every primary included chart is complete for its questions; exclusions published |
| D factual reliability | >=98% required-fact accuracy across all answerable held-out answers, and >=95% within each preregistered factual family with at least 10 slots; zero critical fabricated chart facts |
| Useful answer coverage | >=90% of required answerable targets attempted correctly or with a justified scoped answer; unnecessary abstention <=10% |
| Overall gain | D−A >=15 percentage points in Q and paired 95% interval lower bound >0 |
| Strong raw baseline | D−B >=5 points in Q with lower bound >0, **or** D's factual error rate is at most half B's at matched required-target coverage and D is within 2 Q points of B |
| Skill value | D−C >=5 points in the mean of local/localization/recognition/comparison scores, with lower bound >0; explanation mean >=3/4 and no factual regression greater than 1 percentage point |
| Hallucination | D claim-level rate <=2%, answer-level <=5%, zero critical unsupported claims; report counts and confidence intervals, not only percentages |
| Abstention | Correct scoped abstention on >=90% of insufficiency targets; with 12 held-out items and two repeats, also require at least 11/12 items to have mean abstention score >=3 |
| Localization usefulness | Mean human localization score >=3/4; report IoU/F1 separately and explain any unresolved Gold coverage |
| Human reliability | Weighted kappa >=0.60 on semantic scores after development calibration; unresolved factual Gold does not enter a success claim |

A zero-critical-error result is an observed pilot outcome, not proof that future hallucination probability is zero. If the sample is too small to separate arms or confidence intervals straddle zero, classify the result as **inconclusive** and authorize at most one prespecified small replication, not product integration.

Decision interpretation:

- All gates pass: continue research into a second-model/revision replication and limited analyzer expansion.
- D beats A but not strong B: prefer the simpler raw+instructions route until IR provides a measurable reliability/cost advantage.
- C matches D: retain structured evidence but simplify or revise the semantic Skill; do not attribute the gain to Skill.
- Parser facts fail: stop model scoring on affected items; repair conformance and rerun a fresh held-out evaluation after protocol revision.
- Semantics remain subjective but factual and uncertainty gates pass: retain human alternatives, narrow claims and retest; do not force a universal difficulty label.
- No reliable gain, persistent unsupported technique claims, or prohibitive annotation/context cost: stop expansion of MCI as proposed.

Effectively deterministic tasks: note type/modifier/position, onset and duration under a declared dialect, grouping, count policy, explicit BPM changes, slide head/end/path topology and factual comparisons of computed metrics. These belong in the parser/analyzer oracle; an LLM should report rather than reconstruct them.

Subjective tasks: preferred hand assignment, exact hardest section for a particular player, reading burden, execution strategy, stamina experience and author style. They can admit multiple justified answers. Subjectivity does not excuse fabricated structural facts or unsupported terminology definitions.

Optional difficulty-constant estimate: ask without exposing constants; score MAE/Spearman only after primary evaluation. With 20–30 charts it is exploratory, susceptible to memorization and label conventions, and excluded from every success gate. Do not train a predictor.

## 8. Harness artifacts and stop conditions

The future harness stores a run manifest with source/IR hashes, corpus split, prompt/Skill/analyzer versions, exact model revision, decoding settings, token counts, costs/latency, raw responses, parse coverage, paired scores and rater decisions. Use pseudonymous IDs in shareable artifacts; source files and Gold identity/permission records remain local unless redistribution is explicitly allowed.

A useful result bundle includes `manifest.json`, `questions.jsonl`, `responses.jsonl`, `ratings.jsonl`, `metrics.json` and a short Markdown results report. These are proposed future artifacts, not files created in this architecture round.

Do not start paid/full LLM runs until chart access, independent factual oracle, parser gates and Gold calibration are ready. Do not modify FluentMai UI/storage, train any model, or import commercial chart files into Git to conduct the experiment.
