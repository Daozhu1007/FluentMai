from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Iterable, Sequence

from .models import Chart, MajorVersion, Song, difficulty_name, normalize_song_type, normalize_title, now_ts
from .runtime_paths import backup_root, database_path, prepare_database_path


SCHEMA_VERSION = 3


def default_db_path() -> str:
    return str(database_path())


def connect(db_path: str | None = None) -> sqlite3.Connection:
    resolved = prepare_database_path(db_path)
    existed = resolved.exists() and resolved.stat().st_size > 0
    conn = sqlite3.connect(resolved)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    try:
        ensure_schema(conn, db_path=resolved, existed=existed)
    except Exception:
        conn.close()
        raise
    return conn


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS songs (
    song_id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    title_norm TEXT NOT NULL,
    artist TEXT,
    genre TEXT,
    version INTEGER,
    bpm INTEGER,
    map TEXT,
    rights TEXT,
    locked INTEGER NOT NULL DEFAULT 0,
    disabled INTEGER NOT NULL DEFAULT 0,
    jacket_url TEXT,
    provider TEXT,
    updated_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_songs_title_norm ON songs(title_norm);

CREATE TABLE IF NOT EXISTS song_aliases (
    song_id INTEGER NOT NULL,
    alias TEXT NOT NULL,
    alias_norm TEXT NOT NULL,
    provider TEXT NOT NULL,
    updated_at REAL NOT NULL,
    PRIMARY KEY (song_id, alias_norm)
);

CREATE INDEX IF NOT EXISTS idx_song_aliases_norm ON song_aliases(alias_norm);

CREATE TABLE IF NOT EXISTS charts (
    song_id INTEGER NOT NULL,
    chart_type TEXT NOT NULL,
    difficulty_index INTEGER NOT NULL,
    difficulty_name TEXT NOT NULL,
    level TEXT,
    level_value REAL,
    charter TEXT,
    chart_version INTEGER,
    chart_version_name TEXT,
    notes_total INTEGER,
    notes_tap INTEGER,
    notes_hold INTEGER,
    notes_slide INTEGER,
    notes_touch INTEGER,
    notes_break INTEGER,
    is_utage INTEGER NOT NULL DEFAULT 0,
    updated_at REAL NOT NULL,
    PRIMARY KEY (song_id, chart_type, difficulty_index),
    FOREIGN KEY (song_id) REFERENCES songs(song_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_charts_lookup ON charts(chart_type, difficulty_index, level);

CREATE TABLE IF NOT EXISTS score_records (
    identity_key TEXT PRIMARY KEY,
    song_id INTEGER,
    chart_type TEXT NOT NULL,
    difficulty_index INTEGER NOT NULL,
    difficulty_name TEXT NOT NULL,
    title TEXT NOT NULL,
    title_norm TEXT NOT NULL,
    achievements REAL NOT NULL,
    dx_score INTEGER,
    rank TEXT,
    full_combo TEXT,
    full_sync TEXT,
    play_time TEXT,
    source TEXT NOT NULL,
    source_batch_id TEXT NOT NULL,
    raw_identifier TEXT,
    raw_fingerprint TEXT,
    level TEXT,
    level_value REAL,
    imported_at REAL NOT NULL,
    updated_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_score_song ON score_records(song_id, chart_type, difficulty_index);
CREATE INDEX IF NOT EXISTS idx_score_title ON score_records(title_norm);
CREATE INDEX IF NOT EXISTS idx_score_source ON score_records(source);

CREATE TABLE IF NOT EXISTS score_record_sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    identity_key TEXT NOT NULL,
    source TEXT NOT NULL,
    raw_identifier TEXT,
    raw_fingerprint TEXT,
    imported_at REAL NOT NULL,
    UNIQUE(identity_key, source, raw_fingerprint)
);

CREATE TABLE IF NOT EXISTS quarantine_records (
    id TEXT PRIMARY KEY,
    source_batch_id TEXT NOT NULL,
    source TEXT NOT NULL,
    reason TEXT NOT NULL,
    difficulty_index INTEGER,
    title TEXT,
    raw_fingerprint TEXT NOT NULL,
    created_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quarantine_batch ON quarantine_records(source_batch_id);

CREATE TABLE IF NOT EXISTS import_batches (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    started_at REAL NOT NULL,
    imported_at REAL NOT NULL,
    fetched_count INTEGER NOT NULL,
    parsed_count INTEGER NOT NULL,
    inserted INTEGER NOT NULL,
    updated INTEGER NOT NULL,
    skipped_duplicate INTEGER NOT NULL,
    quarantined INTEGER NOT NULL,
    rejected INTEGER NOT NULL,
    failed INTEGER NOT NULL,
    message TEXT
);

CREATE TABLE IF NOT EXISTS provider_cache (
    provider TEXT NOT NULL,
    cache_key TEXT NOT NULL,
    body TEXT NOT NULL,
    etag TEXT,
    fetched_at REAL NOT NULL,
    PRIMARY KEY (provider, cache_key)
);

CREATE TABLE IF NOT EXISTS schema_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS major_versions (
    version_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    provider TEXT NOT NULL,
    updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS rating_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recorded_at REAL NOT NULL,
    rating INTEGER NOT NULL,
    source TEXT NOT NULL,
    source_batch_id TEXT,
    note TEXT,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rating_history_recorded ON rating_history(recorded_at, id);

CREATE TABLE IF NOT EXISTS rating_snapshots (
    batch_id TEXT PRIMARY KEY,
    current_version_id INTEGER NOT NULL,
    b35_count INTEGER NOT NULL,
    b15_count INTEGER NOT NULL,
    b35_rating INTEGER NOT NULL,
    b15_rating INTEGER NOT NULL,
    total_rating INTEGER NOT NULL,
    ineligible_count INTEGER NOT NULL,
    computed_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS recommendation_exclusions (
    identity_key TEXT PRIMARY KEY,
    created_at REAL NOT NULL
);
"""


def ensure_schema(
    conn: sqlite3.Connection,
    *,
    db_path: Path | None = None,
    existed: bool = True,
) -> None:
    current_version = int(conn.execute("PRAGMA user_version").fetchone()[0])
    if current_version > SCHEMA_VERSION:
        raise sqlite3.DatabaseError(
            f"Database schema {current_version} is newer than supported schema {SCHEMA_VERSION}."
        )
    if existed and current_version < SCHEMA_VERSION and db_path is not None:
        _backup_before_migration(conn, db_path, current_version)
    conn.executescript(SCHEMA_SQL)
    _migrate_schema(conn)
    bootstrap_legacy_music_data(conn)
    conn.execute(
        "INSERT INTO schema_metadata(key, value) VALUES ('schema_version', ?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (str(SCHEMA_VERSION),),
    )
    conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    conn.commit()


def _migrate_schema(conn: sqlite3.Connection) -> None:
    """Apply additive migrations that CREATE TABLE IF NOT EXISTS cannot express."""

    rating_columns = {
        str(row["name"]) for row in conn.execute("PRAGMA table_info(rating_history)")
    }
    if "note" not in rating_columns:
        conn.execute("ALTER TABLE rating_history ADD COLUMN note TEXT")
    if "updated_at" not in rating_columns:
        conn.execute("ALTER TABLE rating_history ADD COLUMN updated_at REAL")
    conn.execute(
        "UPDATE rating_history SET updated_at = created_at WHERE updated_at IS NULL"
    )


def _backup_before_migration(
    conn: sqlite3.Connection,
    db_path: Path,
    current_version: int,
) -> Path:
    root = backup_root()
    root.mkdir(parents=True, exist_ok=True)
    stamp = int(now_ts() * 1000)
    destination = root / f"{db_path.stem}-schema-{current_version}-{stamp}.db"
    backup = sqlite3.connect(destination)
    try:
        conn.backup(backup)
    finally:
        backup.close()
    return destination


def bootstrap_legacy_music_data(conn: sqlite3.Connection) -> None:
    exists = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='music_data'"
    ).fetchone()
    if not exists:
        return
    has_catalog = conn.execute("SELECT 1 FROM charts LIMIT 1").fetchone()
    if has_catalog:
        return

    now = now_ts()
    rows = conn.execute(
        "SELECT id, title, type, ds_basic, ds_advanced, ds_expert, ds_master, ds_remaster, "
        "level_basic, level_advanced, level_expert, level_master, level_remaster FROM music_data"
    ).fetchall()
    for row in rows:
        song_id = int(row["id"])
        title = row["title"]
        chart_type = normalize_song_type(row["type"])
        conn.execute(
            """
            INSERT INTO songs(song_id, title, title_norm, provider, updated_at)
            VALUES (?, ?, ?, 'diving-fish-legacy', ?)
            ON CONFLICT(song_id) DO UPDATE SET
                title=excluded.title,
                title_norm=excluded.title_norm,
                updated_at=excluded.updated_at
            """,
            (song_id, title, normalize_title(title), now),
        )
        for idx, label in enumerate(("basic", "advanced", "expert", "master", "remaster")):
            level = row[f"level_{label}"]
            ds = row[f"ds_{label}"]
            if level is None and ds is None:
                continue
            conn.execute(
                """
                INSERT INTO charts(
                    song_id, chart_type, difficulty_index, difficulty_name,
                    level, level_value, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(song_id, chart_type, difficulty_index) DO UPDATE SET
                    level=COALESCE(excluded.level, charts.level),
                    level_value=COALESCE(excluded.level_value, charts.level_value),
                    updated_at=excluded.updated_at
                """,
                (song_id, chart_type, idx, difficulty_name(idx), level, ds, now),
            )


def upsert_catalog(
    conn: sqlite3.Connection,
    songs: Sequence[Song],
    charts: Sequence[Chart],
    major_versions: Sequence[MajorVersion] | None = None,
) -> None:
    now = now_ts()
    with conn:
        _upsert_catalog_rows(conn, songs, charts, now)
        if major_versions:
            _upsert_major_version_rows(conn, major_versions, now)


def replace_catalog(
    conn: sqlite3.Connection,
    songs: Sequence[Song],
    charts: Sequence[Chart],
    major_versions: Sequence[MajorVersion] | None = None,
) -> None:
    """Replace song/chart metadata while preserving local score records."""
    now = now_ts()
    with conn:
        conn.execute("DELETE FROM charts")
        conn.execute("DELETE FROM songs")
        _upsert_catalog_rows(conn, songs, charts, now)
        if major_versions is not None:
            conn.execute("DELETE FROM major_versions")
            _upsert_major_version_rows(conn, major_versions, now)


def replace_major_versions(conn: sqlite3.Connection, versions: Sequence[MajorVersion]) -> None:
    now = now_ts()
    with conn:
        conn.execute("DELETE FROM major_versions")
        _upsert_major_version_rows(conn, versions, now)


def _upsert_major_version_rows(
    conn: sqlite3.Connection,
    versions: Sequence[MajorVersion],
    now: float,
) -> None:
    conn.executemany(
        """
        INSERT INTO major_versions(version_id, name, provider, updated_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(version_id) DO UPDATE SET
            name=excluded.name,
            provider=excluded.provider,
            updated_at=excluded.updated_at
        """,
        [
            (version.version_id, version.name.strip(), version.provider, now)
            for version in versions
            if version.version_id > 0 and version.name.strip()
        ],
    )


def _upsert_catalog_rows(
    conn: sqlite3.Connection,
    songs: Sequence[Song],
    charts: Sequence[Chart],
    now: float,
) -> None:
    for song in songs:
        conn.execute(
            """
            INSERT INTO songs(
                song_id, title, title_norm, artist, genre, version, bpm, map, rights,
                locked, disabled, jacket_url, provider, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(song_id) DO UPDATE SET
                title=excluded.title,
                title_norm=excluded.title_norm,
                artist=COALESCE(NULLIF(excluded.artist, ''), songs.artist),
                genre=COALESCE(NULLIF(excluded.genre, ''), songs.genre),
                version=COALESCE(excluded.version, songs.version),
                bpm=COALESCE(excluded.bpm, songs.bpm),
                map=COALESCE(NULLIF(excluded.map, ''), songs.map),
                rights=COALESCE(NULLIF(excluded.rights, ''), songs.rights),
                locked=excluded.locked,
                disabled=excluded.disabled,
                jacket_url=COALESCE(NULLIF(excluded.jacket_url, ''), songs.jacket_url),
                provider=excluded.provider,
                updated_at=excluded.updated_at
            """,
            (
                song.song_id,
                song.title,
                normalize_title(song.title),
                song.artist,
                song.genre,
                song.version,
                song.bpm,
                song.map,
                song.rights,
                int(song.locked),
                int(song.disabled),
                song.jacket_url,
                song.provider,
                now,
            ),
        )
    for chart in charts:
        conn.execute(
            """
            INSERT INTO charts(
                song_id, chart_type, difficulty_index, difficulty_name, level, level_value,
                charter, chart_version, chart_version_name, notes_total, notes_tap,
                notes_hold, notes_slide, notes_touch, notes_break, is_utage, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(song_id, chart_type, difficulty_index) DO UPDATE SET
                difficulty_name=excluded.difficulty_name,
                level=COALESCE(NULLIF(excluded.level, ''), charts.level),
                level_value=COALESCE(excluded.level_value, charts.level_value),
                charter=COALESCE(NULLIF(excluded.charter, ''), charts.charter),
                chart_version=COALESCE(excluded.chart_version, charts.chart_version),
                chart_version_name=COALESCE(NULLIF(excluded.chart_version_name, ''), charts.chart_version_name),
                notes_total=COALESCE(excluded.notes_total, charts.notes_total),
                notes_tap=COALESCE(excluded.notes_tap, charts.notes_tap),
                notes_hold=COALESCE(excluded.notes_hold, charts.notes_hold),
                notes_slide=COALESCE(excluded.notes_slide, charts.notes_slide),
                notes_touch=COALESCE(excluded.notes_touch, charts.notes_touch),
                notes_break=COALESCE(excluded.notes_break, charts.notes_break),
                is_utage=excluded.is_utage,
                updated_at=excluded.updated_at
            """,
            (
                chart.song_id,
                normalize_song_type(chart.chart_type),
                chart.difficulty_index,
                chart.difficulty_name,
                chart.level,
                chart.level_value,
                chart.charter,
                chart.chart_version,
                chart.chart_version_name,
                chart.notes_total,
                chart.notes_tap,
                chart.notes_hold,
                chart.notes_slide,
                chart.notes_touch,
                chart.notes_break,
                int(chart.is_utage),
                now,
            ),
        )


def resolve_chart(
    conn: sqlite3.Connection,
    *,
    title: str,
    chart_type: str,
    difficulty_index: int,
    level: str | None = None,
) -> sqlite3.Row | None:
    rows = conn.execute(
        """
        SELECT s.song_id, s.title, c.chart_type, c.difficulty_index, c.level, c.level_value
        FROM songs s
        JOIN charts c ON c.song_id = s.song_id
        WHERE s.title_norm = ? AND c.chart_type = ? AND c.difficulty_index = ?
        """,
        (normalize_title(title), normalize_song_type(chart_type), difficulty_index),
    ).fetchall()
    if not rows:
        return None
    if len(rows) == 1 or not level:
        return rows[0]
    wanted = level.strip().upper()
    for row in rows:
        if (row["level"] or "").strip().upper() == wanted:
            return row
    return rows[0]


def search_songs(conn: sqlite3.Connection, query: str = "", limit: int = 200) -> list[sqlite3.Row]:
    params: list[object] = []
    where = ""
    q = query.strip()
    if q:
        if q.isdigit():
            where = "WHERE s.song_id = ? OR s.title_norm LIKE ?"
            params.extend([int(q), f"%{normalize_title(q)}%"])
        else:
            where = "WHERE s.title_norm LIKE ? OR COALESCE(s.artist, '') LIKE ?"
            params.extend([f"%{normalize_title(q)}%", f"%{q}%"])
    params.append(limit)
    return conn.execute(
        f"""
        SELECT
            s.song_id, s.title, s.artist, s.genre, s.version, s.bpm, s.disabled,
            COUNT(c.difficulty_index) AS chart_count,
            MAX(sr.achievements) AS best_achievement
        FROM songs s
        LEFT JOIN charts c ON c.song_id = s.song_id
        LEFT JOIN score_records sr ON sr.song_id = s.song_id
        {where}
        GROUP BY s.song_id
        ORDER BY s.title
        LIMIT ?
        """,
        params,
    ).fetchall()


def list_charts_for_song(conn: sqlite3.Connection, song_id: int) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT
            c.*, sr.achievements, sr.dx_score, sr.full_combo, sr.full_sync,
            sr.source, sr.updated_at AS score_updated_at
        FROM charts c
        LEFT JOIN score_records sr
            ON sr.song_id = c.song_id
            AND sr.chart_type = c.chart_type
            AND sr.difficulty_index = c.difficulty_index
        WHERE c.song_id = ?
        ORDER BY c.chart_type DESC, c.difficulty_index
        """,
        (song_id,),
    ).fetchall()


def list_scores(
    conn: sqlite3.Connection,
    *,
    query: str = "",
    source: str = "",
    difficulty_index: int | None = None,
    limit: int = 500,
) -> list[sqlite3.Row]:
    clauses: list[str] = []
    params: list[object] = []
    if query.strip():
        if query.strip().isdigit():
            clauses.append("(song_id = ? OR title_norm LIKE ?)")
            params.extend([int(query.strip()), f"%{normalize_title(query)}%"])
        else:
            clauses.append("title_norm LIKE ?")
            params.append(f"%{normalize_title(query)}%")
    if source.strip():
        clauses.append("source = ?")
        params.append(source.strip())
    if difficulty_index is not None:
        clauses.append("difficulty_index = ?")
        params.append(difficulty_index)
    where = "WHERE " + " AND ".join(clauses) if clauses else ""
    params.append(limit)
    return conn.execute(
        f"""
        SELECT *
        FROM score_records
        {where}
        ORDER BY achievements DESC, title ASC, difficulty_index ASC
        LIMIT ?
        """,
        params,
    ).fetchall()


def count_quarantine(conn: sqlite3.Connection) -> int:
    return int(conn.execute("SELECT COUNT(*) FROM quarantine_records").fetchone()[0])


def recent_quarantine(conn: sqlite3.Connection, limit: int = 200) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM quarantine_records ORDER BY created_at DESC LIMIT ?",
        (limit,),
    ).fetchall()


def sources(conn: sqlite3.Connection) -> list[str]:
    return [
        row[0]
        for row in conn.execute(
            "SELECT DISTINCT source FROM score_records ORDER BY source"
        ).fetchall()
    ]


def insert_cache(conn: sqlite3.Connection, provider: str, cache_key: str, body: str, etag: str = "") -> None:
    conn.execute(
        """
        INSERT INTO provider_cache(provider, cache_key, body, etag, fetched_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(provider, cache_key) DO UPDATE SET
            body=excluded.body,
            etag=excluded.etag,
            fetched_at=excluded.fetched_at
        """,
        (provider, cache_key, body, etag, now_ts()),
    )
    conn.commit()


def load_cache(conn: sqlite3.Connection, provider: str, cache_key: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM provider_cache WHERE provider = ? AND cache_key = ?",
        (provider, cache_key),
    ).fetchone()


def iter_score_rows(conn: sqlite3.Connection) -> Iterable[sqlite3.Row]:
    return conn.execute("SELECT * FROM score_records ORDER BY title, chart_type, difficulty_index")


def latest_rating_snapshot(conn: sqlite3.Connection) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM rating_snapshots ORDER BY computed_at DESC, rowid DESC LIMIT 1"
    ).fetchone()


def list_rating_history(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM rating_history ORDER BY recorded_at, id"
    ).fetchall()


def add_manual_rating_history(
    conn: sqlite3.Connection,
    *,
    recorded_at: float,
    rating: int,
    note: str | None = None,
    created_at: float | None = None,
) -> int:
    _validate_manual_rating(rating, note)
    timestamp = now_ts() if created_at is None else float(created_at)
    cursor = conn.execute(
        """
        INSERT INTO rating_history(
            recorded_at, rating, source, source_batch_id, note, created_at, updated_at
        ) VALUES (?, ?, 'manual', NULL, ?, ?, ?)
        """,
        (float(recorded_at), int(rating), _normalize_note(note), timestamp, timestamp),
    )
    conn.commit()
    return int(cursor.lastrowid)


def update_manual_rating_history(
    conn: sqlite3.Connection,
    entry_id: int,
    *,
    recorded_at: float,
    rating: int,
    note: str | None = None,
    updated_at: float | None = None,
) -> bool:
    _validate_manual_rating(rating, note)
    timestamp = now_ts() if updated_at is None else float(updated_at)
    cursor = conn.execute(
        """
        UPDATE rating_history
        SET recorded_at = ?, rating = ?, note = ?, updated_at = ?
        WHERE id = ? AND source = 'manual'
        """,
        (float(recorded_at), int(rating), _normalize_note(note), timestamp, int(entry_id)),
    )
    conn.commit()
    return cursor.rowcount == 1


def delete_manual_rating_history(conn: sqlite3.Connection, entry_id: int) -> bool:
    cursor = conn.execute(
        "DELETE FROM rating_history WHERE id = ? AND source = 'manual'",
        (int(entry_id),),
    )
    conn.commit()
    return cursor.rowcount == 1


def _validate_manual_rating(rating: int, note: str | None) -> None:
    if not 0 <= int(rating) <= 30_000:
        raise ValueError("Rating must be between 0 and 30000")
    if note is not None and len(note.strip()) > 200:
        raise ValueError("Rating history note must not exceed 200 characters")


def _normalize_note(note: str | None) -> str | None:
    normalized = (note or "").strip()
    return normalized or None


def replace_song_aliases(
    conn: sqlite3.Connection,
    aliases_by_song_id: dict[int, Sequence[str]],
    *,
    provider: str,
    updated_at: float | None = None,
) -> tuple[int, int]:
    rows: list[tuple[int, str, str, str, float]] = []
    timestamp = now_ts() if updated_at is None else float(updated_at)
    for song_id, aliases in aliases_by_song_id.items():
        seen: set[str] = set()
        for alias in aliases:
            value = str(alias).strip()
            normalized = normalize_title(value)
            if int(song_id) <= 0 or not value or not normalized or normalized in seen:
                continue
            seen.add(normalized)
            rows.append((int(song_id), value, normalized, provider, timestamp))
    if not rows:
        raise ValueError("Alias catalog is empty")
    with conn:
        conn.execute("DELETE FROM song_aliases")
        conn.executemany(
            """
            INSERT INTO song_aliases(song_id, alias, alias_norm, provider, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            rows,
        )
    return len({row[0] for row in rows}), len(rows)


def list_song_aliases(conn: sqlite3.Connection) -> dict[int, tuple[str, ...]]:
    result: dict[int, list[str]] = {}
    for row in conn.execute(
        "SELECT song_id, alias FROM song_aliases ORDER BY song_id, alias_norm"
    ):
        result.setdefault(int(row["song_id"]), []).append(str(row["alias"]))
    return {song_id: tuple(aliases) for song_id, aliases in result.items()}


def set_recommendation_excluded(
    conn: sqlite3.Connection,
    identity_key: str,
    excluded: bool,
) -> None:
    key = identity_key.strip()
    if not key:
        raise ValueError("identity_key is required")
    if excluded:
        conn.execute(
            """
            INSERT INTO recommendation_exclusions(identity_key, created_at)
            VALUES (?, ?)
            ON CONFLICT(identity_key) DO NOTHING
            """,
            (key, now_ts()),
        )
    else:
        conn.execute(
            "DELETE FROM recommendation_exclusions WHERE identity_key = ?", (key,)
        )
    conn.commit()


def recommendation_exclusions(conn: sqlite3.Connection) -> set[str]:
    return {
        str(row["identity_key"])
        for row in conn.execute("SELECT identity_key FROM recommendation_exclusions")
    }
