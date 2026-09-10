import hashlib
from pathlib import Path
import sqlite3

import sync_data
from fluentmai_core import database
from fluentmai_core import runtime_paths


def test_all_database_entry_points_honor_explicit_runtime_path(monkeypatch, tmp_path):
    expected = tmp_path / "fluentmai-test.db"
    monkeypatch.setenv("FLUENTMAI_DB_PATH", str(expected))

    assert Path(sync_data.default_db_path()) == expected
    assert Path(database.default_db_path()) == expected


def test_default_database_paths_do_not_point_to_repository_root(monkeypatch):
    monkeypatch.delenv("FLUENTMAI_DB_PATH", raising=False)

    for path in (sync_data.default_db_path(), database.default_db_path()):
        assert Path(path).name == "maimai_data.db"


def test_default_database_uses_local_app_data(monkeypatch, tmp_path):
    monkeypatch.delenv("FLUENTMAI_DB_PATH", raising=False)
    monkeypatch.delenv("FLUENTMAI_DATA_DIR", raising=False)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "local"))

    assert Path(database.default_db_path()) == tmp_path / "local" / "FluentMai" / "data" / "maimai_data.db"


def test_legacy_database_is_copied_and_migrated_without_changing_source(monkeypatch, tmp_path):
    legacy = tmp_path / "legacy.db"
    conn = sqlite3.connect(legacy)
    conn.execute("CREATE TABLE legacy_user_data(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
    conn.execute("INSERT INTO legacy_user_data(value) VALUES ('keep-me')")
    conn.commit()
    conn.close()
    before = hashlib.sha256(legacy.read_bytes()).hexdigest()

    data_root = tmp_path / "app-data"
    monkeypatch.delenv("FLUENTMAI_DB_PATH", raising=False)
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(data_root))
    monkeypatch.setenv("FLUENTMAI_LEGACY_DB_PATH", str(legacy))

    target = runtime_paths.prepare_database_path()
    assert target == data_root / "data" / "maimai_data.db"
    assert target.is_file()
    assert not list(target.parent.glob(".*.migrating-*.tmp*"))
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == before

    migrated = database.connect()
    assert migrated.execute("SELECT value FROM legacy_user_data").fetchone()[0] == "keep-me"
    assert migrated.execute("PRAGMA user_version").fetchone()[0] == database.SCHEMA_VERSION
    assert {row[1] for row in migrated.execute("PRAGMA table_info(rating_history)")} >= {
        "note",
        "updated_at",
    }
    assert migrated.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    migrated.close()

    backups = list((data_root / "backups").glob("*.db"))
    assert len(backups) == 1
    assert runtime_paths.sqlite_integrity(backups[0]) == "ok"

    second = database.connect()
    second.close()
    assert list((data_root / "backups").glob("*.db")) == backups


def test_corrupt_legacy_database_is_not_copied(monkeypatch, tmp_path):
    corrupt = tmp_path / "corrupt.db"
    corrupt.write_bytes(b"not a sqlite database")
    data_root = tmp_path / "app-data"
    monkeypatch.delenv("FLUENTMAI_DB_PATH", raising=False)
    monkeypatch.setenv("FLUENTMAI_DATA_DIR", str(data_root))
    monkeypatch.setenv("FLUENTMAI_LEGACY_DB_PATH", str(corrupt))

    target = runtime_paths.prepare_database_path()

    assert not target.exists()
    assert corrupt.read_bytes() == b"not a sqlite database"


def test_newer_schema_is_rejected_without_deleting_data(monkeypatch, tmp_path):
    path = tmp_path / "future.db"
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE future_data(value TEXT NOT NULL)")
    conn.execute("INSERT INTO future_data(value) VALUES ('preserve')")
    conn.execute("PRAGMA user_version = 999")
    conn.commit()
    conn.close()
    monkeypatch.setenv("FLUENTMAI_DB_PATH", str(path))

    try:
        database.connect()
    except sqlite3.DatabaseError as exc:
        assert "newer than supported" in str(exc)
    else:
        raise AssertionError("newer schema should be rejected")

    check = sqlite3.connect(path)
    assert check.execute("SELECT value FROM future_data").fetchone()[0] == "preserve"
    check.close()
