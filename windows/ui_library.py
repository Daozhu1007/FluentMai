from __future__ import annotations

import os

from PyQt6.QtCore import (
    QAbstractListModel,
    QEvent,
    QModelIndex,
    QObject,
    QPoint,
    QRect,
    QRectF,
    QSize,
    Qt,
    QThread,
    QTimer,
    pyqtSignal,
)
from PyQt6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QPen, QPixmap
from PyQt6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListView,
    QScrollArea,
    QSizePolicy,
    QSplitter,
    QStyle,
    QStyledItemDelegate,
    QVBoxLayout,
    QWidget,
)
from qfluentwidgets import BodyLabel, FluentIcon as FIF, InfoBar, InfoBarPosition, PushButton, SubtitleLabel

from cover_manager import BulkCoverWorker, resolve_jacket_path
from fluentmai_core import database
from fluentmai_core.aliases import refresh_alias_catalog
from fluentmai_core.catalog import safe_api_error, sync_diving_fish_catalog, sync_lxns_catalog
from fluentmai_core.chart_browser import (
    DIFFICULTY_OPTIONS,
    SORT_OPTIONS,
    STATUS_OPTIONS,
    CatalogStats,
    ChartFilterOptions,
    ChartFilters,
    ChartQueryResult,
    ChartRecord,
    DISPLAY_LIMIT,
    FilterOption,
    catalog_stats,
    filter_options_for_records,
    load_chart_records,
    query_chart_records,
)
from fluentmai_core.player_records import AchievementRank, FullComboStatus, FullSyncStatus
from fluentmai_core.rating import resolve_current_version_id
from ui_tokens import (
    BODY_PX,
    CARD_PADDING,
    CARD_RADIUS,
    CONTROL_HEIGHT,
    PAGE_GAP,
    PAGE_MARGIN_X,
    PAGE_MARGIN_Y,
    PAGE_MAX_WIDTH,
    SECONDARY_PX,
    TITLE_PX,
)


FILTER_COMPACT_WIDTH = 860
DETAIL_HIDE_WIDTH = 980
RESULT_MIN_WIDTH = 430
DETAIL_MIN_WIDTH = 340
DETAIL_MAX_WIDTH = 460
CARD_MIN_WIDTH = 390
CARD_MAX_WIDTH = 500
CARD_HEIGHT = 188
JACKET_SIZE = 104


class OpaqueComboBox(QComboBox):
    def showPopup(self) -> None:
        super().showPopup()
        view = self.view()
        if view is not None:
            view.setAutoFillBackground(True)
            view.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
            view.viewport().setAutoFillBackground(True)
            view.viewport().setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)


class ChartQueryWorker(QThread):
    loaded = pyqtSignal(int, object, object, object)
    failed = pyqtSignal(int, str)

    def __init__(self, sequence: int, filters: ChartFilters, parent=None):
        super().__init__(parent)
        self.sequence = sequence
        self.filters = filters

    def run(self) -> None:
        try:
            conn = database.connect()
            try:
                records = load_chart_records(conn)
                result = query_chart_records(
                    records,
                    self.filters,
                    current_version_id=resolve_current_version_id(conn),
                )
                options = filter_options_for_records(records)
                stats = catalog_stats(conn)
            finally:
                conn.close()
            self.loaded.emit(self.sequence, result, options, stats)
        except Exception as exc:
            self.failed.emit(self.sequence, safe_api_error(exc))


class CatalogRefreshWorker(QThread):
    finished_with_message = pyqtSignal(bool, str)

    def run(self) -> None:
        try:
            count = sync_lxns_catalog()
            conn = database.connect()
            try:
                alias_songs, alias_count = refresh_alias_catalog(conn)
            except Exception as alias_exc:
                self.finished_with_message.emit(
                    True,
                    f"LXNS 曲库已刷新：{count} 首歌曲；别名保留旧缓存（{safe_api_error(alias_exc)}）",
                )
                return
            finally:
                conn.close()
            self.finished_with_message.emit(True, f"LXNS 曲库已刷新：{count} 首歌曲；别名 {alias_songs} 首 / {alias_count} 条")
            return
        except Exception as exc:
            first_error = safe_api_error(exc)

        try:
            count = sync_diving_fish_catalog(replace=True)
            self.finished_with_message.emit(True, f"Diving-Fish 回退曲库已刷新：{count} 首歌曲；LXNS 失败：{first_error}")
        except Exception as exc:
            self.finished_with_message.emit(False, f"LXNS 失败：{first_error}；Diving-Fish 失败：{safe_api_error(exc)}")


class JacketDownloadCoordinator(QObject):
    jacket_ready = pyqtSignal(int, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._queued: dict[int, str] = {}
        self._failed: set[int] = set()
        self._active_ids: set[int] = set()
        self._completed_ids: set[int] = set()
        self._worker: BulkCoverWorker | None = None
        self._flush_timer = QTimer(self)
        self._flush_timer.setInterval(120)
        self._flush_timer.setSingleShot(True)
        self._flush_timer.timeout.connect(self._start_next_batch)

    def request(self, song_id: int, jacket_url: str = "") -> None:
        if not song_id:
            return
        existing = resolve_jacket_path(song_id, jacket_url)
        if existing:
            QTimer.singleShot(0, lambda sid=song_id, path=existing: self.jacket_ready.emit(sid, path))
            return
        if os.environ.get("FLUENTMAI_DISABLE_JACKET_NETWORK") == "1":
            return
        if song_id in self._queued or song_id in self._active_ids or song_id in self._failed:
            return
        self._queued[song_id] = jacket_url
        if not self._flush_timer.isActive():
            self._flush_timer.start()

    def _start_next_batch(self) -> None:
        if self._worker is not None and self._worker.isRunning():
            return
        if not self._queued:
            return

        items = list(self._queued.items())[:8]
        for song_id, _jacket_url in items:
            self._queued.pop(song_id, None)
        self._active_ids = {song_id for song_id, _jacket_url in items}
        self._completed_ids = set()

        self._worker = BulkCoverWorker([(song_id, jacket_url) for song_id, jacket_url in items], self)
        self._worker.cover_done.connect(self._on_cover_done)
        self._worker.all_done.connect(self._on_batch_done)
        self._worker.start()

    def _on_cover_done(self, song_id: int, path: str) -> None:
        self._completed_ids.add(song_id)
        self._failed.discard(song_id)
        self.jacket_ready.emit(song_id, path)

    def _on_batch_done(self) -> None:
        self._failed.update(self._active_ids - self._completed_ids)
        if self._worker is not None:
            self._worker.deleteLater()
        self._worker = None
        self._active_ids = set()
        self._completed_ids = set()
        if self._queued:
            self._start_next_batch()


class ChartListModel(QAbstractListModel):
    RECORD_ROLE = Qt.ItemDataRole.UserRole + 1

    def __init__(self, parent=None):
        super().__init__(parent)
        self._records: list[ChartRecord] = []

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self._records)

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or not 0 <= index.row() < len(self._records):
            return None
        record = self._records[index.row()]
        if role == Qt.ItemDataRole.DisplayRole:
            return record.title
        if role == self.RECORD_ROLE:
            return record
        return None

    def set_records(self, records: list[ChartRecord]) -> None:
        self.beginResetModel()
        self._records = records
        self.endResetModel()

    def record_at(self, row: int) -> ChartRecord | None:
        if 0 <= row < len(self._records):
            return self._records[row]
        return None


class ChartCardDelegate(QStyledItemDelegate):
    jacket_requested = pyqtSignal(int, str)

    _difficulty_colors = {
        0: QColor("#2f9e44"),
        1: QColor("#d9480f"),
        2: QColor("#e03131"),
        3: QColor("#7c3aed"),
        4: QColor("#f3e8ff"),
    }

    def __init__(self, parent=None):
        super().__init__(parent)
        self._pixmap_cache: dict[tuple[int, str], QPixmap | None] = {}
        self._requested: set[tuple[int, str]] = set()

    def sizeHint(self, option, index: QModelIndex) -> QSize:
        return QSize(CARD_MIN_WIDTH, CARD_HEIGHT)

    def paint(self, painter: QPainter, option, index: QModelIndex) -> None:
        record: ChartRecord | None = index.data(ChartListModel.RECORD_ROLE)
        if record is None:
            return

        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = option.rect.adjusted(5, 5, -5, -5)
        selected = bool(option.state & QStyle.StateFlag.State_Selected)
        hovered = bool(option.state & QStyle.StateFlag.State_MouseOver)

        bg = QColor("#211936") if selected else QColor("#171b26" if hovered else "#141821")
        border = QColor("#8b5cf6") if selected else QColor("#2c3345")
        path = QPainterPath()
        path.addRoundedRect(QRectF(rect), CARD_RADIUS, CARD_RADIUS)
        painter.fillPath(path, bg)
        painter.setPen(QPen(border, 1.2))
        painter.drawPath(path)

        image_rect = QRect(rect.left() + 14, rect.top() + 14, JACKET_SIZE, JACKET_SIZE)
        self._draw_jacket(painter, image_rect, record)

        text_left = image_rect.right() + 14
        text_width = max(80, rect.right() - text_left - 14)
        top = rect.top() + 12

        title_font = QFont(option.font)
        title_font.setPixelSize(BODY_PX + 1)
        title_font.setBold(True)
        painter.setFont(title_font)
        painter.setPen(QColor("#f5f7fb"))
        fm = QFontMetrics(title_font)
        painter.drawText(
            QRect(text_left, top, text_width, 24),
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
            fm.elidedText(record.title, Qt.TextElideMode.ElideRight, text_width),
        )

        body_font = QFont(option.font)
        body_font.setPixelSize(SECONDARY_PX)
        painter.setFont(body_font)
        body_fm = QFontMetrics(body_font)
        painter.setPen(QColor("#aab2c4"))
        artist = record.artist or "未知艺术家"
        painter.drawText(
            QRect(text_left, top + 27, text_width, 20),
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
            body_fm.elidedText(artist, Qt.TextElideMode.ElideRight, text_width),
        )

        pill_y = top + 54
        x = text_left
        x = self._draw_pill(painter, QPoint(x, pill_y), record.type_label, QColor("#334155"), QColor("#dbeafe"))
        diff_color = QColor("#64748b") if record.is_utage else self._difficulty_colors.get(record.difficulty_index, QColor("#64748b"))
        diff_text = QColor("#581c87") if record.difficulty_index == 4 and not record.is_utage else QColor("#ffffff")
        diff_border = QColor("#c4b5fd") if record.difficulty_index == 4 and not record.is_utage else None
        x = self._draw_pill(painter, QPoint(x + 6, pill_y), record.difficulty_label, diff_color, diff_text, diff_border)
        level_text = f"{record.level or '--'} / {record.const_label}"
        self._draw_pill(painter, QPoint(x + 6, pill_y), level_text, QColor("#27233b"), QColor("#e9d5ff"))

        meta_lines = [
            f"BPM {record.bpm or '--'}    {record.version_label}",
            f"物量 {record.notes_label}    谱师 {record.charter or '--'}",
        ]
        painter.setFont(body_font)
        for line_index, line in enumerate(meta_lines):
            painter.setPen(QColor("#9ba6bb"))
            painter.drawText(
                QRect(text_left, top + 88 + line_index * 20, text_width, 20),
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                body_fm.elidedText(line, Qt.TextElideMode.ElideRight, text_width),
            )

        status = "已游玩" if record.played else "未游玩"
        score_text = ""
        if record.played:
            score_text = f"{record.achievements:.4f}%"
            if record.dx_score is not None:
                score_text += f"  DX {record.dx_score}"
        painter.setPen(QColor("#7dd3fc") if record.played else QColor("#94a3b8"))
        painter.drawText(
            QRect(image_rect.left(), image_rect.bottom() + 10, image_rect.width(), 22),
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
            status,
        )
        if score_text:
            painter.drawText(
                QRect(text_left, top + 128, text_width, 20),
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                body_fm.elidedText(score_text, Qt.TextElideMode.ElideRight, text_width),
            )
        painter.restore()

    def _draw_jacket(self, painter: QPainter, rect: QRect, record: ChartRecord) -> None:
        painter.save()
        path = QPainterPath()
        path.addRoundedRect(QRectF(rect), 7, 7)
        painter.setClipPath(path)
        pixmap = self._pixmap_for(record)
        if pixmap and not pixmap.isNull():
            scaled = pixmap.scaled(
                rect.size(),
                Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                Qt.TransformationMode.SmoothTransformation,
            )
            x = rect.left() + (rect.width() - scaled.width()) // 2
            y = rect.top() + (rect.height() - scaled.height()) // 2
            painter.drawPixmap(x, y, scaled)
        else:
            painter.fillRect(rect, QColor("#222938"))
            painter.setPen(QColor("#76839a"))
            font = QFont(painter.font())
            font.setPixelSize(SECONDARY_PX - 1)
            font.setBold(False)
            painter.setFont(font)
            painter.drawText(rect, Qt.AlignmentFlag.AlignCenter, "No\nJacket")
            self._request_jacket(record)
        painter.restore()
        painter.setPen(QPen(QColor("#30384c"), 1))
        painter.drawRoundedRect(rect, 7, 7)

    def _pixmap_for(self, record: ChartRecord) -> QPixmap | None:
        key = (record.song_id, record.jacket_url or "")
        if key in self._pixmap_cache:
            return self._pixmap_cache[key]
        path = resolve_jacket_path(record.song_id, record.jacket_url)
        pixmap = QPixmap(path) if path else QPixmap()
        self._pixmap_cache[key] = pixmap if not pixmap.isNull() else None
        return self._pixmap_cache[key]

    def _request_jacket(self, record: ChartRecord) -> None:
        key = (record.song_id, record.jacket_url or "")
        if key in self._requested:
            return
        self._requested.add(key)
        self.jacket_requested.emit(record.song_id, record.jacket_url)

    def mark_jacket_ready(self, song_id: int) -> None:
        for key in [key for key in self._pixmap_cache if key[0] == song_id]:
            self._pixmap_cache.pop(key, None)
        for key in [key for key in self._requested if key[0] == song_id]:
            self._requested.discard(key)

    def _draw_pill(
        self,
        painter: QPainter,
        pos: QPoint,
        text: str,
        bg: QColor,
        fg: QColor,
        border: QColor | None = None,
    ) -> int:
        font = QFont(painter.font())
        font.setPixelSize(SECONDARY_PX - 1)
        font.setBold(True)
        painter.setFont(font)
        fm = QFontMetrics(font)
        width = min(150, fm.horizontalAdvance(text) + 18)
        rect = QRect(pos.x(), pos.y(), width, 24)
        painter.setPen(QPen(border, 1) if border is not None else Qt.PenStyle.NoPen)
        painter.setBrush(bg)
        painter.drawRoundedRect(rect, 6, 6)
        painter.setPen(fg)
        painter.drawText(
            rect.adjusted(8, 0, -8, 0),
            Qt.AlignmentFlag.AlignCenter,
            fm.elidedText(text, Qt.TextElideMode.ElideRight, width - 16),
        )
        return rect.right()


class ChartDetailPanel(QFrame):
    lossRequested = pyqtSignal(object)
    chartChanged = pyqtSignal(object)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("DetailPanel")
        self.setMinimumWidth(DETAIL_MIN_WIDTH)
        self.setMaximumWidth(DETAIL_MAX_WIDTH)
        self._current_record: ChartRecord | None = None

        layout = QVBoxLayout(self)
        layout.setContentsMargins(CARD_PADDING, CARD_PADDING, CARD_PADDING, CARD_PADDING)
        layout.setSpacing(12)

        self.cover_label = QLabel("No\nJacket")
        self.cover_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.cover_label.setFixedSize(204, 204)
        self.cover_label.setStyleSheet(
            "background:#222938; color:#76839a; border:1px solid #30384c; "
            "border-radius:8px; font-size:13px;"
        )
        layout.addWidget(self.cover_label, alignment=Qt.AlignmentFlag.AlignHCenter)

        self.title_label = SubtitleLabel("选择一张谱面")
        self.title_label.setWordWrap(True)
        self.title_label.setStyleSheet("font-size: 20px; font-weight: 700;")
        layout.addWidget(self.title_label)

        self.subtitle_label = BodyLabel("点击左侧卡片查看详情")
        self.subtitle_label.setWordWrap(True)
        self.subtitle_label.setStyleSheet("color:#aab2c4;")
        layout.addWidget(self.subtitle_label)

        self.sibling_combo = QComboBox()
        self.sibling_combo.setAccessibleName("切换同一歌曲的谱面难度")
        self.sibling_combo.currentIndexChanged.connect(self._on_sibling_changed)
        layout.addWidget(self.sibling_combo)

        self.fields: dict[str, BodyLabel] = {}
        for key in (
            "difficulty",
            "identity",
            "aliases",
            "constant",
            "meta",
            "notes",
            "charter",
            "status",
            "score",
            "source",
        ):
            label = BodyLabel("")
            label.setWordWrap(True)
            label.setStyleSheet(f"color:#d7dce8; font-size:{BODY_PX}px; line-height:1.45;")
            self.fields[key] = label
            layout.addWidget(label)
        self.loss_button = PushButton(FIF.CALORIES, "失分和容错")
        self.loss_button.setMinimumHeight(CONTROL_HEIGHT)
        self.loss_button.clicked.connect(
            lambda: self.lossRequested.emit(self._current_record)
            if self._current_record is not None else None
        )
        self.loss_button.hide()
        layout.addWidget(self.loss_button)
        layout.addStretch(1)

    def set_record(
        self,
        record: ChartRecord | None,
        siblings: list[ChartRecord] | None = None,
    ) -> None:
        self._current_record = record
        self.sibling_combo.blockSignals(True)
        self.sibling_combo.clear()
        for sibling in siblings or ([record] if record is not None else []):
            self.sibling_combo.addItem(
                f"{sibling.chart_type} · {sibling.difficulty_label} · {sibling.level} / {sibling.const_label}",
                sibling,
            )
        selected = next(
            (
                index
                for index in range(self.sibling_combo.count())
                if getattr(self.sibling_combo.itemData(index), "key", None) == getattr(record, "key", None)
            ),
            0,
        )
        self.sibling_combo.setCurrentIndex(selected)
        self.sibling_combo.blockSignals(False)
        self.sibling_combo.setVisible(self.sibling_combo.count() > 1)
        if record is None:
            self.cover_label.setPixmap(QPixmap())
            self.cover_label.setText("No\nJacket")
            self.title_label.setText("选择一张谱面")
            self.subtitle_label.setText("点击左侧卡片查看详情")
            for label in self.fields.values():
                label.clear()
            self.loss_button.hide()
            return

        self.title_label.setText(record.title)
        self.subtitle_label.setText(record.artist or "未知艺术家")
        self._set_cover(record)
        self.fields["difficulty"].setText(f"难度：{record.type_label} / {record.difficulty_label}")
        self.fields["identity"].setText(f"歌曲 ID：{record.song_id}    稳定谱面身份：{record.key}")
        self.fields["aliases"].setText(f"别名：{' / '.join(record.aliases) if record.aliases else '--'}")
        self.fields["constant"].setText(f"等级：{record.level or '--'}    定数：{record.const_label}")
        self.fields["meta"].setText(f"BPM：{record.bpm or '--'}    版本：{record.version_label}\n分区：{record.genre or '--'}")
        self.fields["notes"].setText(
            "物量：{total}    Tap {tap} / Hold {hold} / Slide {slide} / Touch {touch} / Break {break_}".format(
                total=record.notes_label,
                tap=record.notes_tap if record.notes_tap is not None else "--",
                hold=record.notes_hold if record.notes_hold is not None else "--",
                slide=record.notes_slide if record.notes_slide is not None else "--",
                touch=record.notes_touch if record.notes_touch is not None else "--",
                break_=record.notes_break if record.notes_break is not None else "--",
            )
        )
        self.fields["charter"].setText(f"谱师：{record.charter or '--'}")
        self.fields["status"].setText(f"游玩状态：{'已游玩' if record.played else '未游玩'}")
        if record.played:
            score = f"达成率：{record.achievements:.4f}%"
            if record.dx_score is not None:
                score += f"    DX Score：{record.dx_score}"
            if record.full_combo:
                score += f"    FC：{record.full_combo.upper()}"
            if record.full_sync:
                score += f"    FS：{record.full_sync.upper()}"
            if record.rating is not None:
                score += f"    Rating：{record.rating}"
            self.fields["score"].setText(score)
            source = record.score_source or "--"
            if record.play_time:
                source += f"    游玩时间：{record.play_time}"
            elif record.score_updated_at:
                source += f"    导入时间：{record.score_updated_at:.0f}"
            self.fields["source"].setText(f"来源：{source}")
        else:
            self.fields["score"].setText("达成率：--    DX Score：--")
            self.fields["source"].setText("来源：--")
        self.loss_button.setVisible(
            all(
                value is not None
                for value in (
                    record.notes_tap,
                    record.notes_hold,
                    record.notes_slide,
                    record.notes_touch,
                    record.notes_break,
                )
            )
        )

    def _on_sibling_changed(self, index: int) -> None:
        record = self.sibling_combo.itemData(index)
        if isinstance(record, ChartRecord) and record.key != getattr(self._current_record, "key", None):
            self.chartChanged.emit(record)

    def _set_cover(self, record: ChartRecord) -> None:
        path = resolve_jacket_path(record.song_id, record.jacket_url)
        if path and self._apply_cover_path(path):
            return
        self.cover_label.setPixmap(QPixmap())
        self.cover_label.setText("No\nJacket")

    def refresh_jacket(self, song_id: int, path: str) -> None:
        if self._current_record and self._current_record.song_id == song_id:
            self._apply_cover_path(path)

    def _apply_cover_path(self, path: str) -> bool:
        pixmap = QPixmap(path)
        if pixmap.isNull():
            return False
        self.cover_label.setText("")
        self.cover_label.setPixmap(
            pixmap.scaled(
                self.cover_label.size(),
                Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                Qt.TransformationMode.SmoothTransformation,
            )
        )
        return True


class LibraryInterface(QWidget):
    lossRequested = pyqtSignal(object)

    def __init__(self, parent=None):
        super().__init__(parent=parent)
        self.setObjectName("LibraryInterface")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self._query_sequence = 0
        self._query_workers: dict[int, ChartQueryWorker] = {}
        self._refresh_worker: CatalogRefreshWorker | None = None
        self._filters_compact: bool | None = None
        self._detail_visible: bool | None = None

        self._debounce = QTimer(self)
        self._debounce.setInterval(180)
        self._debounce.setSingleShot(True)
        self._debounce.timeout.connect(self._start_query)

        outer = QHBoxLayout(self)
        outer.setContentsMargins(PAGE_MARGIN_X, PAGE_MARGIN_Y, PAGE_MARGIN_X, 20)
        outer.setSpacing(0)

        self.content = QWidget(self)
        self.content.setObjectName("LibraryContent")
        self.content.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.content.setMaximumWidth(PAGE_MAX_WIDTH)
        self.content.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.layout = QVBoxLayout(self.content)
        self.layout.setContentsMargins(0, 0, 0, 0)
        self.layout.setSpacing(PAGE_GAP)

        outer.addStretch(1)
        outer.addWidget(self.content, 100)
        outer.addStretch(1)

        self.jacket_queue = JacketDownloadCoordinator(self)
        self.jacket_queue.jacket_ready.connect(self._on_jacket_ready)

        self._build_header()
        self._build_filters()
        self._build_results()
        self._apply_style()

        QTimer.singleShot(0, self._sync_responsive_layout)
        self._schedule_query()

    def _build_header(self) -> None:
        header = QFrame()
        header.setObjectName("HeaderPanel")
        layout = QHBoxLayout(header)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)

        title_box = QVBoxLayout()
        title_box.setSpacing(5)
        title = SubtitleLabel("歌曲与谱面查询")
        title.setStyleSheet(f"font-size: {TITLE_PX}px; font-weight: 800;")
        self.catalog_count_label = BodyLabel("曲库谱面 --")
        self.catalog_count_label.setStyleSheet(f"color:#aab2c4; font-size:{SECONDARY_PX}px;")
        title_box.addWidget(title)
        title_box.addWidget(self.catalog_count_label)

        self.result_count_label = BodyLabel("匹配 --")
        self.result_count_label.setStyleSheet(f"color:#d7dce8; font-size:{BODY_PX}px; font-weight:600;")
        self.refresh_button = PushButton(FIF.UPDATE, "刷新曲库")
        self.refresh_button.setMinimumHeight(CONTROL_HEIGHT)
        self.refresh_button.clicked.connect(self._refresh_catalog)

        layout.addLayout(title_box, 1)
        layout.addWidget(self.result_count_label)
        layout.addWidget(self.refresh_button)
        self.layout.addWidget(header)
        self.player_stats_label = BodyLabel("筛选统计 --")
        self.player_stats_label.setWordWrap(True)
        self.player_stats_label.setStyleSheet("color:#94a3b8; font-size:12px;")
        self.layout.addWidget(self.player_stats_label)

    def _build_filters(self) -> None:
        self.filter_panel = QFrame()
        self.filter_panel.setObjectName("FilterPanel")
        self.filter_grid = QGridLayout(self.filter_panel)
        self.filter_grid.setContentsMargins(0, 0, 0, 0)
        self.filter_grid.setHorizontalSpacing(10)
        self.filter_grid.setVerticalSpacing(10)

        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("曲名 / 别名 / 艺术家 / 谱师 / 版本 / ID")
        self.level_input = QLineEdit()
        self.level_input.setPlaceholderText("等级或定数：13 / 13+ / 13.3")
        self.constant_min_input = QLineEdit()
        self.constant_min_input.setPlaceholderText("如 12.0")
        self.constant_max_input = QLineEdit()
        self.constant_max_input.setPlaceholderText("如 14.9")
        self.achievement_min_input = QLineEdit()
        self.achievement_min_input.setPlaceholderText("如 97.0")
        self.achievement_max_input = QLineEdit()
        self.achievement_max_input.setPlaceholderText("如 100.5")
        self.difficulty_combo = OpaqueComboBox()
        self.genre_combo = OpaqueComboBox()
        self.version_combo = OpaqueComboBox()
        self.status_combo = OpaqueComboBox()
        self.type_combo = OpaqueComboBox()
        self.rank_combo = OpaqueComboBox()
        self.full_combo_combo = OpaqueComboBox()
        self.full_sync_combo = OpaqueComboBox()
        self.sort_combo = OpaqueComboBox()

        for combo in (
            self.difficulty_combo,
            self.genre_combo,
            self.version_combo,
            self.status_combo,
            self.type_combo,
            self.rank_combo,
            self.full_combo_combo,
            self.full_sync_combo,
            self.sort_combo,
        ):
            self._prepare_combo(combo)

        self._set_combo_options(self.difficulty_combo, DIFFICULTY_OPTIONS, "all")
        self._set_combo_options(self.genre_combo, [FilterOption("all", "全部分区")], "all")
        self._set_combo_options(self.version_combo, [FilterOption("all", "全部版本")], "all")
        self._set_combo_options(self.status_combo, STATUS_OPTIONS, "all")
        self._set_combo_options(self.type_combo, [FilterOption("all", "全部类型"), FilterOption("SD", "SD"), FilterOption("DX", "DX"), FilterOption("UTAGE", "宴会场")], "all")
        self._set_combo_options(self.rank_combo, [FilterOption("all", "全部成绩等级")] + [FilterOption(item.value, item.value) for item in AchievementRank], "all")
        self._set_combo_options(self.full_combo_combo, [FilterOption("all", "全部 FC/AP")] + [FilterOption(item.value, item.value) for item in FullComboStatus if item != FullComboStatus.UNKNOWN], "all")
        self._set_combo_options(self.full_sync_combo, [FilterOption("all", "全部 FS")] + [FilterOption(item.value, item.value) for item in FullSyncStatus if item != FullSyncStatus.UNKNOWN], "all")
        self._set_combo_options(self.sort_combo, SORT_OPTIONS, "constant_desc")

        self._filter_items = [
            (self._filter_label("搜索"), self.search_input, 520),
            (self._filter_label("等级 / 定数"), self.level_input, 280),
            (self._filter_label("定数下限"), self.constant_min_input, 220),
            (self._filter_label("定数上限"), self.constant_max_input, 220),
            (self._filter_label("难度"), self.difficulty_combo, 240),
            (self._filter_label("SD / DX"), self.type_combo, 220),
            (self._filter_label("分区"), self.genre_combo, 280),
            (self._filter_label("版本"), self.version_combo, 260),
            (self._filter_label("游玩状态"), self.status_combo, 240),
            (self._filter_label("达成率下限"), self.achievement_min_input, 220),
            (self._filter_label("达成率上限"), self.achievement_max_input, 220),
            (self._filter_label("成绩等级"), self.rank_combo, 220),
            (self._filter_label("FC / AP"), self.full_combo_combo, 220),
            (self._filter_label("FS / FSD"), self.full_sync_combo, 220),
            (self._filter_label("排序"), self.sort_combo, 300),
        ]
        for _label, widget, maximum in self._filter_items:
            widget.setMinimumHeight(CONTROL_HEIGHT)
            widget.setMinimumWidth(150)
            widget.setMaximumWidth(maximum)
            widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

        for widget in (
            self.search_input,
            self.level_input,
            self.constant_min_input,
            self.constant_max_input,
            self.achievement_min_input,
            self.achievement_max_input,
            self.difficulty_combo,
            self.genre_combo,
            self.version_combo,
            self.status_combo,
            self.type_combo,
            self.rank_combo,
            self.full_combo_combo,
            self.full_sync_combo,
            self.sort_combo,
        ):
            if isinstance(widget, QLineEdit):
                widget.textChanged.connect(self._schedule_query)
            else:
                widget.currentIndexChanged.connect(self._schedule_query)

        self.layout.addWidget(self.filter_panel)

    def _prepare_combo(self, combo: QComboBox) -> None:
        view = QListView(combo)
        view.setUniformItemSizes(True)
        view.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel)
        view.viewport().setAutoFillBackground(True)
        view.setStyleSheet(
            """
            QListView {
                background: #171b26;
                color: #eef2ff;
                border: 1px solid #485166;
                border-radius: 8px;
                outline: 0;
                padding: 6px;
                selection-background-color: #3730a3;
                selection-color: #ffffff;
            }
            QListView::item {
                min-height: 34px;
                padding: 7px 10px;
                background: #171b26;
                color: #eef2ff;
            }
            QListView::item:hover {
                background: #243044;
            }
            QListView::item:selected {
                background: #3730a3;
                color: #ffffff;
            }
            """
        )
        combo.setView(view)
        combo.view().setAutoFillBackground(True)
        combo.view().viewport().setAutoFillBackground(True)
        combo.setMaxVisibleItems(8)

    def _relayout_filters(self) -> None:
        if not hasattr(self, "filter_grid"):
            return
        compact = self.content.width() < FILTER_COMPACT_WIDTH
        if compact == self._filters_compact:
            return
        self._filters_compact = compact

        while self.filter_grid.count():
            self.filter_grid.takeAt(0)
        for column in range(4):
            self.filter_grid.setColumnStretch(column, 0)

        if compact:
            for row, (label, widget, _maximum) in enumerate(self._filter_items):
                label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
                widget.setMaximumWidth(16777215)
                self.filter_grid.addWidget(label, row, 0)
                self.filter_grid.addWidget(widget, row, 1)
            self.filter_grid.setColumnMinimumWidth(0, 86)
            self.filter_grid.setColumnStretch(1, 1)
            return

        for index, (label, widget, maximum) in enumerate(self._filter_items):
            row = index // 2
            col = (index % 2) * 2
            label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            widget.setMaximumWidth(maximum)
            self.filter_grid.addWidget(label, row, col)
            self.filter_grid.addWidget(widget, row, col + 1)

        self.filter_grid.setColumnMinimumWidth(0, 76)
        self.filter_grid.setColumnMinimumWidth(2, 76)
        self.filter_grid.setColumnStretch(1, 2)
        self.filter_grid.setColumnStretch(3, 1)

    def _build_results(self) -> None:
        self.splitter = QSplitter(Qt.Orientation.Horizontal)
        self.splitter.setChildrenCollapsible(False)
        self.splitter.setHandleWidth(10)

        self.model = ChartListModel(self)
        self.delegate = ChartCardDelegate(self)
        self.delegate.jacket_requested.connect(self.jacket_queue.request)
        self.result_view = QListView()
        self.result_view.setModel(self.model)
        self.result_view.setItemDelegate(self.delegate)
        self.result_view.setViewMode(QListView.ViewMode.IconMode)
        self.result_view.setResizeMode(QListView.ResizeMode.Adjust)
        self.result_view.setFlow(QListView.Flow.LeftToRight)
        self.result_view.setWrapping(True)
        self.result_view.setUniformItemSizes(True)
        self.result_view.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.result_view.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel)
        self.result_view.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.result_view.setMinimumWidth(RESULT_MIN_WIDTH)
        self.result_view.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.result_view.setMouseTracking(True)
        self.result_view.clicked.connect(self._on_chart_clicked)
        self.result_view.viewport().installEventFilter(self)

        self.detail_panel = ChartDetailPanel()
        self.detail_panel.lossRequested.connect(self.lossRequested)
        self.detail_panel.chartChanged.connect(self._show_detail_record)
        self.detail_scroll = QScrollArea()
        self.detail_scroll.setWidgetResizable(True)
        self.detail_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.detail_scroll.setMinimumWidth(DETAIL_MIN_WIDTH)
        self.detail_scroll.setMaximumWidth(DETAIL_MAX_WIDTH + 20)
        self.detail_scroll.setWidget(self.detail_panel)

        self.splitter.addWidget(self.result_view)
        self.splitter.addWidget(self.detail_scroll)
        self.splitter.setStretchFactor(0, 3)
        self.splitter.setStretchFactor(1, 1)
        self.splitter.setSizes([780, 390])
        self.layout.addWidget(self.splitter, 1)
        QTimer.singleShot(0, self._update_grid_size)

    def _filter_label(self, text: str) -> QLabel:
        label = QLabel(text)
        label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        label.setStyleSheet(f"color:#aab2c4; font-size:{SECONDARY_PX}px;")
        label.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Preferred)
        return label

    def _schedule_query(self) -> None:
        self._debounce.start()

    def _start_query(self) -> None:
        self._query_sequence += 1
        sequence = self._query_sequence
        worker = ChartQueryWorker(sequence, self._current_filters(), self)
        self._query_workers[sequence] = worker
        worker.loaded.connect(self._on_query_loaded)
        worker.failed.connect(self._on_query_failed)
        worker.finished.connect(lambda seq=sequence: self._query_workers.pop(seq, None))
        worker.start()

    def _on_query_loaded(
        self,
        sequence: int,
        result: ChartQueryResult,
        options: ChartFilterOptions,
        stats: CatalogStats,
    ) -> None:
        if sequence != self._query_sequence:
            return
        self._apply_filter_options(options)
        self._apply_result(result, stats)

    def _on_query_failed(self, sequence: int, message: str) -> None:
        if sequence != self._query_sequence:
            return
        self.model.set_records([])
        self.result_count_label.setText("匹配 0")
        self.detail_panel.set_record(None)
        InfoBar.error("曲库查询失败", message, duration=5000, parent=self)

    def _apply_result(self, result: ChartQueryResult, stats: CatalogStats) -> None:
        self.model.set_records(result.records)
        self._update_grid_size()
        catalog_text = f"曲库谱面 {stats.chart_count}"
        if stats.chart_count:
            catalog_text += f"（常规 {stats.regular_chart_count} / 宴会 {stats.utage_count}）"
        if stats.metadata_sparse:
            catalog_text += " · 元数据待刷新"
        self.catalog_count_label.setText(catalog_text)

        if result.is_limited:
            self.result_count_label.setText(
                f"匹配 {result.total_count}，展示 {result.displayed_count} / {result.total_count}（上限 {result.limit}）"
            )
        else:
            self.result_count_label.setText(f"匹配 {result.total_count}")

        player = result.stats
        if player is not None:
            ranks = player.rank_counts
            combos = player.full_combo_counts
            syncs = player.full_sync_counts
            self.player_stats_label.setText(
                f"当前筛选：总谱面 {player.total_charts} · 已游玩 {player.played_charts} · 未游玩 {player.unplayed_charts} · "
                f"SSS+ {ranks.get(AchievementRank.SSS_PLUS, 0)} · SSS {ranks.get(AchievementRank.SSS, 0)} · "
                f"FC {combos.get(FullComboStatus.FC, 0)} · FC+ {combos.get(FullComboStatus.FC_PLUS, 0)} · "
                f"AP {combos.get(FullComboStatus.AP, 0)} · AP+ {combos.get(FullComboStatus.AP_PLUS, 0)} · "
                f"SYNC {syncs.get(FullSyncStatus.SYNC, 0)} · FS {syncs.get(FullSyncStatus.FS, 0)} · "
                f"FS+ {syncs.get(FullSyncStatus.FS_PLUS, 0)} · FSD {syncs.get(FullSyncStatus.FSD, 0)} · "
                f"FSD+ {syncs.get(FullSyncStatus.FSD_PLUS, 0)}"
            )

        if result.records:
            first = self.model.index(0, 0)
            self.result_view.setCurrentIndex(first)
            self._show_detail_record(result.records[0])
        else:
            self.detail_panel.set_record(None)

    def _apply_filter_options(self, options: ChartFilterOptions) -> None:
        self._set_combo_options(self.genre_combo, options.genres, self._combo_code(self.genre_combo))
        self._set_combo_options(self.version_combo, options.versions, self._combo_code(self.version_combo))

    def _current_filters(self) -> ChartFilters:
        diff_code = self._combo_code(self.difficulty_combo)
        difficulty_index = None if diff_code == "all" else int(diff_code)
        return ChartFilters(
            search=self.search_input.text(),
            level=self.level_input.text(),
            difficulty_index=difficulty_index,
            constant_min=self._optional_float(self.constant_min_input),
            constant_max=self._optional_float(self.constant_max_input),
            genre=self._combo_code(self.genre_combo),
            version=self._combo_code(self.version_combo),
            status=self._combo_code(self.status_combo),
            chart_type=self._combo_code(self.type_combo),
            achievement_min=self._optional_float(self.achievement_min_input),
            achievement_max=self._optional_float(self.achievement_max_input),
            rank=self._combo_code(self.rank_combo),
            full_combo=self._combo_code(self.full_combo_combo),
            full_sync=self._combo_code(self.full_sync_combo),
            sort=self._combo_code(self.sort_combo),
            limit=DISPLAY_LIMIT,
        )

    @staticmethod
    def _optional_float(widget: QLineEdit) -> float | None:
        try:
            return float(widget.text().strip()) if widget.text().strip() else None
        except ValueError:
            return None

    def _combo_code(self, combo: QComboBox) -> str:
        data = combo.currentData()
        return str(data) if data is not None else "all"

    def _set_combo_options(self, combo: QComboBox, options: list[FilterOption], current_code: str) -> None:
        combo.blockSignals(True)
        combo.clear()
        valid_codes = {option.code for option in options}
        selected_code = current_code if current_code in valid_codes else options[0].code
        selected_index = 0
        for index, option in enumerate(options):
            combo.addItem(option.label, option.code)
            if option.code == selected_code:
                selected_index = index
        combo.setCurrentIndex(selected_index)
        if combo.view() is not None:
            combo.view().setMinimumWidth(max(combo.minimumWidth(), combo.sizeHint().width()))
        combo.blockSignals(False)

    def _on_chart_clicked(self, index: QModelIndex) -> None:
        record = self.model.record_at(index.row())
        if record:
            self._show_detail_record(record)

    def _show_detail_record(self, record: ChartRecord) -> None:
        conn = database.connect()
        try:
            siblings = load_chart_records(conn, song_id=record.song_id)
        finally:
            conn.close()
        self.detail_panel.set_record(record, siblings)
        self._request_detail_jacket(record)

    def _request_detail_jacket(self, record: ChartRecord) -> None:
        if not resolve_jacket_path(record.song_id, record.jacket_url):
            self.jacket_queue.request(record.song_id, record.jacket_url)

    def _on_jacket_ready(self, song_id: int, path: str) -> None:
        self.delegate.mark_jacket_ready(song_id)
        if hasattr(self, "result_view"):
            self.result_view.viewport().update()
        if hasattr(self, "detail_panel"):
            self.detail_panel.refresh_jacket(song_id, path)

    def _refresh_catalog(self) -> None:
        if self._refresh_worker and self._refresh_worker.isRunning():
            return
        self.refresh_button.setEnabled(False)
        self.refresh_button.setText("刷新中")
        self._refresh_worker = CatalogRefreshWorker(self)
        self._refresh_worker.finished_with_message.connect(self._on_catalog_refreshed)
        self._refresh_worker.start()

    def _on_catalog_refreshed(self, success: bool, message: str) -> None:
        self.refresh_button.setEnabled(True)
        self.refresh_button.setText("刷新曲库")
        if success:
            InfoBar.success("曲库已刷新", message, duration=3500, parent=self, position=InfoBarPosition.TOP_RIGHT)
            self._schedule_query()
        else:
            InfoBar.error("曲库刷新失败", message, duration=7000, parent=self, position=InfoBarPosition.TOP_RIGHT)

    def refresh_for_test(self) -> None:
        conn = database.connect()
        try:
            records = load_chart_records(conn)
            self._apply_filter_options(filter_options_for_records(records))
            self._apply_result(query_chart_records(records, self._current_filters()), catalog_stats(conn))
        finally:
            conn.close()

    def eventFilter(self, watched, event) -> bool:
        if watched is getattr(self, "result_view", None).viewport() and event.type() == QEvent.Type.Resize:
            self._update_grid_size()
        return super().eventFilter(watched, event)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._sync_responsive_layout()

    def _sync_responsive_layout(self) -> None:
        self._relayout_filters()
        self._update_detail_visibility()
        self._update_grid_size()

    def _update_detail_visibility(self) -> None:
        if not hasattr(self, "detail_scroll"):
            return
        show_detail = self.content.width() >= DETAIL_HIDE_WIDTH
        if show_detail == self._detail_visible:
            return
        self._detail_visible = show_detail
        self.detail_scroll.setVisible(show_detail)
        if show_detail:
            self.splitter.setSizes([780, 390])

    def _update_grid_size(self) -> None:
        if not hasattr(self, "result_view"):
            return
        width = max(CARD_MIN_WIDTH, self.result_view.viewport().width() - 18)
        columns = 2 if width >= 840 else 1
        raw_width = (width - (columns - 1) * 12) // columns
        card_width = max(CARD_MIN_WIDTH, min(CARD_MAX_WIDTH, raw_width))
        self.result_view.setGridSize(QSize(card_width, CARD_HEIGHT + 10))
        self.result_view.setSpacing(10)

    def _apply_style(self) -> None:
        self.setStyleSheet(
            """
            QWidget#LibraryInterface, QWidget#LibraryContent {
                background: #0f1117;
                color: #eef2ff;
            }
            QFrame#HeaderPanel, QFrame#FilterPanel {
                background: transparent;
                border: none;
            }
            QLineEdit, QComboBox {
                min-height: 40px;
                border: 1px solid #30384c;
                border-radius: 8px;
                padding: 0 10px;
                background: #151a24;
                color: #eef2ff;
                font-size: 15px;
                selection-background-color: #7c3aed;
            }
            QLineEdit:focus, QComboBox:focus {
                border: 1px solid #8b5cf6;
            }
            QComboBox::drop-down {
                width: 30px;
                border: none;
                background: transparent;
            }
            QComboBox QAbstractItemView {
                background: #171b26;
                color: #eef2ff;
                border: 1px solid #485166;
                border-radius: 8px;
                outline: 0;
                padding: 6px;
                selection-background-color: #3730a3;
                selection-color: #ffffff;
            }
            QComboBox QAbstractItemView::item {
                min-height: 34px;
                padding: 7px 10px;
                background: #171b26;
                color: #eef2ff;
            }
            QComboBox QAbstractItemView::item:hover {
                background: #243044;
            }
            QComboBox QAbstractItemView::item:selected {
                background: #3730a3;
                color: #ffffff;
            }
            QListView {
                background: transparent;
                border: none;
                outline: none;
            }
            QListView::item {
                background: transparent;
            }
            QScrollArea {
                background: transparent;
            }
            QScrollArea > QWidget > QWidget {
                background: transparent;
            }
            QSplitter::handle {
                background: transparent;
            }
            QFrame#DetailPanel {
                background: #141821;
                border: 1px solid #2c3345;
                border-radius: 8px;
            }
            """
        )
