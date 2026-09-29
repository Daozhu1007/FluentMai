from __future__ import annotations

import uuid
from typing import Iterable

from . import database
from .rating import record_import_snapshot
from .models import (
    ImportSummary,
    ParsedScoreRecord,
    ScoreRecordDraft,
    as_float,
    as_int,
    difficulty_name,
    normalize_fc,
    normalize_fs,
    normalize_song_type,
    normalize_title,
    now_ts,
    score_identity_key,
    sha256_text,
)


def validate_and_match(
    conn,
    parsed: ParsedScoreRecord,
    source: str,
) -> tuple[ScoreRecordDraft | None, list[str]]:
    reasons: list[str] = []
    title = (parsed.title or "").strip()
    song_type = normalize_song_type(parsed.song_type)
    level_index = as_int(parsed.difficulty_index)
    achievements = as_float(parsed.achievements)
    dx_score = as_int(parsed.dx_score)
    song_id = as_int(parsed.song_id)
    level = (parsed.level or "").strip()

    if not title:
        reasons.append("blank_title")
    if level_index is None or level_index not in range(5):
        reasons.append("invalid_level_index")
    if achievements is None or achievements < 0 or achievements > 101:
        reasons.append("invalid_achievement")

    resolved = None
    if title and level_index is not None and level_index in range(5):
        if song_id is None:
            resolved = database.resolve_chart(
                conn,
                title=title,
                chart_type=song_type,
                difficulty_index=level_index,
                level=level,
            )
            if resolved is not None:
                song_id = resolved["song_id"]
                title = resolved["title"]
                song_type = resolved["chart_type"]
                if not level:
                    level = resolved["level"] or ""
        else:
            resolved = database.resolve_chart(
                conn,
                title=title,
                chart_type=song_type,
                difficulty_index=level_index,
                level=level,
            )

    if reasons:
        return None, sorted(set(reasons))

    identity_key = score_identity_key(
        title=title,
        song_type=song_type,
        difficulty_index=level_index,
        song_id=song_id,
    )
    fingerprint = parsed.raw_fingerprint or sha256_text(
        "|".join(
            [
                title,
                str(song_id or ""),
                song_type,
                str(level_index),
                str(achievements),
                str(dx_score or ""),
                parsed.play_time or "",
            ]
        )
    )
    raw_identifier = parsed.source_record_id or fingerprint[:16]
    return (
        ScoreRecordDraft(
            identity_key=identity_key,
            title=title,
            song_id=song_id,
            chart_type=song_type,
            difficulty_index=level_index,
            level=level,
            level_value=resolved["level_value"] if resolved is not None else None,
            achievements=achievements,
            dx_score=dx_score,
            rank=(parsed.rank or "").strip().lower(),
            full_combo=normalize_fc(parsed.full_combo),
            full_sync=normalize_fs(parsed.full_sync),
            play_time=parsed.play_time,
            source=source,
            raw_identifier=raw_identifier,
            raw_fingerprint=fingerprint,
        ),
        [],
    )


def should_update(existing, draft: ScoreRecordDraft) -> bool:
    old_ach = float(existing["achievements"])
    if draft.achievements > old_ach + 0.00005:
        return True
    if abs(draft.achievements - old_ach) <= 0.00005:
        old_dx = existing["dx_score"]
        if draft.dx_score is not None and (old_dx is None or draft.dx_score > int(old_dx)):
            return True
        for attr, column in (
            ("rank", "rank"),
            ("full_combo", "full_combo"),
            ("full_sync", "full_sync"),
            ("play_time", "play_time"),
            ("level", "level"),
        ):
            if getattr(draft, attr) and not (existing[column] or ""):
                return True
        if draft.song_id is not None and existing["song_id"] is None:
            return True
    return False


def import_parsed_records(
    records: Iterable[ParsedScoreRecord],
    *,
    source: str,
    db_path: str | None = None,
    fetched_count: int | None = None,
    message: str = "",
) -> ImportSummary:
    parsed = list(records)
    batch_id = str(uuid.uuid4())
    started_at = now_ts()
    imported_at = started_at
    inserted = 0
    updated = 0
    skipped = 0
    quarantined = 0
    rejected = 0
    failed = 0
    seen_in_batch: set[str] = set()

    conn = database.connect(db_path)
    try:
        with conn:
            for index, item in enumerate(parsed):
                draft, reasons = validate_and_match(conn, item, source)
                if draft is None:
                    quarantined += 1
                    fp = item.raw_fingerprint or sha256_text(f"{source}|{index}|{item}")
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO quarantine_records(
                            id, source_batch_id, source, reason, difficulty_index,
                            title, raw_fingerprint, created_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            f"quarantine-{batch_id}-{index}-{fp[:16]}",
                            batch_id,
                            source,
                            "|".join(reasons),
                            item.difficulty_index,
                            item.title,
                            fp,
                            imported_at,
                        ),
                    )
                    continue

                if draft.identity_key in seen_in_batch:
                    skipped += 1
                    continue
                seen_in_batch.add(draft.identity_key)

                existing = conn.execute(
                    "SELECT * FROM score_records WHERE identity_key = ?",
                    (draft.identity_key,),
                ).fetchone()
                if existing is None:
                    conn.execute(
                        """
                        INSERT INTO score_records(
                            identity_key, song_id, chart_type, difficulty_index, difficulty_name,
                            title, title_norm, achievements, dx_score, rank, full_combo, full_sync,
                            play_time, source, source_batch_id, raw_identifier, raw_fingerprint,
                            level, level_value, imported_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        _score_values(draft, batch_id, imported_at),
                    )
                    inserted += 1
                elif should_update(existing, draft):
                    conn.execute(
                        """
                        UPDATE score_records SET
                            song_id = COALESCE(?, song_id),
                            title = ?,
                            title_norm = ?,
                            chart_type = ?,
                            difficulty_index = ?,
                            difficulty_name = ?,
                            achievements = ?,
                            dx_score = COALESCE(?, dx_score),
                            rank = COALESCE(NULLIF(?, ''), rank),
                            full_combo = COALESCE(NULLIF(?, ''), full_combo),
                            full_sync = COALESCE(NULLIF(?, ''), full_sync),
                            play_time = COALESCE(?, play_time),
                            source = ?,
                            source_batch_id = ?,
                            raw_identifier = ?,
                            raw_fingerprint = ?,
                            level = COALESCE(NULLIF(?, ''), level),
                            level_value = COALESCE(?, level_value),
                            updated_at = ?
                        WHERE identity_key = ?
                        """,
                        (
                            draft.song_id,
                            draft.title,
                            normalize_title(draft.title),
                            draft.chart_type,
                            draft.difficulty_index,
                            difficulty_name(draft.difficulty_index),
                            draft.achievements,
                            draft.dx_score,
                            draft.rank,
                            draft.full_combo,
                            draft.full_sync,
                            draft.play_time,
                            draft.source,
                            batch_id,
                            draft.raw_identifier,
                            draft.raw_fingerprint,
                            draft.level,
                            draft.level_value,
                            imported_at,
                            draft.identity_key,
                        ),
                    )
                    updated += 1
                else:
                    skipped += 1

                conn.execute(
                    """
                    INSERT OR IGNORE INTO score_record_sources(
                        identity_key, source, raw_identifier, raw_fingerprint, imported_at
                    )
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        draft.identity_key,
                        draft.source,
                        draft.raw_identifier,
                        draft.raw_fingerprint,
                        imported_at,
                    ),
                )

            rating_snapshot = record_import_snapshot(
                conn,
                batch_id=batch_id,
                source=source,
                recorded_at=imported_at,
            )
            best_set = rating_snapshot.best_set
            summary = ImportSummary(
                batch_id=batch_id,
                source=source,
                fetched_count=fetched_count if fetched_count is not None else len(parsed),
                parsed_count=len(parsed),
                inserted=inserted,
                updated=updated,
                skipped_duplicate=skipped,
                quarantined=quarantined,
                rejected=rejected,
                failed=failed,
                message=message,
                current_version_id=best_set.current_version_id,
                b35_count=len(best_set.old_best),
                b15_count=len(best_set.new_best),
                b35_rating=best_set.b35_rating,
                b15_rating=best_set.b15_rating,
                rating_before=rating_snapshot.rating_before,
                rating_after=best_set.rating if best_set.current_version_id is not None else None,
            )
            conn.execute(
                """
                INSERT INTO import_batches(
                    id, source, started_at, imported_at, fetched_count, parsed_count,
                    inserted, updated, skipped_duplicate, quarantined, rejected, failed, message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    summary.batch_id,
                    summary.source,
                    started_at,
                    imported_at,
                    summary.fetched_count,
                    summary.parsed_count,
                    summary.inserted,
                    summary.updated,
                    summary.skipped_duplicate,
                    summary.quarantined,
                    summary.rejected,
                    summary.failed,
                    summary.message,
                ),
            )
            return summary
    finally:
        conn.close()


def _score_values(draft: ScoreRecordDraft, batch_id: str, imported_at: float) -> tuple:
    return (
        draft.identity_key,
        draft.song_id,
        draft.chart_type,
        draft.difficulty_index,
        difficulty_name(draft.difficulty_index),
        draft.title,
        normalize_title(draft.title),
        draft.achievements,
        draft.dx_score,
        draft.rank,
        draft.full_combo,
        draft.full_sync,
        draft.play_time,
        draft.source,
        batch_id,
        draft.raw_identifier,
        draft.raw_fingerprint,
        draft.level,
        draft.level_value,
        imported_at,
        imported_at,
    )
