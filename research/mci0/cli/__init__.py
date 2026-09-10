"""Small offline JSON inspection command."""
import argparse
import json
from pathlib import Path
import sys

from ..parser import InputError, parse


def main(argv=None):
    ap = argparse.ArgumentParser(description='Inspect a declared Simai subset as exact structural IR')
    inputs = ap.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--body', help='Bare original/synthetic Simai text')
    inputs.add_argument('--path', type=Path, help='Local UTF-8 input path')
    ap.add_argument('--format', choices=['bare', 'maidata'], default='bare')
    ap.add_argument('--selected', help='body for bare text; explicit inote_1 through inote_7 for maidata')
    ap.add_argument('--profile', required=True)
    ap.add_argument('--offset', help='Optional signed decimal audio offset, bare input only')
    ap.add_argument('--output', type=Path, help='Write JSON here instead of stdout')
    args = ap.parse_args(argv)
    if args.output and args.path and args.output.resolve() == args.path.resolve():
        ap.error('Output must not overwrite input')
    try:
        data = args.path.read_bytes() if args.path else args.body.encode('utf-8')
        selected = args.selected if args.selected is not None else ('body' if args.format == 'bare' else '')
        try:
            chart = parse(data, profile=args.profile, source_format=args.format,
                          selected_body=selected, offset=args.offset)
            output = chart.to_json()
            code = {'COMPLETE': 0, 'PARTIAL': 2, 'INVALID': 3}[chart.status]
        except InputError as error:
            output = json.dumps(error.to_dict(), sort_keys=True, indent=2) + '\n'
            code = 3
        if args.output:
            args.output.write_bytes(output.encode('utf-8'))
        else:
            # Avoid Windows cp936 corruption and newline-dependent output bytes.
            sys.stdout.buffer.write(output.encode('utf-8'))
        return code
    except OSError as error:
        print('Local file I/O failed: ' + str(error), file=sys.stderr)
        return 4
