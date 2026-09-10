from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

from PyQt6.QtCore import QPoint, QRect, QSize
from PyQt6.QtTest import QTest
from PyQt6.QtWidgets import QAbstractScrollArea

from fluentmai_core.window_state import (
    adaptive_minimum_size,
    centered_window_rect,
    clamp_window_rect,
)


def test_default_geometry_is_centered_and_inside_work_area():
    available = QRect(0, 0, 1280, 680)
    result = centered_window_rect(available)

    assert available.contains(result)
    assert result.size() == QSize(1180, 648)
    assert result.center().x() == available.center().x()
    assert result.top() == 16


def test_minimum_size_never_exceeds_high_dpi_logical_work_area():
    available = QRect(0, 0, 683, 384)
    minimum = adaptive_minimum_size(available)

    assert minimum.width() <= available.width() - 32
    assert minimum.height() <= available.height() - 32


def test_missing_monitor_geometry_returns_fully_to_primary_screen():
    primary = QRect(0, 0, 1280, 680)
    restored = QRect(3000, 100, 1180, 760)

    result = clamp_window_rect(restored, [primary])

    assert primary.contains(result)
    assert result == QRect(84, 16, 1180, 648)


def test_long_pages_have_one_explicit_primary_scroll_area(qapp, tmp_path, monkeypatch):
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "data"))
    from ui_home import HomeInterface
    from ui_settings import SettingsInterface

    pages = [HomeInterface(), SettingsInterface()]
    try:
        for page in pages:
            assert isinstance(page, QAbstractScrollArea)
            assert page.widgetResizable()
            assert page.minimumSizeHint().height() < 200
            page.resize(640, 320)
            page.show()
            qapp.processEvents()
            scrollbar = page.verticalScrollBar()
            scrollbar.setValue(scrollbar.maximum())
            qapp.processEvents()
            content_bottom = page.view.mapTo(
                page.viewport(), QPoint(0, page.view.height() - 1)
            ).y()
            assert content_bottom <= page.viewport().height()
    finally:
        for page in pages:
            page.close()
            page.deleteLater()


def test_main_shell_fits_under_supported_qt_scale_factors(tmp_path):
    script = r"""
import os, sys
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
os.environ.setdefault('FLUENTMAI_DISABLE_JACKET_NETWORK', '1')
from PyQt6.QtWidgets import QApplication
from ui_main import MainWindow
app = QApplication(sys.argv[:1])
window = MainWindow()
window.show()
app.processEvents()
available = window.screen().availableGeometry()
geometry = window.frameGeometry()
assert window.minimumSizeHint().height() <= window.minimumHeight()
assert geometry.width() <= available.width()
assert geometry.height() <= available.height()
assert window.import_interface.verticalScrollBar().maximum() > 0
window.import_interface._capture_worker = None
window.hide()
"""
    for scale in ("1", "1.25", "1.5", "1.75", "2"):
        env = os.environ.copy()
        env["QT_QPA_PLATFORM"] = "offscreen"
        env["QT_SCALE_FACTOR"] = scale
        env["FLUENTMAI_DATA_DIR"] = str(tmp_path / f"data-{scale}")
        env["FLUENTMAI_DB_PATH"] = str(tmp_path / f"dpi-{scale}.db")
        env["FLUENTMAI_CACHE_DIR"] = str(tmp_path / "cache")
        env["FLUENTMAI_DISABLE_JACKET_NETWORK"] = "1"
        result = subprocess.run(
            [sys.executable, "-c", script],
            cwd=Path(__file__).resolve().parents[1],
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )
        assert result.returncode == 0, result.stdout + result.stderr


def test_wide_navigation_reserves_space_and_narrow_navigation_compacts(qapp, tmp_path, monkeypatch):
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("FLUENTMAI_DB_PATH", str(tmp_path / "window.db"))
    monkeypatch.setenv("FLUENTMAI_DISABLE_JACKET_NETWORK", "1")
    from ui_main import MainWindow

    window = MainWindow()
    window.resize(1180, 760)
    window._navigation_wide = None
    window._sync_navigation_mode()
    assert window._navigation_wide
    assert window.navigationInterface.panel.width() == 207

    window.resize(800, 700)
    window._navigation_wide = None
    window._sync_navigation_mode()
    QTest.qWait(350)
    assert not window._navigation_wide
    assert window.navigationInterface.panel.width() == 48
    window.close()
