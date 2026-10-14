from fluentmai_core.catalog import (
    parse_diving_fish_music_data,
    parse_lxns_major_versions,
    parse_lxns_song_list,
)
from fluentmai_core.providers import diving_fish_records_to_parsed, lxns_records_to_parsed


def test_diving_fish_records_to_parsed():
    records = [
        {
            "title": "Alea jacta est!",
            "type": "SD",
            "level_index": 3,
            "achievements": 101,
            "dxScore": 2711,
            "fc": "fc",
            "fs": "",
        }
    ]

    parsed = diving_fish_records_to_parsed(records)

    assert parsed[0].title == "Alea jacta est!"
    assert parsed[0].song_type == "SD"
    assert parsed[0].difficulty_index == 3
    assert parsed[0].dx_score == 2711


def test_lxns_records_to_parsed():
    records = [
        {
            "id": 834,
            "song_name": "LXNS Song",
            "type": "standard",
            "level_index": 4,
            "achievements": 100.75,
            "dx_score": 2500,
            "fc": "app",
            "fs": None,
            "play_time": "2023-12-31T16:00:00Z",
        }
    ]

    parsed = lxns_records_to_parsed(records)

    assert parsed[0].song_id == 834
    assert parsed[0].song_type == "SD"
    assert parsed[0].full_combo == "app"
    assert parsed[0].play_time == "2023-12-31T16:00:00Z"


def test_parse_lxns_song_list_with_notes():
    payload = {
        "versions": [
            {"version": 10000, "title": "maimai"},
            {"version": 24500, "title": "舞萌DX 2024"},
        ],
        "songs": [
            {
                "id": 8,
                "title": "True Love Song",
                "artist": "Kai",
                "genre": "maimai",
                "bpm": 150,
                "version": 10000,
                "difficulties": {
                    "standard": [
                        {
                            "type": "standard",
                            "difficulty": 3,
                            "level": "12",
                            "level_value": 12.4,
                            "note_designer": "Nya",
                            "version": 10000,
                            "notes": {"total": 302, "tap": 263, "hold": 14, "slide": 19, "touch": 0, "break": 6},
                        }
                    ],
                    "dx": [],
                    "utage": [
                        {
                            "type": "utage",
                            "difficulty": 0,
                            "level": "14+?",
                            "level_value": 0,
                            "note_designer": "",
                            "version": 24500,
                            "notes": {"total": 1000, "tap": 700, "hold": 20, "slide": 80, "touch": 0, "break": 200},
                        }
                    ],
                },
            }
        ]
    }

    songs, charts = parse_lxns_song_list(payload)
    versions = parse_lxns_major_versions(payload)

    assert songs[0].song_id == 8
    assert songs[0].bpm == 150
    assert charts[0].chart_type == "SD"
    assert charts[0].notes_total == 302
    assert charts[0].chart_version_name == "maimai"
    assert charts[1].chart_type == "UTAGE"
    assert charts[1].is_utage is True
    assert charts[1].notes_total == 1000
    assert [(item.version_id, item.name) for item in versions] == [
        (10000, "maimai"),
        (24500, "舞萌DX 2024"),
    ]


def test_parse_diving_fish_music_data():
    songs, charts = parse_diving_fish_music_data(
        [
            {
                "id": "100",
                "title": "DF Song",
                "type": "DX",
                "ds": [1.0, 2.0],
                "level": ["1", "2"],
                "charts": [{"charter": "-"}, {"charter": "Charter"}],
                "basic_info": {"artist": "Artist", "genre": "Genre", "bpm": 180},
            }
        ]
    )

    assert songs[0].title == "DF Song"
    assert charts[1].chart_type == "DX"
    assert charts[1].level_value == 2.0
