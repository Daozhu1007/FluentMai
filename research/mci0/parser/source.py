"""Byte-accurate source maps over decoded, unmodified UTF-8 input."""
from bisect import bisect_right
import hashlib

from ..ir import SourceSpan


class Source:
    def __init__(self, data: bytes):
        self.data = data
        self.sha = hashlib.sha256(data).hexdigest()
        self.text = data.decode('utf-8', errors='strict')
        self.byte_offsets = [0]
        self.line_starts = [0]
        for i, char in enumerate(self.text):
            self.byte_offsets.append(self.byte_offsets[-1] + len(char.encode('utf-8')))
            if char == '\n':
                self.line_starts.append(i + 1)

    def span(self, start: int, end: int):
        line = bisect_right(self.line_starts, start)
        return SourceSpan(self.sha, self.byte_offsets[start], self.byte_offsets[end],
                          line, start - self.line_starts[line - 1] + 1, self.text[start:end])
