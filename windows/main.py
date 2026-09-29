import sys
import os

from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QIcon
from PyQt6.QtWidgets import QApplication
from PyQt6.QtWidgets import QMessageBox

if getattr(sys, 'frozen', False):
    data_dir = sys._MEIPASS
else:
    data_dir = os.path.dirname(os.path.abspath(__file__))

from ui_main import MainWindow
from ui_tokens import apply_app_style
from fluentmai_core.app_lifecycle import recover_network_before_window


if __name__ == '__main__':
    smoke_test = "--smoke-test" in sys.argv or os.environ.get("FLUENTMAI_SMOKE_TEST") == "1"
    qt_args = [arg for arg in sys.argv if arg != "--smoke-test"]
    try:
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            'daozhu.fluentmai.v1'
        )
    except Exception:
        pass

    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
    )

    app = QApplication(qt_args)
    apply_app_style(app)
    app.setWindowIcon(QIcon(os.path.join(data_dir, "assets", "logo.ico")))
    try:
        recovered_previous_capture = recover_network_before_window()
    except Exception:
        QMessageBox.critical(
            None,
            "FluentMai 网络恢复失败",
            "无法确认上一次抓取后的系统网络已经恢复。FluentMai 将停止启动。\n\n"
            "请双击程序目录 scripts\\Restore-FluentMai-Network.cmd，恢复成功后再启动。",
        )
        raise SystemExit(2)
    window = MainWindow()
    if recovered_previous_capture:
        window.import_interface.report_startup_recovery()
    window.show()
    if smoke_test:
        QTimer.singleShot(3000, app.quit)
    sys.exit(app.exec())
