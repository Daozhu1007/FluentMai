# MCI-0.1 offline structural spike

Python 3.10+; standard library only. Run from the repository root. No install,
network service, product imports or application dependencies are needed.
The exact admission contract is [mci-simai-0.1](PROFILE.md).

```powershell
python -m research.mci0 --body '(120){4}1/5,1-4[4:1],,E' --profile mci-simai-0.1 --offset 0

python -m research.mci0 --path 'D:\private\maidata.txt' --format maidata --selected inote_5 --profile mci-simai-0.1 --output 'D:\private\chart-ir.json'

python -m research.mci0 --path research/mci0/private/synthetic.txt --format bare --profile mci-simai-0.1

python -m research.mci0.tests --report research/mci0/reports/conformance.json
```

The path examples are placeholders; supply an existing local input. The CLI writes
UTF-8 JSON to stdout or `--output`. Coverage and diagnostics are inside that JSON.
Exit codes: 0 COMPLETE structure, 2 PARTIAL structure, 3 INVALID input, 4 local I/O
failure. Missing/invalid CLI arguments use argparse's exit code 2 with no IR.
An omitted audio offset leaves `audio_alignment=PARTIAL` even when structure is
COMPLETE. `--offset` applies only to bare input; container precedence is explicit.

`selected_body` is a source slot, not a FluentMai difficulty index or chart type.
No title matching, catalog association or source acquisition is performed.

## Minimal IR boundary

- `Chart`: source hash, input identity, versions, ordered raw container metadata,
  timeline, notes, slides, diagnostics, coverage and byte-coverage ledger.
- `Timeline` / `TimingEvent`: exact chart seconds and quarter coordinates; BPM,
  numeric/absolute grid and END directives. Empty commas retain source-ledger
  entries. Offset is independent of chart time. No inferred musical meter.
- `NoteEvent`: TAP, HOLD, TOUCH or TOUCH_HOLD; typed location, flags, source order,
  exact onset/release, frozen/seconds duration specification and Slide-head links.
- `SlideEvent` / `SlideSegment`: distinct head identity, start/endpoint, symbolic
  family/direction, relative wait, movement start, duration and end. Shared heads
  are explicit; one segment per admitted track; geometry is unavailable.
- `Diagnostic` / `Coverage`: structured reasons and COMPLETE/PARTIAL/INVALID
  restrictions. `structure` covers all requested normalized selected-body facts;
  `timing` covers the notation cursor and timing prerequisites of retained events;
  `audio_alignment` additionally requires a known effective offset. A COMPLETE
  timing entry never overrides PARTIAL structure for omitted note objects.

The smaller schema is deliberately named `mci-ir-spike/0.1`, not the complete
proposed `mci-ir/0.1`. It omits identity mapping, tempo-segment indexes, metric
pipelines, general chains, semantic annotations and all geometry. `DurationSpec`
source spans refer to the containing atom so implicit defaults and explicit
timing remain traceable; shared tracks use expansion ordinals within that atom.

All authoritative numbers are `fractions.Fraction`; JSON uses reduced `n/d`
strings. There are no rounded display times or approximate simultaneity checks in
the parser. Arrays preserve time/source/expansion order. IDs and JSON contain no
machine paths or runtime timestamps.

## Using the Python API

```python
from fractions import Fraction
from research.mci0.parser import parse
from research.mci0.ir import slice_interval

chart = parse(b'(120){4}1h[1:1]/2-5[2:1],E',
              profile='mci-simai-0.1', offset='0')
assert chart.status == 'COMPLETE'
view = slice_interval(chart, Fraction(3, 4), Fraction(5, 4))
```

The slice returns note IDs, moving Slide IDs and context head IDs, retaining the
full source events for crossing sustains. It rejects incomplete charts and empty
or reversed intervals. It is a small inspection primitive, not the future
benchmark's context-window/evidence projection implementation.

`parse` returns a Chart even for many malformed constructs, with INVALID coverage.
It raises `InputError` for unsupported profile, invalid UTF-8 and whole-input
resource limits; `InputError.to_dict()` provides a rejection envelope, source hash
and rejected byte range. Consumers must inspect coverage before using any counts
or slicing. Empty lists on invalid input are not a zero-note result.

## Optional upstream experiment

The upstream comparison is independent of the runtime and normal test suite. It
requires an already available local PySimaiParser snapshot whose files match the
[recorded pin and SHA-256 hashes](reports/upstream_pin.json). It never downloads
anything and refuses mismatched source. The original comparison output is checked
in as synthetic evidence.

```powershell
python research/mci0/tests/probe_upstream.py --upstream 'D:\external\PySimaiParser' --output research/mci0/reports/upstream_observations.json
```

The experiment tries the public upstream API and a terminal-E prefilter on 14
selected frozen fixtures. Its bounded checks are not a full second implementation
or a universal compatibility score. Runtime parser/IR code is independently
written; no upstream code is vendored or imported there.

Only [original fixtures](fixtures/README.md) and synthetic reports belong in Git.
Keep future real charts and their raw-containing IR outside Git; `private/` is
locally ignored. This phase has not parsed real charts or run a benchmark.
