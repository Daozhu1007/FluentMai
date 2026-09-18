from __future__ import annotations

import pytest

from fluentmai_core import database


def test_manual_rating_history_is_editable_but_automatic_history_is_protected(tmp_path):
    conn = database.connect(str(tmp_path / "history.db"))
    manual_id = database.add_manual_rating_history(
        conn,
        recorded_at=100.0,
        rating=14000,
        note="真实补录",
        created_at=101.0,
    )
    conn.execute(
        """
        INSERT INTO rating_history(
            recorded_at, rating, source, source_batch_id, note, created_at, updated_at
        ) VALUES (200, 14100, 'wahlap-wechat', 'batch', NULL, 200, 200)
        """
    )
    conn.commit()
    automatic_id = int(conn.execute("SELECT MAX(id) FROM rating_history").fetchone()[0])

    assert database.update_manual_rating_history(
        conn, manual_id, recorded_at=110.0, rating=14050, note="修正", updated_at=111.0
    )
    assert not database.update_manual_rating_history(
        conn, automatic_id, recorded_at=210.0, rating=1, note=None
    )
    assert not database.delete_manual_rating_history(conn, automatic_id)
    assert database.delete_manual_rating_history(conn, manual_id)
    rows = database.list_rating_history(conn)
    assert len(rows) == 1
    assert rows[0]["source"] == "wahlap-wechat"
    conn.close()


def test_manual_rating_history_validates_rating_and_note(tmp_path):
    conn = database.connect(str(tmp_path / "history-validation.db"))
    with pytest.raises(ValueError):
        database.add_manual_rating_history(conn, recorded_at=1, rating=30001)
    with pytest.raises(ValueError):
        database.add_manual_rating_history(conn, recorded_at=1, rating=1, note="x" * 201)
    conn.close()


def test_alias_replace_is_atomic_and_recommendation_exclusions_persist(tmp_path):
    conn = database.connect(str(tmp_path / "product.db"))
    assert database.replace_song_aliases(
        conn,
        {1: ["潘多拉", "ＰＡＮＤＯＲＡ", "潘多拉"], 2: ["雪月花"]},
        provider="fixture",
        updated_at=1.0,
    ) == (2, 3)
    assert database.list_song_aliases(conn)[1] == ("ＰＡＮＤＯＲＡ", "潘多拉")

    with pytest.raises(ValueError):
        database.replace_song_aliases(conn, {}, provider="fixture")
    assert len(database.list_song_aliases(conn)) == 2

    database.set_recommendation_excluded(conn, "1:DX:3", True)
    assert database.recommendation_exclusions(conn) == {"1:DX:3"}
    database.set_recommendation_excluded(conn, "1:DX:3", False)
    assert database.recommendation_exclusions(conn) == set()
    conn.close()
