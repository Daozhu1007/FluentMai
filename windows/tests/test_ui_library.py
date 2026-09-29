from __future__ import annotations

import os
import sys

import pytest
from PyQt6.QtWidgets import QApplication

from fluentmai_core import database
from fluentmai_core.models import Chart, Song


@pytest.fixture(scope="session")
def qapp():
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    app = QApplication.instance()
    if app is None:
        app = QApplication(sys.argv[:1])
    return app


def _seed_ui_catalog(db_path: str) -> None:
    conn = database.connect(db_path)
    database.replace_catalog(
        conn,
        [Song(song_id=5001, title="Smoke Song", artist="Smoke Artist", genre="maimai", version=25000, bpm=180, provider="test")],
        [Chart(song_id=5001, chart_type="DX", difficulty_index=3, level="13+", level_value=13.8, charter="Smoke Mapper", chart_version=25000, chart_version_name="DX 2025", notes_total=900)],
    )
    conn.close()


def test_library_page_instantiates_with_empty_database(qapp, tmp_path, monkeypatch):
    monkeypatch.setenv("FLUENTMAI_DB_PATH", str(tmp_path / "empty.db"))
    from ui_library import LibraryInterface

    page = LibraryInterface()
    page._debounce.stop()
    page.refresh_for_test()

    assert page.model.rowCount() == 0
    assert "曲库谱面" in page.catalog_count_label.text()
    page.deleteLater()


def test_library_controls_filter_without_jacket(qapp, tmp_path, monkeypatch):
    db_path = str(tmp_path / "ui.db")
    _seed_ui_catalog(db_path)
    monkeypatch.setenv("FLUENTMAI_DB_PATH", db_path)
    from ui_library import LibraryInterface

    page = LibraryInterface()
    page._debounce.stop()
    page.search_input.setText("Smoke Mapper")
    page.level_input.setText("13+")
    page.difficulty_combo.setCurrentIndex(4)
    page.refresh_for_test()

    assert page.model.rowCount() == 1
    record = page.model.record_at(0)
    assert record is not None
    assert record.title == "Smoke Song"
    assert record.notes_total == 900
    page.detail_panel.set_record(record)
    assert "未游玩" in page.detail_panel.fields["status"].text()
    page.deleteLater()


def test_main_window_can_switch_to_library_page(qapp, tmp_path, monkeypatch):
    monkeypatch.setenv("FLUENTMAI_DB_PATH", str(tmp_path / "window.db"))
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "app-data"))
    from ui_main import MainWindow

    window = MainWindow()
    if hasattr(window, "switchTo"):
        window.switchTo(window.library_interface)
    elif hasattr(window, "stackedWidget"):
        window.stackedWidget.setCurrentWidget(window.library_interface)

    assert window.library_interface.objectName() == "LibraryInterface"
    window.close()
    window.deleteLater()
