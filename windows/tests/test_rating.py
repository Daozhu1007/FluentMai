from __future__ import annotations

import csv
from pathlib import Path

from fluentmai_core import database
from fluentmai_core.import_pipeline import import_parsed_records
from fluentmai_core.models import Chart, MajorVersion, ParsedScoreRecord, Song
from fluentmai_core.rating import calculate_dx_rating, compute_best_set, dx_rating_coefficient


def test_dx_rating_matches_android_boundary_golden_values():
    assert calculate_dx_rating(13.5, 100.6217) == 303
    assert calculate_dx_rating(14.9, 100.4999) == 332
    assert calculate_dx_rating(14.9, 100.4567) == 323
    assert calculate_dx_rating(14.9, 99.9999) == 318
    assert calculate_dx_rating(14.9, 96.9999) == 254
    assert calculate_dx_rating(14.9, 79.9999) == 152
    assert dx_rating_coefficient(10.0) == 1.6
    assert dx_rating_coefficient(9.9999) == 0.0


def _seed_versioned_scores(db_path):
    conn = database.connect(db_path)
    songs = []
    charts = []
    records = []
    fixture = Path(__file__).resolve().parents[2] / "test-fixtures" / "b50" / "future-version.tsv"
    with fixture.open(encoding="utf-8", newline="") as handle:
        specs = [
            (row["bucket"], int(row["chart_version"]), int(row["count"]), float(row["level_value"]))
            for row in csv.DictReader(handle, delimiter="\t")
        ]
    song_id = 1
    for prefix, version, count, level_value in specs:
        for index in range(count):
            title = f"{prefix}-{index:02d}"
            songs.append(Song(song_id=song_id, title=title, version=version, provider="fixture"))
            charts.append(
                Chart(
                    song_id=song_id,
                    chart_type="DX",
                    difficulty_index=3,
                    level="14",
                    level_value=level_value - index / 100,
                    chart_version=version,
                    chart_version_name="Current" if version == 25500 else "Other",
                )
            )
            records.append(
                ParsedScoreRecord(
                    title=title,
                    song_id=song_id,
                    song_type="DX",
                    difficulty_index=3,
                    level="14",
                    achievements=100.5,
                )
            )
            song_id += 1
    database.upsert_catalog(
        conn,
        songs,
        charts,
        [MajorVersion(version_id=25500, name="Current", provider="fixture")],
    )
    conn.close()
    return records


def test_future_catalog_rows_do_not_redefine_b15_and_repeat_does_not_duplicate_trend(tmp_path):
    db_path = str(tmp_path / "rating.db")
    records = _seed_versioned_scores(db_path)

    first = import_parsed_records(records, source="wahlap", db_path=db_path)
    second = import_parsed_records(records, source="wahlap", db_path=db_path)

    assert first.current_version_id == 25500
    assert first.b35_count == 35
    assert first.b15_count == 15
    assert first.rating_after == first.b35_rating + first.b15_rating
    assert first.rating_after == 14789
    assert second.inserted == 0
    assert second.skipped_duplicate == 65
    assert second.rating_before == first.rating_after
    assert second.rating_after == first.rating_after

    conn = database.connect(db_path)
    best_set = compute_best_set(conn)
    assert all(item.chart_version == 25500 for item in best_set.new_best)
    assert all(item.chart_version < 25500 for item in best_set.old_best)
    assert best_set.ineligible_count == 5
    assert len(database.list_rating_history(conn)) == 1
    assert database.latest_rating_snapshot(conn)["total_rating"] == first.rating_after
    conn.close()


def test_missing_version_metadata_fails_closed_without_rating_history(tmp_path):
    db_path = str(tmp_path / "missing-version.db")
    conn = database.connect(db_path)
    database.upsert_catalog(
        conn,
        [Song(song_id=1, title="Unknown Version", provider="fixture")],
        [Chart(song_id=1, chart_type="DX", difficulty_index=3, level_value=13.5)],
    )
    conn.close()

    summary = import_parsed_records(
        [
            ParsedScoreRecord(
                title="Unknown Version",
                song_id=1,
                song_type="DX",
                difficulty_index=3,
                achievements=100.5,
            )
        ],
        source="wahlap",
        db_path=db_path,
    )

    assert summary.current_version_id is None
    assert summary.rating_after is None
    conn = database.connect(db_path)
    assert database.list_rating_history(conn) == []
    assert database.latest_rating_snapshot(conn) is None
    conn.close()
