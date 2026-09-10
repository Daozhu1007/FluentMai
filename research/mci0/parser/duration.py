"""Independent decimal/rational duration grammar for the frozen profile."""
from fractions import Fraction
import re

from ..ir import DurationSpec


class SyntaxIssue(ValueError):
    def __init__(self, code, message, *, unsupported=False, poison=False):
        super().__init__(message)
        self.code, self.unsupported, self.poison = code, unsupported, poison


def decimal(text, *, positive=False, signed=False, code='INVALID_DURATION'):
    if len(text) > 24:
        raise SyntaxIssue('RESOURCE_LIMIT', 'Numeric literal exceeds 24 characters')
    pattern = r'[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)' if signed else r'(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)'
    if not re.fullmatch(pattern, text):
        raise SyntaxIssue(code, 'Expected a finite decimal literal')
    value = Fraction(text)
    if positive and value <= 0:
        raise SyntaxIssue(code, 'Timing basis must be positive')
    return value


def spec(source, amount, bpm=None, *, explicit=False, seconds=False, implicit=False):
    resolved = amount if seconds else (amount * 60 / bpm if bpm is not None else None)
    return DurationSpec(source, 'SECONDS' if seconds else 'FROZEN_BPM_QUARTERS', amount,
                        'NOT_APPLICABLE' if seconds else ('EXPLICIT' if explicit else 'NOTE_ONSET'),
                        bpm if explicit else None, implicit, None if seconds else bpm,
                        resolved, 'RESOLVED' if resolved is not None else 'UNKNOWN')


def duration(text, source, bpm, *, seconds_plain=False):
    explicit = False
    if text.startswith('#'):
        return spec(source, decimal(text[1:]), seconds=True)
    if '#' in text:
        parts = text.split('#')
        if len(parts) != 2:
            raise SyntaxIssue('INVALID_DURATION', 'Invalid duration separator')
        bpm = decimal(parts[0], positive=True)
        explicit = True
        text = parts[1]
    if ':' in text:
        parts = text.split(':')
        if len(parts) != 2:
            raise SyntaxIssue('INVALID_DURATION', 'Expected divisor:count')
        divisor, count = decimal(parts[0], positive=True), decimal(parts[1])
        return spec(source, 4 * count / divisor, bpm, explicit=explicit)
    if seconds_plain:
        return spec(source, decimal(text), seconds=True)
    raise SyntaxIssue('INVALID_DURATION', 'Hold seconds require #; beat duration requires divisor:count')


def slide_timing(text, source, bpm):
    if '##' in text:
        parts = text.split('##')
        if len(parts) != 2 or parts[1].startswith('#'):
            raise SyntaxIssue('INVALID_DURATION', 'Expected wait##duration')
        wait = spec(source, decimal(parts[0]), seconds=True)
        return wait, duration(parts[1], source, bpm, seconds_plain=True)
    if text.startswith('#') or (':' not in text and '#' not in text):
        raise SyntaxIssue('INVALID_DURATION', 'Unsupported slide timing form')
    override = decimal(text.split('#')[0], positive=True) if '#' in text else None
    wait = spec(source, Fraction(1), override if override is not None else bpm,
                explicit=override is not None, implicit=override is None)
    return wait, duration(text, source, bpm, seconds_plain=True)
