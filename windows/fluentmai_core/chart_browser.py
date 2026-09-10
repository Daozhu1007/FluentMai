from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
import sqlite3
from typing import TYPE_CHECKING

from .models import DIFFICULTY_NAMES, difficulty_name, normalize_song_type, normalize_title
from .rating import calculate_dx_rating, resolve_current_version_id
from .search_normalization import normalize_search

if TYPE_CHECKING:
    from .player_records import PlayerStats


DISPLAY_LIMIT = 500


@dataclass(frozen=True)
class FilterOption:
    code: str
    label: str


@dataclass(frozen=True)
class ChartFilters:
    search: str = ""
    level: str = ""
    difficulty_index: int | None = None
    constant_min: float | None = None
    constant_max: float | None = None
    genre: str = "all"
    version: str = "all"
    status: str = "all"
    chart_type: str = "all"
    achievement_min: float | None = None
    achievement_max: float | None = None
    rank: str = "all"
    full_combo: str = "all"
    full_sync: str = "all"
    sort: str = "constant_desc"
    limit: int = DISPLAY_LIMIT


@dataclass(frozen=True)
class ChartRecord:
    song_id: int
    title: str
    title_norm: str
    artist: str
    genre: str
    song_version: int | None
    bpm: int | None
    map_name: str
    jacket_url: str
    chart_type: str
    difficulty_index: int
    difficulty_name: str
    level: str
    level_value: float | None
    charter: str
    chart_version: int | None
    chart_version_name: str
    notes_total: int | None
    notes_tap: int | None
    notes_hold: int | None
    notes_slide: int | None
    notes_touch: int | None
    notes_break: int | None
    is_utage: bool
    aliases: tuple[str, ...] = ()
    locked: bool = False
    disabled: bool = False
    achievements: float | None = None
    dx_score: int | None = None
    full_combo: str = ""
    full_sync: str = ""
    score_source: str = ""
    score_updated_at: float | None = None
    score_imported_at: float | None = None
    play_time: str | None = None

    @property
    def key(self) -> str:
        return f"{self.song_id}:{self.chart_type}:{self.difficulty_index}"

    @property
    def played(self) -> bool:
        return self.achievements is not None

    @property
    def type_label(self) -> str:
        return "UTAGE" if self.is_utage else self.chart_type

    @property
    def difficulty_label(self) -> str:
        return "宴会场" if self.is_utage else self.difficulty_name

    @property
    def const_label(self) -> str:
        if self.level_value is None or self.level_value <= 0:
            return "--"
        return f"{self.level_value:.1f}"

    @property
    def version_label(self) -> str:
        return (
            self.chart_version_name
            or version_name_for(self.chart_version)
            or version_name_for(self.song_version)
            or (str(self.chart_version) if self.chart_version else "")
            or (str(self.song_version) if self.song_version else "--")
        )

    @property
    def notes_label(self) -> str:
        return str(self.notes_total) if self.notes_total else "--"

    @property
    def rating(self) -> int | None:
        if self.achievements is None or self.level_value is None or self.level_value <= 0:
            return None
        return calculate_dx_rating(self.level_value, self.achievements)


@dataclass(frozen=True)
class ChartQueryResult:
    records: list[ChartRecord]
    total_count: int
    displayed_count: int
    limit: int
    stats: "PlayerStats | None" = None

    @property
    def is_limited(self) -> bool:
        return self.displayed_count < self.total_count


@dataclass(frozen=True)
class CatalogStats:
    song_count: int = 0
    chart_count: int = 0
    regular_chart_count: int = 0
    utage_count: int = 0
    sd_count: int = 0
    dx_count: int = 0
    by_difficulty: dict[int, int] = field(default_factory=dict)
    title_nonempty: int = 0
    artist_nonempty: int = 0
    genre_nonempty: int = 0
    bpm_valid: int = 0
    version_nonempty: int = 0
    charter_nonempty: int = 0
    notes_valid: int = 0
    level_value_valid: int = 0

    @property
    def metadata_sparse(self) -> bool:
        return self.chart_count > 0 and (
            self.artist_nonempty == 0
            or self.genre_nonempty == 0
            or self.bpm_valid == 0
            or self.version_nonempty == 0
            or self.notes_valid == 0
        )


@dataclass(frozen=True)
class ChartFilterOptions:
    genres: list[FilterOption]
    versions: list[FilterOption]


SORT_OPTIONS = [
    FilterOption("constant_desc", "定数降序"),
    FilterOption("constant_asc", "定数升序"),
    FilterOption("rating_desc", "Rating 降序"),
    FilterOption("id_asc", "歌曲 ID"),
    FilterOption("version_desc", "上线新到旧"),
    FilterOption("version_asc", "上线旧到新"),
    FilterOption("achievement_asc", "成绩升序"),
    FilterOption("achievement_desc", "成绩降序"),
    FilterOption("title_asc", "曲名升序"),
    FilterOption("title_desc", "曲名降序"),
]

STATUS_OPTIONS = [
    FilterOption("all", "全部"),
    FilterOption("played", "已游玩"),
    FilterOption("missing", "未游玩"),
]

DIFFICULTY_OPTIONS = [FilterOption("all", "全部难度")] + [
    FilterOption(str(index), label) for index, label in enumerate(DIFFICULTY_NAMES)
]

GENRE_ORDER = [
    FilterOption("maimai", "舞萌区"),
    FilterOption("ongeki_chunithm", "中二 / 音击区"),
    FilterOption("vocaloid", "VOCALOID"),
    FilterOption("touhou", "东方区"),
    FilterOption("game_variety", "GAME & VARIETY"),
    FilterOption("pops_anime", "POPS & ANIME"),
    FilterOption("utage", "宴会场"),
    FilterOption("other", "其他"),
]

VERSION_RANGES = [
    ("dx_2026", "DX 2026", 25500, 26000),
    ("dx_2025", "DX 2025", 25000, 25500),
    ("dx_2024", "DX 2024", 24000, 25000),
    ("dx_2023", "DX 2023", 23000, 24000),
    ("dx_2022", "DX 2022", 22000, 23000),
    ("dx_2021", "DX 2021", 21000, 22000),
    ("dx", "舞萌 DX", 20000, 21000),
    ("finale", "FiNALE", 19900, 20000),
    ("milk_plus", "MiLK PLUS", 19500, 19900),
    ("milk", "MiLK", 19000, 19500),
    ("murasaki_plus", "MURASAKi PLUS", 18500, 19000),
    ("murasaki", "MURASAKi", 18000, 18500),
    ("pink_plus", "PiNK PLUS", 17000, 18000),
    ("pink", "PiNK", 16000, 17000),
    ("orange_plus", "ORANGE PLUS", 15000, 16000),
    ("orange", "ORANGE", 14000, 15000),
    ("green_plus", "GreeN PLUS", 13000, 14000),
    ("green", "GreeN", 12000, 13000),
    ("maimai_plus", "maimai PLUS", 11000, 12000),
    ("maimai", "maimai", 10000, 11000),
    ("classic", "旧框体", 1, 10000),
]


def normalize_query(value: str | None) -> str:
    return normalize_search(value)


def load_chart_records(
    conn: sqlite3.Connection,
    *,
    song_id: int | None = None,
) -> list[ChartRecord]:
    chart_rows = conn.execute(
        """
        SELECT
            s.song_id, s.title, s.title_norm, COALESCE(s.artist, '') AS artist,
            COALESCE(s.genre, '') AS genre, s.version AS song_version, s.bpm,
            COALESCE(s.map, '') AS map_name, COALESCE(s.jacket_url, '') AS jacket_url,
            s.locked, s.disabled,
            c.chart_type, c.difficulty_index, c.difficulty_name, COALESCE(c.level, '') AS level,
            c.level_value, COALESCE(c.charter, '') AS charter, c.chart_version,
            COALESCE(c.chart_version_name, '') AS chart_version_name, c.notes_total,
            c.notes_tap, c.notes_hold, c.notes_slide, c.notes_touch, c.notes_break,
            c.is_utage
        FROM charts c
        JOIN songs s ON s.song_id = c.song_id
        WHERE (? IS NULL OR s.song_id = ?)
        ORDER BY s.title_norm, c.chart_type, c.difficulty_index
        """,
        (song_id, song_id),
    ).fetchall()
    if song_id is None:
        score_query = conn.execute("SELECT * FROM score_records")
    else:
        score_query = conn.execute(
            "SELECT * FROM score_records WHERE song_id = ? OR song_id IS NULL",
            (song_id,),
        )
    score_rows = [dict(row) for row in score_query.fetchall()]
    aliases_by_song_id = _database_aliases(conn)

    title_counts: Counter[tuple[str, str, int]] = Counter()
    for row in chart_rows:
        key = (
            normalize_title(row["title"]),
            normalize_song_type(row["chart_type"]),
            int(row["difficulty_index"]),
        )
        title_counts[key] += 1

    exact_scores: dict[tuple[int, str, int], dict] = {}
    title_scores: dict[tuple[str, str, int], dict] = {}
    for score in score_rows:
        chart_type = normalize_song_type(score["chart_type"])
        difficulty_index = int(score["difficulty_index"])
        if score["song_id"] is not None:
            key = (int(score["song_id"]), chart_type, difficulty_index)
            _keep_best_score(exact_scores, key, score)
        else:
            key = (normalize_title(score["title"]), chart_type, difficulty_index)
            _keep_best_score(title_scores, key, score)

    records: list[ChartRecord] = []
    for row in chart_rows:
        chart_type = normalize_song_type(row["chart_type"])
        difficulty_index = int(row["difficulty_index"])
        exact_key = (int(row["song_id"]), chart_type, difficulty_index)
        title_key = (normalize_title(row["title"]), chart_type, difficulty_index)
        score = exact_scores.get(exact_key)
        if score is None and title_counts[title_key] == 1:
            score = title_scores.get(title_key)
        records.append(
            _chart_record_from_row(
                row,
                score,
                aliases_by_song_id.get(int(row["song_id"]), ()),
            )
        )
    return records


def query_charts(conn: sqlite3.Connection, filters: ChartFilters | None = None) -> ChartQueryResult:
    return query_chart_records(
        load_chart_records(conn),
        filters or ChartFilters(),
        current_version_id=resolve_current_version_id(conn),
    )


def query_chart_records(
    records: list[ChartRecord],
    filters: ChartFilters,
    *,
    current_version_id: int | None = None,
) -> ChartQueryResult:
    from .player_records import player_stats

    latest_song_version = current_version_id or _latest_named_chart_version(records)
    matched = [
        record
        for record in records
        if _difficulty_matches(record, filters.difficulty_index)
        and _constant_matches(record, filters.constant_min, filters.constant_max)
        and genre_matches(filters.genre, record)
        and version_matches(filters.version, record, latest_song_version)
        and level_matches(record, filters.level)
        and search_matches(record, filters.search)
        and status_matches(filters.status, record)
        and _chart_type_matches(record, filters.chart_type)
        and _score_matches(record, filters)
    ]
    _sort_records(matched, filters.sort)
    limit = filters.limit if filters.limit > 0 else DISPLAY_LIMIT
    displayed = matched[:limit]
    return ChartQueryResult(
        records=displayed,
        total_count=len(matched),
        displayed_count=len(displayed),
        limit=limit,
        stats=player_stats(matched),
    )


def catalog_filter_options(conn: sqlite3.Connection) -> ChartFilterOptions:
    return filter_options_for_records(load_chart_records(conn))


def filter_options_for_records(records: list[ChartRecord]) -> ChartFilterOptions:
    present_genres = {genre_code_for(record) for record in records}
    genres = [FilterOption("all", "全部分区")] + [
        option for option in GENRE_ORDER if option.code in present_genres
    ]

    versions = [FilterOption("all", "全部版本")]
    latest_version = _latest_song_version(records)
    if latest_version:
        versions.append(FilterOption("current", "当前版本"))
    present_versions = {version_bucket_for(_version_value(record)) for record in records}
    versions.extend(
        FilterOption(code, label)
        for code, label, _start, _end in VERSION_RANGES
        if code in present_versions
    )
    return ChartFilterOptions(genres=genres, versions=versions)


def catalog_stats(conn: sqlite3.Connection) -> CatalogStats:
    records = load_chart_records(conn)
    song_rows = conn.execute(
        """
        SELECT title, artist, genre, version, bpm
        FROM songs
        """
    ).fetchall()
    by_difficulty = Counter(record.difficulty_index for record in records if not record.is_utage)
    return CatalogStats(
        song_count=len(song_rows),
        chart_count=len(records),
        regular_chart_count=sum(1 for record in records if not record.is_utage),
        utage_count=sum(1 for record in records if record.is_utage),
        sd_count=sum(1 for record in records if record.chart_type == "SD"),
        dx_count=sum(1 for record in records if record.chart_type == "DX"),
        by_difficulty=dict(by_difficulty),
        title_nonempty=sum(1 for row in song_rows if (row["title"] or "").strip()),
        artist_nonempty=sum(1 for row in song_rows if (row["artist"] or "").strip()),
        genre_nonempty=sum(1 for row in song_rows if (row["genre"] or "").strip()),
        bpm_valid=sum(1 for row in song_rows if _valid_bpm(row["bpm"])),
        version_nonempty=sum(1 for row in song_rows if row["version"] is not None),
        charter_nonempty=sum(1 for record in records if record.charter.strip()),
        notes_valid=sum(1 for record in records if record.notes_total is not None and record.notes_total > 0),
        level_value_valid=sum(1 for record in records if record.level_value is not None and record.level_value > 0),
    )


def genre_code_for(record: ChartRecord) -> str:
    raw = normalize_query(record.genre)
    if record.is_utage or "宴会" in raw or "宴會" in raw or "utage" in raw:
        return "utage"
    if raw == "maimai":
        return "maimai"
    if "ongeki" in raw or "chunithm" in raw or "オンゲキ" in record.genre or "中二" in raw or "音击" in raw:
        return "ongeki_chunithm"
    if "vocaloid" in raw or "niconico" in raw or "ボーカロイド" in record.genre:
        return "vocaloid"
    if "東方" in record.genre or "东方" in raw:
        return "touhou"
    if "game" in raw or "variety" in raw or "ゲーム" in record.genre or "バラエティ" in record.genre:
        return "game_variety"
    if "pops" in raw or "anime" in raw or "アニメ" in record.genre:
        return "pops_anime"
    return "other"


def genre_matches(code: str, record: ChartRecord) -> bool:
    return code in {"", "all"} or genre_code_for(record) == code


def version_matches(code: str, record: ChartRecord, latest_song_version: int | None = None) -> bool:
    if code in {"", "all"}:
        return True
    value = _version_value(record)
    if value is None:
        return False
    if code == "current":
        return latest_song_version is not None and record.song_version == latest_song_version
    return version_bucket_for(value) == code


def version_bucket_for(version: int | None) -> str:
    if version is None:
        return "unknown"
    for code, _label, start, end in VERSION_RANGES:
        if start <= version < end:
            return code
    return "unknown"


def version_name_for(version: int | None) -> str:
    if version is None or version <= 0:
        return ""
    for _code, label, start, end in VERSION_RANGES:
        if start <= version < end:
            return label
    return ""


def level_matches(record: ChartRecord, query: str) -> bool:
    trimmed = query.strip()
    if not trimmed:
        return True
    numeric = _to_float(trimmed)
    if numeric is not None and "." in trimmed:
        return record.level_value is not None and abs(record.level_value - numeric) < 0.0001

    normalized = normalize_query(trimmed)
    normalized_level = normalize_query(record.level).rstrip("?")
    is_plus_level = normalized.endswith("+")
    base_level = _to_int(normalized.removesuffix("+"))
    if base_level is not None:
        if normalized_level == normalized:
            return True
        value = record.level_value
        if value is None:
            return False
        if is_plus_level:
            return base_level + 0.6 - 0.0001 <= value <= base_level + 0.9 + 0.0001
        return base_level - 0.0001 <= value <= base_level + 0.5 + 0.0001

    return normalized_level == normalized


def search_matches(record: ChartRecord, query: str) -> bool:
    if not query.strip():
        return True
    normalized = normalize_query(query)
    fields = [
        record.title,
        record.artist,
        record.genre,
        record.charter,
        record.version_label,
        record.map_name,
        record.chart_type,
        record.difficulty_label,
        record.level,
        str(record.song_id),
        record.key,
        "" if record.bpm is None else str(record.bpm),
        "" if record.song_version is None else str(record.song_version),
        "" if record.chart_version is None else str(record.chart_version),
    ] + list(record.aliases)
    normalized_fields = [normalize_query(field) for field in fields]
    if any(normalized in field for field in normalized_fields):
        return True
    aliases = _designer_aliases_for(normalized)
    return any(alias in field for alias in aliases for field in normalized_fields)


def status_matches(code: str, record: ChartRecord) -> bool:
    if code in {"", "all"}:
        return True
    if code == "played":
        return record.played
    if code == "missing":
        return not record.played
    return True


def _difficulty_matches(record: ChartRecord, difficulty_index: int | None) -> bool:
    if difficulty_index is None:
        return True
    return not record.is_utage and record.difficulty_index == difficulty_index


def _constant_matches(record: ChartRecord, minimum: float | None, maximum: float | None) -> bool:
    value = record.level_value
    return not (
        minimum is not None and (value is None or value < minimum)
        or maximum is not None and (value is None or value > maximum)
    )


def _chart_type_matches(record: ChartRecord, chart_type: str) -> bool:
    return chart_type in {"", "all"} or record.chart_type == normalize_song_type(chart_type)


def _score_matches(record: ChartRecord, filters: ChartFilters) -> bool:
    from .player_records import achievement_rank, full_combo_status, full_sync_status

    if filters.achievement_min is not None and (
        not record.played or record.achievements < filters.achievement_min
    ):
        return False
    if filters.achievement_max is not None and (
        not record.played or record.achievements > filters.achievement_max
    ):
        return False
    if filters.rank not in {"", "all"} and (
        not record.played or achievement_rank(record.achievements).value != filters.rank
    ):
        return False
    combo = full_combo_status(record.full_combo)
    if filters.full_combo not in {"", "all"} and (
        combo is None or combo.value != filters.full_combo
    ):
        return False
    sync = full_sync_status(record.full_sync)
    if filters.full_sync not in {"", "all"} and (
        sync is None or sync.value != filters.full_sync
    ):
        return False
    return True


def _sort_records(records: list[ChartRecord], sort: str) -> None:
    if sort == "constant_asc":
        records.sort(key=lambda item: (_const_or(item, 999.0), item.difficulty_index, item.title_norm))
    elif sort == "rating_desc":
        records.sort(
            key=lambda item: (
                item.rating if item.rating is not None else -1,
                _score_or(item, -1.0),
                _const_or(item, -1.0),
                item.title_norm,
            ),
            reverse=True,
        )
    elif sort == "id_asc":
        records.sort(key=lambda item: (item.song_id, item.chart_type, item.difficulty_index))
    elif sort == "version_desc":
        records.sort(
            key=lambda item: (
                _version_sort_value(item.chart_version),
                _version_sort_value(item.song_version),
                _const_or(item, -1.0),
            ),
            reverse=True,
        )
    elif sort == "version_asc":
        records.sort(
            key=lambda item: (
                _version_sort_value(item.chart_version, high_for_missing=True),
                _version_sort_value(item.song_version, high_for_missing=True),
                _const_or(item, 999.0),
            )
        )
    elif sort == "achievement_asc":
        records.sort(key=lambda item: (_score_or(item, 999.0), -_const_or(item, -1.0), item.title_norm))
    elif sort == "achievement_desc":
        records.sort(key=lambda item: (_score_or(item, -1.0), _const_or(item, -1.0), item.title_norm), reverse=True)
    elif sort == "title_asc":
        records.sort(key=lambda item: (item.title_norm, -_const_or(item, -1.0), item.chart_type, item.difficulty_index))
    elif sort == "title_desc":
        records.sort(key=lambda item: (item.title_norm, _const_or(item, -1.0)), reverse=True)
    else:
        records.sort(key=lambda item: (_const_or(item, -1.0), item.difficulty_index, item.title_norm), reverse=True)


def _chart_record_from_row(
    row: sqlite3.Row,
    score: dict | None,
    aliases: tuple[str, ...],
) -> ChartRecord:
    chart_type = normalize_song_type(row["chart_type"])
    is_utage = bool(row["is_utage"]) or chart_type == "UTAGE"
    return ChartRecord(
        song_id=int(row["song_id"]),
        title=row["title"],
        title_norm=row["title_norm"],
        artist=row["artist"],
        genre=row["genre"],
        song_version=row["song_version"],
        bpm=row["bpm"],
        map_name=row["map_name"],
        jacket_url=row["jacket_url"],
        chart_type=chart_type,
        difficulty_index=int(row["difficulty_index"]),
        difficulty_name=difficulty_name(row["difficulty_index"]),
        level=row["level"],
        level_value=row["level_value"],
        charter=row["charter"],
        chart_version=row["chart_version"],
        chart_version_name=row["chart_version_name"],
        notes_total=row["notes_total"],
        notes_tap=row["notes_tap"],
        notes_hold=row["notes_hold"],
        notes_slide=row["notes_slide"],
        notes_touch=row["notes_touch"],
        notes_break=row["notes_break"],
        is_utage=is_utage,
        aliases=aliases,
        locked=bool(row["locked"]),
        disabled=bool(row["disabled"]),
        achievements=score["achievements"] if score else None,
        dx_score=score["dx_score"] if score else None,
        full_combo=(score["full_combo"] or "") if score else "",
        full_sync=(score["full_sync"] or "") if score else "",
        score_source=(score["source"] or "") if score else "",
        score_updated_at=score["updated_at"] if score else None,
        score_imported_at=score["imported_at"] if score else None,
        play_time=score["play_time"] if score else None,
    )


def _keep_best_score(target: dict, key: tuple, score: dict) -> None:
    current = target.get(key)
    if current is None or _score_sort_tuple(score) > _score_sort_tuple(current):
        target[key] = score


def _score_sort_tuple(score: dict) -> tuple[float, int, float]:
    return (
        float(score["achievements"] or 0),
        int(score["dx_score"] or -1),
        float(score["updated_at"] or 0),
    )


def _latest_song_version(records: list[ChartRecord]) -> int | None:
    values = [record.song_version for record in records if record.song_version]
    return max(values) if values else None


def _latest_named_chart_version(records: list[ChartRecord]) -> int | None:
    values = [
        int(record.chart_version)
        for record in records
        if record.chart_version and record.chart_version_name.strip()
    ]
    return max(values) if values else None


def _database_aliases(conn: sqlite3.Connection) -> dict[int, tuple[str, ...]]:
    result: dict[int, list[str]] = {}
    for row in conn.execute(
        "SELECT song_id, alias FROM song_aliases ORDER BY song_id, alias_norm"
    ):
        result.setdefault(int(row["song_id"]), []).append(str(row["alias"]))
    return {song_id: tuple(aliases) for song_id, aliases in result.items()}


def _version_value(record: ChartRecord) -> int | None:
    return record.song_version or record.chart_version


def _valid_bpm(value: int | None) -> bool:
    return value is not None and 20 <= int(value) <= 400


def _const_or(record: ChartRecord, fallback: float) -> float:
    if record.level_value is None or record.level_value <= 0:
        return fallback
    return float(record.level_value)


def _score_or(record: ChartRecord, fallback: float) -> float:
    return float(record.achievements) if record.achievements is not None else fallback


def _version_sort_value(value: int | None, high_for_missing: bool = False) -> int:
    if value is None:
        return 999999 if high_for_missing else -1
    return int(value)


def _to_float(value: str) -> float | None:
    try:
        return float(value)
    except ValueError:
        return None


def _to_int(value: str) -> int | None:
    try:
        return int(value)
    except ValueError:
        return None


def _designer_aliases_for(query: str) -> set[str]:
    if "沙发太" in query or "safata" in query:
        return {normalize_query("サファ太")}
    if "哈皮" in query or "happy" in query:
        return {normalize_query("はっぴー")}
    if "73" in query or "shichimi" in query or "シチミ" in query or "七味" in query:
        return {
            normalize_query("7.3GHz"),
            normalize_query("シチミッピー"),
            normalize_query("シチミヘルツ"),
            normalize_query("しちみへるつ"),
            normalize_query("超七味星人"),
        }
    return set()
