package dev.fluentmai.android.core.database

import dev.fluentmai.android.core.importer.MaimaiSongCatalog
import dev.fluentmai.android.core.model.ImportBatch
import dev.fluentmai.android.core.model.QuarantineRecord
import dev.fluentmai.android.core.model.RatingHistoryEntry
import dev.fluentmai.android.core.model.RatingHistorySource
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongType
import java.util.UUID

class FluentMaiRepository(
    private val database: FluentMaiDatabase,
) {
    suspend fun scoreCount(): Int =
        database.scoreRecordDao().count()

    suspend fun scores(): List<ScoreRecord> {
        val counts = database.playActivityDao().counts().associateBy { Triple(it.title, it.songType, it.difficulty) }
        val observed = database.playActivityDao().records().groupingBy { Triple(it.title, it.songType, it.difficulty) }.eachCount()
        return database.scoreRecordDao().getAll().map { entity ->
            val key = Triple(entity.title, entity.songType, entity.difficulty)
            entity.toModel().copy(playCount = counts[key]?.takeUnless { it.isUpperBound }?.count,
                playCountUpperBound = counts[key]?.takeIf { it.isUpperBound }?.count, observedPlayCount = observed[key] ?: 0)
        }
    }

    suspend fun playRecords() = database.playActivityDao().records().map { it.model() }
    suspend fun playCounts() = database.playActivityDao().counts().map {
        dev.fluentmai.android.core.model.ChartPlayCount(it.title, dev.fluentmai.android.core.model.SongType.valueOf(it.songType),
            dev.fluentmai.android.core.model.Difficulty.valueOf(it.difficulty), it.count, it.isUpperBound)
    }
    suspend fun savePlayRecords(records: List<dev.fluentmai.android.core.model.PlayRecord>) =
        database.playActivityDao().add(records.map { PlayRecordEntity(it.id, it.songId, it.title, it.songType.name, it.difficulty.name, it.playedAt, it.achievement, it.dxScore, it.fc, it.fs) })
    suspend fun savePlayCounts(counts: List<dev.fluentmai.android.core.model.ChartPlayCount>) =
        database.playActivityDao().saveCounts(counts.map { ChartPlayCountEntity(it.title, it.songType.name, it.difficulty.name, it.count, it.isUpperBound) })

    suspend fun quarantineCount(): Int =
        database.quarantineRecordDao().count()

    suspend fun quarantineRecords(): List<QuarantineRecord> =
        database.quarantineRecordDao().getAll().map(QuarantineRecordEntity::toModel)

    suspend fun latestImportBatch(): ImportBatch? =
        database.importBatchDao().latest()?.toModel()

    suspend fun ratingHistory(): List<RatingHistoryEntry> =
        database.ratingHistoryDao().getAll().map(RatingHistoryEntity::toModel)

    suspend fun recordAutomaticRating(
        recordedAtEpochMillis: Long,
        rating: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        validateRatingHistoryInput(recordedAtEpochMillis, rating, null)
        return database.ratingHistoryDao().insertAutomaticIfChanged(
            RatingHistoryEntity(
                id = UUID.randomUUID().toString(),
                recordedAtEpochMillis = recordedAtEpochMillis,
                rating = rating,
                source = RatingHistorySource.AUTOMATIC_IMPORT.name,
                note = null,
                createdAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    suspend fun addManualRating(
        recordedAtEpochMillis: Long,
        rating: Int,
        note: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): RatingHistoryEntry {
        val normalizedNote = note.normalizedRatingNote()
        validateRatingHistoryInput(recordedAtEpochMillis, rating, normalizedNote)
        val entity = RatingHistoryEntity(
            id = UUID.randomUUID().toString(),
            recordedAtEpochMillis = recordedAtEpochMillis,
            rating = rating,
            source = RatingHistorySource.MANUAL.name,
            note = normalizedNote,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
        database.ratingHistoryDao().insert(entity)
        return entity.toModel()
    }

    suspend fun updateManualRating(
        id: String,
        recordedAtEpochMillis: Long,
        rating: Int,
        note: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(id.isNotBlank()) { "记录 ID 不能为空" }
        val normalizedNote = note.normalizedRatingNote()
        validateRatingHistoryInput(recordedAtEpochMillis, rating, normalizedNote)
        return database.ratingHistoryDao().updateManual(
            id = id,
            recordedAtEpochMillis = recordedAtEpochMillis,
            rating = rating,
            note = normalizedNote,
            updatedAtEpochMillis = nowEpochMillis,
        ) > 0
    }

    suspend fun deleteManualRating(id: String): Boolean =
        id.isNotBlank() && database.ratingHistoryDao().deleteManual(id) > 0

    suspend fun deleteScoresNotInCatalog(catalog: MaimaiSongCatalog): Int {
        val invalidIds = database.scoreRecordDao().getAll()
            .filter { entity ->
                val songType = runCatching { SongType.valueOf(entity.songType) }.getOrNull()
                    ?: return@filter false
                catalog.chartExists(entity.title, entity.levelIndex, songType) == false
            }
            .map { it.id }

        return if (invalidIds.isEmpty()) {
            0
        } else {
            database.scoreRecordDao().deleteByIds(invalidIds)
        }
    }
}

private fun validateRatingHistoryInput(
    recordedAtEpochMillis: Long,
    rating: Int,
    note: String?,
) {
    require(recordedAtEpochMillis > 0) { "记录时间无效" }
    require(rating in 0..30_000) { "Rating 必须在 0 到 30000 之间" }
    require(note == null || note.length <= 200) { "备注不能超过 200 个字符" }
}

private fun String?.normalizedRatingNote(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)
