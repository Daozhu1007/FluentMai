from dataclasses import fields, is_dataclass
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from .. import PROFILE
from ..ir import slice_interval
from ..parser import InputError, parse

BASE = Path(__file__).resolve().parents[1]
CASES = json.loads((BASE / 'fixtures/cases.json').read_text(encoding='utf-8'))


def parse_case(case):
    return parse(case.get('source', case.get('body')).encode('utf-8'), profile=PROFILE,
                 source_format=case.get('format', 'bare'), selected_body=case.get('selected', 'body'),
                 offset=case.get('offset', '0') if case.get('format', 'bare') == 'bare' else None)


def rational(value):
    return None if value is None else f'{value.numerator}/{value.denominator}'


def note_row(note):
    loc = note.location
    location = str(loc.lane) if loc.kind == 'BUTTON' else loc.area + (str(loc.lane) if loc.lane else '')
    return [note.kind, location, rational(note.onset_seconds), rational(note.end_seconds),
            ''.join(f for f, enabled in [('b', note.is_break), ('x', note.is_ex), ('f', note.firework)] if enabled),
            'H' if note.role == 'SLIDE_HEAD' else 'O']


def slide_row(slide, chart):
    seg = slide.segments[0]
    return [[n.id for n in chart.notes].index(slide.head_note_id), slide.start_location.lane,
            seg.end_selector.lane, seg.shape_family, seg.direction, rational(slide.wait.resolved_seconds),
            rational(slide.movement_start_seconds), rational(slide.duration.resolved_seconds),
            rational(slide.end_seconds), slide.is_break]


class Conformance(unittest.TestCase):
    def assert_integrity(self, chart, data):
        cursor = 0
        diagnostics = {d.id: d for d in chart.diagnostics}
        objects = chart.notes + chart.slides + chart.timeline.events
        ids = {o.id for o in objects}
        self.assertEqual(len(ids), len(objects))
        for record in chart.source_constructs:
            span = record.source
            self.assertEqual(span.byte_start, cursor)
            self.assertGreater(span.byte_end, span.byte_start)
            self.assertEqual(data[span.byte_start:span.byte_end].decode('utf-8'), span.raw_text)
            prefix = data[:span.byte_start].decode('utf-8')
            self.assertEqual(span.line, prefix.count('\n') + 1)
            self.assertEqual(span.column, len(prefix.rsplit('\n', 1)[-1]) + 1)
            self.assertEqual(span.artifact_id, hashlib.sha256(data).hexdigest())
            self.assertIn(record.disposition, ('NORMALIZED', 'PRESERVED_WITH_DIAGNOSTIC', 'REJECTED'))
            if record.disposition != 'NORMALIZED':
                self.assertTrue(record.diagnostic_ids)
            self.assertTrue(set(record.diagnostic_ids) <= diagnostics.keys())
            self.assertTrue(set(record.normalized_ids) <= ids)
            cursor = span.byte_end
        self.assertEqual(cursor, len(data))
        for obj in objects:
            span = obj.source
            self.assertEqual(data[span.byte_start:span.byte_end].decode('utf-8'), span.raw_text)
        for slide in chart.slides:
            self.assertIn(slide.head_note_id, ids)
            self.assertEqual(len(slide.segments), 1)
            seg = slide.segments[0]
            self.assertEqual(seg.start_location, slide.start_location)
            self.assertEqual(seg.start_seconds, slide.movement_start_seconds)
            self.assertEqual(seg.end_seconds, slide.end_seconds)
            self.assertEqual(seg.duration, slide.duration)
            self.assertEqual(seg.geometry_status, 'UNAVAILABLE')
            if slide.reference_onset_seconds is not None:
                self.assertEqual(slide.movement_start_seconds,
                                 slide.reference_onset_seconds + slide.wait.resolved_seconds)
                self.assertEqual(slide.end_seconds, slide.movement_start_seconds + slide.duration.resolved_seconds)
        for note in chart.notes:
            self.assertEqual(note.location.kind, 'TOUCH_SENSOR' if note.kind.startswith('TOUCH') else 'BUTTON')
            if note.role == 'SLIDE_HEAD':
                linked = [s for s in chart.slides if s.head_note_id == note.id]
                self.assertEqual(note.slide_ids, [s.id for s in linked])
                self.assertEqual(note.head_visual, 'STAR')
                if len(linked) > 1:
                    self.assertIsNotNone(linked[0].shared_head_group_id)
                    self.assertEqual(len({s.shared_head_group_id for s in linked}), 1)
                else:
                    self.assertIsNone(linked[0].shared_head_group_id)
            else:
                self.assertEqual(note.slide_ids, [])
            if note.duration and note.onset_seconds is not None:
                self.assertEqual(note.end_seconds, note.onset_seconds + note.duration.resolved_seconds)
        for groups, events, attr in [(chart.timeline.onset_groups, chart.notes, 'onset_seconds'),
                                      (chart.timeline.slide_start_groups, chart.slides, 'movement_start_seconds')]:
            by_id = {e.id: e for e in events}
            self.assertEqual(len({g.id for g in groups}), len(groups))
            grouped = [eid for group in groups for eid in group.member_ids]
            self.assertEqual(len(grouped), len(set(grouped)))
            self.assertEqual(set(grouped), {e.id for e in events if getattr(e, attr) is not None})
            for group in groups:
                self.assertEqual(group.size, len(group.member_ids))
                self.assertTrue(all(getattr(by_id[eid], attr) == group.time_seconds for eid in group.member_ids))

        def visit(value):
            self.assertNotIsInstance(value, float)
            if is_dataclass(value):
                for f in fields(value):
                    self.assertNotIn(f.name, {'hard', 'stamina', 'cross_hand', '换手', '交互', '抢手'})
                    visit(getattr(value, f.name))
            elif isinstance(value, (list, tuple)):
                for v in value:
                    visit(v)
            elif isinstance(value, dict):
                for v in value.values():
                    visit(v)
        visit(chart)

    def check_case(self, case):
        chart = parse_case(case)
        self.assertEqual(chart.status, case['status'])
        self.assertEqual(rational(chart.timeline.notation_end_seconds), case['end'])
        self.assertEqual([note_row(n) for n in chart.notes], case['notes'])
        self.assertEqual([slide_row(s, chart) for s in chart.slides], case['slides'])
        self.assertEqual(sorted({d.code for d in chart.diagnostics}), sorted(case['codes']))
        obj = chart.to_dict()
        for path, expected in case.get('facts', {}).items():
            current = obj
            for part in path.split('.'):
                current = current[int(part)] if isinstance(current, list) else current[part]
            self.assertEqual(current, expected, path)
        if 'slice' in case:
            expected = case['slice']
            result = slice_interval(chart, Fraction(expected['start']), Fraction(expected['end']))
            self.assertEqual(result['note_ids'], [chart.notes[i].id for i in expected['notes']])
            self.assertEqual(result['slide_ids'], [chart.slides[i].id for i in expected['slides']])
            self.assertEqual(result['context_head_ids'], [chart.notes[i].id for i in expected['context_heads']])
        if chart.status != 'COMPLETE':
            with self.assertRaises(ValueError):
                slice_interval(chart, Fraction(0), Fraction(1))
        self.assert_integrity(chart, case.get('source', case.get('body')).encode('utf-8'))
        self.assertEqual(chart.to_json(), parse_case(case).to_json())
        self.assertEqual(chart.to_json(), chart.to_json())


for fixture in CASES:
    def test(self, case=fixture):
        self.check_case(case)
    setattr(Conformance, 'test_' + fixture['id'], test)


class DefensiveChecks(Conformance):
    # Do not inherit fixture test methods a second time (see load_tests below).
    def test_structural_distinctions(self):
        a, b = parse_case(CASES[12]), parse_case(CASES[13])
        self.assertNotEqual(slide_row(a.slides[0], a), slide_row(b.slides[0], b))
        straight = parse(b'(120){4}1-3[4:1],,E', profile=PROFILE, offset='0')
        arc = parse(b'(120){4}1^3[4:1],,E', profile=PROFILE, offset='0')
        self.assertEqual(straight.slides[0].segments[0].end_selector, arc.slides[0].segments[0].end_selector)
        self.assertNotEqual(straight.slides[0].segments[0].shape_family, arc.slides[0].segments[0].shape_family)

    def test_explicit_waits_shared_head_groups(self):
        data = b'(120){4}1-4[0##0.5]*-5[0.25##0.5],,E'
        chart = parse(data, profile=PROFILE, offset='0')
        self.assertEqual(chart.status, 'COMPLETE')
        self.assertEqual([g.time_seconds for g in chart.timeline.slide_start_groups], [Fraction(0), Fraction(1, 4)])
        self.assert_integrity(chart, data)

    def test_same_cursor_bpm_order_and_whitespace(self):
        data = b'(1 20)(240){4}1 / 2, E'
        chart = parse(data, profile=PROFILE, offset='-0.25')
        self.assertEqual(chart.status, 'COMPLETE')
        self.assertEqual([e.value for e in chart.timeline.events[:2]], [Fraction(120), Fraction(240)])
        self.assertEqual(chart.timeline.notation_end_seconds, Fraction(1, 4))
        self.assert_integrity(chart, data)

    def test_slice_boundaries_and_waiting(self):
        chart = parse_case(CASES[20])
        at_release = slice_interval(chart, Fraction(2), Fraction(3))
        self.assertEqual(at_release['note_ids'], [])
        before_movement = slice_interval(chart, Fraction(1, 4), Fraction(1, 2))
        self.assertEqual(before_movement['slide_ids'], [])
        at_movement = slice_interval(chart, Fraction(1, 2), Fraction(3, 4))
        self.assertEqual(at_movement['slide_ids'], [chart.slides[0].id])
        with self.assertRaises(ValueError):
            slice_interval(chart, Fraction(1), Fraction(1))

    def test_transactional_shared_head_rejection(self):
        for body in ['1-4[4:1]*-9[4:1]', '1-4[4:1]*', '1-4[4:1]*-5[0:1]']:
            chart = parse(('(120){4}' + body + ',E').encode(), profile=PROFILE, offset='0')
            self.assertEqual(chart.status, 'INVALID')
            self.assertEqual(chart.notes, [])
            self.assertEqual(chart.slides, [])

    def test_poisoning_unknown_directive_and_comments(self):
        for body in ['(120){4}1,{HS:2}2,3,E', '(120){4}1,||comment\n2,3,E']:
            chart = parse(body.encode(), profile=PROFILE, offset='0')
            self.assertEqual(chart.status, 'PARTIAL')
            self.assertIsNone(chart.timeline.notation_end_seconds)
            self.assertIsNone(chart.notes[-1].onset_seconds)
            self.assertIsNone(chart.timeline.last_event_end_seconds)
            self.assert_integrity(chart, body.encode())

    def test_input_rejection(self):
        for data, profile, code in [(b'\xff', PROFILE, 'INVALID_ENCODING'),
                                     (b'E', 'simai', 'PROFILE_REQUIRED'),
                                     (b'x' * (1024 * 1024 + 1), PROFILE, 'RESOURCE_LIMIT')]:
            with self.assertRaises(InputError) as caught:
                parse(data, profile=profile)
            self.assertEqual(caught.exception.code, code)
            self.assertEqual(caught.exception.to_dict()['source_rejection']['byte_end'], len(data))

    def test_expansion_and_fraction_resource_bounds(self):
        with self.assertRaises(InputError) as caught:
            parse(('(120){4}' + ('1' * 4000 + ',') * 6 + 'E').encode(), profile=PROFILE)
        self.assertEqual(caught.exception.code, 'RESOURCE_LIMIT')
        # Pairwise coprime BPM denominators stress exact accumulation, not notation semantics.
        primes = [n for n in range(10000, 20000) if all(n % d for d in range(2, int(n ** .5) + 1))][:200]
        body = '(120){4}' + ''.join('(' + str(p) + '),' for p in primes) + 'E'
        with self.assertRaises(InputError) as caught:
            parse(body.encode(), profile=PROFILE)
        self.assertEqual(caught.exception.code, 'RESOURCE_LIMIT')

    def test_numeric_and_delimiter_rejections(self):
        for token in ['(NaN)', '(inf)', '(1e2)', '(-120)', '{-4}', '{0}', '{#0}']:
            data = ('(120){4}1,' + token + '2,E').encode()
            chart = parse(data, profile=PROFILE, offset='0')
            self.assertEqual(chart.status, 'INVALID', token)
            self.assertIsNone(chart.notes[-1].onset_seconds)
        for body in ['1h[4:[1]]', '1h[4:1]]', '1/E', 'E,', '1(240),E']:
            data = ('(120){4}' + body).encode()
            chart = parse(data, profile=PROFILE, offset='0')
            self.assertEqual(chart.status, 'INVALID', body)
            self.assert_integrity(chart, data)

    def test_container_validation_and_global_offset(self):
        chart = parse(b'&first=.25\n&inote_5=(120){4}E', profile=PROFILE,
                      source_format='maidata', selected_body='inote_5')
        self.assertEqual(chart.timeline.audio_offset_seconds, Fraction(1, 4))
        self.assertEqual(chart.status, 'COMPLETE')
        for source, selected, offset, code in [
            ('&first=bad\n&first_5=0\n&inote_5=(120){4}E', 'inote_5', None, 'INVALID_OFFSET'),
            ('&inote_5=(120){4}E', 'inote_5', '0', 'OFFSET_CONFLICT'),
            ('&inote_5=(120){4}E', '', None, 'BODY_SELECTION'),
            ('&inote_5=(120){4}E\n&inote_5=(120){4}1,E', 'inote_5', None, 'DUPLICATE_KEY')]:
            chart = parse(source.encode(), profile=PROFILE, source_format='maidata',
                          selected_body=selected, offset=offset)
            self.assertEqual(chart.status, 'INVALID')
            self.assertIn(code, {d.code for d in chart.diagnostics})
            self.assert_integrity(chart, source.encode())

    def test_malformed_corpus_bounded_process(self):
        script = """
import random
from research.mci0.parser import parse
random.seed(20260911)
alphabet='1239ABCExbhf-^*[]{}():,/#`?! .0'
for i in range(1200):
    raw=''.join(random.choice(alphabet) for _ in range(random.randrange(1,100)))
    chart=parse(('(120){4}'+raw+',E').encode(),profile='mci-simai-0.1',offset='0')
    assert chart.status in ('COMPLETE','PARTIAL','INVALID')
for raw in ['['*2000, '('*2000, '1-'*2000, '1h['+'9'*4000+':1]', '1'*5000]:
    assert parse(('(120){4}'+raw+',E').encode(),profile='mci-simai-0.1').status == 'INVALID'
"""
        subprocess.run([sys.executable, '-c', script], check=True, timeout=15, capture_output=True)

    def test_cross_process_determinism_all_fixtures(self):
        script = ('from research.mci0.tests.test_conformance import CASES, parse_case; '
                  'print("".join(parse_case(c).to_json() for c in CASES))')
        results = []
        for seed in ['1', '8675309']:
            result = subprocess.run([sys.executable, '-c', script], check=True, timeout=15,
                                    capture_output=True, env={**os.environ, 'PYTHONHASHSEED': seed,
                                                            'PYTHONIOENCODING': 'utf-8'})
            results.append(result.stdout)
        self.assertEqual(*results)

    def test_cli_paths_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, output = root / 'synthetic.txt', root / 'ir.json'
            path.write_text('&first=0\n&inote_5=(120){4}E1,E', encoding='utf-8')
            result = subprocess.run([sys.executable, '-m', 'research.mci0', '--path', str(path),
                                     '--format', 'maidata', '--selected', 'inote_5', '--profile', PROFILE,
                                     '--output', str(output)], timeout=10, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(output.read_text())['notes'][0]['location']['area'], 'E')
            for body, expected in [('(120){4}1`2,E', 2), ('(120){4}9,E', 3), ('(120){4}1,E', 0)]:
                result = subprocess.run([sys.executable, '-m', 'research.mci0', '--body', body,
                                         '--profile', PROFILE], timeout=10, capture_output=True)
                self.assertEqual(result.returncode, expected, result.stderr)
                json.loads(result.stdout)


def load_tests(loader, tests, pattern):
    suite = loader.loadTestsFromTestCase(Conformance)
    for name in DefensiveChecks.__dict__:
        if name.startswith('test_'):
            suite.addTest(DefensiveChecks(name))
    return suite
