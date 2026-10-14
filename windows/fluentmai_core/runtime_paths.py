from __future__ import annotations

import os
from pathlib import Path
import shutil
import sqlite3
import sys
import uuid


APP_NAME = "FluentMai"
DATABASE_NAME = "maimai_data.db"


def source_root() -> Path:
    return Path(__file__).resolve().parent.parent


def executable_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return source_root()


def user_data_root() -> Path:
    override = os.environ.get("FLUENTMAI_DATA_DIR")
    if override:
        return Path(override).expanduser().resolve()
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        return Path(local_app_data) / APP_NAME
    return Path.home() / ".local" / "share" / APP_NAME


def database_path() -> Path:
    override = os.environ.get("FLUENTMAI_DB_PATH")
    if override:
        return Path(override).expanduser().resolve()
    return user_data_root() / "data" / DATABASE_NAME


def cache_root() -> Path:
    override = os.environ.get("FLUENTMAI_CACHE_DIR")
    if override:
        return Path(override).expanduser().resolve()
    return user_data_root() / "cache"


def backup_root() -> Path:
    return user_data_root() / "backups"


def capture_root() -> Path:
    return user_data_root() / "capture"


def update_root() -> Path:
    return user_data_root() / "updates"


def settings_path() -> Path:
    """Return the per-user settings file used by portable and installed builds."""

    return user_data_root() / "settings.ini"


def legacy_database_candidates(target: Path | None = None) -> list[Path]:
    target = (target or database_path()).resolve()
    explicit = os.environ.get("FLUENTMAI_LEGACY_DB_PATH")
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    else:
        candidates.extend(
            [
                executable_root() / DATABASE_NAME,
                source_root() / DATABASE_NAME,
                Path.cwd() / DATABASE_NAME,
            ]
        )

    result: list[Path] = []
    seen: set[Path] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved == target or resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file():
            result.append(resolved)
    if explicit:
        return result
    return sorted(result, key=lambda item: item.stat().st_mtime, reverse=True)


def prepare_database_path(path: str | Path | None = None) -> Path:
    target = Path(path).expanduser().resolve() if path else database_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return target
    if path is not None or os.environ.get("FLUENTMAI_DB_PATH"):
        return target

    for source in legacy_database_candidates(target):
        if sqlite_integrity(source) != "ok":
            continue
        temporary = target.with_name(f".{target.name}.migrating-{uuid.uuid4().hex}.tmp")
        try:
            shutil.copy2(source, temporary)
            if sqlite_integrity(temporary) != "ok":
                raise sqlite3.DatabaseError("copied legacy database failed integrity check")
            os.replace(temporary, target)
            return target
        finally:
            _remove_sqlite_family(temporary)
    return target


def _remove_sqlite_family(path: Path) -> None:
    path.unlink(missing_ok=True)
    path.with_name(path.name + "-wal").unlink(missing_ok=True)
    path.with_name(path.name + "-shm").unlink(missing_ok=True)


def sqlite_integrity(path: str | Path) -> str:
    candidate = Path(path).resolve()
    uri = candidate.as_uri() + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    try:
        try:
            row = conn.execute("PRAGMA integrity_check").fetchone()
            return str(row[0]) if row else "missing"
        except sqlite3.DatabaseError:
            return "corrupt"
    finally:
        conn.close()
