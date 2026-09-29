from __future__ import annotations

from PyQt6.QtCore import QSettings, Qt, pyqtSignal
from PyQt6.QtWidgets import QFrame, QGridLayout, QHBoxLayout, QLabel, QVBoxLayout, QWidget
from qfluentwidgets import BodyLabel, CardWidget, FluentIcon as FIF, PushButton, ScrollArea, SubtitleLabel

from fluentmai_core import database
from fluentmai_core.chart_browser import load_chart_records
from fluentmai_core.player_records import AchievementRank, FullComboStatus, PlateKind, calculate_plate_progress, player_stats
from fluentmai_core.rating import BestSet, RatedScore, compute_best_set
from fluentmai_core.recommendations import RecommendationFilters, build_recommendations
from fluentmai_core.runtime_paths import settings_path
from fluentmai_core.version_catalog import KNOWN_VERSIONS


DIFFICULTY_NAMES = ("BASIC", "ADVANCED", "EXPERT", "MASTER", "Re:MASTER")


class OverviewInterface(ScrollArea):
    """Local-first Windows home page backed only by FluentMai's SQLite data."""

    navigationRequested = pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("OverviewInterface")
        self.view = QWidget(self)
        self.view.setObjectName("OverviewContent")
        self.layout = QVBoxLayout(self.view)
        self.layout.setContentsMargins(28, 28, 28, 28)
        self.layout.setSpacing(16)
        self.setWidget(self.view)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setStyleSheet("QScrollArea { background: transparent; border: none; }")

        header = QHBoxLayout()
        title_box = QVBoxLayout()
        self.player_name = SubtitleLabel("本地玩家")
        self.player_name.setStyleSheet("font-size: 26px; font-weight: 800;")
        self.updated_label = BodyLabel("本地数据")
        self.updated_label.setStyleSheet("color: #94a3b8;")
        title_box.addWidget(self.player_name)
        title_box.addWidget(self.updated_label)
        header.addLayout(title_box, 1)
        refresh = PushButton(FIF.UPDATE, "刷新")
        refresh.setAccessibleName("刷新本地首页")
        refresh.clicked.connect(self.refresh)
        header.addWidget(refresh)
        self.layout.addLayout(header)

        actions = QHBoxLayout()
        for icon, text, route in (
            (FIF.SYNC, "导入成绩", "import"),
            (FIF.SEARCH, "查看已游玩谱面", "charts-played"),
            (FIF.TILES, "牌子进度", "tools-plate"),
            (FIF.UP, "推分建议", "tools-recommendations"),
        ):
            button = PushButton(icon, text)
            button.setMinimumHeight(44)
            button.clicked.connect(lambda _checked=False, target=route: self.navigationRequested.emit(target))
            actions.addWidget(button)
        actions.addStretch(1)
        self.layout.addLayout(actions)

        self.metrics_grid = QGridLayout()
        self.metrics_grid.setSpacing(12)
        self.metric_cards: list[CardWidget] = []
        self.metric_values: dict[str, QLabel] = {}
        for key, title in (
            ("rating", "Rating"),
            ("scores", "本地成绩"),
            ("catalog", "曲库谱面"),
            ("b35", "B35"),
            ("b15", "B15"),
            ("trend", "Trend"),
        ):
            card = CardWidget()
            card.setMinimumWidth(150)
            card.setStyleSheet("CardWidget { background:#151a24; border:1px solid #30384c; border-radius:8px; }")
            box = QVBoxLayout(card)
            box.setContentsMargins(16, 14, 16, 14)
            label = BodyLabel(title)
            label.setStyleSheet("color:#94a3b8; font-size:12px;")
            value = QLabel("--")
            value.setStyleSheet("color:#f8fafc; font-size:24px; font-weight:800; background:transparent;")
            box.addWidget(label)
            box.addWidget(value)
            self.metric_cards.append(card)
            self.metric_values[key] = value
        self.layout.addLayout(self.metrics_grid)

        self.stats_card, self.stats_text = self._text_card("玩家统计")
        self.layout.addWidget(self.stats_card)

        self.plate_card, self.plate_text = self._text_card("牌子进度摘要")
        self.layout.addWidget(self.plate_card)

        self.recommendation_card, self.recommendation_text = self._text_card("推分建议摘要")
        self.layout.addWidget(self.recommendation_card)

        self.b35_card, self.b35_rows = self._best_card("B35 · 旧曲 Best 35")
        self.layout.addWidget(self.b35_card)
        self.b15_card, self.b15_rows = self._best_card("B15 · 当前版本 Best 15")
        self.layout.addWidget(self.b15_card)
        self.layout.addStretch(1)
        self._metric_columns = 0
        self._layout_metric_cards()
        self.refresh()

    def _text_card(self, title: str):
        card = CardWidget()
        card.setStyleSheet("CardWidget { background:#151a24; border:1px solid #30384c; border-radius:8px; }")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(18, 16, 18, 16)
        heading = BodyLabel(title)
        heading.setStyleSheet("color:#f8fafc; font-size:15px; font-weight:700;")
        text = BodyLabel("--")
        text.setWordWrap(True)
        text.setStyleSheet("color:#cbd5e1; line-height:1.5;")
        layout.addWidget(heading)
        layout.addWidget(text)
        return card, text

    def _best_card(self, title: str):
        card = CardWidget()
        card.setStyleSheet("CardWidget { background:#111620; border:1px solid #30384c; border-radius:8px; }")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(8)
        heading = BodyLabel(title)
        heading.setStyleSheet("color:#f8fafc; font-size:16px; font-weight:700;")
        rows = QVBoxLayout()
        rows.setSpacing(6)
        layout.addWidget(heading)
        layout.addLayout(rows)
        return card, rows

    def refresh(self) -> None:
        conn = database.connect()
        try:
            best_set = compute_best_set(conn)
            records = load_chart_records(conn)
            stats = player_stats(records)
            history = database.list_rating_history(conn)
            score_count = int(conn.execute("SELECT COUNT(*) FROM score_records").fetchone()[0])
            exclusions = database.recommendation_exclusions(conn)
        finally:
            conn.close()

        settings = QSettings(str(settings_path()), QSettings.Format.IniFormat)
        self.player_name.setText(settings.value("profile/display_name", "本地玩家", type=str) or "本地玩家")
        self.metric_values["rating"].setText(str(best_set.rating) if best_set.current_version_id else "数据不足")
        self.metric_values["scores"].setText(f"{score_count:,}")
        self.metric_values["catalog"].setText(f"{stats.total_charts:,}")
        self.metric_values["b35"].setText(f"{best_set.b35_rating} · {len(best_set.old_best)}/35")
        self.metric_values["b15"].setText(f"{best_set.b15_rating} · {len(best_set.new_best)}/15")

        if history:
            latest = history[-1]
            delta = int(latest["rating"]) - int(history[0]["rating"])
            self.metric_values["trend"].setText(f"{int(latest['rating'])} ({delta:+d})")
        else:
            self.metric_values["trend"].setText("暂无记录")

        rank = stats.rank_counts
        combo = stats.full_combo_counts
        self.stats_text.setText(
            f"总谱面 {stats.total_charts:,} · 已游玩 {stats.played_charts:,} · 未游玩 {stats.unplayed_charts:,} · "
            f"SSS+ {rank.get(AchievementRank.SSS_PLUS, 0):,} · SSS {rank.get(AchievementRank.SSS, 0):,} · "
            f"FC+ {combo.get(FullComboStatus.FC_PLUS, 0):,} · AP/AP+ "
            f"{combo.get(FullComboStatus.AP, 0) + combo.get(FullComboStatus.AP_PLUS, 0):,}"
        )

        plate_version = next(
            (item for item in reversed(KNOWN_VERSIONS) if item.plate and (best_set.current_version_id is None or item.version_id <= best_set.current_version_id)),
            None,
        )
        if plate_version:
            plate = calculate_plate_progress(records, PlateKind.GENERAL, plate_version.version_id)
            self.plate_text.setText(
                f"{plate.plate_name}：{plate.completed_count}/{plate.required_count}，剩余 {plate.remaining_count}。"
                if plate.data_sufficient else f"{plate.plate_name}：{plate.data_message}"
            )
        else:
            self.plate_text.setText("曲库缺少可核验的版本牌数据。")

        recommendations = build_recommendations(
            records,
            best_set.current_version_id,
            RecommendationFilters(excluded_identities=frozenset(exclusions)),
        )
        self.recommendation_text.setText(
            f"基于本地 B35/B15 尾项可产生 {len(recommendations.recommendations)} 条确定性建议；"
            f"当前 Rating {recommendations.current_total_rating}。"
            if recommendations.availability.value == "available" else "当前曲库版本不足，暂不生成建议。"
        )
        self._populate_best_rows(self.b35_rows, best_set.old_best)
        self._populate_best_rows(self.b15_rows, best_set.new_best)
        self.updated_label.setText("本地数据库已刷新 · 不依赖第三方玩家缓存")

    def _populate_best_rows(self, layout: QVBoxLayout, scores: tuple[RatedScore, ...]) -> None:
        while layout.count():
            item = layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()
        if not scores:
            empty = BodyLabel("暂无可核验成绩。请先导入成绩并刷新曲库。")
            empty.setStyleSheet("color:#94a3b8;")
            layout.addWidget(empty)
            return
        for index, score in enumerate(scores, start=1):
            row = QFrame()
            row.setStyleSheet("QFrame { background:#171d29; border-radius:6px; } QLabel { background:transparent; }")
            line = QHBoxLayout(row)
            line.setContentsMargins(12, 8, 12, 8)
            rank = QLabel(f"#{index}")
            rank.setFixedWidth(34)
            title = QLabel(score.title)
            title.setToolTip(score.title)
            title.setStyleSheet("color:#f8fafc; font-weight:600;")
            difficulty_name = DIFFICULTY_NAMES[score.difficulty_index] if 0 <= score.difficulty_index < 5 else score.difficulty_name
            difficulty = QLabel(difficulty_name)
            difficulty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            difficulty.setMinimumWidth(90)
            if score.difficulty_index == 4:
                difficulty.setStyleSheet("color:#581c87; background:#f3e8ff; border:1px solid #c4b5fd; border-radius:5px; padding:3px 7px; font-weight:700;")
            elif score.difficulty_index == 3:
                difficulty.setStyleSheet("color:white; background:#7c3aed; border-radius:5px; padding:3px 7px; font-weight:700;")
            detail = QLabel(
                f"{score.chart_type} · {score.achievement:.4f}% · Ra {score.rating} · "
                f"DX {score.dx_score if score.dx_score is not None else '--'} · "
                f"{score.full_combo or '--'} / {score.full_sync or '--'}"
            )
            detail.setStyleSheet("color:#cbd5e1;")
            line.addWidget(rank)
            line.addWidget(title, 1)
            line.addWidget(difficulty)
            line.addWidget(detail)
            layout.addWidget(row)

    def _layout_metric_cards(self) -> None:
        width = self.viewport().width()
        columns = 1 if width < 520 else 2 if width < 900 else 3
        if columns == self._metric_columns:
            return
        self._metric_columns = columns
        while self.metrics_grid.count():
            self.metrics_grid.takeAt(0)
        for index, card in enumerate(self.metric_cards):
            self.metrics_grid.addWidget(card, index // columns, index % columns)
        for column in range(columns):
            self.metrics_grid.setColumnStretch(column, 1)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._layout_metric_cards()
