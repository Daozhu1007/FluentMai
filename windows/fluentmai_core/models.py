from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import hashlib
import math
import time
import unicodedata


DIFFICULTY_NAMES = ["BASIC", "ADVANCED", "EXPERT", "MASTER", "Re:MASTER"]
DIFFICULTY_WIRE_NAMES = ["basic", "advanced", "expert", "master", "remaster"]
VALID_FC = {"", "fc", "fcp", "ap", "app"}
VALID_FS = {"", "sync", "fs", "fsp", "fsd", "fsdp"}


def now_ts() -> float:
    return time.time()


def normalize_title(title: str | None) -> str:
    return unicodedata.normalize("NFKC", (title or "").strip()).casefold()


def normalize_song_type(value: str | None) -> str:
    raw = (value or "").strip().lower()
    if raw in {"utage", "宴", "宴会場", "宴会场"}:
        return "UTAGE"
    if raw in {"dx", "deluxe"}:
        return "DX"
    if raw in {"standard", "std", "sd"}:
        return "SD"
    return "SD"


def difficulty_name(level_index: int | None) -> str:
    if level_index is not None and 0 <= level_index < len(DIFFICULTY_NAMES):
        return DIFFICULTY_NAMES[level_index]
    return ""


def normalize_fc(value: str | None) -> str:
    raw = (value or "").strip().lower()
    return raw if raw in VALID_FC else ""


def normalize_fs(value: str | None) -> str:
    raw = (value or "").strip().lower()
    return raw if raw in VALID_FS else ""


def as_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def as_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        parsed = float(str(value).strip().replace("%", ""))
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def score_identity_key(
    *,
    title: str,
    song_type: str,
    difficulty_index: int,
    song_id: int | None = None,
) -> str:
    chart_type = normalize_song_type(song_type)
    if song_id is not None:
        raw = f"score|song:{song_id}|{chart_type}|{difficulty_index}"
    else:
        raw = f"score|title:{normalize_title(title)}|{chart_type}|{difficulty_index}"
    return "score-" + sha256_text(raw)


@dataclass(frozen=True)
class Song:
    song_id: int
    title: str
    artist: str = ""
    genre: str = ""
    version: int | None = None
    bpm: int | None = None
    map: str = ""
    rights: str = ""
    locked: bool = False
    disabled: bool = False
    jacket_url: str = ""
    provider: str = ""


@dataclass(frozen=True)
class Chart:
    song_id: int
    chart_type: str
    difficulty_index: int
    level: str = ""
    level_value: float | None = None
    charter: str = ""
    chart_version: int | None = None
    chart_version_name: str = ""
    notes_total: int | None = None
    notes_tap: int | None = None
    notes_hold: int | None = None
    notes_slide: int | None = None
    notes_touch: int | None = None
    notes_break: int | None = None
    is_utage: bool = False

    @property
    def difficulty_name(self) -> str:
        return difficulty_name(self.difficulty_index)


@dataclass(frozen=True)
class MajorVersion:
    version_id: int
    name: str
    provider: str = ""


@dataclass(frozen=True)
class ParsedScoreRecord:
    title: str | None
    song_id: int | None = None
    song_type: str = "SD"
    difficulty_index: int | None = None
    level: str | None = None
    achievements: float | None = None
    dx_score: int | None = None
    rank: str | None = None
    full_combo: str | None = None
    full_sync: str | None = None
    play_time: str | None = None
    source_record_id: str | None = None
    raw_fingerprint: str = ""


@dataclass(frozen=True)
class ScoreRecordDraft:
    identity_key: str
    title: str
    song_id: int | None
    chart_type: str
    difficulty_index: int
    level: str
    level_value: float | None
    achievements: float
    dx_score: int | None
    rank: str
    full_combo: str
    full_sync: str
    play_time: str | None
    source: str
    raw_identifier: str
    raw_fingerprint: str


@dataclass(frozen=True)
class ImportSummary:
    batch_id: str
    source: str
    fetched_count: int
    parsed_count: int
    inserted: int
    updated: int
    skipped_duplicate: int
    quarantined: int
    rejected: int
    failed: int
    message: str = ""
    current_version_id: int | None = None
    b35_count: int = 0
    b15_count: int = 0
    b35_rating: int = 0
    b15_rating: int = 0
    rating_before: int | None = None
    rating_after: int | None = None

    @property
    def accepted(self) -> int:
        return self.inserted + self.updated + self.skipped_duplicate

    def as_dict(self) -> dict[str, Any]:
        return {
            "batch_id": self.batch_id,
            "source": self.source,
            "fetched_count": self.fetched_count,
            "parsed_count": self.parsed_count,
            "inserted": self.inserted,
            "updated": self.updated,
            "skipped_duplicate": self.skipped_duplicate,
            "quarantined": self.quarantined,
            "rejected": self.rejected,
            "failed": self.failed,
            "message": self.message,
            "current_version_id": self.current_version_id,
            "b35_count": self.b35_count,
            "b15_count": self.b15_count,
            "b35_rating": self.b35_rating,
            "b15_rating": self.b15_rating,
            "rating_before": self.rating_before,
            "rating_after": self.rating_after,
            "rating_delta": (
                self.rating_after - self.rating_before
                if self.rating_before is not None and self.rating_after is not None
                else None
            ),
        }
