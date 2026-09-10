from __future__ import annotations

from collections.abc import Callable
import json
import sqlite3

import requests

from . import database


LXNS_ALIAS_URL = "https://maimai.lxns.net/api/v0/maimai/alias/list"
YUZU_ALIAS_URL = "https://www.yuzuchan.moe/api/v2/aliases/maimaidx/aliases"


def parse_lxns_aliases(payload: str | bytes | dict) -> dict[int, tuple[str, ...]]:
    root = _json(payload)
    return _merge_entries(root.get("aliases", []) if isinstance(root, dict) else [], yuzu_ids=False)


def parse_yuzu_aliases(payload: str | bytes | list) -> dict[int, tuple[str, ...]]:
    root = _json(payload)
    return _merge_entries(root if isinstance(root, list) else [], yuzu_ids=True)


def merge_alias_catalogs(*catalogs: dict[int, tuple[str, ...]]) -> dict[int, tuple[str, ...]]:
    merged: dict[int, dict[str, str]] = {}
    for catalog in catalogs:
        for song_id, aliases in catalog.items():
            target = merged.setdefault(song_id, {})
            for alias in aliases:
                target.setdefault(alias.casefold(), alias)
    return {
        song_id: tuple(sorted(values.values(), key=lambda item: item.casefold()))
        for song_id, values in sorted(merged.items())
        if values
    }


def refresh_alias_catalog(
    conn: sqlite3.Connection,
    *,
    fetch: Callable[[str], str] | None = None,
) -> tuple[int, int]:
    fetcher = fetch or _fetch_text
    catalogs: list[dict[int, tuple[str, ...]]] = []
    errors: list[str] = []
    for name, url, parser in (
        ("LXNS", LXNS_ALIAS_URL, parse_lxns_aliases),
        ("Yuzu", YUZU_ALIAS_URL, parse_yuzu_aliases),
    ):
        try:
            parsed = parser(fetcher(url))
            if parsed:
                catalogs.append(parsed)
            else:
                errors.append(f"{name}=empty")
        except Exception as exc:
            errors.append(f"{name}={type(exc).__name__}")
    if not catalogs:
        raise RuntimeError("Unable to obtain a usable community alias catalog: " + ", ".join(errors))
    merged = merge_alias_catalogs(*catalogs)
    incoming_songs, incoming_aliases = _metrics(merged)
    existing = database.list_song_aliases(conn)
    if existing:
        existing_songs, existing_aliases = _metrics(existing)
        if incoming_songs < _retained_minimum(existing_songs) or incoming_aliases < _retained_minimum(existing_aliases):
            raise RuntimeError(
                f"Refusing unsafe alias refresh: incoming {incoming_songs}/{incoming_aliases}, "
                f"existing {existing_songs}/{existing_aliases}"
            )
    return database.replace_song_aliases(conn, merged, provider="LXNS+Yuzu")


def _merge_entries(entries: list, *, yuzu_ids: bool) -> dict[int, tuple[str, ...]]:
    result: dict[int, dict[str, str]] = {}
    for item in entries:
        if not isinstance(item, dict):
            continue
        try:
            song_id = int(item.get("song_id", -1))
        except (TypeError, ValueError):
            continue
        if yuzu_ids and 10_000 <= song_id < 100_000:
            song_id %= 10_000
        if song_id <= 0:
            continue
        aliases = item.get("alias" if yuzu_ids else "aliases")
        if not isinstance(aliases, list):
            continue
        target = result.setdefault(song_id, {})
        for raw in aliases:
            alias = str(raw).strip()
            if alias:
                target.setdefault(alias.casefold(), alias)
    return {
        song_id: tuple(sorted(values.values(), key=lambda item: item.casefold()))
        for song_id, values in result.items()
        if values
    }


def _json(payload):
    return json.loads(payload) if isinstance(payload, (str, bytes, bytearray)) else payload


def _fetch_text(url: str) -> str:
    response = requests.get(url, timeout=(5, 20), headers={"User-Agent": "FluentMai-Windows/2"})
    response.raise_for_status()
    if len(response.content) > 16 * 1024 * 1024:
        raise ValueError("alias response exceeds 16 MiB")
    return response.text


def _metrics(catalog) -> tuple[int, int]:
    return len(catalog), sum(len(aliases) for aliases in catalog.values())


def _retained_minimum(value: int) -> int:
    return (value * 80 + 99) // 100
