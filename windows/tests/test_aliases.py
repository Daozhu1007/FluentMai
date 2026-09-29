from __future__ import annotations

import json

import pytest

from fluentmai_core import database
from fluentmai_core.aliases import LXNS_ALIAS_URL, YUZU_ALIAS_URL, merge_alias_catalogs, parse_lxns_aliases, parse_yuzu_aliases, refresh_alias_catalog


def test_alias_parsers_merge_sources_and_normalize_yuzu_dx_ids():
    lxns = parse_lxns_aliases(json.dumps({"aliases": [{"song_id": 834, "aliases": ["潘多拉", "PANDORA"]}]}))
    yuzu = parse_yuzu_aliases(json.dumps([{"song_id": 10834, "alias": ["潘多拉", "Pandora Paradox"]}]))
    merged = merge_alias_catalogs(lxns, yuzu)

    assert set(merged) == {834}
    assert merged[834] == ("PANDORA", "Pandora Paradox", "潘多拉")


def test_alias_refresh_accepts_one_available_source_and_preserves_old_on_regression(tmp_path):
    conn = database.connect(str(tmp_path / "aliases.db"))

    def initial(url: str) -> str:
        if url == LXNS_ALIAS_URL:
            return json.dumps({"aliases": [{"song_id": index, "aliases": [f"Alias {index}"]} for index in range(1, 11)]})
        raise RuntimeError("Yuzu offline")

    assert refresh_alias_catalog(conn, fetch=initial) == (10, 10)

    def truncated(url: str) -> str:
        if url == LXNS_ALIAS_URL:
            return json.dumps({"aliases": [{"song_id": 1, "aliases": ["Only one"]}]})
        raise RuntimeError("Yuzu offline")

    with pytest.raises(RuntimeError, match="unsafe alias refresh"):
        refresh_alias_catalog(conn, fetch=truncated)
    assert len(database.list_song_aliases(conn)) == 10
    conn.close()
