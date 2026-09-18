from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from enum import Enum

from .chart_browser import ChartRecord
from .version_catalog import plate_version_for, version_name_for


class AchievementRank(str, Enum):
    SSS_PLUS = "SSS+"
    SSS = "SSS"
    SS_PLUS = "SS+"
    SS = "SS"
    S_PLUS = "S+"
    S = "S"
    AAA = "AAA"
    AA = "AA"
    A = "A"
    BBB = "BBB"
    BB = "BB"
    B = "B"
    C = "C"
    D = "D"


class FullComboStatus(str, Enum):
    FC = "FC"
    FC_PLUS = "FC+"
    AP = "AP"
    AP_PLUS = "AP+"
    UNKNOWN = "未知"


class FullSyncStatus(str, Enum):
    SYNC = "SYNC"
    FS = "FS"
    FS_PLUS = "FS+"
    FSD = "FSD"
    FSD_PLUS = "FSD+"
    UNKNOWN = "未知"


class PlateKind(str, Enum):
    GENERAL = "general"
    EXTREME = "extreme"
    GOD = "god"
    MAIMAI = "maimai"
    CONQUEROR = "conqueror"

    @property
    def display_name(self) -> str:
        return {
            PlateKind.GENERAL: "将",
            PlateKind.EXTREME: "极",
            PlateKind.GOD: "神",
            PlateKind.MAIMAI: "舞舞",
            PlateKind.CONQUEROR: "霸者",
        }[self]


@dataclass(frozen=True)
class PlayerStats:
    total_charts: int
    played_charts: int
    unplayed_charts: int
    rank_counts: dict[AchievementRank, int]
    full_combo_counts: dict[FullComboStatus, int]
    full_sync_counts: dict[FullSyncStatus, int]


@dataclass(frozen=True)
class PlateBlocker:
    record: ChartRecord
    current_value: str
    requirement_gap: str


@dataclass(frozen=True)
class PlateProgress:
    kind: PlateKind
    version_id: int | None
    version_name: str
    plate_name: str
    required_count: int
    completed_count: int
    blockers: tuple[PlateBlocker, ...]
    eligible_records: tuple[ChartRecord, ...]
    data_sufficient: bool
    data_message: str | None = None

    @property
    def remaining_count(self) -> int:
        return max(0, self.required_count - self.completed_count)

    @property
    def is_complete(self) -> bool:
        return self.data_sufficient and self.required_count > 0 and self.remaining_count == 0


def achievement_rank(value: float) -> AchievementRank:
    for threshold, rank in (
        (100.5, AchievementRank.SSS_PLUS), (100.0, AchievementRank.SSS),
        (99.5, AchievementRank.SS_PLUS), (99.0, AchievementRank.SS),
        (98.0, AchievementRank.S_PLUS), (97.0, AchievementRank.S),
        (94.0, AchievementRank.AAA), (90.0, AchievementRank.AA),
        (80.0, AchievementRank.A), (75.0, AchievementRank.BBB),
        (70.0, AchievementRank.BB), (60.0, AchievementRank.B),
        (50.0, AchievementRank.C),
    ):
        if value >= threshold:
            return rank
    return AchievementRank.D


def full_combo_status(value: str | None) -> FullComboStatus | None:
    normalized = _normalize_flag(value)
    if not normalized:
        return None
    return {
        "fc": FullComboStatus.FC,
        "fcp": FullComboStatus.FC_PLUS,
        "fcplus": FullComboStatus.FC_PLUS,
        "ap": FullComboStatus.AP,
        "app": FullComboStatus.AP_PLUS,
        "applus": FullComboStatus.AP_PLUS,
    }.get(normalized, FullComboStatus.UNKNOWN)


def full_sync_status(value: str | None) -> FullSyncStatus | None:
    normalized = _normalize_flag(value)
    if not normalized:
        return None
    return {
        "sync": FullSyncStatus.SYNC,
        "fs": FullSyncStatus.FS,
        "fsp": FullSyncStatus.FS_PLUS,
        "fsplus": FullSyncStatus.FS_PLUS,
        "fsd": FullSyncStatus.FSD,
        "fdx": FullSyncStatus.FSD,
        "fsdp": FullSyncStatus.FSD_PLUS,
        "fdxp": FullSyncStatus.FSD_PLUS,
        "fsdplus": FullSyncStatus.FSD_PLUS,
        "fdxplus": FullSyncStatus.FSD_PLUS,
    }.get(normalized, FullSyncStatus.UNKNOWN)


def player_stats(records: list[ChartRecord]) -> PlayerStats:
    played = [record for record in records if record.played]
    return PlayerStats(
        total_charts=len(records),
        played_charts=len(played),
        unplayed_charts=len(records) - len(played),
        rank_counts=dict(Counter(achievement_rank(record.achievements or 0.0) for record in played)),
        full_combo_counts=dict(Counter(filter(None, (full_combo_status(record.full_combo) for record in played)))),
        full_sync_counts=dict(Counter(filter(None, (full_sync_status(record.full_sync) for record in played)))),
    )


def calculate_plate_progress(
    records: list[ChartRecord],
    kind: PlateKind,
    version_id: int | None = None,
) -> PlateProgress:
    version_name = version_name_for(version_id)
    plate = plate_version_for(version_id)
    if kind != PlateKind.CONQUEROR and version_id is None:
        return _unavailable(kind, version_id, version_name, "缺少版本信息")
    if kind != PlateKind.CONQUEROR and plate is None:
        return _unavailable(kind, version_id, version_name, "该曲库版本还没有可核验的版本牌要求")
    if kind != PlateKind.CONQUEROR and kind.value not in plate.supported_kinds:
        return _unavailable(kind, version_id, version_name, f"该版本没有{kind.display_name}牌要求")

    if kind == PlateKind.CONQUEROR:
        eligible = [record for record in records if record.chart_type == "SD" and not record.is_utage]
        plate_name = "霸者"
        label = "全标准谱面"
    else:
        assert plate is not None
        eligible = [
            record for record in records
            if record.difficulty_index != 4
            and not record.is_utage
            and record.chart_version is not None
            and plate.contains(record.song_id, record.chart_version)
        ]
        plate_name = " / ".join(f"{prefix}{kind.display_name}" for prefix in plate.prefixes)
        label = version_name
    if not eligible:
        message = "曲库中没有可核验的标准谱面" if kind == PlateKind.CONQUEROR else "该版本没有可核验的 BASIC～MASTER 谱面"
        return _unavailable(kind, version_id, label, message, plate_name=plate_name)

    blockers = tuple(
        blocker for record in eligible if (blocker := _plate_blocker(record, kind)) is not None
    )
    return PlateProgress(
        kind=kind,
        version_id=None if kind == PlateKind.CONQUEROR else version_id,
        version_name=label,
        plate_name=plate_name,
        required_count=len(eligible),
        completed_count=len(eligible) - len(blockers),
        blockers=blockers,
        eligible_records=tuple(eligible),
        data_sufficient=True,
    )


def _plate_blocker(record: ChartRecord, kind: PlateKind) -> PlateBlocker | None:
    achievement = record.achievements or 0.0
    combo = full_combo_status(record.full_combo)
    sync = full_sync_status(record.full_sync)
    if kind == PlateKind.GENERAL:
        return None if achievement >= 100.0 else PlateBlocker(record, _achievement_text(record), f"达成率还差 {max(0.0, 100.0 - achievement):.4f}%")
    if kind == PlateKind.EXTREME:
        return None if combo not in {None, FullComboStatus.UNKNOWN} else PlateBlocker(record, combo.value if combo else "未达成", "需要 FC 或更高状态")
    if kind == PlateKind.GOD:
        return None if combo in {FullComboStatus.AP, FullComboStatus.AP_PLUS} else PlateBlocker(record, combo.value if combo else "未达成", "需要 AP 或 AP+")
    if kind == PlateKind.MAIMAI:
        return None if sync in {FullSyncStatus.FSD, FullSyncStatus.FSD_PLUS} else PlateBlocker(record, sync.value if sync else "未达成", "需要 FSD 或 FSD+")
    return None if achievement >= 80.0 else PlateBlocker(record, _achievement_text(record), f"距 CLEAR 还差 {max(0.0, 80.0 - achievement):.4f}%")


def _achievement_text(record: ChartRecord) -> str:
    return f"{record.achievements:.4f}%" if record.played else "未游玩"


def _unavailable(kind, version_id, version_name, message, *, plate_name=None):
    return PlateProgress(kind, version_id, version_name, plate_name or ("霸者" if kind == PlateKind.CONQUEROR else f"{version_name}{kind.display_name}"), 0, 0, (), (), False, message)


def _normalize_flag(value: str | None) -> str:
    return (value or "").strip().lower().replace("+", "plus").replace("_", "").replace("-", "").replace(" ", "")
