import sys
import os

if getattr(sys, 'frozen', False):
    data_dir = sys._MEIPASS
else:
    data_dir = os.path.dirname(os.path.abspath(__file__))

from PyQt6.QtGui import QPixmap, QIcon
from PyQt6.QtCore import QSettings, QTimer, Qt, pyqtSignal
from PyQt6.QtGui import QGuiApplication
from PyQt6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QSizePolicy
from qfluentwidgets import (FluentWindow, FluentIcon as FIF,
                            NavigationItemPosition, Theme, setTheme, qconfig)

from i18n import i18n
from ui_home import HomeInterface
from ui_library import LibraryInterface
from ui_overview import OverviewInterface
from ui_tools import ToolsInterface
from ui_settings import SettingsInterface
from ui_about import AboutInterface
from fluentmai_core.runtime_paths import settings_path
from fluentmai_core.window_state import (
    adaptive_minimum_size,
    centered_window_rect,
    clamp_window_rect,
)


class BrandingWidget(QWidget):
    """Logo + title in the navigation sidebar."""

    clicked = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedHeight(48)
        self.layout = QHBoxLayout(self)
        self.layout.setContentsMargins(16, 12, 0, 0)
        self.layout.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignTop)

        self.icon_label = QLabel(self)
        self.icon_label.setStyleSheet("background: transparent; border: none;")
        self.icon_label.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)

        logo_path = os.path.join(data_dir, "assets", "logo.png")
        if not os.path.exists(logo_path):
            logo_path = os.path.join(data_dir, "assets", "logo.jpg")
        if os.path.exists(logo_path):
            pixmap = QPixmap(logo_path)
            scaled = pixmap.scaledToHeight(22, Qt.TransformationMode.SmoothTransformation)
            self.icon_label.setPixmap(scaled)

        self.title_label = QLabel("FluentMai", self)
        self.title_label.setStyleSheet(
            "font-size: 15px; font-weight: bold; color: white; "
            "background: transparent; margin-left: 10px;"
        )
        self.title_label.setWordWrap(False)
        self.title_label.setSizePolicy(QSizePolicy.Policy.Minimum, QSizePolicy.Policy.Preferred)

        self.layout.addWidget(self.icon_label)
        self.layout.addWidget(self.title_label)

    def setSelected(self, selected: bool):
        pass

    def setCompacted(self, compacted: bool):
        pass


class MainWindow(FluentWindow):
    """FluentMai main window — sidebar navigation + stacked pages."""

    def __init__(self):
        super().__init__()

        setTheme(Theme.DARK)
        qconfig.set(qconfig.themeMode, Theme.DARK)

        self.setWindowTitle("FluentMai")
        self.setWindowIcon(QIcon(os.path.join(data_dir, "assets", "logo.ico")))
        settings_file = settings_path()
        settings_file.parent.mkdir(parents=True, exist_ok=True)
        self._window_settings = QSettings(
            str(settings_file),
            QSettings.Format.IniFormat,
        )
        self._screen_signal_connected = False
        self._navigation_wide: bool | None = None

        self.navigationInterface.setReturnButtonVisible(False)
        self.navigationInterface.setExpandWidth(207)

        if hasattr(self.titleBar, 'titleLabel'):
            self.titleBar.titleLabel.hide()
        if hasattr(self.titleBar, 'iconLabel'):
            self.titleBar.iconLabel.hide()

        try:
            nav_panel = self.navigationInterface.panel
            nav_panel.vBoxLayout.removeWidget(nav_panel.menuButton)
            nav_panel.menuButton.hide()
            nav_panel.menuButton.setParent(None)
        except Exception:
            pass

        self.navigationInterface.panel.setMinimumExpandWidth(800)

        self.branding_widget = BrandingWidget(self)
        self.navigationInterface.addWidget(
            routeKey='branding', widget=self.branding_widget,
            onClick=None, position=NavigationItemPosition.TOP
        )

        self.overview_interface = OverviewInterface(self)
        self.import_interface = HomeInterface(self)
        self.library_interface = LibraryInterface(self)
        self.tools_interface = ToolsInterface(self)
        self.settings_interface = SettingsInterface(self)
        self.about_interface = AboutInterface(self)

        self.addSubInterface(self.overview_interface, FIF.HOME, "首页")
        self.addSubInterface(self.import_interface, FIF.SYNC, "导入")
        self.addSubInterface(self.library_interface, FIF.SEARCH, "谱面")
        self.addSubInterface(self.tools_interface, FIF.APPLICATION, "工具")
        self.addSubInterface(
            self.settings_interface, FIF.SETTING, i18n.tr("tab_settings"),
            position=NavigationItemPosition.BOTTOM
        )
        self.addSubInterface(
            self.about_interface, FIF.HELP, i18n.tr("tab_about"),
            position=NavigationItemPosition.BOTTOM
        )

        self.overview_interface.navigationRequested.connect(self._navigate_product_route)
        self.import_interface.dataChanged.connect(self._refresh_product_data)
        self.library_interface.lossRequested.connect(self._open_chart_loss_tool)

        self._restore_window_placement()
        self._sync_navigation_mode()

    def _sync_navigation_mode(self) -> None:
        wide = self.width() >= 960
        if wide == self._navigation_wide:
            return
        self._navigation_wide = wide
        if wide:
            self.navigationInterface.expand(useAni=False)
        elif self.navigationInterface.panel.width() > 48:
            self.navigationInterface.panel.collapse()

    def _navigate_product_route(self, route: str) -> None:
        if route == "import":
            self.switchTo(self.import_interface)
            return
        if route == "charts-played":
            index = self.library_interface.status_combo.findData("played")
            if index >= 0:
                self.library_interface.status_combo.setCurrentIndex(index)
            self.switchTo(self.library_interface)
            return
        if route.startswith("tools-"):
            self.switchTo(self.tools_interface)
            QTimer.singleShot(0, lambda: self.tools_interface.scroll_to(route.removeprefix("tools-")))

    def _refresh_product_data(self) -> None:
        self.overview_interface.refresh()
        self.tools_interface.refresh_data()
        self.library_interface._schedule_query()

    def _open_chart_loss_tool(self, record) -> None:
        self.switchTo(self.tools_interface)
        QTimer.singleShot(0, lambda: self.tools_interface.select_chart_for_loss(record))

    @staticmethod
    def _available_screen_rects():
        primary = QGuiApplication.primaryScreen()
        screens = QGuiApplication.screens()
        ordered = ([primary] if primary is not None else []) + [
            screen for screen in screens if screen is not primary
        ]
        return [screen.availableGeometry() for screen in ordered]

    def _restore_window_placement(self) -> None:
        screen = self.screen() or QGuiApplication.primaryScreen()
        if screen is None:
            self.resize(1180, 760)
            self.setMinimumSize(760, 520)
            return

        available = screen.availableGeometry()
        self.setMinimumSize(adaptive_minimum_size(available))
        saved_geometry = self._window_settings.value("window/geometry")
        restored = bool(saved_geometry) and self.restoreGeometry(saved_geometry)
        if not restored:
            self.setGeometry(centered_window_rect(available))
        self._ensure_window_visible()

        maximized = self._window_settings.value("window/maximized", False, type=bool)
        if maximized:
            self.setWindowState(self.windowState() | Qt.WindowState.WindowMaximized)

    def _ensure_window_visible(self) -> None:
        screen_rects = self._available_screen_rects()
        if not screen_rects:
            return
        target = clamp_window_rect(self.geometry(), screen_rects)
        current_screen = self.screen() or QGuiApplication.primaryScreen()
        if current_screen is not None:
            self.setMinimumSize(adaptive_minimum_size(current_screen.availableGeometry()))
        if not self.isMaximized() and target != self.geometry():
            self.setGeometry(target)

    def _on_screen_changed(self, screen) -> None:
        if screen is not None:
            self.setMinimumSize(adaptive_minimum_size(screen.availableGeometry()))
        QTimer.singleShot(0, self._ensure_window_visible)

    def showEvent(self, event):
        super().showEvent(event)
        handle = self.windowHandle()
        if handle is not None and not self._screen_signal_connected:
            handle.screenChanged.connect(self._on_screen_changed)
            self._screen_signal_connected = True
        self._sync_navigation_mode()
        QTimer.singleShot(0, self._ensure_window_visible)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        QTimer.singleShot(0, self._sync_navigation_mode)

    def closeEvent(self, event):
        if not self.import_interface.prepare_to_close():
            event.ignore()
            return
        self._window_settings.setValue("window/geometry", self.saveGeometry())
        self._window_settings.setValue("window/maximized", self.isMaximized())
        self._window_settings.sync()
        super().closeEvent(event)
