from __future__ import annotations

from dataclasses import dataclass
import math
import sqlite3


MAX_RATING_ACHIEVEMENT = 100.5


@dataclass(frozen=True)
class RatedScore:
    identity_key: str
    title: str
    achievement: float
    level_value: float
    chart_version: int
    rating: int
    chart_type: str
    difficulty_index: int
    difficulty_name: str
    dx_score: int | None
    full_combo: str
    full_sync: str


@dataclass(frozen=True)
class BestSet:
    current_version_id: int | None
    old_best: tuple[RatedScore, ...]
    new_best: tuple[RatedScore, ...]
    ineligible_count: int

    @property
    def b35_rating(self) -> int:
        return sum(item.rating for item in self.old_best)

    @property
    def b15_rating(self) -> int:
        return sum(item.rating for item in self.new_best)

    @property
    def rating(self) -> int:
        return self.b35_rating + self.b15_rating


@dataclass(frozen=True)
class RatingSnapshotResult:
    best_set: BestSet
    rating_before: int | None
    history_inserted: bool


def dx_rating_coefficient(achievement: float) -> float:
    thresholds = (
        (100.5, 22.4),
        (100.4999, 22.2),
        (100.0, 21.6),
        (99.9999, 21.4),
        (99.5, 21.1),
        (99.0, 20.8),
        (98.9999, 20.6),
        (98.0, 20.3),
        (97.0, 20.0),
        (96.9999, 17.6),
        (94.0, 16.8),
        (90.0, 15.2),
        (80.0, 13.6),
        (79.9999, 12.8),
        (75.0, 12.0),
        (70.0, 11.2),
        (60.0, 9.6),
        (50.0, 8.0),
        (40.0, 6.4),
        (30.0, 4.8),
        (20.0, 3.2),
        (10.0, 1.6),
    )
    for minimum, coefficient in thresholds:
        if achievement >= minimum:
            return coefficient
    return 0.0


def calculate_dx_rating(level_value: float, achievement: float, combo_flag: str | None = None) -> int:
    del combo_flag
    capped = min(float(achievement), MAX_RATING_ACHIEVEMENT)
    return math.floor(float(level_value) * (capped / 100.0) * dx_rating_coefficient(capped))


def resolve_current_version_id(conn: sqlite3.Connection) -> int | None:
    explicit = conn.execute(
        "SELECT version_id FROM major_versions WHERE version_id > 0 AND TRIM(name) <> '' "
        "ORDER BY version_id DESC LIMIT 1"
    ).fetchone()
    if explicit is not None:
        return int(explicit[0])
    named_chart = conn.execute(
        "SELECT chart_version FROM charts "
        "WHERE chart_version > 0 AND TRIM(COALESCE(chart_version_name, '')) <> '' "
        "ORDER BY chart_version DESC LIMIT 1"
    ).fetchone()
    return int(named_chart[0]) if named_chart is not None else None


def compute_best_set(conn: sqlite3.Connection) -> BestSet:
    current_version_id = resolve_current_version_id(conn)
    rows = conn.execute(
        """
        SELECT
            sr.identity_key, sr.title, sr.achievements,
            sr.chart_type, sr.difficulty_index, sr.difficulty_name,
            sr.dx_score, sr.full_combo, sr.full_sync,
            COALESCE(c.level_value, sr.level_value) AS rating_level_value,
            c.chart_version
        FROM score_records sr
        LEFT JOIN charts c
            ON c.song_id = sr.song_id
            AND c.chart_type = sr.chart_type
            AND c.difficulty_index = sr.difficulty_index
        """
    ).fetchall()
    if current_version_id is None:
        return BestSet(None, (), (), len(rows))

    old: list[RatedScore] = []
    current: list[RatedScore] = []
    ineligible = 0
    for row in rows:
        level_value = row["rating_level_value"]
        chart_version = row["chart_version"]
        if level_value is None or chart_version is None or int(chart_version) <= 0:
            ineligible += 1
            continue
        version = int(chart_version)
        if version > current_version_id:
            ineligible += 1
            continue
        rated = RatedScore(
            identity_key=str(row["identity_key"]),
            title=str(row["title"]),
            achievement=float(row["achievements"]),
            level_value=float(level_value),
            chart_version=version,
            rating=calculate_dx_rating(float(level_value), float(row["achievements"])),
            chart_type=str(row["chart_type"]),
            difficulty_index=int(row["difficulty_index"]),
            difficulty_name=str(row["difficulty_name"]),
            dx_score=int(row["dx_score"]) if row["dx_score"] is not None else None,
            full_combo=str(row["full_combo"] or ""),
            full_sync=str(row["full_sync"] or ""),
        )
        (current if version == current_version_id else old).append(rated)

    def sort_key(item: RatedScore):
        return (-item.rating, -item.achievement, -item.level_value, item.title, item.identity_key)

    return BestSet(
        current_version_id=current_version_id,
        old_best=tuple(sorted(old, key=sort_key)[:35]),
        new_best=tuple(sorted(current, key=sort_key)[:15]),
        ineligible_count=ineligible,
    )


def record_import_snapshot(
    conn: sqlite3.Connection,
    *,
    batch_id: str,
    source: str,
    recorded_at: float,
) -> RatingSnapshotResult:
    best_set = compute_best_set(conn)
    if best_set.current_version_id is None:
        return RatingSnapshotResult(best_set, None, False)

    previous = conn.execute(
        "SELECT rating FROM rating_history ORDER BY recorded_at DESC, id DESC LIMIT 1"
    ).fetchone()
    rating_before = int(previous[0]) if previous is not None else None
    conn.execute(
        """
        INSERT INTO rating_snapshots(
            batch_id, current_version_id, b35_count, b15_count, b35_rating,
            b15_rating, total_rating, ineligible_count, computed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            batch_id,
            best_set.current_version_id,
            len(best_set.old_best),
            len(best_set.new_best),
            best_set.b35_rating,
            best_set.b15_rating,
            best_set.rating,
            best_set.ineligible_count,
            recorded_at,
        ),
    )
    history_inserted = best_set.rating > 0 and rating_before != best_set.rating
    if history_inserted:
        conn.execute(
            """
            INSERT INTO rating_history(
                recorded_at, rating, source, source_batch_id, note, created_at, updated_at
            ) VALUES (?, ?, ?, ?, NULL, ?, ?)
            """,
            (recorded_at, best_set.rating, source, batch_id, recorded_at, recorded_at),
        )
    return RatingSnapshotResult(best_set, rating_before, history_inserted)
