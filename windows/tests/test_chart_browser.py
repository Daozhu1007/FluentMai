from __future__ import annotations

from fluentmai_core import database
from fluentmai_core.chart_browser import ChartFilters, catalog_stats, query_charts
from fluentmai_core.models import Chart, MajorVersion, Song, normalize_title, now_ts, score_identity_key


def _open_seeded_db(tmp_path):
    db_path = str(tmp_path / "charts.db")
    conn = database.connect(db_path)
    database.replace_catalog(
        conn,
        [
            Song(song_id=100, title="Alpha Song", artist="Artist A", genre="maimai", version=25000, bpm=180, provider="test"),
            Song(song_id=101, title="Alpha Song", artist="Artist B", genre="maimai", version=24000, bpm=181, provider="test"),
            Song(song_id=102, title="Beta Tune", artist="Beta Artist", genre="オンゲキCHUNITHM", version=24000, bpm=160, provider="test"),
            Song(song_id=103, title="Gamma Vocal", artist="Vocal P", genre="niconicoボーカロイド", version=23000, bpm=150, provider="test"),
            Song(song_id=104, title="Utage Song", artist="Party", genre="宴会場", version=24500, bpm=200, provider="test"),
        ],
        [
            Chart(song_id=100, chart_type="SD", difficulty_index=3, level="13", level_value=13.3, charter="Mapper A", chart_version=25000, chart_version_name="DX 2025", notes_total=700),
            Chart(song_id=100, chart_type="DX", difficulty_index=3, level="13+", level_value=13.8, charter="Mapper DX", chart_version=25000, chart_version_name="DX 2025", notes_total=900),
            Chart(song_id=100, chart_type="DX", difficulty_index=4, level="14", level_value=14.2, charter="Mapper Re", chart_version=25000, chart_version_name="DX 2025", notes_total=950),
            Chart(song_id=101, chart_type="SD", difficulty_index=3, level="12+", level_value=12.9, charter="Mapper B", chart_version=24000, chart_version_name="DX 2024", notes_total=650),
            Chart(song_id=102, chart_type="SD", difficulty_index=2, level="11", level_value=11.4, charter="Beta Mapper", chart_version=24000, chart_version_name="DX 2024", notes_total=500),
            Chart(song_id=103, chart_type="DX", difficulty_index=1, level="8+", level_value=8.8, charter="Vocal Mapper", chart_version=23000, chart_version_name="DX 2023", notes_total=320),
            Chart(song_id=104, chart_type="UTAGE", difficulty_index=0, level="14+?", level_value=0.0, charter="", chart_version=24500, chart_version_name="DX 2024", notes_total=1000, is_utage=True),
        ],
    )
    return conn


def _insert_score(
    conn,
    *,
    title: str,
    chart_type: str,
    difficulty_index: int,
    achievements: float,
    song_id: int | None = None,
    dx_score: int | None = 3000,
    source: str = "fixture",
):
    identity_key = score_identity_key(
        title=title,
        song_type=chart_type,
        difficulty_index=difficulty_index,
        song_id=song_id,
    )
    timestamp = now_ts()
    conn.execute(
        """
        INSERT INTO score_records(
            identity_key, song_id, chart_type, difficulty_index, difficulty_name,
            title, title_norm, achievements, dx_score, rank, full_combo, full_sync,
            play_time, source, source_batch_id, raw_identifier, raw_fingerprint,
            level, level_value, imported_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', '', NULL, ?, 'batch', ?, ?, '', NULL, ?, ?)
        """,
        (
            identity_key,
            song_id,
            chart_type,
            difficulty_index,
            ["Basic", "Advanced", "Expert", "Master", "Re:Master"][difficulty_index],
            title,
            normalize_title(title),
            achievements,
            dx_score,
            source,
            identity_key,
            identity_key,
            timestamp,
            timestamp,
        ),
    )
    conn.commit()


def test_query_counts_all_charts_and_maps_fields(tmp_path):
    conn = database.connect(str(tmp_path / "many.db"))
    songs = [
        Song(song_id=index, title=f"Song {index:03}", artist="Artist", genre="maimai", version=25000, bpm=180, provider="test")
        for index in range(1, 230)
    ]
    charts = [
        Chart(song_id=song.song_id, chart_type="DX", difficulty_index=4, level="13", level_value=13.3, charter="Mapper", notes_total=600)
        for song in songs
    ]
    database.replace_catalog(conn, songs, charts)

    stats = catalog_stats(conn)
    result = query_charts(conn, ChartFilters(limit=500))

    assert stats.song_count == 229
    assert stats.chart_count == 229
    assert result.total_count == 229
    assert result.displayed_count == 229
    assert result.records[0].bpm == 180
    assert result.records[0].difficulty_index == 4
    conn.close()


def test_search_matches_title_artist_charter_version_and_id(tmp_path):
    conn = _open_seeded_db(tmp_path)

    assert query_charts(conn, ChartFilters(search="Alpha")).total_count == 4
    assert query_charts(conn, ChartFilters(search="Beta Artist")).total_count == 1
    assert query_charts(conn, ChartFilters(search="Mapper DX")).records[0].song_id == 100
    assert query_charts(conn, ChartFilters(search="DX 2023")).records[0].song_id == 103
    assert query_charts(conn, ChartFilters(search="102")).records[0].title == "Beta Tune"
    conn.close()


def test_level_query_distinguishes_display_level_and_constant(tmp_path):
    conn = _open_seeded_db(tmp_path)

    plain_13 = query_charts(conn, ChartFilters(level="13")).records
    plus_13 = query_charts(conn, ChartFilters(level="13+")).records
    exact_133 = query_charts(conn, ChartFilters(level="13.3")).records

    assert {record.level for record in plain_13} == {"13"}
    assert {record.level for record in plus_13} == {"13+"}
    assert [record.const_label for record in exact_133] == ["13.3"]
    conn.close()


def test_filters_and_sorting_apply_before_display_limit(tmp_path):
    conn = _open_seeded_db(tmp_path)
    _insert_score(conn, title="Alpha Song", song_id=100, chart_type="DX", difficulty_index=3, achievements=99.0)
    _insert_score(conn, title="Beta Tune", song_id=None, chart_type="SD", difficulty_index=2, achievements=97.5)

    assert query_charts(conn, ChartFilters(difficulty_index=3)).total_count == 3
    assert query_charts(conn, ChartFilters(genre="ongeki_chunithm")).records[0].title == "Beta Tune"
    assert query_charts(conn, ChartFilters(version="current")).total_count == 3
    assert query_charts(conn, ChartFilters(status="played")).total_count == 2
    assert query_charts(conn, ChartFilters(status="missing")).total_count == 5

    by_const = query_charts(conn, ChartFilters(sort="constant_desc")).records
    assert by_const[0].level_value == 14.2
    by_version = query_charts(conn, ChartFilters(sort="version_desc")).records
    assert by_version[0].chart_version == 25000
    by_score = query_charts(conn, ChartFilters(sort="achievement_asc")).records
    assert by_score[0].achievements == 97.5
    by_title = query_charts(conn, ChartFilters(sort="title_asc")).records
    assert by_title[0].title == "Alpha Song"
    conn.close()


def test_score_association_does_not_cross_type_difficulty_or_ambiguous_titles(tmp_path):
    conn = _open_seeded_db(tmp_path)
    _insert_score(conn, title="Alpha Song", song_id=100, chart_type="DX", difficulty_index=3, achievements=99.0)
    _insert_score(conn, title="Alpha Song", song_id=None, chart_type="SD", difficulty_index=3, achievements=98.0)
    _insert_score(conn, title="Beta Tune", song_id=None, chart_type="SD", difficulty_index=2, achievements=97.5)

    rows = query_charts(conn, ChartFilters(search="Alpha Song", sort="title_asc")).records
    dx_master = [row for row in rows if row.song_id == 100 and row.chart_type == "DX" and row.difficulty_index == 3][0]
    sd_master_same_title = [row for row in rows if row.song_id == 100 and row.chart_type == "SD"][0]
    re_master = [row for row in rows if row.song_id == 100 and row.difficulty_index == 4][0]
    other_same_title = [row for row in rows if row.song_id == 101][0]
    beta = query_charts(conn, ChartFilters(search="Beta Tune")).records[0]

    assert dx_master.played
    assert not sd_master_same_title.played
    assert not re_master.played
    assert not other_same_title.played
    assert beta.played
    conn.close()


def test_catalog_replace_preserves_local_scores(tmp_path):
    conn = _open_seeded_db(tmp_path)
    _insert_score(conn, title="Beta Tune", song_id=102, chart_type="SD", difficulty_index=2, achievements=97.5)

    database.replace_catalog(
        conn,
        [Song(song_id=102, title="Beta Tune", artist="Beta Artist", genre="maimai", version=25000, bpm=160, provider="refresh")],
        [Chart(song_id=102, chart_type="SD", difficulty_index=2, level="11", level_value=11.4, charter="New Mapper", notes_total=510)],
    )

    assert conn.execute("SELECT COUNT(*) FROM score_records").fetchone()[0] == 1
    row = query_charts(conn, ChartFilters(search="Beta Tune")).records[0]
    assert row.played
    assert row.notes_total == 510
    conn.close()


def test_android_equivalent_score_type_range_alias_and_rating_filters(tmp_path):
    conn = _open_seeded_db(tmp_path)
    database.replace_song_aliases(conn, {100: ["繁體別名", "Alpha DX"]}, provider="fixture")
    _insert_score(conn, title="Alpha Song", song_id=100, chart_type="DX", difficulty_index=3, achievements=100.5, source="fixture")
    _insert_score(conn, title="Beta Tune", song_id=102, chart_type="SD", difficulty_index=2, achievements=97.5, source="fixture")

    assert query_charts(conn, ChartFilters(search="繁体别名")).total_count == 3
    assert query_charts(conn, ChartFilters(chart_type="DX")).total_count == 3
    assert query_charts(conn, ChartFilters(constant_min=14.0, constant_max=14.5)).total_count == 1
    assert query_charts(conn, ChartFilters(achievement_min=100.0)).total_count == 1
    assert query_charts(conn, ChartFilters(rank="SSS+")).total_count == 1
    assert query_charts(conn, ChartFilters(sort="rating_desc")).records[0].song_id == 100
    assert [row.song_id for row in query_charts(conn, ChartFilters(sort="id_asc")).records[:3]] == [100, 100, 100]

    result = query_charts(conn, ChartFilters())
    assert result.stats.total_charts == 7
    assert result.stats.played_charts == 2
    conn.close()


def test_current_version_filter_ignores_future_catalog_batch(tmp_path):
    conn = _open_seeded_db(tmp_path)
    database.replace_major_versions(
        conn,
        [MajorVersion(version_id=25000, name="Current", provider="fixture")],
    )
    database.upsert_catalog(
        conn,
        [Song(song_id=999, title="Future", version=25500, provider="fixture")],
        [Chart(song_id=999, chart_type="DX", difficulty_index=3, level_value=14.0, chart_version=25500, chart_version_name="Future")],
    )

    current = query_charts(conn, ChartFilters(version="current"))
    assert current.total_count == 3
    assert all(record.chart_version == 25000 for record in current.records)
    conn.close()
