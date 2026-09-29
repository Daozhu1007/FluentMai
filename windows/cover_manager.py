"""
Jacket image resolution and cache helpers.

Read order:
1. user cache (%LOCALAPPDATA%/FluentMai/cache/jackets)
2. source checkout assets/jackets while developing
3. PyInstaller bundled assets/jackets
4. legacy executable-adjacent assets/jackets for old builds

Writes only go to the user cache.
"""

from __future__ import annotations

from dataclasses import dataclass
import logging
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

from PyQt6.QtCore import QThread, pyqtSignal
from PyQt6.QtGui import QImage
import requests
from fluentmai_core.runtime_paths import cache_root


APP_NAME = "FluentMai"
SUPPORTED_EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp", ".bmp")
LXNS_BASE_URL = "https://assets2.lxns.net/maimai/jacket/{}.png"
DIVING_FISH_BASE_URL = "https://www.diving-fish.com/covers/{}.png"
HTTP_TIMEOUT = 10

logger = logging.getLogger("cover_manager")


@dataclass(frozen=True)
class JacketLocation:
    path: Path
    source: str


def source_root() -> Path:
    return Path(__file__).resolve().parent


def bundled_root() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS).resolve()
    return source_root()


def executable_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return source_root()


def user_cache_root() -> Path:
    return cache_root()


def jacket_cache_dir() -> Path:
    return user_cache_root() / "jackets"


def static_jacket_dirs() -> list[tuple[str, Path]]:
    candidates: list[tuple[str, Path]] = [
        ("source", source_root() / "assets" / "jackets"),
        ("bundled", bundled_root() / "assets" / "jackets"),
        ("legacy-exe", executable_root() / "assets" / "jackets"),
    ]
    result: list[tuple[str, Path]] = []
    seen: set[Path] = set()
    for label, path in candidates:
        resolved = path.resolve()
        if resolved not in seen:
            seen.add(resolved)
            result.append((label, resolved))
    return result


def jacket_key_candidates(song_id: int | str | None, jacket_url: str = "") -> list[str]:
    candidates: list[str] = []

    def add(value: object) -> None:
        text = str(value).strip()
        if text and text not in candidates:
            candidates.append(text)

    if jacket_url:
        parsed = urlparse(jacket_url)
        name = Path(parsed.path).name
        if name:
            add(Path(name).stem)
            stripped = Path(name).stem.lstrip("0")
            if stripped:
                add(stripped)

    if song_id is not None:
        raw = str(song_id).strip()
        if raw:
            add(raw)
            if raw.isdigit():
                add(str(int(raw)))
                add(raw.zfill(5))
                add(raw.zfill(4))

    return candidates


def jacket_filename_candidates(song_id: int | str | None, jacket_url: str = "") -> list[str]:
    names: list[str] = []

    def add(name: str) -> None:
        if name and name not in names:
            names.append(name)

    if jacket_url:
        parsed = urlparse(jacket_url)
        url_name = Path(parsed.path).name
        if Path(url_name).suffix.lower() in SUPPORTED_EXTENSIONS:
            add(url_name)

    for key in jacket_key_candidates(song_id, jacket_url):
        for ext in SUPPORTED_EXTENSIONS:
            add(f"{key}{ext}")
    return names


def _file_index(directory: Path) -> dict[str, Path]:
    if not directory.is_dir():
        return {}
    return {path.name.casefold(): path for path in directory.iterdir() if path.is_file()}


def _find_in_dir(directory: Path, names: list[str]) -> Path | None:
    indexed = _file_index(directory)
    for name in names:
        path = indexed.get(name.casefold())
        if path and path.stat().st_size > 0:
            return path
    return None


def resolve_jacket_location(song_id: int | str | None, jacket_url: str = "") -> JacketLocation | None:
    names = jacket_filename_candidates(song_id, jacket_url)
    cache_hit = _find_in_dir(jacket_cache_dir(), names)
    if cache_hit:
        return JacketLocation(cache_hit, "user-cache")

    for source, directory in static_jacket_dirs():
        hit = _find_in_dir(directory, names)
        if hit:
            return JacketLocation(hit, source)
    return None


def resolve_jacket_path(song_id: int | str | None, jacket_url: str = "") -> str | None:
    location = resolve_jacket_location(song_id, jacket_url)
    return str(location.path) if location else None


def writable_jacket_path(song_id: int | str | None, jacket_url: str = "") -> Path:
    keys = jacket_key_candidates(song_id, jacket_url)
    key = keys[0] if keys else "unknown"
    return jacket_cache_dir() / f"{key}.png"


def cover_path(song_id: int, jacket_url: str = "") -> str:
    return resolve_jacket_path(song_id, jacket_url) or str(writable_jacket_path(song_id, jacket_url))


def has_cover(song_id: int, jacket_url: str = "") -> bool:
    return resolve_jacket_path(song_id, jacket_url) is not None


def _ensure_cache_dir() -> None:
    jacket_cache_dir().mkdir(parents=True, exist_ok=True)


def _build_urls(song_id: int | str | None, jacket_url: str = "") -> list[str]:
    urls: list[str] = []

    def add(url: str) -> None:
        if url and url not in urls:
            urls.append(url)

    if jacket_url:
        add(jacket_url)
    for key in jacket_key_candidates(song_id, jacket_url):
        add(LXNS_BASE_URL.format(key))
    for key in jacket_key_candidates(song_id, jacket_url):
        add(DIVING_FISH_BASE_URL.format(key))
    return urls


def download_cover_sync(song_id: int, jacket_url: str = "") -> str | None:
    existing = resolve_jacket_path(song_id, jacket_url)
    if existing:
        return existing

    if os.environ.get("FLUENTMAI_DISABLE_JACKET_NETWORK") == "1":
        return None

    _ensure_cache_dir()
    target = writable_jacket_path(song_id, jacket_url)
    urls = _build_urls(song_id, jacket_url)

    for url in urls:
        tmp = target.with_suffix(target.suffix + ".tmp")
        try:
            response = requests.get(url, timeout=HTTP_TIMEOUT, stream=True)
            if response.status_code == 404:
                continue
            response.raise_for_status()
            with tmp.open("wb") as file:
                for chunk in response.iter_content(8192):
                    if chunk:
                        file.write(chunk)
            tmp.replace(target)
            if QImage(str(target)).isNull():
                target.unlink(missing_ok=True)
                continue
            return str(target)
        except requests.RequestException:
            if tmp.exists():
                tmp.unlink(missing_ok=True)
            continue
        except OSError:
            logger.exception("Failed to write jacket cache for song %s", song_id)
            return None

    logger.debug("Jacket not found for song %s (tried %d URLs)", song_id, len(urls))
    return resolve_jacket_path(song_id, jacket_url)


class BulkCoverWorker(QThread):
    cover_done = pyqtSignal(int, str)
    all_done = pyqtSignal()

    def __init__(self, song_ids: list[int] | list[tuple[int, str]], parent=None):
        super().__init__(parent)
        self.items: list[tuple[int, str]] = []
        for item in song_ids:
            if isinstance(item, tuple):
                self.items.append((int(item[0]), str(item[1] or "")))
            else:
                self.items.append((int(item), ""))

    def run(self) -> None:
        for song_id, jacket_url in self.items:
            path = download_cover_sync(song_id, jacket_url)
            if path:
                self.cover_done.emit(song_id, path)
        self.all_done.emit()
