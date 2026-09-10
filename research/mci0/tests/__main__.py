"""Run gates and optionally save deterministic, machine-readable test results."""
import argparse
import hashlib
import json
from pathlib import Path
import unittest

from . import test_conformance


class Recorder(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.records = []

    def addSuccess(self, test):
        super().addSuccess(test)
        self.records.append({'test': test.id(), 'status': 'PASS'})

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self.records.append({'test': test.id(), 'status': 'FAIL'})

    def addError(self, test, err):
        super().addError(test, err)
        self.records.append({'test': test.id(), 'status': 'ERROR'})


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--report', type=Path)
    args = ap.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromModule(test_conformance)
    result = unittest.TextTestRunner(verbosity=2, resultclass=Recorder).run(suite)
    if args.report:
        base = Path(__file__).resolve().parents[1]
        report = {'profile': 'mci-simai-0.1', 'fixture_count': len(test_conformance.CASES),
                  'fixture_sha256': hashlib.sha256((base / 'fixtures/cases.json').read_bytes()).hexdigest(),
                  'tests_run': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
                  'all_passed': result.wasSuccessful(), 'results': sorted(result.records, key=lambda x: x['test'])}
        args.report.write_bytes((json.dumps(report, sort_keys=True, indent=2) + '\n').encode('utf-8'))
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == '__main__':
    main()
