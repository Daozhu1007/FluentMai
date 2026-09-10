"""Strict forward scanner and transactional note normalization. No upstream imports."""
from fractions import Fraction
import hashlib
import re

from .. import PARSER_VERSION, PROFILE
from ..ir import (Chart, Coverage, Diagnostic, EventGroup, Location, NoteEvent,
                  SlideEvent, SlideSegment, SourceConstruct, TimingEvent)
from .duration import SyntaxIssue, decimal, duration, slide_timing, spec
from .source import Source

MAX_BYTES = 1024 * 1024
MAX_OBJECTS = 20000
MAX_RATIONAL_BITS = 2048
TRIVIA = ' \t\r\n'


class InputError(ValueError):
    """Input cannot safely become a decoded Chart; original bytes are rejected."""
    def __init__(self, code, message, data):
        super().__init__(message)
        self.code = code
        self.sha256 = hashlib.sha256(data).hexdigest()
        self.byte_length = len(data)

    def to_dict(self):
        return {'chart': None, 'coverage': [{'capability': 'structure', 'status': 'INVALID'}],
                'diagnostics': [{'code': self.code, 'severity': 'ERROR', 'effect': 'REJECTED',
                                 'message': str(self)}],
                'source_rejection': {'sha256': self.sha256, 'byte_start': 0,
                                     'byte_end': self.byte_length, 'disposition': 'REJECTED'}}


def parse(data: bytes, *, profile: str, source_format: str = 'bare',
          selected_body: str = 'body', offset: str | None = None) -> Chart:
    if profile != PROFILE:
        raise InputError('PROFILE_REQUIRED', 'Declare the exact supported profile: ' + PROFILE, data)
    if len(data) > MAX_BYTES:
        raise InputError('RESOURCE_LIMIT', 'Input exceeds 1 MiB; nothing was truncated', data)
    try:
        source = Source(data)
    except UnicodeDecodeError as error:
        raise InputError('INVALID_ENCODING', 'Input must be valid UTF-8', data) from error
    parser = Parser(source, source_format, selected_body)
    return parser.run(offset)


class Parser:
    def __init__(self, source, source_format, selected):
        self.source = source
        self.chart = Chart('', source.sha, source_format, selected, PARSER_VERSION, PROFILE)
        self.chart.chart_id = self.ident('chart', source.span(0, len(source.text)))
        self.t = Fraction(0)
        self.q = Fraction(0)
        self.bpm = None
        self.grid = None
        self.grid_seconds = False
        self.initialized = False
        self.missing_reported = False
        self.ended = False
        self.restrictions = {'structure': [], 'timing': [], 'audio_alignment': []}
        self.claims = []

    def ident(self, kind, span, expansion=0):
        seed = '|'.join(map(str, (self.source.sha, self.chart.selected_body, PROFILE,
                                 PARSER_VERSION, span.byte_start, span.byte_end, kind, expansion)))
        return kind + '-' + hashlib.sha256(seed.encode('utf-8')).hexdigest()[:24]

    def diagnose(self, code, message, span=None, *, status=None, timing=False,
                 severity=None, affected=None, event_ids=None):
        fields = affected or (['structure', 'timing'] if timing else ['structure'])
        severity = severity or ('ERROR' if status == 'INVALID' else 'WARNING')
        effect = 'REJECTED' if status == 'INVALID' else ('UNRESOLVED' if status else 'PRESERVED_ONLY')
        source = span or self.source.span(0, 0)
        did = self.ident('diagnostic', source, len(self.chart.diagnostics))
        self.chart.diagnostics.append(Diagnostic(did, code, severity, span, message, effect,
                                                 fields, event_ids or []))
        if status:
            for capability in fields:
                self.restrictions[capability].append((status, did))
        return did

    def claim(self, start, end, kind, disposition='NORMALIZED', ids=None, diagnostics=None):
        if end > start:
            self.claims.append((start, end, SourceConstruct(self.source.span(start, end), kind,
                                disposition, ids or [], diagnostics or [])))

    def issue(self, error, span):
        status = 'PARTIAL' if error.unsupported else 'INVALID'
        did = self.diagnose(error.code, str(error), span, status=status, timing=error.poison)
        if error.poison:
            self.t = self.q = None
            self.bpm = self.grid = None
        return did

    def prerequisites(self, span):
        if not self.initialized and not self.missing_reported:
            self.missing_reported = True
            self.t = self.q = None
            return self.diagnose('MISSING_INITIAL_TIMING', 'Explicit BPM followed by grid is required',
                                 span, status='INVALID', timing=True)
        return None

    def run(self, offset):
        start = 1 if self.source.text.startswith('\ufeff') else 0
        if start:
            self.claim(0, 1, 'UTF8_BOM')
        end = len(self.source.text)
        try:
            if self.chart.source_format == 'maidata':
                if offset is not None:
                    raise SyntaxIssue('OFFSET_CONFLICT', 'Bare offset argument cannot accompany maidata')
                start, end = self.container(start)
            elif self.chart.source_format == 'bare':
                if self.chart.selected_body != 'body':
                    raise SyntaxIssue('BODY_SELECTION', 'Bare input requires selected body "body"')
                if offset is not None:
                    self.chart.timeline.audio_offset_seconds = decimal(offset, signed=True, code='INVALID_OFFSET')
            else:
                raise SyntaxIssue('INVALID_FORMAT', 'Explicit input format must be bare or maidata')
        except SyntaxIssue as error:
            # Envelope validation is transactional: no arbitrary selected chart on failure.
            self.claims.clear()
            self.chart.metadata.clear()
            span = self.source.span(0, len(self.source.text))
            error.poison = True
            did = self.issue(error, span)
            self.restrictions['audio_alignment'].append(('INVALID', did))
            self.claim(0, len(self.source.text), 'CONTAINER', 'REJECTED', diagnostics=[did])
            return self.finish()
        if self.chart.timeline.audio_offset_seconds is None:
            self.diagnose('OFFSET_UNKNOWN', 'Audio offset is unknown; chart timing remains independent',
                          status='PARTIAL', severity='INFO', affected=['audio_alignment'])
        self.body(start, end)
        if not self.ended:
            self.diagnose('MISSING_END', 'Selected body requires explicit final E',
                          self.source.span(end, end), status='INVALID', timing=True)
        return self.finish()

    def container(self, start):
        text = self.source.text
        matches = list(re.finditer(r'(?m)^[ \t]*&([A-Za-z_][A-Za-z_0-9]*)=', text[start:]))
        if not matches or text[start:start + matches[0].start()].strip(TRIVIA):
            raise SyntaxIssue('INVALID_CONTAINER', 'Expected line-oriented &key=value records')
        selected = self.chart.selected_body
        if not re.fullmatch(r'inote_[1-7]', selected):
            raise SyntaxIssue('BODY_SELECTION', 'Select exactly inote_1 through inote_7')
        records = []
        seen = set()
        for i, match in enumerate(matches):
            key = match.group(1)
            if key in seen:
                raise SyntaxIssue('DUPLICATE_KEY', 'Duplicate container key: ' + key)
            seen.add(key)
            a, value_start = start + match.start(), start + match.end()
            b = start + matches[i + 1].start() if i + 1 < len(matches) else len(text)
            if re.fullmatch(r'inote_.*', key) and not re.fullmatch(r'inote_[1-7]', key):
                raise SyntaxIssue('BODY_SELECTION', 'Invalid inote key')
            value = text[value_start:b]
            offset_value = None
            if key == 'first' or re.fullmatch(r'first_[1-7]', key):
                offset_value = decimal(value.strip(TRIVIA), signed=True, code='INVALID_OFFSET')
            records.append((key, a, b, value_start, value, offset_value))
        if selected not in seen:
            raise SyntaxIssue('BODY_SELECTION', 'Selected body is absent')
        winning_key = 'first_' + selected[-1] if 'first_' + selected[-1] in seen else 'first'
        for key, a, b, value_start, value, offset_value in records:
            span = self.source.span(a, b)
            if key == selected:
                self.claim(a, value_start, 'BODY_HEADER')
                selected_range = (value_start, b)
                continue
            self.chart.metadata.append({'key': key, 'value': value, 'source': span})
            if offset_value is not None:
                if key == winning_key:
                    self.chart.timeline.audio_offset_seconds = offset_value
                    self.chart.timeline.offset_source = span
                self.claim(a, b, 'OFFSET_RECORD')
            else:
                code = 'UNSELECTED_BODY' if key.startswith('inote_') else 'UNINTERPRETED_METADATA'
                did = self.diagnose(code, 'Record retained without selected-body interpretation', span,
                                    severity='INFO')
                self.claim(a, b, 'CONTAINER_RECORD', 'PRESERVED_WITH_DIAGNOSTIC', diagnostics=[did])
        return selected_range

    def timing_event(self, kind, span, value=None):
        event = TimingEvent(self.ident('timing', span), kind, span, span.byte_start,
                            self.q, self.t, value)
        self.chart.timeline.events.append(event)
        return event.id

    def directive(self, raw, span, cell_has_notes):
        if cell_has_notes:
            raise SyntaxIssue('MISPLACED_DIRECTIVE', 'Timing directives must precede cell notes', poison=True)
        try:
            value_text = raw[1:-1]
            if raw.startswith('('):
                value = decimal(value_text, positive=True, code='INVALID_TIMING')
                self.bpm = value
                return self.timing_event('BPM', span, value)
            absolute = value_text.startswith('#')
            value = decimal(value_text[1:] if absolute else value_text,
                            positive=True, code='INVALID_TIMING')
            if self.bpm is None and not self.initialized:
                self.prerequisites(span)
            else:
                self.initialized = True
            self.grid, self.grid_seconds = value, absolute
            return self.timing_event('GRID_SECONDS' if absolute else 'GRID_DIVISION', span, value)
        except SyntaxIssue as error:
            error.poison = True
            # Alphabetic extensions are preserved, not misrepresented as malformed decimals.
            nonfinite = raw[1:-1].lower().lstrip('+-') in ('nan', 'inf', 'infinity')
            if (re.search(r'[A-Za-z]', raw) and not nonfinite
                    and not re.match(r'[({][+-]?[0-9.]', raw) and error.code != 'RESOURCE_LIMIT'):
                error.code = 'UNSUPPORTED_TIMING'
                error.unsupported = True
            raise

    def body(self, start, end):
        positions = [i for i in range(start, end) if self.source.text[i] not in TRIVIA]
        compact = ''.join(self.source.text[i] for i in positions)
        i = 0
        cell_has_notes = False
        last_atom = False
        slash_pending = False
        while i < len(compact):
            a = positions[i]
            char = compact[i]
            if char in '({':
                closing = ')' if char == '(' else '}'
                j = compact.find(closing, i + 1)
                if j < 0 or any(c in compact[i + 1:j] for c in '(){}[],/'):
                    error = SyntaxIssue('MALFORMED_DELIMITER', 'Malformed timing delimiter', poison=True)
                    did = self.issue(error, self.source.span(a, end))
                    self.claim(a, end, 'TIMING', 'REJECTED', diagnostics=[did])
                    break
                b = positions[j] + 1
                span = self.source.span(a, b)
                try:
                    tid = self.directive(compact[i:j + 1], span, cell_has_notes)
                    self.claim(a, b, 'TIMING', ids=[tid])
                except SyntaxIssue as error:
                    did = self.issue(error, span)
                    self.claim(a, b, 'TIMING', 'PRESERVED_WITH_DIAGNOSTIC' if error.unsupported else 'REJECTED',
                               diagnostics=[did])
                i = j + 1
                continue
            if char in ',/':
                span = self.source.span(a, a + 1)
                diagnostics = []
                if (char == '/' and not last_atom) or (char == ',' and slash_pending):
                    diagnostics.append(self.diagnose('EMPTY_MEMBER', 'Empty simultaneous-note member',
                                                     span, status='INVALID'))
                if char == ',':
                    missing = self.prerequisites(span)
                    if missing:
                        diagnostics.append(missing)
                    if self.t is not None:
                        if self.bpm is None or self.grid is None:
                            self.t = self.q = None
                        else:
                            step = self.grid if self.grid_seconds else 240 / (self.bpm * self.grid)
                            self.t += step
                            self.q += step * self.bpm / 60
                            if any(max(v.numerator.bit_length(), v.denominator.bit_length()) > MAX_RATIONAL_BITS
                                   for v in (self.t, self.q)):
                                raise InputError('RESOURCE_LIMIT', 'Accumulated rational exceeds 2048 bits',
                                                 self.source.data)
                    cell_has_notes = False
                    slash_pending = False
                else:
                    slash_pending = True
                self.claim(a, a + 1, 'COMMA' if char == ',' else 'SIMULTANEOUS_SEPARATOR',
                           'REJECTED' if diagnostics else 'NORMALIZED', diagnostics=diagnostics)
                last_atom = False
                i += 1
                continue
            # Consume an entire atom before normalization; brackets protect inner syntax.
            j = i
            depth = 0
            malformed = False
            while j < len(compact):
                c = compact[j]
                if depth == 0 and c in ',/({':
                    break
                if c == '[':
                    if depth:
                        malformed = True
                    depth += 1
                elif c == ']':
                    depth -= 1
                    if depth < 0:
                        malformed = True
                elif depth and c in '(){},/':
                    malformed = True
                j += 1
            b = positions[j - 1] + 1
            span = self.source.span(a, b)
            raw = compact[i:j]
            if depth or malformed:
                did = self.issue(SyntaxIssue('MALFORMED_DELIMITER', 'Unbalanced or nested duration delimiter',
                                             poison=True), span)
                self.claim(a, b, 'ATOM', 'REJECTED', diagnostics=[did])
                i = j
                continue
            missing = self.prerequisites(span)
            if raw == 'E':
                if cell_has_notes or slash_pending:
                    did = self.diagnose('INVALID_END', 'END must occupy its own cell', span, status='INVALID')
                    self.claim(a, b, 'END', 'REJECTED', diagnostics=[did])
                else:
                    eid = self.timing_event('END', span)
                    self.chart.timeline.notation_end_seconds = self.t
                    self.claim(a, b, 'END', ids=[eid], diagnostics=[missing] if missing else [])
                    self.ended = True
                if j < len(compact):
                    suffix_a = positions[j]
                    suffix_span = self.source.span(suffix_a, end)
                    did = self.diagnose('CONTENT_AFTER_END', 'Content after END is rejected',
                                        suffix_span, status='INVALID')
                    self.claim(suffix_a, end, 'AFTER_END', 'REJECTED', diagnostics=[did])
                break
            try:
                if last_atom:
                    raise SyntaxIssue('MISSING_SEPARATOR', 'Expected slash or comma between notes')
                notes, slides = self.atom(raw, span)
                if len(self.chart.notes) + len(self.chart.slides) + len(notes) + len(slides) > MAX_OBJECTS:
                    raise InputError('RESOURCE_LIMIT', 'Note/track expansion exceeds 20000 objects', self.source.data)
                self.chart.notes.extend(notes)
                self.chart.slides.extend(slides)
                self.claim(a, b, 'NOTE_ATOM', ids=[x.id for x in notes + slides],
                           diagnostics=[missing] if missing else [])
            except SyntaxIssue as error:
                did = self.issue(error, span)
                self.claim(a, b, 'NOTE_ATOM', 'PRESERVED_WITH_DIAGNOSTIC' if error.unsupported else 'REJECTED',
                           diagnostics=[did])
            last_atom = cell_has_notes = True
            slash_pending = False
            i = j
        if slash_pending:
            self.diagnose('EMPTY_MEMBER', 'Trailing slash has no member', self.source.span(end, end),
                          status='INVALID')

    def note(self, location, flags, span, expansion=0, hold_duration=None, head=False):
        touch = location.kind == 'TOUCH_SENSOR'
        kind = ('TOUCH_HOLD' if touch else 'HOLD') if hold_duration else ('TOUCH' if touch else 'TAP')
        length = hold_duration.resolved_seconds if hold_duration else Fraction(0)
        end = self.t + length if self.t is not None and length is not None else None
        return NoteEvent(self.ident('note', span, expansion), kind, span, span.byte_start, expansion,
                         location, self.q, self.t, hold_duration, end, 'b' in flags, 'x' in flags,
                         'f' in flags, 'STAR' if head else 'NORMAL', 'SLIDE_HEAD' if head else 'ORDINARY')

    def atom(self, raw, span):
        if len(raw) > 4096:
            raise SyntaxIssue('RESOURCE_LIMIT', 'Atom exceeds 4096 characters', poison=True)
        if '`' in raw:
            raise SyntaxIssue('UNSUPPORTED_PSEUDO_EACH', 'Pseudo-EACH timing is outside this profile',
                              unsupported=True, poison=True)
        if re.fullmatch(r'[0-9]+', raw):
            if any(c not in '12345678' for c in raw):
                raise SyntaxIssue('INVALID_LOCATION', 'Button lanes must be 1 through 8')
            return [self.note(Location('BUTTON', int(c)), '', span, i) for i, c in enumerate(raw)], []
        match = re.match(r'([A-Z][0-9]*|[0-9])', raw)
        if not match:
            raise SyntaxIssue('UNKNOWN_SYNTAX', 'Unrecognized source construct', unsupported=True, poison=True)
        token = match.group()
        if token[0].isalpha():
            if token in ('C', 'C1', 'C2'):
                location = Location('TOUCH_SENSOR', None, 'C')
            elif re.fullmatch(r'[ABDE][1-8]', token):
                location = Location('TOUCH_SENSOR', int(token[1]), token[0])
            elif re.fullmatch(r'[A-Z][0-9]*', token) and (len(token) > 1 or token in 'ABCDE'):
                raise SyntaxIssue('INVALID_LOCATION', 'Invalid touch sensor')
            else:
                raise SyntaxIssue('UNKNOWN_SYNTAX', 'Unrecognized source construct', unsupported=True, poison=True)
        else:
            if token not in '12345678':
                raise SyntaxIssue('INVALID_LOCATION', 'Button lanes must be 1 through 8')
            location = Location('BUTTON', int(token))
        rest = raw[len(token):]
        flags_match = re.match(r'[bhxf]*', rest)
        flags = flags_match.group()
        rest = rest[len(flags):]
        if len(flags) != len(set(flags)):
            raise SyntaxIssue('INVALID_MODIFIER', 'Modifier may appear only once')
        touch = location.kind == 'TOUCH_SENSOR'
        if (touch and any(f in flags for f in 'bx')) or (not touch and 'f' in flags):
            raise SyntaxIssue('INVALID_MODIFIER', 'Modifier does not apply to this location/type')
        if not rest or rest.startswith('['):
            if 'h' in flags:
                if not rest:
                    hold_duration = spec(span, Fraction(1, 320), self.bpm, implicit=True)
                elif re.fullmatch(r'\[[^\[\]]*\]', rest):
                    hold_duration = duration(rest[1:-1], span, self.bpm)
                else:
                    raise SyntaxIssue('INVALID_DURATION', 'Malformed Hold duration')
                return [self.note(location, flags, span, hold_duration=hold_duration)], []
            if rest:
                raise SyntaxIssue('INVALID_DURATION', 'Only Hold/Slide may have duration brackets')
            return [self.note(location, flags, span)], []
        if any(c in rest for c in '? !$@<>vVpqszw'.replace(' ', '')):
            raise SyntaxIssue('UNSUPPORTED_NOTE', 'Path, chain or visual syntax is not admitted', unsupported=True)
        if rest[0] not in '-^':
            raise SyntaxIssue('UNKNOWN_SYNTAX', 'Unrecognized suffix', unsupported=True, poison=True)
        if touch or 'h' in flags:
            raise SyntaxIssue('INVALID_MODIFIER', 'Ordinary Slide requires a button Tap head')
        tracks = rest.split('*')
        head = self.note(location, flags, span, head=True)
        slides = []
        for i, track in enumerate(tracks):
            pattern = re.fullmatch(r'([-^])([0-9])\[([^\[\]]*)\](b?)', track)
            if not pattern:
                if re.fullmatch(r'[-^][0-9]\[[^\[\]]*\][bxfh]+', track):
                    raise SyntaxIssue('INVALID_MODIFIER', 'Only a single track Break suffix is admitted')
                if '[' in track and re.fullmatch(r'(?:[-^][0-9](?:\[[^\[\]]*\])?){2,}b?', track):
                    raise SyntaxIssue('UNSUPPORTED_NOTE', 'Connected chains are not admitted', unsupported=True)
                raise SyntaxIssue('INVALID_DURATION', 'Each track requires shape, endpoint and duration')
            shape, endpoint, timing, modifier = pattern.groups()
            lane = int(endpoint)
            delta = (lane - location.lane) % 8
            if not 1 <= lane <= 8 or (shape == '-' and delta not in (2, 3, 4, 5, 6)) or (
                    shape == '^' and delta not in (1, 2, 3, 5, 6, 7)):
                raise SyntaxIssue('INVALID_ENDPOINT', 'Endpoint is outside the admitted shape/lane pairs')
            wait, length = slide_timing(timing, span, self.bpm)
            movement = self.t + wait.resolved_seconds if self.t is not None and wait.resolved_seconds is not None else None
            end = movement + length.resolved_seconds if movement is not None and length.resolved_seconds is not None else None
            sid = self.ident('slide', span, i)
            direction = 'NOT_APPLICABLE' if shape == '-' else ('CW' if delta <= 3 else 'CCW')
            segment = SlideSegment(self.ident('segment', span, i), span, 0, shape,
                                   'STRAIGHT' if shape == '-' else 'ARC', direction,
                                   location, Location('BUTTON', lane), length, movement, end)
            slides.append(SlideEvent(sid, span, span.byte_start, i, head.id, self.q, self.t,
                                     location, wait, movement, length, end, [segment], modifier == 'b',
                                     self.ident('shared-head', span) if len(tracks) > 1 else None))
            head.slide_ids.append(sid)
        return [head], slides

    def groups(self, events, basis, attr):
        by_time = {}
        for event in events:
            time = getattr(event, attr)
            if time is not None:
                by_time.setdefault(time, []).append(event)
        groups = []
        for time, members in sorted(by_time.items()):
            gid = self.ident('group', members[0].source, basis + ':' + str(time))
            groups.append(EventGroup(gid, basis, time, [x.id for x in members], len(members)))
            for event in members:
                if basis == 'HIT_ONSET':
                    event.onset_group_id = gid
                else:
                    event.slide_start_group_id = gid
        return groups

    def finish(self):
        def ordering(event, attr):
            value = getattr(event, attr)
            return (value is None, value if value is not None else Fraction(0),
                    event.source_ordinal, event.expansion_ordinal)
        self.chart.notes.sort(key=lambda n: ordering(n, 'onset_seconds'))
        self.chart.slides.sort(key=lambda s: ordering(s, 'reference_onset_seconds'))
        timeline = self.chart.timeline
        timeline.onset_groups = self.groups(self.chart.notes, 'HIT_ONSET', 'onset_seconds')
        timeline.slide_start_groups = self.groups(self.chart.slides, 'SLIDE_MOVEMENT_START', 'movement_start_seconds')
        all_events = self.chart.notes + self.chart.slides
        incomplete = bool(self.restrictions['structure'])
        if all_events and not incomplete and all(x.end_seconds is not None for x in all_events):
            timeline.last_event_end_seconds = max(x.end_seconds for x in all_events)
        for event in all_events:
            if (timeline.notation_end_seconds is not None and event.end_seconds is not None
                    and event.end_seconds > timeline.notation_end_seconds):
                self.diagnose('TAIL_AFTER_END', 'Event tail exceeds explicit chart END; tail retained',
                              event.source, event_ids=[event.id])
        # Fill only trivia gaps. Any accidental nontrivia gap becomes explicit failure.
        cursor = 0
        for a, b, entry in sorted(self.claims, key=lambda row: row[0]):
            if a < cursor:
                raise AssertionError('Overlapping source claims')
            if a > cursor:
                self.gap(cursor, a)
            self.chart.source_constructs.append(entry)
            cursor = b
        if cursor < len(self.source.text):
            self.gap(cursor, len(self.source.text))
        for capability, restrictions in self.restrictions.items():
            status = 'INVALID' if any(s == 'INVALID' for s, _ in restrictions) else ('PARTIAL' if restrictions else 'COMPLETE')
            self.chart.coverage.append(Coverage(capability, status, [did for _, did in restrictions]))
        return self.chart

    def gap(self, a, b):
        span = self.source.span(a, b)
        if span.raw_text.strip(TRIVIA):
            did = self.diagnose('UNACCOUNTED_SOURCE', 'Nontrivia source has no normalized construct', span,
                                status='INVALID', timing=True)
            entry = SourceConstruct(span, 'UNACCOUNTED', 'REJECTED', [], [did])
        else:
            entry = SourceConstruct(span, 'TRIVIA', 'NORMALIZED', [], [])
        self.chart.source_constructs.append(entry)
