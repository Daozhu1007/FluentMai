from __future__ import annotations

from datetime import datetime
import time

from PyQt6.QtCore import QDateTime, Qt
from PyQt6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDateTimeEdit,
    QDoubleSpinBox,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)
from qfluentwidgets import BodyLabel, CardWidget, FluentIcon as FIF, InfoBar, InfoBarPosition, PushButton, ScrollArea, SubtitleLabel

from fluentmai_core import database
from fluentmai_core.chart_browser import ChartRecord, load_chart_records, normalize_query
from fluentmai_core.player_records import PlateKind, calculate_plate_progress
from fluentmai_core.rating import resolve_current_version_id
from fluentmai_core.recommendations import RecommendationFilters, VersionAgeFilter, build_recommendations
from fluentmai_core.tools import KALEID_SCOPE, Judgement, NoteCounts, NoteKind, calculate_achievement, calculate_single_song_rating
from fluentmai_core.version_catalog import KNOWN_VERSIONS


class ToolsInterface(ScrollArea):
    """Android-equivalent local tools with explicit, auditable inputs."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("ToolsInterface")
        self.view = QWidget(self)
        self.layout = QVBoxLayout(self.view)
        self.layout.setContentsMargins(28, 28, 28, 28)
        self.layout.setSpacing(16)
        self.setWidget(self.view)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setStyleSheet("QScrollArea { background:transparent; border:none; }")
        self._records: list[ChartRecord] = []
        self._editing_history_id: int | None = None
        self.section_cards: dict[str, QWidget] = {}

        title = SubtitleLabel("工具")
        title.setStyleSheet("font-size:26px; font-weight:800;")
        self.layout.addWidget(title)
        subtitle = BodyLabel("所有计算只使用本地曲库与成绩；不估计玩家能力，也不虚构缺失数据。")
        subtitle.setStyleSheet("color:#94a3b8;")
        self.layout.addWidget(subtitle)

        self._build_rating()
        self._build_note_loss()
        self._build_trend()
        self._build_recommendations()
        self._build_plate()
        self._build_versions()
        self._build_kaleid()
        self.layout.addStretch(1)
        self.refresh_data()

    def _card(self, key: str, title: str, subtitle: str) -> tuple[CardWidget, QVBoxLayout]:
        card = CardWidget()
        card.setObjectName(f"ToolCard_{key}")
        card.setStyleSheet("CardWidget { background:#151a24; border:1px solid #30384c; border-radius:8px; }")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)
        heading = BodyLabel(title)
        heading.setStyleSheet("color:#f8fafc; font-size:17px; font-weight:750;")
        description = BodyLabel(subtitle)
        description.setWordWrap(True)
        description.setStyleSheet("color:#94a3b8;")
        layout.addWidget(heading)
        layout.addWidget(description)
        self.section_cards[key] = card
        self.layout.addWidget(card)
        return card, layout

    def _build_rating(self) -> None:
        _card, layout = self._card("rating", "单曲 Rating", "与 Android 使用同一系数表、100.5% 上限与向下取整规则。")
        form = QFormLayout()
        self.rating_constant = QDoubleSpinBox()
        self.rating_constant.setRange(0.1, 20.0)
        self.rating_constant.setDecimals(1)
        self.rating_constant.setSingleStep(0.1)
        self.rating_constant.setValue(13.5)
        self.rating_achievement = QDoubleSpinBox()
        self.rating_achievement.setRange(0.0, 101.0)
        self.rating_achievement.setDecimals(4)
        self.rating_achievement.setValue(100.5)
        form.addRow("谱面定数", self.rating_constant)
        form.addRow("达成率 %", self.rating_achievement)
        layout.addLayout(form)
        button = PushButton(FIF.CALORIES, "计算 Rating")
        button.setMinimumHeight(44)
        button.clicked.connect(self._calculate_rating)
        layout.addWidget(button)
        self.rating_result = BodyLabel("等待计算")
        self.rating_result.setStyleSheet("color:#cbd5e1; font-size:15px;")
        layout.addWidget(self.rating_result)

    def _calculate_rating(self) -> None:
        result = calculate_single_song_rating(self.rating_constant.value(), self.rating_achievement.value())
        self.rating_result.setText(
            f"Rating {result.rating} · {result.rank.value} · 系数 {result.coefficient:.1f} · "
            f"计入达成率 {result.capped_achievement:.4f}%"
        )

    def _build_note_loss(self) -> None:
        _card, layout = self._card("loss", "选谱 Note 失分与容错", "默认搜索并选择本地谱面自动填入 Note；曲库缺失时可切换手动模式。")
        self.note_search = QLineEdit()
        self.note_search.setPlaceholderText("搜索曲名、别名、歌曲 ID 或谱师")
        self.note_search.textChanged.connect(self._refresh_note_picker)
        layout.addWidget(self.note_search)
        self.note_chart = QComboBox()
        self.note_chart.currentIndexChanged.connect(self._on_note_chart_changed)
        layout.addWidget(self.note_chart)
        self.note_manual = QCheckBox("手动 Note 模式")
        self.note_manual.toggled.connect(self._set_note_manual)
        layout.addWidget(self.note_manual)

        counts = QHBoxLayout()
        self.note_counts: dict[str, QSpinBox] = {}
        for key, label in (("tap", "Tap"), ("hold", "Hold"), ("slide", "Slide"), ("touch", "Touch"), ("break", "Break")):
            box = QVBoxLayout()
            name = BodyLabel(label)
            name.setStyleSheet("color:#94a3b8; font-size:12px;")
            value = QSpinBox()
            value.setRange(0, 5000)
            value.valueChanged.connect(self._sync_occurrence_limit)
            self.note_counts[key] = value
            box.addWidget(name)
            box.addWidget(value)
            counts.addLayout(box)
        layout.addLayout(counts)

        controls = QHBoxLayout()
        self.note_kind = QComboBox()
        for kind in NoteKind:
            self.note_kind.addItem(kind.value, kind)
        self.note_kind.currentIndexChanged.connect(self._sync_occurrence_limit)
        self.note_judgement = QComboBox()
        for judgement in Judgement:
            self.note_judgement.addItem(judgement.value, judgement)
        self.note_occurrences = QSpinBox()
        self.note_occurrences.setRange(0, 0)
        self.note_target = QDoubleSpinBox()
        self.note_target.setRange(0.0, 101.0)
        self.note_target.setDecimals(4)
        self.note_target.setValue(100.5)
        for label, widget in (("音符", self.note_kind), ("判定", self.note_judgement), ("数量", self.note_occurrences), ("目标 %", self.note_target)):
            column = QVBoxLayout()
            text = BodyLabel(label)
            text.setStyleSheet("color:#94a3b8; font-size:12px;")
            column.addWidget(text)
            column.addWidget(widget)
            controls.addLayout(column)
        layout.addLayout(controls)
        calculate = PushButton(FIF.CALORIES, "计算失分与容错")
        calculate.setMinimumHeight(44)
        calculate.clicked.connect(self._calculate_note_loss)
        layout.addWidget(calculate)
        self.note_result = BodyLabel("请选择含完整 Note 数据的谱面，或启用手动模式。")
        self.note_result.setWordWrap(True)
        self.note_result.setStyleSheet("color:#cbd5e1;")
        layout.addWidget(self.note_result)

    def _refresh_note_picker(self) -> None:
        query = normalize_query(self.note_search.text())
        candidates = [
            record for record in self._records
            if all(value is not None for value in (record.notes_tap, record.notes_hold, record.notes_slide, record.notes_touch, record.notes_break))
            and (
                not query
                or query in normalize_query(" ".join((record.title, record.charter, str(record.song_id), *record.aliases)))
            )
        ][:100]
        current_key = self.note_chart.currentData().key if isinstance(self.note_chart.currentData(), ChartRecord) else None
        self.note_chart.blockSignals(True)
        self.note_chart.clear()
        self.note_chart.addItem("请选择谱面", None)
        for record in candidates:
            self.note_chart.addItem(
                f"{record.title} · {record.chart_type} {record.difficulty_label} · {record.level} ({record.const_label})",
                record,
            )
        index = next((i for i in range(self.note_chart.count()) if getattr(self.note_chart.itemData(i), "key", None) == current_key), 0)
        self.note_chart.setCurrentIndex(index)
        self.note_chart.blockSignals(False)
        self._on_note_chart_changed(index)

    def _on_note_chart_changed(self, _index: int) -> None:
        record = self.note_chart.currentData()
        if not isinstance(record, ChartRecord) or self.note_manual.isChecked():
            return
        for key, value in (
            ("tap", record.notes_tap), ("hold", record.notes_hold), ("slide", record.notes_slide),
            ("touch", record.notes_touch), ("break", record.notes_break),
        ):
            self.note_counts[key].setValue(int(value or 0))
        self.note_result.setText(f"已选择 {record.title} · 稳定谱面身份 {record.key} · Note {record.notes_total or '--'}")

    def _set_note_manual(self, manual: bool) -> None:
        self.note_chart.setEnabled(not manual)
        for value in self.note_counts.values():
            value.setEnabled(manual)
        if not manual:
            self._on_note_chart_changed(self.note_chart.currentIndex())

    def _sync_occurrence_limit(self) -> None:
        kind = self.note_kind.currentData()
        key = {NoteKind.TAP: "tap", NoteKind.HOLD: "hold", NoteKind.SLIDE: "slide", NoteKind.TOUCH: "touch", NoteKind.BREAK: "break"}.get(kind, "tap")
        self.note_occurrences.setMaximum(self.note_counts[key].value())

    def _calculate_note_loss(self) -> None:
        try:
            notes = NoteCounts(
                self.note_counts["tap"].value(), self.note_counts["hold"].value(),
                self.note_counts["slide"].value(), self.note_counts["touch"].value(),
                self.note_counts["break"].value(),
            )
            result = calculate_achievement(notes, self.note_kind.currentData(), self.note_judgement.currentData(), self.note_occurrences.value(), self.note_target.value())
        except ValueError as exc:
            self._error(str(exc))
            return
        self.note_result.setText(
            f"单个判定损失 {result.loss_per_judgement:.8f}% · {result.occurrences} 个后 "
            f"{result.resulting_achievement:.4f}% · 保持 {result.target_achievement:.4f}% 最多容许 "
            f"{result.tolerated_occurrences} 个"
        )

    def _build_trend(self) -> None:
        _card, layout = self._card("trend", "Rating Trend", "自动导入去重；手动记录可补录、编辑和删除，并在重启后保留。")
        editor = QFormLayout()
        self.trend_datetime = QDateTimeEdit(QDateTime.currentDateTime())
        self.trend_datetime.setCalendarPopup(True)
        self.trend_rating = QSpinBox()
        self.trend_rating.setRange(0, 30000)
        self.trend_note = QLineEdit()
        self.trend_note.setMaxLength(200)
        editor.addRow("真实时间", self.trend_datetime)
        editor.addRow("Rating", self.trend_rating)
        editor.addRow("备注（可选）", self.trend_note)
        layout.addLayout(editor)
        actions = QHBoxLayout()
        self.trend_save = PushButton(FIF.SAVE, "添加手动记录")
        self.trend_save.clicked.connect(self._save_trend)
        self.trend_cancel = PushButton(FIF.CANCEL, "取消编辑")
        self.trend_cancel.clicked.connect(self._cancel_trend_edit)
        self.trend_cancel.hide()
        self.trend_range = QComboBox()
        self.trend_range.addItem("最近一个月", 30)
        self.trend_range.addItem("最近三个月", 90)
        self.trend_range.addItem("全部", None)
        self.trend_range.currentIndexChanged.connect(self._refresh_trend)
        actions.addWidget(self.trend_save)
        actions.addWidget(self.trend_cancel)
        actions.addStretch(1)
        actions.addWidget(self.trend_range)
        layout.addLayout(actions)
        self.trend_summary = BodyLabel("暂无记录")
        self.trend_summary.setStyleSheet("color:#cbd5e1;")
        layout.addWidget(self.trend_summary)
        self.trend_rows = QVBoxLayout()
        self.trend_rows.setSpacing(6)
        layout.addLayout(self.trend_rows)

    def _save_trend(self) -> None:
        conn = database.connect()
        try:
            timestamp = self.trend_datetime.dateTime().toSecsSinceEpoch()
            if self._editing_history_id is None:
                database.add_manual_rating_history(conn, recorded_at=timestamp, rating=self.trend_rating.value(), note=self.trend_note.text())
            else:
                database.update_manual_rating_history(conn, self._editing_history_id, recorded_at=timestamp, rating=self.trend_rating.value(), note=self.trend_note.text())
        finally:
            conn.close()
        self._cancel_trend_edit()
        self._refresh_trend()

    def _cancel_trend_edit(self) -> None:
        self._editing_history_id = None
        self.trend_datetime.setDateTime(QDateTime.currentDateTime())
        self.trend_note.clear()
        self.trend_save.setText("添加手动记录")
        self.trend_cancel.hide()

    def _refresh_trend(self) -> None:
        conn = database.connect()
        try:
            rows = database.list_rating_history(conn)
        finally:
            conn.close()
        days = self.trend_range.currentData()
        if days is not None:
            cutoff = time.time() - int(days) * 86400
            visible = [row for row in rows if float(row["recorded_at"]) >= cutoff]
        else:
            visible = rows
        self._clear_layout(self.trend_rows)
        if visible:
            delta = int(visible[-1]["rating"]) - int(visible[0]["rating"])
            self.trend_summary.setText(f"当前 {int(visible[-1]['rating'])} · 范围变化 {delta:+d} · {len(visible)} 个真实时间点")
        else:
            self.trend_summary.setText("当前范围没有 Rating 记录。")
        for row in reversed(visible):
            frame = self._row_frame()
            line = QHBoxLayout(frame)
            line.setContentsMargins(12, 8, 12, 8)
            source = "手动补录" if row["source"] == "manual" else "自动导入"
            text = QLabel(f"{int(row['rating'])} · {source} · {datetime.fromtimestamp(float(row['recorded_at'])).strftime('%Y-%m-%d %H:%M')} · {row['note'] or ''}")
            text.setStyleSheet("color:#e2e8f0;")
            line.addWidget(text, 1)
            if row["source"] == "manual":
                edit = PushButton(FIF.EDIT, "编辑")
                delete = PushButton(FIF.DELETE, "删除")
                edit.clicked.connect(lambda _checked=False, item=dict(row): self._edit_trend(item))
                delete.clicked.connect(lambda _checked=False, entry_id=int(row["id"]): self._delete_trend(entry_id))
                line.addWidget(edit)
                line.addWidget(delete)
            self.trend_rows.addWidget(frame)

    def _edit_trend(self, row: dict) -> None:
        self._editing_history_id = int(row["id"])
        self.trend_datetime.setDateTime(QDateTime.fromSecsSinceEpoch(int(row["recorded_at"])))
        self.trend_rating.setValue(int(row["rating"]))
        self.trend_note.setText(str(row.get("note") or ""))
        self.trend_save.setText("保存修改")
        self.trend_cancel.show()

    def _delete_trend(self, entry_id: int) -> None:
        if QMessageBox.question(self, "删除手动记录", "确认删除这条真实手动 Rating 记录？") != QMessageBox.StandardButton.Yes:
            return
        conn = database.connect()
        try:
            database.delete_manual_rating_history(conn, entry_id)
        finally:
            conn.close()
        self._refresh_trend()

    def _build_recommendations(self) -> None:
        _card, layout = self._card("recommendations", "推分建议", "按 Android 规则逐谱面模拟目标成绩对真实 B35/B15 尾项的增量。")
        form = QHBoxLayout()
        self.rec_total = QSpinBox()
        self.rec_total.setRange(-1, 30000)
        self.rec_total.setSpecialValueText("不限制")
        self.rec_total.setValue(-1)
        self.rec_achievement = QDoubleSpinBox()
        self.rec_achievement.setRange(-1.0, 101.0)
        self.rec_achievement.setSpecialValueText("下一里程碑")
        self.rec_achievement.setDecimals(4)
        self.rec_achievement.setValue(-1.0)
        self.rec_min = QDoubleSpinBox()
        self.rec_min.setRange(-1.0, 20.0)
        self.rec_min.setSpecialValueText("不限")
        self.rec_min.setValue(-1.0)
        self.rec_max = QDoubleSpinBox()
        self.rec_max.setRange(-1.0, 20.0)
        self.rec_max.setSpecialValueText("不限")
        self.rec_max.setValue(-1.0)
        self.rec_age = QComboBox()
        self.rec_age.addItem("全部版本", VersionAgeFilter.ALL)
        self.rec_age.addItem("当前版本", VersionAgeFilter.CURRENT)
        self.rec_age.addItem("旧曲", VersionAgeFilter.OLD)
        for label, widget in (("目标总 Rating", self.rec_total), ("目标达成率", self.rec_achievement), ("最低定数", self.rec_min), ("最高定数", self.rec_max), ("版本", self.rec_age)):
            box = QVBoxLayout()
            caption = BodyLabel(label)
            caption.setStyleSheet("color:#94a3b8; font-size:12px;")
            box.addWidget(caption)
            box.addWidget(widget)
            form.addLayout(box)
        layout.addLayout(form)
        self.rec_exclude_sss = QCheckBox("排除已完成 SSS+")
        self.rec_exclude_sss.setChecked(True)
        layout.addWidget(self.rec_exclude_sss)
        button = PushButton(FIF.UP, "生成确定性建议")
        button.clicked.connect(self._refresh_recommendations)
        layout.addWidget(button)
        self.rec_summary = BodyLabel("等待生成")
        self.rec_summary.setWordWrap(True)
        self.rec_summary.setStyleSheet("color:#cbd5e1;")
        layout.addWidget(self.rec_summary)
        self.rec_rows = QVBoxLayout()
        self.rec_rows.setSpacing(6)
        layout.addLayout(self.rec_rows)

    def _refresh_recommendations(self) -> None:
        conn = database.connect()
        try:
            current = resolve_current_version_id(conn)
            exclusions = database.recommendation_exclusions(conn)
        finally:
            conn.close()
        try:
            filters = RecommendationFilters(
                target_total_rating=None if self.rec_total.value() < 0 else self.rec_total.value(),
                target_achievement=None if self.rec_achievement.value() < 0 else self.rec_achievement.value(),
                constant_min=None if self.rec_min.value() < 0 else self.rec_min.value(),
                constant_max=None if self.rec_max.value() < 0 else self.rec_max.value(),
                version_age=self.rec_age.currentData(),
                exclude_sss_plus=self.rec_exclude_sss.isChecked(),
                excluded_identities=frozenset(exclusions),
            )
            result = build_recommendations(self._records, current, filters)
        except ValueError as exc:
            self._error(str(exc))
            return
        self._clear_layout(self.rec_rows)
        self.rec_summary.setText(
            f"当前 Rating {result.current_total_rating} · B35 尾项 {result.old_best_cutoff or '--'} · "
            f"B15 尾项 {result.current_best_cutoff or '--'} · {len(result.recommendations)} 条建议"
        )
        for item in result.recommendations[:50]:
            frame = self._row_frame()
            line = QHBoxLayout(frame)
            line.setContentsMargins(12, 8, 12, 8)
            text = QLabel(
                f"{item.chart.title} · {item.bucket.value} · {item.current_achievement:.4f}% → "
                f"{item.target_achievement:.4f}% · 单曲 +{item.theoretical_single_gain} · "
                f"B50 +{item.actual_b50_gain} · {item.reason.value}"
            )
            text.setWordWrap(True)
            text.setStyleSheet("color:#e2e8f0;")
            exclude = PushButton(FIF.REMOVE, "不想练")
            exclude.clicked.connect(lambda _checked=False, key=item.identity_key: self._exclude_recommendation(key))
            line.addWidget(text, 1)
            line.addWidget(exclude)
            self.rec_rows.addWidget(frame)

    def _exclude_recommendation(self, key: str) -> None:
        conn = database.connect()
        try:
            database.set_recommendation_excluded(conn, key, True)
        finally:
            conn.close()
        self._refresh_recommendations()

    def _build_plate(self) -> None:
        _card, layout = self._card("plate", "牌子进度", "使用 chartVersion、稳定谱面身份和 Android 的版本范围/排除曲规则；数据不足绝不宣布完成。")
        controls = QHBoxLayout()
        self.plate_version = QComboBox()
        for version in reversed(KNOWN_VERSIONS):
            if version.plate:
                self.plate_version.addItem(version.official_name, version.version_id)
        self.plate_kind = QComboBox()
        for kind in PlateKind:
            self.plate_kind.addItem(kind.display_name, kind)
        button = PushButton(FIF.UPDATE, "计算进度")
        button.clicked.connect(self._refresh_plate)
        controls.addWidget(self.plate_version, 1)
        controls.addWidget(self.plate_kind)
        controls.addWidget(button)
        layout.addLayout(controls)
        self.plate_summary = BodyLabel("等待计算")
        self.plate_summary.setWordWrap(True)
        self.plate_summary.setStyleSheet("color:#cbd5e1;")
        layout.addWidget(self.plate_summary)
        self.plate_rows = QVBoxLayout()
        self.plate_rows.setSpacing(6)
        layout.addLayout(self.plate_rows)

    def _refresh_plate(self) -> None:
        kind = self.plate_kind.currentData()
        version = None if kind == PlateKind.CONQUEROR else self.plate_version.currentData()
        result = calculate_plate_progress(self._records, kind, version)
        self._clear_layout(self.plate_rows)
        if not result.data_sufficient:
            self.plate_summary.setText(f"{result.plate_name}：数据不足 · {result.data_message}")
            return
        self.plate_summary.setText(f"{result.plate_name}：{result.completed_count}/{result.required_count} · 剩余 {result.remaining_count} · {'已完成' if result.is_complete else '进行中'}")
        for blocker in result.blockers[:100]:
            label = BodyLabel(f"{blocker.record.title} · {blocker.record.chart_type} {blocker.record.difficulty_label} · {blocker.current_value} · {blocker.requirement_gap}")
            label.setWordWrap(True)
            label.setStyleSheet("color:#e2e8f0; background:#171d29; border-radius:6px; padding:8px;")
            self.plate_rows.addWidget(label)

    def _build_versions(self) -> None:
        _card, layout = self._card("versions", "版本名称与牌子对照", "曲库名称、玩家常用名、牌子简称和内部范围来自同一领域表。")
        for version in reversed(KNOWN_VERSIONS):
            plate = version.plate
            plate_text = " / ".join(plate.prefixes) if plate else "暂无独立牌子集合"
            range_text = f"{plate.chart_version_start}–{plate.chart_version_end - 1}" if plate else "--"
            label = BodyLabel(f"{version.version_id} · {version.official_name} · {version.generation} · 牌子 {plate_text} · {range_text}")
            label.setStyleSheet("color:#cbd5e1; padding:3px 0;")
            layout.addWidget(label)

    def _build_kaleid(self) -> None:
        _card, layout = self._card("kaleid", "Kaleid×Scope", "门曲与解锁条件只接受可审查、可更新的数据源。")
        status = BodyLabel(f"数据源待接入：{KALEID_SCOPE.reason}")
        status.setWordWrap(True)
        status.setStyleSheet("color:#fecaca; background:#451a1a; border:1px solid #7f1d1d; border-radius:6px; padding:12px;")
        layout.addWidget(status)
        sources = BodyLabel("已审查来源：" + " · ".join(KALEID_SCOPE.reviewed_sources))
        sources.setWordWrap(True)
        sources.setStyleSheet("color:#94a3b8;")
        layout.addWidget(sources)

    def refresh_data(self) -> None:
        conn = database.connect()
        try:
            self._records = load_chart_records(conn)
        finally:
            conn.close()
        self._refresh_note_picker()
        self._refresh_trend()
        self._refresh_plate()

    def scroll_to(self, section: str) -> None:
        card = self.section_cards.get(section)
        if card is not None:
            self.ensureWidgetVisible(card, 0, 16)

    def select_chart_for_loss(self, record: ChartRecord) -> None:
        self.note_manual.setChecked(False)
        self.note_search.setText(record.title)
        index = next(
            (
                item
                for item in range(self.note_chart.count())
                if getattr(self.note_chart.itemData(item), "key", None) == record.key
            ),
            -1,
        )
        if index < 0:
            self.note_chart.addItem(
                f"{record.title} · {record.chart_type} {record.difficulty_label} · {record.level}",
                record,
            )
            index = self.note_chart.count() - 1
        self.note_chart.setCurrentIndex(index)
        self.scroll_to("loss")

    @staticmethod
    def _row_frame() -> QFrame:
        frame = QFrame()
        frame.setStyleSheet("QFrame { background:#171d29; border-radius:6px; } QLabel { background:transparent; }")
        return frame

    @staticmethod
    def _clear_layout(layout: QVBoxLayout) -> None:
        while layout.count():
            item = layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()

    def _error(self, message: str) -> None:
        InfoBar.error("无法计算", message, duration=4500, position=InfoBarPosition.TOP_RIGHT, parent=self)
