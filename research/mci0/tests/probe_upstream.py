"""Optional offline bounded wrapper experiment; never a runtime dependency.

Run with --upstream pointing to the externally retained pinned source snapshot.
Only a terminal-E prefilter is tried; this is deliberately not a second parser.
"""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from fractions import Fraction


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--upstream', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    base = Path(__file__).resolve().parents[1]
    pin = json.loads((base / 'reports/upstream_pin.json').read_text())
    for rel, digest in pin['files'].items():
        if hashlib.sha256((args.upstream / rel).read_bytes()).hexdigest() != digest:
            raise SystemExit('Pinned upstream content mismatch: ' + rel)
    sys.path.insert(0, str(args.upstream.resolve()))
    from SimaiParser.core import SimaiChart
    cases = json.loads((base / 'fixtures/cases.json').read_text(encoding='utf-8'))
    chosen = {'01', '02', '04', '06', '08', '12', '13', '14', '16', '19', '22', '25', '29', '40'}
    results = []
    for case in cases:
        if case['id'][:2] not in chosen:
            continue
        variants = {}
        for strategy in ('unwrapped', 'terminal_e_prefilter'):
            body = case['body']
            if strategy == 'terminal_e_prefilter':
                body = re.sub(r'(?<=,)E\s*$', '', body)
            warnings = io.StringIO()
            with contextlib.redirect_stdout(warnings):
                chart = SimaiChart()
                chart.load_from_text('&first=0\n&inote_5=' + body)
            events = chart.processed_fumens_data[4]['note_events']
            flat = [(point['time'], n) for point in events for n in point['notes']]
            # Comparisons are narrow observations, NOT complete upstream conformance.
            expected_count = len(case['notes'])
            checks = {'hit_record_count': len(flat) == expected_count}
            if case['status'] == 'COMPLETE' and len(flat) == expected_count:
                checks['onsets'] = all(abs(t - float(Fraction(row[2]))) < 1e-12
                                      for (t, _), row in zip(flat, case['notes']))
                holds = [(n, row) for (_, n), row in zip(flat, case['notes'])
                         if row[0] in ('HOLD', 'TOUCH_HOLD')]
                if holds:
                    checks['hold_durations'] = all(
                        abs(n['hold_time'] - float(Fraction(row[3]) - Fraction(row[2]))) < 1e-12
                        for n, row in holds)
            tracks = [n for _, n in flat if n['note_type'] == 'SLIDE']
            if case['slides']:
                checks['structured_endpoint_and_shape'] = bool(tracks) and all(
                    'end_position' in n and 'shape_family' in n for n in tracks)
                if len(tracks) == len(case['slides']):
                    checks['slide_duration'] = all(
                        abs(n['slide_time'] - float(Fraction(row[7]))) < 1e-12
                        for n, row in zip(tracks, case['slides']))
            if case['id'].startswith('25'):
                checks['invalid_lane_rejected'] = not any(n['start_position'] == 9 for _, n in flat)
            if case['id'].startswith('29'):
                checks['unsupported_timing_restricted'] = not any(n['start_position'] == 3 for _, n in flat)
            variants[strategy] = {
                'checks': checks, 'warnings': warnings.getvalue().splitlines(),
                'notes': [{'onset': t, **n} for t, n in flat]
            }
        results.append({'fixture': case['id'], 'variants': variants})
    result = {'upstream_commit': pin['commit'], 'pin_bytes_verified': True,
              'fixture_sha256': hashlib.sha256((base / 'fixtures/cases.json').read_bytes()).hexdigest(),
              'scope': '14 bounded synthetic probes; approximate float comparisons only for upstream observations',
              'results': results}
    args.output.write_bytes((json.dumps(result, indent=2, sort_keys=True) + '\n').encode('utf-8'))
    for variant in ('unwrapped', 'terminal_e_prefilter'):
        checks = [v for r in results for v in r['variants'][variant]['checks'].values()]
        print(variant, sum(checks), '/', len(checks), 'bounded checks passed')


if __name__ == '__main__':
    main()
