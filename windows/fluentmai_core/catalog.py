from __future__ import annotations

import json
from typing import Any

import requests

from . import database
from .models import Chart, MajorVersion, Song, normalize_song_type
from .privacy import redactor


DIVING_FISH_MUSIC_DATA_URL = "https://www.diving-fish.com/api/maimaidxprober/music_data"
LXNS_SONG_LIST_URL = "https://maimai.lxns.net/api/v0/maimai/song/list?notes=true"
HTTP_TIMEOUT = 30


def parse_diving_fish_music_data(payload: list[dict[str, Any]]) -> tuple[list[Song], list[Chart]]:
    songs: list[Song] = []
    charts: list[Chart] = []
    for item in payload:
        try:
            song_id = int(item["id"])
        except (KeyError, TypeError, ValueError):
            continue
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        basic = item.get("basic_info") or {}
        song_type = normalize_song_type(item.get("type"))
        songs.append(
            Song(
                song_id=song_id,
                title=title,
                artist=str(basic.get("artist") or ""),
                genre=str(basic.get("genre") or ""),
                bpm=_optional_int(basic.get("bpm")),
                provider="diving-fish",
            )
        )
        ds = item.get("ds") or []
        levels = item.get("level") or []
        chart_infos = item.get("charts") or []
        for idx in range(max(len(ds), len(levels), len(chart_infos))):
            level = str(levels[idx]) if idx < len(levels) and levels[idx] is not None else ""
            level_value = _optional_float(ds[idx] if idx < len(ds) else None)
            info = chart_infos[idx] if idx < len(chart_infos) and isinstance(chart_infos[idx], dict) else {}
            charts.append(
                Chart(
                    song_id=song_id,
                    chart_type=song_type,
                    difficulty_index=idx,
                    level=level,
                    level_value=level_value,
                    charter=str(info.get("charter") or ""),
                    notes_total=_optional_int(info.get("notes") or info.get("combo")),
                )
            )
    return songs, charts


def parse_lxns_song_list(payload: dict[str, Any]) -> tuple[list[Song], list[Chart]]:
    root = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    raw_songs = root.get("songs") if isinstance(root, dict) else []
    version_names = _parse_lxns_version_names(root.get("versions") if isinstance(root, dict) else None)
    songs: list[Song] = []
    charts: list[Chart] = []
    for item in raw_songs or []:
        if not isinstance(item, dict):
            continue
        song_id = _optional_int(item.get("id"))
        title = str(item.get("title") or "").strip()
        if song_id is None or not title:
            continue
        songs.append(
            Song(
                song_id=song_id,
                title=title,
                artist=str(item.get("artist") or ""),
                genre=str(item.get("genre") or ""),
                version=_optional_int(item.get("version")),
                bpm=_optional_int(item.get("bpm")),
                map=str(item.get("map") or ""),
                rights=str(item.get("rights") or ""),
                locked=bool(item.get("locked", False)),
                disabled=bool(item.get("disabled", False)),
                jacket_url=f"https://assets2.lxns.net/maimai/jacket/{song_id}.png",
                provider="lxns",
            )
        )
        difficulties = item.get("difficulties") or {}
        for chart_type, key in (("SD", "standard"), ("DX", "dx")):
            for chart_item in difficulties.get(key) or []:
                chart = _parse_lxns_chart(song_id, chart_type, chart_item, version_names, is_utage=False)
                if chart:
                    charts.append(chart)
        for chart_item in difficulties.get("utage") or []:
            chart = _parse_lxns_chart(song_id, "UTAGE", chart_item, version_names, is_utage=True)
            if chart:
                charts.append(chart)
    return songs, charts


def parse_lxns_major_versions(payload: dict[str, Any]) -> list[MajorVersion]:
    root = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    raw_versions = root.get("versions") if isinstance(root, dict) else None
    names = _parse_lxns_version_names(raw_versions)
    return [
        MajorVersion(version_id=version_id, name=name, provider="lxns")
        for version_id, name in sorted(names.items())
    ]


def sync_diving_fish_catalog(
    db_path: str | None = None,
    session: requests.Session | None = None,
    *,
    replace: bool = False,
) -> int:
    session = session or requests.Session()
    response = session.get(DIVING_FISH_MUSIC_DATA_URL, timeout=HTTP_TIMEOUT)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, list):
        raise ValueError("Diving-Fish music_data did not return a list")
    songs, charts = parse_diving_fish_music_data(payload)
    conn = database.connect(db_path)
    try:
        database.insert_cache(conn, "diving-fish", "music_data", json.dumps(payload, ensure_ascii=False))
        if replace:
            database.replace_catalog(conn, songs, charts)
        else:
            database.upsert_catalog(conn, songs, charts)
        return len(songs)
    finally:
        conn.close()


def sync_lxns_catalog(db_path: str | None = None, session: requests.Session | None = None) -> int:
    session = session or requests.Session()
    response = session.get(
        LXNS_SONG_LIST_URL,
        timeout=HTTP_TIMEOUT,
        headers={"Accept": "application/json", "User-Agent": "FluentMai Windows"},
    )
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError("LXNS song list did not return an object")
    songs, charts = parse_lxns_song_list(payload)
    major_versions = parse_lxns_major_versions(payload)
    conn = database.connect(db_path)
    try:
        database.insert_cache(conn, "lxns", "song_list_notes", json.dumps(payload, ensure_ascii=False))
        database.replace_catalog(conn, songs, charts, major_versions)
        return len(songs)
    finally:
        conn.close()


def safe_api_error(exc: Exception) -> str:
    return redactor.redact(exc)


def _parse_lxns_chart(
    song_id: int,
    chart_type: str,
    item: dict[str, Any],
    version_names: dict[int, str],
    is_utage: bool,
) -> Chart | None:
    if not isinstance(item, dict):
        return None
    difficulty_index = _optional_int(item.get("difficulty"))
    if difficulty_index is None:
        return None
    notes = item.get("notes") if isinstance(item.get("notes"), dict) else {}
    chart_version = _optional_int(item.get("version"))
    return Chart(
        song_id=song_id,
        chart_type=chart_type,
        difficulty_index=difficulty_index,
        level=str(item.get("level") or ""),
        level_value=_optional_float(item.get("level_value")),
        charter=str(item.get("note_designer") or ""),
        chart_version=chart_version,
        chart_version_name=version_names.get(chart_version or 0, ""),
        notes_total=_optional_int(notes.get("total")),
        notes_tap=_optional_int(notes.get("tap")),
        notes_hold=_optional_int(notes.get("hold")),
        notes_slide=_optional_int(notes.get("slide")),
        notes_touch=_optional_int(notes.get("touch")),
        notes_break=_optional_int(notes.get("break")),
        is_utage=is_utage,
    )


def _parse_lxns_version_names(value: Any) -> dict[int, str]:
    if not isinstance(value, list):
        return {}
    result: dict[int, str] = {}
    for item in value:
        if not isinstance(item, dict):
            continue
        version = _optional_int(item.get("version"))
        title = str(item.get("title") or "").strip()
        if version is not None and title:
            result[version] = title
    return result


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
