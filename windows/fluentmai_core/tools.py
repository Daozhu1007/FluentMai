from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import math

from .player_records import AchievementRank, achievement_rank
from .rating import calculate_dx_rating, dx_rating_coefficient


@dataclass(frozen=True)
class SingleSongRatingCalculation:
    level_value: float
    achievement: float
    capped_achievement: float
    coefficient: float
    rating: int
    rank: AchievementRank


def calculate_single_song_rating(level_value: float, achievement: float) -> SingleSongRatingCalculation:
    if not math.isfinite(level_value) or not 0.1 <= level_value <= 20.0:
        raise ValueError("谱面定数必须在 0.1 到 20.0 之间")
    if not math.isfinite(achievement) or not 0.0 <= achievement <= 101.0:
        raise ValueError("达成率必须在 0.0% 到 101.0% 之间")
    capped = min(achievement, 100.5)
    return SingleSongRatingCalculation(level_value, achievement, capped, dx_rating_coefficient(capped), calculate_dx_rating(level_value, achievement), achievement_rank(achievement))


class NoteKind(str, Enum):
    TAP = "Tap"
    HOLD = "Hold"
    SLIDE = "Slide"
    TOUCH = "Touch"
    BREAK = "Break"

    @property
    def base_weight(self) -> int:
        return {NoteKind.TAP: 1, NoteKind.HOLD: 2, NoteKind.SLIDE: 3, NoteKind.TOUCH: 1, NoteKind.BREAK: 5}[self]


class Judgement(str, Enum):
    CRITICAL_PERFECT = "Critical Perfect"
    PERFECT_HIGH = "Perfect（BREAK 2550）"
    PERFECT = "Perfect（BREAK 2500）"
    GREAT = "Great"
    GOOD = "Good"
    MISS = "Miss"

    @property
    def multipliers(self) -> tuple[float, float]:
        return {
            Judgement.CRITICAL_PERFECT: (1.0, 1.0),
            Judgement.PERFECT_HIGH: (1.0, 0.75),
            Judgement.PERFECT: (1.0, 0.5),
            Judgement.GREAT: (0.8, 0.4),
            Judgement.GOOD: (0.5, 0.3),
            Judgement.MISS: (0.0, 0.0),
        }[self]


@dataclass(frozen=True)
class NoteCounts:
    tap: int
    hold: int
    slide: int
    touch: int
    break_count: int

    def __post_init__(self):
        if any(value < 0 for value in (self.tap, self.hold, self.slide, self.touch, self.break_count)):
            raise ValueError("物量不能为负数")
        if self.weighted_count <= 0:
            raise ValueError("至少需要一个音符")

    @property
    def weighted_count(self) -> int:
        return self.tap + self.hold * 2 + self.slide * 3 + self.touch + self.break_count * 5

    @property
    def maximum_achievement(self) -> float:
        return 101.0 if self.break_count > 0 else 100.0

    def count(self, kind: NoteKind) -> int:
        return {NoteKind.TAP: self.tap, NoteKind.HOLD: self.hold, NoteKind.SLIDE: self.slide, NoteKind.TOUCH: self.touch, NoteKind.BREAK: self.break_count}[kind]


@dataclass(frozen=True)
class AchievementCalculation:
    maximum_achievement: float
    loss_per_judgement: float
    occurrences: int
    resulting_achievement: float
    target_achievement: float
    tolerated_occurrences: int


def calculate_achievement(notes: NoteCounts, note_kind: NoteKind, judgement: Judgement, occurrences: int, target_achievement: float) -> AchievementCalculation:
    if occurrences < 0 or occurrences > notes.count(note_kind):
        raise ValueError("判定数量不能超过该类音符物量" if occurrences >= 0 else "判定数量不能为负数")
    if not math.isfinite(target_achievement) or not 0.0 <= target_achievement <= notes.maximum_achievement:
        raise ValueError("目标达成率超出当前谱面的有效范围")
    base_multiplier, break_multiplier = judgement.multipliers
    base_unit = 100.0 / notes.weighted_count
    base_loss = note_kind.base_weight * base_unit * (1.0 - base_multiplier)
    extra_loss = (1.0 - break_multiplier) / notes.break_count if note_kind == NoteKind.BREAK and notes.break_count > 0 else 0.0
    loss = base_loss + extra_loss
    resulting = min(notes.maximum_achievement, max(0.0, notes.maximum_achievement - loss * occurrences))
    available_loss = max(0.0, notes.maximum_achievement - target_achievement)
    tolerated = notes.count(note_kind) if loss == 0.0 else min(notes.count(note_kind), max(0, math.floor((available_loss + 1e-9) / loss)))
    return AchievementCalculation(notes.maximum_achievement, loss, occurrences, resulting, target_achievement, tolerated)


@dataclass(frozen=True)
class KaleidScopeUnavailable:
    reason: str
    reviewed_sources: tuple[str, ...]


KALEID_SCOPE = KaleidScopeUnavailable(
    reason="官方公开页面尚未提供可审计、结构化的门曲与逐门解锁条件；不会展示虚构数据。",
    reviewed_sources=("https://maimai.sega.com/play/newfunction2/",),
)
