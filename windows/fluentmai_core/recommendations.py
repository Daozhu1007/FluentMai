from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
import math

from .chart_browser import ChartRecord
from .rating import calculate_dx_rating


class RecommendationBucket(str, Enum):
    OLD = "B35"
    CURRENT = "B15"

    @property
    def capacity(self) -> int:
        return 35 if self == RecommendationBucket.OLD else 15


class RecommendationReason(str, Enum):
    TARGET_COMPLETED = "目标已完成"
    ALREADY_IN_BEST_SET = "已在 Best 集合，提升会直接增加总 Rating"
    ENTERS_BEST_SET = "目标成绩会进入 Best 集合并替换尾项"
    TIES_BEST_SET_CUTOFF = "目标成绩与 Best 尾项并列"
    BELOW_BEST_SET_CUTOFF = "目标成绩仍低于 Best 尾项"


class RecommendationAvailability(str, Enum):
    AVAILABLE = "available"
    CURRENT_VERSION_UNAVAILABLE = "current_version_unavailable"
    NO_ELIGIBLE_SCORES = "no_eligible_scores"


class VersionAgeFilter(str, Enum):
    ALL = "all"
    CURRENT = "current"
    OLD = "old"


@dataclass(frozen=True)
class RecommendationFilters:
    target_total_rating: int | None = None
    target_achievement: float | None = None
    constant_min: float | None = None
    constant_max: float | None = None
    version_age: VersionAgeFilter = VersionAgeFilter.ALL
    exclude_sss_plus: bool = True
    excluded_identities: frozenset[str] = frozenset()
    only_b50_gain: bool = True


@dataclass(frozen=True)
class Recommendation:
    identity_key: str
    chart: ChartRecord
    bucket: RecommendationBucket
    current_achievement: float
    current_single_rating: int
    target_achievement: float
    target_single_rating: int
    theoretical_single_gain: int
    actual_b50_gain: int
    current_total_rating: int
    projected_total_rating: int
    bucket_cutoff_rating: int
    was_in_best_set: bool
    will_enter_best_set: bool
    is_completed: bool
    reason: RecommendationReason


@dataclass(frozen=True)
class RecommendationResult:
    availability: RecommendationAvailability
    current_total_rating: int
    old_best_cutoff: int | None
    current_best_cutoff: int | None
    eligible_played_charts: int
    recommendations: tuple[Recommendation, ...]


@dataclass(frozen=True)
class _Candidate:
    chart: ChartRecord
    identity_key: str
    bucket: RecommendationBucket
    level_value: float
    achievement: float
    rating: int


@dataclass(frozen=True)
class _BucketState:
    candidates: tuple[_Candidate, ...]
    best: tuple[_Candidate, ...]
    best_keys: frozenset[str]
    total_rating: int
    cutoff_rating: int
    has_full_best_set: bool


def build_recommendations(
    records: list[ChartRecord],
    current_version_id: int | None,
    filters: RecommendationFilters = RecommendationFilters(),
) -> RecommendationResult:
    _validate_filters(filters)
    if current_version_id is None:
        return _unavailable(RecommendationAvailability.CURRENT_VERSION_UNAVAILABLE)
    candidates = tuple(
        candidate for record in records
        if (candidate := _to_candidate(record, current_version_id)) is not None
    )
    if not candidates:
        return _unavailable(RecommendationAvailability.NO_ELIGIBLE_SCORES)

    states: dict[RecommendationBucket, _BucketState] = {}
    for bucket in RecommendationBucket:
        bucket_candidates = tuple(sorted(
            (candidate for candidate in candidates if candidate.bucket == bucket),
            key=_candidate_sort_key,
        ))
        best = bucket_candidates[:bucket.capacity]
        states[bucket] = _BucketState(
            candidates=bucket_candidates,
            best=best,
            best_keys=frozenset(item.identity_key for item in best),
            total_rating=sum(item.rating for item in best),
            cutoff_rating=best[-1].rating if len(best) == bucket.capacity else 0,
            has_full_best_set=len(best) == bucket.capacity,
        )
    current_total = sum(state.total_rating for state in states.values())
    requirements_specified = filters.target_total_rating is not None or filters.target_achievement is not None
    results: list[Recommendation] = []

    for candidate in candidates:
        if not _matches(candidate, filters):
            continue
        state = states[candidate.bucket]
        was_in_best = candidate.identity_key in state.best_keys
        completed = (
            (filters.target_total_rating is None or current_total >= filters.target_total_rating)
            and (filters.target_achievement is None or candidate.achievement >= filters.target_achievement)
        ) if requirements_specified else candidate.achievement >= 100.5

        if completed:
            target_achievement = filters.target_achievement if filters.target_achievement is not None else candidate.achievement
            target_rating = candidate.rating
        else:
            target_achievement = _resolve_target(candidate, filters, current_total, state, was_in_best, not requirements_specified)
            if target_achievement is None:
                continue
            target_achievement = min(101.0, max(candidate.achievement, target_achievement))
            target_rating = calculate_dx_rating(candidate.level_value, target_achievement)

        target = replace(candidate, achievement=target_achievement, rating=target_rating)
        simulated = tuple(sorted(
            (target if item.identity_key == candidate.identity_key else item for item in state.candidates),
            key=_candidate_sort_key,
        ))[:candidate.bucket.capacity]
        projected = current_total - state.total_rating + sum(item.rating for item in simulated)
        actual_gain = max(0, projected - current_total)
        will_enter = any(item.identity_key == candidate.identity_key for item in simulated)
        theoretical_gain = max(0, target_rating - candidate.rating)

        if filters.target_total_rating is not None and current_total < filters.target_total_rating and projected < filters.target_total_rating:
            continue
        if filters.only_b50_gain and actual_gain <= 0:
            continue
        reason = (
            RecommendationReason.TARGET_COMPLETED if completed else
            RecommendationReason.ALREADY_IN_BEST_SET if was_in_best else
            RecommendationReason.ENTERS_BEST_SET if actual_gain > 0 else
            RecommendationReason.TIES_BEST_SET_CUTOFF if will_enter else
            RecommendationReason.BELOW_BEST_SET_CUTOFF
        )
        results.append(Recommendation(
            identity_key=candidate.identity_key,
            chart=candidate.chart,
            bucket=candidate.bucket,
            current_achievement=candidate.achievement,
            current_single_rating=candidate.rating,
            target_achievement=target_achievement,
            target_single_rating=target_rating,
            theoretical_single_gain=theoretical_gain,
            actual_b50_gain=actual_gain,
            current_total_rating=current_total,
            projected_total_rating=projected,
            bucket_cutoff_rating=state.cutoff_rating,
            was_in_best_set=was_in_best,
            will_enter_best_set=will_enter,
            is_completed=completed,
            reason=reason,
        ))

    results.sort(key=lambda item: (
        -item.actual_b50_gain,
        -item.theoretical_single_gain,
        item.target_achievement - item.current_achievement,
        -item.target_single_rating,
        -(item.chart.level_value or -1.0),
        item.chart.title,
        item.identity_key,
    ))
    return RecommendationResult(
        availability=RecommendationAvailability.AVAILABLE,
        current_total_rating=current_total,
        old_best_cutoff=states[RecommendationBucket.OLD].cutoff_rating if states[RecommendationBucket.OLD].has_full_best_set else None,
        current_best_cutoff=states[RecommendationBucket.CURRENT].cutoff_rating if states[RecommendationBucket.CURRENT].has_full_best_set else None,
        eligible_played_charts=len(candidates),
        recommendations=tuple(results),
    )


def _to_candidate(record: ChartRecord, current_version_id: int) -> _Candidate | None:
    if not record.played or record.level_value is None or record.level_value <= 0 or record.chart_version is None:
        return None
    if record.chart_version > current_version_id:
        return None
    bucket = RecommendationBucket.CURRENT if record.chart_version == current_version_id else RecommendationBucket.OLD
    achievement = min(101.0, max(0.0, float(record.achievements)))
    return _Candidate(record, record.key, bucket, record.level_value, achievement, calculate_dx_rating(record.level_value, achievement))


def _matches(candidate: _Candidate, filters: RecommendationFilters) -> bool:
    if candidate.chart.disabled or candidate.chart.locked:
        return False
    if candidate.identity_key in filters.excluded_identities:
        return False
    if filters.exclude_sss_plus and candidate.achievement >= 100.5:
        return False
    if filters.constant_min is not None and candidate.level_value < filters.constant_min:
        return False
    if filters.constant_max is not None and candidate.level_value > filters.constant_max:
        return False
    return filters.version_age == VersionAgeFilter.ALL or (
        filters.version_age == VersionAgeFilter.CURRENT and candidate.bucket == RecommendationBucket.CURRENT
    ) or (
        filters.version_age == VersionAgeFilter.OLD and candidate.bucket == RecommendationBucket.OLD
    )


def _resolve_target(candidate, filters, current_total, state, was_in_best, default_milestone):
    target = candidate.achievement
    if default_milestone:
        target = next((value for value in (97.0, 98.0, 99.0, 99.5, 100.0, 100.5) if value > candidate.achievement + 1e-7), 100.5)
    if filters.target_achievement is not None and filters.target_achievement > candidate.achievement:
        target = max(target, filters.target_achievement)
    if filters.target_total_rating is not None and filters.target_total_rating > current_total:
        gain = filters.target_total_rating - current_total
        required_rating = candidate.rating + gain if was_in_best else state.cutoff_rating + gain
        minimum = _minimum_achievement_for_rating(candidate.level_value, required_rating, target)
        if minimum is None:
            return None
        target = max(target, minimum)
    return target if target <= 101.0 else None


def _minimum_achievement_for_rating(level_value, required_rating, lower_bound):
    if calculate_dx_rating(level_value, 100.5) < required_rating:
        return None
    low = math.ceil(min(100.5, max(0.0, lower_bound)) * 10_000)
    high = 1_005_000
    while low < high:
        middle = low + (high - low) // 2
        if calculate_dx_rating(level_value, middle / 10_000.0) >= required_rating:
            high = middle
        else:
            low = middle + 1
    return low / 10_000.0


def _candidate_sort_key(candidate):
    return (-candidate.rating, -candidate.achievement, -candidate.level_value, candidate.chart.title, candidate.identity_key)


def _validate_filters(filters):
    if filters.target_total_rating is not None and not 0 <= filters.target_total_rating <= 30_000:
        raise ValueError("目标总 Rating 必须在 0 到 30000 之间")
    if filters.target_achievement is not None and (not math.isfinite(filters.target_achievement) or not 0.0 <= filters.target_achievement <= 101.0):
        raise ValueError("目标达成率必须在 0.0% 到 101.0% 之间")
    for value, label in ((filters.constant_min, "最低定数"), (filters.constant_max, "最高定数")):
        if value is not None and (not math.isfinite(value) or not 0.1 <= value <= 20.0):
            raise ValueError(f"{label}必须在 0.1 到 20.0 之间")
    if filters.constant_min is not None and filters.constant_max is not None and filters.constant_min > filters.constant_max:
        raise ValueError("最低定数不能高于最高定数")


def _unavailable(availability):
    return RecommendationResult(availability, 0, None, None, 0, ())
