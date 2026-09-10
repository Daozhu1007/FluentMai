"""Minimal exact structural IR; intentionally smaller than the long-term proposal."""
from dataclasses import asdict, dataclass, field
from fractions import Fraction
import json
from typing import Any


@dataclass
class SourceSpan:
    artifact_id: str
    byte_start: int
    byte_end: int
    line: int
    column: int
    raw_text: str


@dataclass
class Location:
    kind: str
    lane: int | None
    area: str | None = None


@dataclass
class DurationSpec:
    source: SourceSpan
    mode: str
    amount: Fraction
    bpm_basis: str
    explicit_bpm: Fraction | None
    is_implicit: bool
    effective_bpm: Fraction | None
    resolved_seconds: Fraction | None
    resolution_status: str


@dataclass
class TimingEvent:
    id: str
    kind: str
    source: SourceSpan
    source_ordinal: int
    position_quarters: Fraction | None
    time_seconds: Fraction | None
    value: Fraction | None


@dataclass
class NoteEvent:
    id: str
    kind: str
    source: SourceSpan
    source_ordinal: int
    expansion_ordinal: int
    location: Location
    onset_quarters: Fraction | None
    onset_seconds: Fraction | None
    duration: DurationSpec | None
    end_seconds: Fraction | None
    is_break: bool
    is_ex: bool
    firework: bool
    head_visual: str
    role: str
    slide_ids: list[str] = field(default_factory=list)
    onset_group_id: str | None = None


@dataclass
class SlideSegment:
    id: str
    source: SourceSpan
    index: int
    shape_token: str
    shape_family: str
    direction: str
    start_location: Location
    end_selector: Location
    duration: DurationSpec
    start_seconds: Fraction | None
    end_seconds: Fraction | None
    waypoints: list[Location] = field(default_factory=list)
    geometry_status: str = 'UNAVAILABLE'


@dataclass
class SlideEvent:
    id: str
    source: SourceSpan
    source_ordinal: int
    expansion_ordinal: int
    head_note_id: str
    reference_onset_quarters: Fraction | None
    reference_onset_seconds: Fraction | None
    start_location: Location
    wait: DurationSpec
    movement_start_seconds: Fraction | None
    duration: DurationSpec
    end_seconds: Fraction | None
    segments: list[SlideSegment]
    is_break: bool
    shared_head_group_id: str | None
    path_resolution: str = 'SYMBOLIC'
    slide_start_group_id: str | None = None


@dataclass
class Diagnostic:
    id: str
    code: str
    severity: str
    source: SourceSpan | None
    message: str
    effect: str
    affected_fields: list[str]
    affected_event_ids: list[str] = field(default_factory=list)
    scope_seconds: list[Fraction] | None = None


@dataclass
class Coverage:
    capability: str
    status: str
    diagnostic_ids: list[str]
    scope_seconds: list[Fraction] | None = None


@dataclass
class SourceConstruct:
    source: SourceSpan
    kind: str
    disposition: str
    normalized_ids: list[str]
    diagnostic_ids: list[str]


@dataclass
class EventGroup:
    id: str
    basis: str
    time_seconds: Fraction
    member_ids: list[str]
    size: int


@dataclass
class Timeline:
    audio_offset_seconds: Fraction | None = None
    offset_source: SourceSpan | None = None
    origin: str = 'FIRST_NOTATION_POSITION'
    events: list[TimingEvent] = field(default_factory=list)
    onset_groups: list[EventGroup] = field(default_factory=list)
    slide_start_groups: list[EventGroup] = field(default_factory=list)
    notation_end_seconds: Fraction | None = None
    last_event_end_seconds: Fraction | None = None


@dataclass
class Chart:
    chart_id: str
    source_sha256: str
    source_format: str
    selected_body: str
    parser_version: str
    source_dialect: str
    schema_version: str = 'mci-ir-spike/0.1'
    parser_name: str = 'mci-strict-python'
    source_encoding: str = 'UTF-8'
    metadata: list[dict[str, Any]] = field(default_factory=list)
    timeline: Timeline = field(default_factory=Timeline)
    notes: list[NoteEvent] = field(default_factory=list)
    slides: list[SlideEvent] = field(default_factory=list)
    diagnostics: list[Diagnostic] = field(default_factory=list)
    coverage: list[Coverage] = field(default_factory=list)
    source_constructs: list[SourceConstruct] = field(default_factory=list)

    @property
    def status(self):
        return next(c.status for c in self.coverage if c.capability == 'structure')

    def to_dict(self):
        return _json_value(asdict(self))

    def to_json(self):
        return json.dumps(self.to_dict(), ensure_ascii=False, sort_keys=True, indent=2) + '\n'


def _json_value(value):
    if isinstance(value, Fraction):
        return f'{value.numerator}/{value.denominator}'
    if isinstance(value, dict):
        return {k: _json_value(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_json_value(v) for v in value]
    return value


def slice_interval(chart: Chart, start: Fraction, end: Fraction):
    """References to full events; no clipping of crossing sustains or silent loss."""
    if chart.status != 'COMPLETE':
        raise ValueError('Interval slicing requires COMPLETE structural coverage')
    if start >= end:
        raise ValueError('Interval must have start < end')

    def overlaps(a, b):
        if a is None or b is None:
            raise ValueError('Interval timing is unknown')
        return start <= a < end if a == b else a < end and b > start

    notes = [n.id for n in chart.notes if overlaps(n.onset_seconds, n.end_seconds)]
    slides = [s for s in chart.slides if overlaps(s.movement_start_seconds, s.end_seconds)]
    heads = list(dict.fromkeys(s.head_note_id for s in slides if s.head_note_id not in notes))
    return {'note_ids': notes, 'slide_ids': [s.id for s in slides], 'context_head_ids': heads}
