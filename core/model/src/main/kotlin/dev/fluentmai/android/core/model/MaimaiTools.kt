package dev.fluentmai.android.core.model

import kotlin.math.floor

data class SingleSongRatingCalculation(
    val levelValue: Double,
    val achievement: Double,
    val cappedAchievement: Double,
    val coefficient: Double,
    val rating: Int,
    val rank: AchievementRank,
)

fun calculateSingleSongRating(
    levelValue: Double,
    achievement: Double,
): SingleSongRatingCalculation {
    require(levelValue.isFinite() && levelValue in 0.1..20.0) {
        "谱面定数必须在 0.1 到 20.0 之间"
    }
    require(achievement.isFinite() && achievement in 0.0..101.0) {
        "达成率必须在 0.0% 到 101.0% 之间"
    }
    val cappedAchievement = achievement.coerceAtMost(100.5)
    val coefficient = dxRatingCoefficient(cappedAchievement)
    return SingleSongRatingCalculation(
        levelValue = levelValue,
        achievement = achievement,
        cappedAchievement = cappedAchievement,
        coefficient = coefficient,
        rating = calculateDxRating(levelValue, achievement),
        rank = AchievementRank.fromAchievement(achievement),
    )
}

enum class MaimaiNoteKind(
    val displayName: String,
    val baseWeight: Int,
) {
    TAP("Tap", 1),
    HOLD("Hold", 2),
    SLIDE("Slide", 3),
    TOUCH("Touch", 1),
    BREAK("Break", 5),
}

enum class MaimaiJudgement(
    val displayName: String,
    internal val baseMultiplier: Double,
    internal val breakExtraMultiplier: Double,
) {
    CRITICAL_PERFECT("Critical Perfect", 1.0, 1.0),
    PERFECT_HIGH("Perfect（BREAK 2550）", 1.0, 0.75),
    PERFECT("Perfect（BREAK 2500）", 1.0, 0.5),
    GREAT("Great", 0.8, 0.4),
    GOOD("Good", 0.5, 0.3),
    MISS("Miss", 0.0, 0.0),
}

data class MaimaiNoteCounts(
    val tap: Int,
    val hold: Int,
    val slide: Int,
    val touch: Int,
    val breakCount: Int,
) {
    init {
        require(listOf(tap, hold, slide, touch, breakCount).all { it >= 0 }) {
            "物量不能为负数"
        }
        require(weightedCount > 0) { "至少需要一个音符" }
    }

    val weightedCount: Int
        get() = tap + hold * 2 + slide * 3 + touch + breakCount * 5

    val maximumAchievement: Double
        get() = 100.0 + if (breakCount > 0) 1.0 else 0.0

    fun count(kind: MaimaiNoteKind): Int =
        when (kind) {
            MaimaiNoteKind.TAP -> tap
            MaimaiNoteKind.HOLD -> hold
            MaimaiNoteKind.SLIDE -> slide
            MaimaiNoteKind.TOUCH -> touch
            MaimaiNoteKind.BREAK -> breakCount
        }
}

data class MaimaiAchievementCalculation(
    val maximumAchievement: Double,
    val lossPerJudgement: Double,
    val occurrences: Int,
    val resultingAchievement: Double,
    val targetAchievement: Double,
    val toleratedOccurrences: Int,
)

const val SSS_PLUS_TARGET_ACHIEVEMENT = 100.5

fun ChartRecord.toMaimaiNoteCountsOrNull(): MaimaiNoteCounts? {
    val source = notes ?: return null
    return runCatching {
        MaimaiNoteCounts(
            tap = source.tap ?: return null,
            hold = source.hold ?: return null,
            slide = source.slide ?: return null,
            touch = source.touch ?: return null,
            breakCount = source.breakCount ?: return null,
        )
    }.getOrNull()
}

fun ChartRecord.sssPlusTapGreatTolerance(): Int? {
    val noteCounts = toMaimaiNoteCountsOrNull() ?: return null
    if (noteCounts.maximumAchievement < SSS_PLUS_TARGET_ACHIEVEMENT) return null
    return calculateMaimaiAchievement(
        notes = noteCounts,
        noteKind = MaimaiNoteKind.TAP,
        judgement = MaimaiJudgement.GREAT,
        occurrences = 0,
        targetAchievement = SSS_PLUS_TARGET_ACHIEVEMENT,
    ).toleratedOccurrences
}

fun calculateMaimaiAchievement(
    notes: MaimaiNoteCounts,
    noteKind: MaimaiNoteKind,
    judgement: MaimaiJudgement,
    occurrences: Int,
    targetAchievement: Double,
): MaimaiAchievementCalculation {
    require(occurrences >= 0) { "判定数量不能为负数" }
    require(occurrences <= notes.count(noteKind)) { "判定数量不能超过该类音符物量" }
    require(targetAchievement.isFinite() && targetAchievement in 0.0..notes.maximumAchievement) {
        "目标达成率超出当前谱面的有效范围"
    }
    val baseUnit = 100.0 / notes.weightedCount
    val baseLoss = noteKind.baseWeight * baseUnit * (1.0 - judgement.baseMultiplier)
    val breakExtraLoss = if (noteKind == MaimaiNoteKind.BREAK && notes.breakCount > 0) {
        (1.0 - judgement.breakExtraMultiplier) / notes.breakCount
    } else {
        0.0
    }
    val lossPerJudgement = baseLoss + breakExtraLoss
    val resultingAchievement = (notes.maximumAchievement - lossPerJudgement * occurrences)
        .coerceIn(0.0, notes.maximumAchievement)
    val availableLoss = (notes.maximumAchievement - targetAchievement).coerceAtLeast(0.0)
    val toleratedOccurrences = if (lossPerJudgement == 0.0) {
        notes.count(noteKind)
    } else {
        floor((availableLoss + CALCULATION_EPSILON) / lossPerJudgement)
            .toInt()
            .coerceIn(0, notes.count(noteKind))
    }
    return MaimaiAchievementCalculation(
        maximumAchievement = notes.maximumAchievement,
        lossPerJudgement = lossPerJudgement,
        occurrences = occurrences,
        resultingAchievement = resultingAchievement,
        targetAchievement = targetAchievement,
        toleratedOccurrences = toleratedOccurrences,
    )
}

private const val CALCULATION_EPSILON = 1e-9
