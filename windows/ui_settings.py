from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout
from qfluentwidgets import (SubtitleLabel, BodyLabel, CardWidget, ComboBox,
                            FluentIcon as FIF, InfoBar, ScrollArea)

from i18n import i18n, save_config, _load_config

LANG_OPTIONS = {
    "zh_CN": "简体中文",
    "en_US": "English",
}


class SettingsInterface(ScrollArea):
    """Settings page — language, token management, cookie guide."""

    def __init__(self, parent=None):
        super().__init__(parent=parent)
        self.setObjectName("SettingsInterface")
        self.view = QWidget(self)
        self.view.setObjectName("SettingsScrollContent")
        self.layout = QVBoxLayout(self.view)
        self.layout.setContentsMargins(24, 32, 24, 24)
        self.layout.setSpacing(20)
        self.setWidget(self.view)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setStyleSheet("QScrollArea { background: transparent; border: none; }")

        title = SubtitleLabel(i18n.tr("settings_title"))
        title.setStyleSheet("font-size: 26px; font-weight: bold;")
        self.layout.addWidget(title)

        # ---- Language ----
        lang_card = CardWidget()
        lang_layout = QHBoxLayout(lang_card)
        lang_layout.setContentsMargins(20, 16, 20, 16)
        lang_layout.setSpacing(15)

        lang_text_layout = QVBoxLayout()
        lang_text_layout.setSpacing(4)
        lang_title = BodyLabel(i18n.tr("settings_language"))
        lang_title.setStyleSheet("font-size: 14px;")
        lang_desc = BodyLabel(i18n.tr("settings_language_desc"))
        lang_desc.setStyleSheet("color: #a0a0a0; font-size: 12px;")
        lang_text_layout.addWidget(lang_title)
        lang_text_layout.addWidget(lang_desc)

        self.lang_combo = ComboBox()
        for code, label in LANG_OPTIONS.items():
            self.lang_combo.addItem(label, userData=code)
        current_idx = list(LANG_OPTIONS.keys()).index(i18n.locale)
        self.lang_combo.setCurrentIndex(current_idx)
        self.lang_combo.currentIndexChanged.connect(self._on_lang_changed)

        lang_layout.addLayout(lang_text_layout, 1)
        lang_layout.addWidget(self.lang_combo)
        self.layout.addWidget(lang_card)

        # ---- Cookie 获取指南 ----
        guide_card = CardWidget()
        guide_layout = QVBoxLayout(guide_card)
        guide_layout.setContentsMargins(20, 16, 20, 16)
        guide_layout.setSpacing(8)

        guide_title = BodyLabel("Cookie 获取指南")
        guide_title.setStyleSheet("font-size: 14px;")
        guide_layout.addWidget(guide_title)

        guide_text = BodyLabel(
            "1. 手机安装 Reqable (https://reqable.com/) 或 HttpCanary\n"
            "2. 安装 CA 证书 (App 内引导)\n"
            "3. 开始抓包 → 微信 → 舞萌公众号 → 我的记录 → 舞萌DX\n"
            "4. 找到任意 maimai.wahlap.com 请求 → 长按 → 复制全部头部\n"
            "5. 粘贴到「成绩同步」页面的输入框 (程序会自动清洗提取)\n\n"
            "Import Token 获取: https://www.diving-fish.com/maimaidx/prober/\n"
            "→ 登录 → 编辑个人资料 → 生成导入 Token"
        )
        guide_text.setStyleSheet("color: #a0a0a0; font-size: 12px; line-height: 1.6;")
        guide_text.setWordWrap(True)
        guide_layout.addWidget(guide_text)

        self.layout.addWidget(guide_card)

        self.layout.addStretch(1)

    def _on_lang_changed(self, index):
        lang_code = self.lang_combo.itemData(index)
        if lang_code == i18n.locale:
            return
        i18n.set_language(lang_code)
        config = _load_config()
        config.setdefault("Settings", {})["Language"] = lang_code
        save_config(config)
        InfoBar.success(
            i18n.tr("settings_language_changed"),
            i18n.tr("settings_language_restart"),
            duration=5000,
            parent=self
        )
