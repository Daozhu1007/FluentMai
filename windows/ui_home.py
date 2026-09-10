from __future__ import annotations

import threading

from PyQt6.QtCore import QThread, pyqtSignal
from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QTextEdit, QLineEdit
from qfluentwidgets import (
    BodyLabel,
    CardWidget,
    FluentIcon as FIF,
    IndeterminateProgressRing,
    InfoBar,
    InfoBarPosition,
    PushButton,
    ScrollArea,
    MessageBox,
    SubtitleLabel,
)

from fluentmai_core.automatic_import import run_wahlap_capture_import
from fluentmai_core.aliases import refresh_alias_catalog
from fluentmai_core.catalog import sync_diving_fish_catalog, sync_lxns_catalog
from fluentmai_core.capture_session import CaptureError, LocalCaptureController
from fluentmai_core.import_pipeline import import_parsed_records
from fluentmai_core.models import ImportSummary
from fluentmai_core.network_recovery import WindowsRegistryProxyBackend
from fluentmai_core.privacy import redactor
from fluentmai_core.providers import DivingFishProvider, LxnsProvider
from fluentmai_core.wahlap import WahlapClient, parse_wahlap_pages


CAPTURE_STAGE_TEXT = {
    "recovering_previous_session": "正在恢复上一次未完成的网络环境…",
    "previous_session_recovered": "上一次网络环境已恢复。",
    "starting_helper": "正在启动 FluentMai 本地抓取组件…",
    "certificate_ready": "专用 CA 已准备，正在检查系统信任状态…",
    "network_proxy_active": "临时代理已启用；恢复快照已受保护保存。",
    "waiting_for_wechat": "请在微信“舞萌 | 中二”中打开：我的记录 → 舞萌DX。",
    "session_captured": "已捕获当前会话，正在读取五个难度页面…",
    "fetching_difficulty": "正在读取难度页面…",
    "retrying_difficulty": "页面暂不可用，正在有限重试…",
    "page_captured": "已收到一个成绩页面。",
    "restoring_network": "正在恢复原始系统网络配置…",
    "network_restored": "系统网络已精确恢复。",
    "parsing": "网络已恢复，正在内存中解析成绩…",
    "validating_and_writing": "正在校验、去重并原子写入本地数据库…",
    "local_import_complete": "本地导入与 Rating 刷新完成。",
}

CAPTURE_ERROR_TEXT = {
    "browser_capture_failed": "微信内置页面抓取失败。网络已恢复，请确认关闭‘使用系统默认浏览器打开第三方网页’后重试。",
    "browser_capture_timeout": "等待微信内置页面返回成绩超时。网络已恢复，请确认微信未被关闭并重试。",
    "browser_capture_too_large": "微信返回的单页数据超出安全上限。网络已恢复，未写入本地数据库。",
    "browser_capture_unexpected_page": "微信返回的不是有效成绩页面。网络已恢复，请重新进入‘我的记录 → 舞萌DX’。",
    "cancelled": "已取消；临时组件已停止，网络已恢复。",
    "capture_timeout": "等待微信个人记录页面超时。网络已恢复，可重新开始。",
    "authentication_expired": "微信中的舞萌登录状态已失效。网络已恢复，请重新登录后再试。",
    "wahlap_challenge_or_unexpected_page": "华立返回了验证页或未知页面。网络已恢复，请稍后重试。",
    "network_timeout": "读取华立成绩超时。网络已恢复，请检查网络后重试。",
    "network_error": "读取华立成绩失败。网络已恢复，请检查网络后重试。",
    "ca_installation_timeout": "等待 Windows 信任 FluentMai 专用 CA 超时；代理未启用。请重新开始并在系统提示中确认。",
    "ca_installation_failed": "FluentMai 专用 CA 安装失败；代理未启用。请检查当前用户证书权限。",
    "ca_installation_verification_failed": "无法确认 FluentMai 专用 CA 已受信任；代理未启用。",
    "helper_exited_before_ipc": "本地抓取组件未能启动；系统网络未修改。",
    "helper_exited": "本地抓取组件意外退出；系统网络已恢复。",
    "helper_ipc_closed": "本地抓取组件连接中断；系统网络已恢复。",
    "ipc_authentication_failed": "本地抓取组件认证失败；系统网络未修改。",
}


class CaptureImportWorker(QThread):
    progress = pyqtSignal(str, dict)
    completed = pyqtSignal(dict)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.cancel_event = threading.Event()

    def request_cancel(self) -> None:
        self.cancel_event.set()

    def run(self) -> None:
        try:
            backend = WindowsRegistryProxyBackend(allow_system_changes=True)
            controller = LocalCaptureController(backend)
            result = run_wahlap_capture_import(
                controller,
                progress=lambda stage, info: self.progress.emit(stage, dict(info)),
                cancel_event=self.cancel_event,
                install_ca=True,
            )
            self.completed.emit(
                {
                    "success": True,
                    "summary": result.summary.as_dict(),
                    "captured_pages": result.captured_pages,
                    "captured_bytes": result.captured_bytes,
                    "elapsed_seconds": result.elapsed_seconds,
                    "helper_version": result.helper_version,
                    "network_restored": True,
                }
            )
        except CaptureError as exc:
            self.completed.emit(
                {
                    "success": False,
                    "cancelled": exc.category == "cancelled",
                    "error_category": exc.category,
                    "network_restored": True,
                }
            )
        except Exception as exc:
            self.completed.emit(
                {
                    "success": False,
                    "error": redactor.redact(exc),
                    "error_category": "network_restoration_unverified",
                    "network_restored": False,
                }
            )


class ImportWorker(QThread):
    progress = pyqtSignal(str)
    finished = pyqtSignal(dict)

    def __init__(self, action: str, payload: dict):
        super().__init__()
        self.action = action
        self.payload = payload

    def run(self) -> None:
        try:
            if self.action == "wahlap":
                self.finished.emit(self._run_wahlap())
            elif self.action == "diving-fish":
                self.finished.emit(self._run_diving_fish())
            elif self.action == "lxns":
                self.finished.emit(self._run_lxns())
            elif self.action == "catalog":
                self.finished.emit(self._run_catalog())
            else:
                raise ValueError(f"Unknown action: {self.action}")
        except Exception as exc:
            self.finished.emit({"success": False, "error": redactor.redact(exc)})

    def _run_wahlap(self) -> dict:
        credential = self.payload["credential"].strip()
        self.progress.emit("Connecting to Wahlap...")
        client = WahlapClient()
        if credential.startswith(("http://", "https://")):
            client.login_with_auth_url(credential)
            self.progress.emit("Auth URL accepted. Fetching five difficulty pages...")
        else:
            client.use_cookie_input(credential)
            self.progress.emit("Cookie accepted. Fetching five difficulty pages...")

        pages = client.fetch_score_pages(
            progress=lambda diff, stage, info: self.progress.emit(
                f"{info.get('label', diff)}: {stage}, {info.get('size_kb', '')} KB"
            )
        )
        records = parse_wahlap_pages(pages, db_path=self.payload.get("db_path"))
        self.progress.emit(f"Parsed {len(records)} Wahlap records. Importing locally...")
        summary = import_parsed_records(
            records,
            source="wahlap",
            db_path=self.payload.get("db_path"),
            fetched_count=sum(1 for _, html in pages if html.strip()),
        )
        return {"success": True, "summary": summary.as_dict()}

    def _run_diving_fish(self) -> dict:
        token = self.payload["token"].strip()
        self.progress.emit("Fetching full Diving-Fish records...")
        records = DivingFishProvider().fetch_parsed_scores(token)
        self.progress.emit(f"Fetched {len(records)} Diving-Fish records. Importing locally...")
        summary = import_parsed_records(
            records,
            source="diving-fish",
            db_path=self.payload.get("db_path"),
            fetched_count=len(records),
        )
        return {"success": True, "summary": summary.as_dict()}

    def _run_lxns(self) -> dict:
        token = self.payload["token"].strip()
        self.progress.emit("Fetching full LXNS records...")
        records = LxnsProvider().fetch_parsed_scores(token)
        self.progress.emit(f"Fetched {len(records)} LXNS records. Importing locally...")
        summary = import_parsed_records(
            records,
            source="lxns",
            db_path=self.payload.get("db_path"),
            fetched_count=len(records),
        )
        return {"success": True, "summary": summary.as_dict()}

    def _run_catalog(self) -> dict:
        errors: list[str] = []
        self.progress.emit("Refreshing LXNS song and chart catalog...")
        try:
            count = sync_lxns_catalog(self.payload.get("db_path"))
            message = f"LXNS {count}"
        except Exception as exc:
            errors.append(f"LXNS: {redactor.redact(exc)}")
            self.progress.emit("Refreshing Diving-Fish fallback catalog...")
            try:
                count = sync_diving_fish_catalog(self.payload.get("db_path"), replace=True)
                message = f"Diving-Fish fallback {count}"
            except Exception as fallback_exc:
                errors.append(f"Diving-Fish: {redactor.redact(fallback_exc)}")
                raise RuntimeError("; ".join(errors) or "Catalog refresh failed.")
        try:
            conn = database.connect(self.payload.get("db_path"))
            try:
                alias_songs, alias_count = refresh_alias_catalog(conn)
                message += f"; aliases {alias_songs}/{alias_count}"
            finally:
                conn.close()
        except Exception as exc:
            errors.append(f"Aliases: {redactor.redact(exc)}")
        return {
            "success": True,
            "summary": {
                "source": "catalog",
                "inserted": 0,
                "updated": 0,
                "skipped_duplicate": 0,
                "quarantined": 0,
                "rejected": 0,
                "failed": len(errors),
                "message": "; ".join([message] + errors),
            },
        }


class HomeInterface(ScrollArea):
    """Import center for local Wahlap, Diving-Fish, and LXNS data."""

    dataChanged = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent=parent)
        self.setObjectName("HomeInterface")
        self._worker: ImportWorker | None = None
        self._capture_worker: CaptureImportWorker | None = None

        self.view = QWidget(self)
        self.view.setObjectName("HomeScrollContent")
        self.layout = QVBoxLayout(self.view)
        self.layout.setContentsMargins(24, 32, 24, 24)
        self.layout.setSpacing(16)
        self.setWidget(self.view)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setStyleSheet("QScrollArea { background: transparent; border: none; }")

        title = SubtitleLabel("导入中心")
        title.setStyleSheet("font-size: 26px; font-weight: bold;")
        self.layout.addWidget(title)

        desc = BodyLabel("从华立、水鱼或落雪导入真实成绩；异常数据会进入 quarantine，不会污染本地成绩表。")
        desc.setStyleSheet("color: #a0a0a0; font-size: 14px; margin-bottom: 8px;")
        self.layout.addWidget(desc)

        capture_card = CardWidget()
        capture_card.setStyleSheet(
            "CardWidget { background: #151824; border: 1px solid #303545; border-radius: 8px; }"
        )
        capture_layout = QVBoxLayout(capture_card)
        capture_layout.setContentsMargins(20, 16, 20, 16)
        capture_layout.setSpacing(8)
        capture_title = BodyLabel("微信自动抓取（推荐）")
        capture_title.setStyleSheet("color: #f8fafc; font-size: 15px; font-weight: 600;")
        capture_layout.addWidget(capture_title)
        capture_hint = BodyLabel(
            "只处理 maimai.wahlap.com。开始后会请求信任 FluentMai 专用 CA，"
            "临时修改当前用户代理，并在解析前恢复原配置；不会默认上传第三方。"
        )
        capture_hint.setWordWrap(True)
        capture_hint.setStyleSheet("color: #cbd5e1; font-size: 12px;")
        capture_layout.addWidget(capture_hint)
        capture_actions = QHBoxLayout()
        self.capture_btn = PushButton(FIF.PLAY, "开始微信抓取")
        self.capture_btn.setMinimumHeight(40)
        self.capture_btn.setStyleSheet(
            "QPushButton { background: #2563eb; color: white; border: 1px solid #3b82f6; "
            "border-radius: 6px; padding: 0 16px; font-weight: 600; } "
            "QPushButton:hover { background: #1d4ed8; } "
            "QPushButton:pressed { background: #1e40af; } "
            "QPushButton:disabled { background: #374151; color: #9ca3af; border-color: #4b5563; }"
        )
        self.capture_btn.setAccessibleName("开始微信抓取")
        self.capture_btn.clicked.connect(self._on_capture)
        capture_actions.addWidget(self.capture_btn)
        self.capture_cancel_btn = PushButton(FIF.CANCEL, "取消并恢复网络")
        self.capture_cancel_btn.setMinimumHeight(40)
        self.capture_cancel_btn.setStyleSheet(
            "QPushButton { background: #242938; color: #f1f5f9; border: 1px solid #4b5563; "
            "border-radius: 6px; padding: 0 16px; } "
            "QPushButton:hover { background: #303748; border-color: #64748b; } "
            "QPushButton:disabled { background: #1f2430; color: #64748b; border-color: #303545; }"
        )
        self.capture_cancel_btn.setAccessibleName("取消微信抓取并恢复网络")
        self.capture_cancel_btn.setEnabled(False)
        self.capture_cancel_btn.clicked.connect(self._cancel_capture)
        capture_actions.addWidget(self.capture_cancel_btn)
        capture_actions.addStretch(1)
        capture_layout.addLayout(capture_actions)
        self.capture_status = BodyLabel("未运行；系统网络不会被修改。")
        self.capture_status.setWordWrap(True)
        self.capture_status.setStyleSheet("color: #94a3b8; font-size: 12px;")
        capture_layout.addWidget(self.capture_status)
        self.layout.addWidget(capture_card)

        self.wahlap_input = QTextEdit()
        self._add_text_card(
            "华立导入",
            "粘贴华立授权 URL、Cookie，或 Reqable 请求头。不会写入日志。",
            self.wahlap_input,
        )
        self.wahlap_btn = PushButton(FIF.SYNC, "获取并导入华立成绩")
        self.wahlap_btn.clicked.connect(self._on_wahlap)
        self.layout.addWidget(self.wahlap_btn)

        self.df_token = QLineEdit()
        self.df_token.setEchoMode(QLineEdit.EchoMode.Password)
        self._add_line_card("水鱼导入", "Import Token，用于拉取完整个人成绩。", self.df_token)
        self.df_btn = PushButton(FIF.DOWNLOAD, "从水鱼导入")
        self.df_btn.clicked.connect(self._on_diving_fish)
        self.layout.addWidget(self.df_btn)

        self.lxns_token = QLineEdit()
        self.lxns_token.setEchoMode(QLineEdit.EchoMode.Password)
        self._add_line_card("落雪导入", "个人 API 密钥，对应请求头 X-User-Token。", self.lxns_token)
        self.lxns_btn = PushButton(FIF.DOWNLOAD, "从落雪导入")
        self.lxns_btn.clicked.connect(self._on_lxns)
        self.layout.addWidget(self.lxns_btn)

        tools = QHBoxLayout()
        self.catalog_btn = PushButton(FIF.UPDATE, "刷新歌曲与谱面缓存")
        self.catalog_btn.clicked.connect(self._on_catalog)
        tools.addWidget(self.catalog_btn)
        self.spinner = IndeterminateProgressRing(self)
        self.spinner.setFixedSize(24, 24)
        self.spinner.hide()
        tools.addWidget(self.spinner)
        tools.addStretch(1)
        self.layout.addLayout(tools)

        self.status_label = BodyLabel("就绪")
        self.status_label.setStyleSheet("color: #a0a0a0; font-size: 13px;")
        self.layout.addWidget(self.status_label)

        self.log_output = QTextEdit()
        self.log_output.setReadOnly(True)
        self.log_output.setMaximumHeight(190)
        self.log_output.setStyleSheet(
            """
            QTextEdit {
                background: #151824;
                color: #d8dee9;
                border: 1px solid #303545;
                border-radius: 6px;
                font-size: 12px;
                padding: 8px;
            }
            """
        )
        self.layout.addWidget(self.log_output)
        self.layout.addStretch(1)

    def _add_text_card(self, title: str, hint: str, input_widget: QTextEdit) -> None:
        card = CardWidget()
        vbox = QVBoxLayout(card)
        vbox.setContentsMargins(20, 16, 20, 16)
        vbox.setSpacing(8)
        vbox.addWidget(BodyLabel(title))
        hint_label = BodyLabel(hint)
        hint_label.setStyleSheet("color: #a0a0a0; font-size: 12px;")
        hint_label.setWordWrap(True)
        vbox.addWidget(hint_label)
        input_widget.setMinimumHeight(88)
        input_widget.setPlaceholderText("https://tgk-wcaime.wahlap.com/... 或 _t=...; userId=...")
        vbox.addWidget(input_widget)
        self.layout.addWidget(card)

    def _add_line_card(self, title: str, hint: str, input_widget: QLineEdit) -> None:
        card = CardWidget()
        vbox = QVBoxLayout(card)
        vbox.setContentsMargins(20, 16, 20, 16)
        vbox.setSpacing(8)
        vbox.addWidget(BodyLabel(title))
        hint_label = BodyLabel(hint)
        hint_label.setStyleSheet("color: #a0a0a0; font-size: 12px;")
        vbox.addWidget(hint_label)
        input_widget.setMinimumHeight(34)
        vbox.addWidget(input_widget)
        self.layout.addWidget(card)

    def _on_wahlap(self) -> None:
        credential = self.wahlap_input.toPlainText().strip()
        if not credential:
            self._warn("请先粘贴华立授权 URL、Cookie 或请求头。")
            return
        self._start_worker("wahlap", {"credential": credential})

    def _on_diving_fish(self) -> None:
        token = self.df_token.text().strip()
        if not token:
            self._warn("请先填写水鱼 Import Token。")
            return
        self._start_worker("diving-fish", {"token": token})

    def _on_lxns(self) -> None:
        token = self.lxns_token.text().strip()
        if not token:
            self._warn("请先填写落雪个人 API 密钥。")
            return
        self._start_worker("lxns", {"token": token})

    def _on_catalog(self) -> None:
        self._start_worker("catalog", {})

    def _on_capture(self) -> None:
        if self._operation_active():
            self._warn("已有导入任务正在运行，请先等待或取消当前任务。")
            return
        dialog = MessageBox(
            "开始本地微信抓取",
            "FluentMai 将安装仅用于 Wahlap 的当前用户根证书“FluentMai Local Capture CA”，"
            "并临时把当前用户系统代理指向随机本机端口。\n\n"
            "开始前，请在微信‘设置 > 通用’中关闭‘使用系统默认浏览器打开第三方网页’，"
            "以便个人记录在微信内置页面中打开；导入结束后可恢复该开关。\n\n"
            "请核对 Windows 证书提示中的名称并亲自确认。抓取完成、取消或失败后，"
            "FluentMai 会终止辅助组件并恢复开始前的代理、PAC、例外列表与 WinHTTP 状态。",
            self,
        )
        dialog.yesButton.setText("我已了解，开始")
        dialog.cancelButton.setText("取消")
        if not dialog.exec():
            return
        self._start_capture_worker()

    def _start_capture_worker(self) -> None:
        previous_worker = self._capture_worker
        if previous_worker is not None and not previous_worker.isRunning():
            previous_worker.deleteLater()
        self.log_output.clear()
        self.status_label.setText("微信抓取运行中…")
        self.capture_status.setText("正在进行环境检查…")
        self._capture_worker = CaptureImportWorker(self)
        self._capture_worker.progress.connect(self._on_capture_progress)
        self._capture_worker.completed.connect(self._on_capture_finished)
        self._set_busy(True, capture=True)
        self._capture_worker.start()

    def _cancel_capture(self) -> None:
        worker = self._capture_worker
        if worker is None or not worker.isRunning():
            return
        self.capture_cancel_btn.setEnabled(False)
        self.capture_status.setText("正在取消并恢复网络，请勿强制关闭 FluentMai…")
        self._log("已请求取消；正在停止本地组件并恢复网络。")
        worker.request_cancel()

    def _on_capture_progress(self, stage: str, info: dict) -> None:
        text = CAPTURE_STAGE_TEXT.get(stage, "微信抓取正在进行…")
        if stage in {"fetching_difficulty", "retrying_difficulty"}:
            difficulty = info.get("difficulty")
            if isinstance(difficulty, int):
                text = f"{text}（{difficulty + 1}/5）"
        elif stage == "page_captured" and info.get("page_kind") == "difficulty":
            difficulty = info.get("difficulty")
            if isinstance(difficulty, int):
                text = f"已收到成绩页面 {difficulty + 1}/5。"
        self.capture_status.setText(text)
        self.status_label.setText(text)
        self._log(text)

    def _on_capture_finished(self, result: dict) -> None:
        self._set_busy(False, capture=False)
        if not result.get("success"):
            category = str(result.get("error_category") or "")
            message = CAPTURE_ERROR_TEXT.get(category)
            if not message:
                message = "无法确认网络已恢复。请立即运行 Restore-FluentMai-Network.cmd 后再继续。"
            self.capture_status.setText(message)
            self.status_label.setText(message)
            self._log(message)
            if result.get("network_restored"):
                level = InfoBar.warning if result.get("cancelled") else InfoBar.error
                level(
                    "抓取已取消" if result.get("cancelled") else "微信抓取失败",
                    message,
                    duration=7000,
                    position=InfoBarPosition.TOP_RIGHT,
                    parent=self,
                )
            else:
                InfoBar.error(
                    "需要恢复网络",
                    message,
                    duration=-1,
                    position=InfoBarPosition.TOP_RIGHT,
                    parent=self,
                )
            return
        summary = result.get("summary") or {}
        text = self._capture_summary_text(result, summary)
        self.capture_status.setText("完成；系统网络已恢复，原始页面已从内存释放。")
        self.status_label.setText(text)
        self._log(text)
        InfoBar.success("微信导入完成", text, duration=7000, position=InfoBarPosition.TOP_RIGHT, parent=self)
        self.dataChanged.emit()

    def _capture_summary_text(self, result: dict, summary: dict) -> str:
        rating_before = summary.get("rating_before")
        rating_after = summary.get("rating_after")
        rating_text = "Rating 暂不可用"
        if rating_after is not None:
            delta = rating_after - rating_before if rating_before is not None else None
            rating_text = f"Rating {rating_after}" + (f"（{delta:+d}）" if delta is not None else "")
        return (
            f"捕获 {result.get('captured_pages', 0)} 页，解析 {summary.get('parsed_count', 0)}；"
            f"新增 {summary.get('inserted', 0)}，更新 {summary.get('updated', 0)}，"
            f"重复 {summary.get('skipped_duplicate', 0)}，隔离 {summary.get('quarantined', 0)}；"
            f"B35 {summary.get('b35_rating', 0)} / B15 {summary.get('b15_rating', 0)}，{rating_text}。"
        )

    def _start_worker(self, action: str, payload: dict) -> None:
        if self._operation_active():
            self._warn("已有导入任务正在运行。")
            return
        self._set_busy(True, capture=False)
        self.log_output.clear()
        self.status_label.setText("运行中...")
        self._worker = ImportWorker(action, payload)
        self._worker.progress.connect(self._log)
        self._worker.finished.connect(self._on_finished)
        self._worker.start()

    def _on_finished(self, result: dict) -> None:
        self._set_busy(False, capture=False)
        if not result.get("success"):
            err = redactor.redact(result.get("error", "未知错误"))
            self.status_label.setText(f"失败：{err}")
            self._log(f"失败：{err}")
            InfoBar.error("导入失败", err, duration=6000, position=InfoBarPosition.TOP_RIGHT, parent=self)
            return
        summary = result.get("summary") or {}
        text = self._summary_text(summary)
        self.status_label.setText(text)
        self._log(text)
        InfoBar.success("完成", text, duration=5000, position=InfoBarPosition.TOP_RIGHT, parent=self)
        self.dataChanged.emit()

    def _summary_text(self, summary: dict) -> str:
        source = summary.get("source", "")
        return (
            f"{source} 完成：新增 {summary.get('inserted', 0)}，更新 {summary.get('updated', 0)}，"
            f"重复 {summary.get('skipped_duplicate', 0)}，quarantine {summary.get('quarantined', 0)}，"
            f"失败 {summary.get('failed', 0)}。{summary.get('message', '')}"
        ).strip()

    def _operation_active(self) -> bool:
        return bool(
            (self._worker is not None and self._worker.isRunning())
            or (self._capture_worker is not None and self._capture_worker.isRunning())
        )

    def _set_busy(self, busy: bool, *, capture: bool) -> None:
        for button in (self.capture_btn, self.wahlap_btn, self.df_btn, self.lxns_btn, self.catalog_btn):
            button.setEnabled(not busy)
        self.capture_cancel_btn.setEnabled(busy and capture)
        self.spinner.setVisible(busy)

    def prepare_to_close(self, timeout_ms: int = 20000) -> bool:
        capture_worker = self._capture_worker
        if capture_worker is not None and capture_worker.isRunning():
            self.capture_status.setText("正在停止抓取并恢复网络，请稍候…")
            capture_worker.request_cancel()
            if not capture_worker.wait(timeout_ms):
                self._log("网络恢复仍在进行，暂时阻止关闭 FluentMai。")
                return False
        import_worker = self._worker
        if import_worker is not None and import_worker.isRunning():
            self._log("本地导入仍在完成数据库事务，暂时阻止关闭 FluentMai。")
            return False
        return True

    def report_startup_recovery(self) -> None:
        text = "检测到上次异常退出；残留抓取组件已停止，系统网络已按受保护快照恢复。"
        self.capture_status.setText(text)
        self.status_label.setText(text)
        self._log(text)

    def _warn(self, message: str) -> None:
        InfoBar.warning("缺少输入", message, duration=3000, parent=self)

    def _log(self, text: str) -> None:
        self.log_output.append(redactor.redact(text))
        scrollbar = self.log_output.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())
