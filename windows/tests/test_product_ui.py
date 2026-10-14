from __future__ import annotations

from fluentmai_core import database
from fluentmai_core.models import Chart, MajorVersion, Song, normalize_title, now_ts, score_identity_key


def _seed(db_path: str) -> None:
    conn = database.connect(db_path)
    database.replace_catalog(
        conn,
        [
            Song(song_id=1, title="PANDORA PARADOXXX", artist="削除", genre="maimai", version=19998, bpm=150, provider="fixture"),
            Song(song_id=2, title="Current Song", artist="Artist", genre="maimai", version=25500, bpm=180, provider="fixture"),
            Song(song_id=3, title="Unplayed Song", artist="Artist", genre="maimai", version=25500, bpm=160, provider="fixture"),
        ],
        [
            Chart(song_id=1, chart_type="SD", difficulty_index=3, level="14+", level_value=14.9, charter="Designer", chart_version=19998, chart_version_name="FiNALE", notes_total=210, notes_tap=100, notes_hold=10, notes_slide=10, notes_touch=10, notes_break=10),
            Chart(song_id=2, chart_type="DX", difficulty_index=4, level="14", level_value=14.2, charter="Designer", chart_version=25500, chart_version_name="舞萌DX 2026", notes_total=500, notes_tap=300, notes_hold=50, notes_slide=50, notes_touch=50, notes_break=50),
            Chart(song_id=3, chart_type="DX", difficulty_index=3, level="13", level_value=13.3, charter="Designer", chart_version=25500, chart_version_name="舞萌DX 2026", notes_total=400, notes_tap=250, notes_hold=40, notes_slide=40, notes_touch=40, notes_break=30),
        ],
    )
    database.replace_major_versions(
        conn,
        [MajorVersion(version_id=25500, name="舞萌DX 2026", provider="fixture")],
    )
    timestamp = now_ts()
    for song_id, title, chart_type, difficulty, achievement, fc, fs in (
        (1, "PANDORA PARADOXXX", "SD", 3, 100.0, "ap", "fsd"),
        (2, "Current Song", "DX", 4, 100.5, "app", "fsdp"),
    ):
        key = score_identity_key(title=title, song_type=chart_type, difficulty_index=difficulty, song_id=song_id)
        conn.execute(
            """
            INSERT INTO score_records(
                identity_key, song_id, chart_type, difficulty_index, difficulty_name,
                title, title_norm, achievements, dx_score, rank, full_combo, full_sync,
                play_time, source, source_batch_id, raw_identifier, raw_fingerprint,
                level, level_value, imported_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 3000, '', ?, ?, NULL, 'fixture', 'batch', ?, ?, '14', 14.0, ?, ?)
            """,
            (key, song_id, chart_type, difficulty, ["Basic", "Advanced", "Expert", "Master", "Re:MASTER"][difficulty], title, normalize_title(title), achievement, fc, fs, key, key, timestamp, timestamp),
        )
    conn.commit()
    conn.close()


def test_local_overview_uses_sqlite_rating_stats_and_best_sets(qapp, tmp_path, monkeypatch):
    db_path = str(tmp_path / "overview.db")
    _seed(db_path)
    monkeypatch.setenv("FLUENTMAI_DB_PATH", db_path)
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "data"))
    from ui_overview import OverviewInterface

    page = OverviewInterface()
    page.refresh()
    qapp.processEvents()

    assert page.metric_values["scores"].text() == "2"
    assert page.metric_values["catalog"].text() == "3"
    assert "1/35" in page.metric_values["b35"].text()
    assert "1/15" in page.metric_values["b15"].text()
    assert "已游玩 2" in page.stats_text.text()
    assert page.b35_rows.count() == 1
    assert page.b15_rows.count() == 1
    page.close()


def test_tools_auto_fill_notes_calculate_and_persist_manual_trend(qapp, tmp_path, monkeypatch):
    db_path = str(tmp_path / "tools.db")
    _seed(db_path)
    monkeypatch.setenv("FLUENTMAI_DB_PATH", db_path)
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "data"))
    from ui_tools import ToolsInterface

    page = ToolsInterface()
    page.note_search.setText("PANDORA")
    qapp.processEvents()
    page.note_chart.setCurrentIndex(1)
    page.note_kind.setCurrentIndex(0)  # Tap
    page.note_judgement.setCurrentIndex(3)  # Great
    page.note_occurrences.setValue(1)
    page._calculate_note_loss()
    assert page.note_counts["tap"].value() == 100
    assert "单个判定损失" in page.note_result.text()

    page.trend_rating.setValue(14500)
    page.trend_note.setText("fixture")
    page._save_trend()
    conn = database.connect(db_path)
    rows = database.list_rating_history(conn)
    conn.close()
    assert len(rows) == 1
    assert rows[0]["source"] == "manual"
    assert rows[0]["note"] == "fixture"
    page.close()


def test_main_navigation_is_the_six_product_destinations(qapp, tmp_path, monkeypatch):
    db_path = str(tmp_path / "main.db")
    _seed(db_path)
    monkeypatch.setenv("FLUENTMAI_DB_PATH", db_path)
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("FLUENTMAI_DISABLE_JACKET_NETWORK", "1")
    from ui_main import MainWindow

    window = MainWindow()
    items = window.navigationInterface.panel.items
    labels = [item.widget.text() for item in items.values() if hasattr(item.widget, "text")]
    assert labels == ["首页", "导入", "谱面", "工具", "设置", "关于"]
    assert not hasattr(window, "dashboard_interface")
    assert not hasattr(window, "scores_interface")
    assert window.overview_interface.objectName() == "OverviewInterface"
    assert window.import_interface.objectName() == "HomeInterface"
    window.close()
