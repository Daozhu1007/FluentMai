from __future__ import annotations

import csv
from pathlib import Path

import pytest

from fluentmai_core.chart_browser import ChartFilters, ChartRecord, query_chart_records
from fluentmai_core.player_records import (
    AchievementRank,
    FullComboStatus,
    FullSyncStatus,
    PlateKind,
    calculate_plate_progress,
    player_stats,
)
from fluentmai_core.recommendations import (
    RecommendationBucket,
    RecommendationFilters,
    RecommendationReason,
    build_recommendations,
)
from fluentmai_core.tools import Judgement, NoteCounts, NoteKind, calculate_achievement, calculate_single_song_rating
from fluentmai_core.version_catalog import plate_version_for, version_name_for, version_reference_for


FIXTURES = Path(__file__).resolve().parents[2] / "test-fixtures"


def _rows(path: str):
    with (FIXTURES / path).open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def _chart(
    song_id: int,
    *,
    chart_type: str = "DX",
    difficulty_index: int = 3,
    chart_version: int = 25500,
    level_value: float = 13.5,
    achievement: float | None = None,
    full_combo: str = "",
    full_sync: str = "",
    disabled: bool = False,
    aliases: tuple[str, ...] = (),
    title: str | None = None,
):
    return ChartRecord(
        song_id=song_id,
        title=title or f"Song {song_id}",
        title_norm=(title or f"Song {song_id}").lower(),
        artist="Artist",
        genre="maimai",
        song_version=chart_version,
        bpm=180,
        map_name="",
        jacket_url="",
        chart_type=chart_type,
        difficulty_index=difficulty_index,
        difficulty_name=["Basic", "Advanced", "Expert", "Master", "Re:MASTER"][difficulty_index],
        level="13+",
        level_value=level_value,
        charter="Designer",
        chart_version=chart_version,
        chart_version_name="Version",
        notes_total=500,
        notes_tap=300,
        notes_hold=50,
        notes_slide=50,
        notes_touch=50,
        notes_break=50,
        is_utage=False,
        aliases=aliases,
        disabled=disabled,
        achievements=achievement,
        full_combo=full_combo,
        full_sync=full_sync,
    )


def test_shared_rating_fixture_matches_android_results():
    for row in _rows("rating/dx-rating.tsv"):
        result = calculate_single_song_rating(float(row["level_value"]), float(row["achievement"]))
        assert result.rating == int(row["expected_rating"]), row["case"]


def test_shared_score_loss_fixture_matches_android_results():
    for row in _rows("score-loss/judgement-loss.tsv"):
        notes = NoteCounts(*(int(row[key]) for key in ("tap", "hold", "slide", "touch", "break")))
        result = calculate_achievement(
            notes,
            NoteKind[row["note_kind"]],
            Judgement[row["judgement"]],
            int(row["occurrences"]),
            float(row["target"]),
        )
        assert result.loss_per_judgement == pytest.approx(float(row["expected_loss"]), abs=1e-12), row["case"]
        assert result.tolerated_occurrences == int(row["expected_tolerated"]), row["case"]
        assert result.resulting_achievement == pytest.approx(float(row["expected_result"]), abs=1e-12), row["case"]


def test_player_stats_count_exact_android_rank_combo_and_sync_states():
    records = [
        _chart(
            int(row["song_id"]),
            achievement=float(row["achievement"]) if row["achievement"] else None,
            full_combo=row["full_combo"],
            full_sync=row["full_sync"],
        )
        for row in _rows("player-records/stats.tsv")
    ]
    stats = player_stats(records)
    assert (stats.played_charts, stats.unplayed_charts) == (2, 1)
    assert stats.rank_counts[AchievementRank.SSS_PLUS] == 1
    assert stats.rank_counts[AchievementRank.SSS] == 1
    assert stats.full_combo_counts[FullComboStatus.AP_PLUS] == 1
    assert stats.full_sync_counts[FullSyncStatus.FSD_PLUS] == 1


def test_shared_finale_fixture_includes_pandora_master_and_uses_bright_plate():
    records = [
        _chart(
            int(row["song_id"]),
            chart_type=row["chart_type"],
            difficulty_index=int(row["difficulty_index"]),
            chart_version=int(row["chart_version"]),
            achievement=float(row["achievement"]),
            title="PANDORA PARADOXXX" if row["song_id"] == "834" else None,
        )
        for row in _rows("plate/finale-general.tsv")
    ]
    progress = calculate_plate_progress(records, PlateKind.GENERAL, 19900)
    assert progress.plate_name == "輝将"
    assert progress.required_count == 1
    assert progress.completed_count == 1
    assert progress.eligible_records[0].song_id == 834
    assert progress.is_complete


def test_version_fixture_uses_android_canonical_boundaries():
    for row in _rows("version-catalog/versions.tsv"):
        version_id = int(row["version_id"])
        assert version_name_for(version_id) == row["expected_name"]
        if row["expected_plate_prefix"]:
            assert plate_version_for(version_id).prefixes[0] == row["expected_plate_prefix"]
    assert version_reference_for(25501).generation == "DX 世代"


def test_search_normalizes_traditional_width_punctuation_and_song_alias():
    records = [
        _chart(1, title="繁体谱面"),
        _chart(2, title="a b-c"),
        _chart(834, title="PANDORA PARADOXXX", aliases=("潘多拉",)),
    ]
    for row in _rows("search/normalization.tsv"):
        assert query_chart_records(records, ChartFilters(search=row["query"])).total_count >= 1


def test_recommendation_matches_android_b15_replacement_and_is_deterministic():
    fixture = {row["role"]: row for row in _rows("recommendations/b15-replacement.tsv")}
    cutoff_row = fixture["cutoff"]
    candidate_row = fixture["candidate"]
    cutoff = [_chart(index, chart_version=int(cutoff_row["chart_version"]), level_value=float(cutoff_row["level_value"]), achievement=float(cutoff_row["achievement"])) for index in range(1, 16)]
    candidate = _chart(int(candidate_row["song_id"]), chart_version=int(candidate_row["chart_version"]), level_value=float(candidate_row["level_value"]), achievement=float(candidate_row["achievement"]))
    current_total = sum(item.rating for item in cutoff)
    filters = RecommendationFilters(target_total_rating=current_total + 5)

    first = build_recommendations(cutoff + [candidate], 25500, filters)
    second = build_recommendations(cutoff + [candidate], 25500, filters)
    recommendation = next(item for item in first.recommendations if item.identity_key == candidate.key)

    assert first == second
    assert recommendation.bucket == RecommendationBucket.CURRENT
    assert recommendation.bucket_cutoff_rating == 292
    assert not recommendation.was_in_best_set
    assert recommendation.will_enter_best_set
    assert recommendation.reason == RecommendationReason.ENTERS_BEST_SET
    assert recommendation.actual_b50_gain >= 5


def test_recommendation_inside_b15_directly_raises_total_and_exclusions_apply():
    candidate = _chart(1, chart_version=25500, level_value=13.5, achievement=100.0)
    others = [_chart(index, chart_version=25500, level_value=12.0, achievement=100.5) for index in range(2, 16)]
    result = build_recommendations(
        [candidate] + others,
        25500,
        RecommendationFilters(target_achievement=100.5),
    )
    item = next(value for value in result.recommendations if value.identity_key == candidate.key)
    assert item.theoretical_single_gain == 12
    assert item.actual_b50_gain == 12
    assert item.reason == RecommendationReason.ALREADY_IN_BEST_SET

    excluded = build_recommendations(
        [candidate] + others,
        25500,
        RecommendationFilters(only_b50_gain=False, excluded_identities=frozenset({candidate.key})),
    )
    assert all(value.identity_key != candidate.key for value in excluded.recommendations)
