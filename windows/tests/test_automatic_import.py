from __future__ import annotations

from fluentmai_core import database
from fluentmai_core.automatic_import import run_wahlap_capture_import
from fluentmai_core.capture_session import LocalCaptureResult
from fluentmai_core.models import Chart, MajorVersion, Song


SCORE_HTML = """
<html><body>
<form action="/maimai-mobile/record/musicDetail/">
  <div class="music_name_block">Local Capture Song</div>
  <div class="music_lv_block">14</div>
  <div class="music_score_block w_112 t_r f_l f_12">100.5000%</div>
  <div class="music_score_block w_190 t_r f_l f_12">3,000</div>
  <div class="music_kind_icon"><img src="images/music_dx.png"></div>
</form>
</body></html>
"""


class FixtureCaptureController:
    def __init__(self):
        self.result = LocalCaptureResult(
            home_html="<html>sensitive home fixture</html>",
            pages=[(index, SCORE_HTML if index == 3 else "<html><body></body></html>") for index in range(5)],
            captured_pages=5,
            captured_bytes=1234,
            helper_version="fixture",
            certificate_fingerprint="safe-fixture",
        )

    def capture(self, **_kwargs):
        return self.result


def test_capture_pages_are_imported_locally_and_released_from_controller_result(tmp_path):
    db_path = str(tmp_path / "automatic.db")
    conn = database.connect(db_path)
    database.upsert_catalog(
        conn,
        [Song(song_id=10, title="Local Capture Song", version=25500, provider="fixture")],
        [
            Chart(
                song_id=10,
                chart_type="DX",
                difficulty_index=3,
                level="14",
                level_value=14.0,
                chart_version=25500,
                chart_version_name="Current",
            )
        ],
        [MajorVersion(version_id=25500, name="Current", provider="fixture")],
    )
    conn.close()
    controller = FixtureCaptureController()
    stages = []

    result = run_wahlap_capture_import(
        controller,
        db_path=db_path,
        progress=lambda stage, _info: stages.append(stage),
        install_ca=False,
    )

    assert result.captured_pages == 5
    assert result.summary.inserted == 1
    assert result.summary.b15_count == 1
    assert result.summary.rating_after == 315
    assert controller.result.home_html == ""
    assert controller.result.pages == []
    assert stages == ["parsing", "validating_and_writing", "local_import_complete"]
    conn = database.connect(db_path)
    assert conn.execute("SELECT COUNT(*) FROM score_records").fetchone()[0] == 1
    assert conn.execute("SELECT COUNT(*) FROM import_batches").fetchone()[0] == 1
    assert conn.execute("SELECT COUNT(*) FROM rating_history").fetchone()[0] == 1
    conn.close()
