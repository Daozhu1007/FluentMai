package dev.fluentmai.android.core.database

import androidx.room.*
import dev.fluentmai.android.core.model.*

@Entity(tableName = "play_records", indices = [Index("playedAt")])
data class PlayRecordEntity(@PrimaryKey val id: String, val songId: Int?, val title: String,
    val songType: String, val difficulty: String, val playedAt: Long, val achievement: Double?,
    val dxScore: Int?, val fc: String?, val fs: String?) {
    fun model() = PlayRecord(id, songId, title, SongType.valueOf(songType), Difficulty.valueOf(difficulty), playedAt, achievement, dxScore, fc, fs)
}

@Entity(tableName = "chart_play_counts", primaryKeys = ["title", "songType", "difficulty"])
data class ChartPlayCountEntity(val title: String, val songType: String, val difficulty: String, val count: Int,
    @ColumnInfo(defaultValue = "0") val isUpperBound: Boolean = false)

@Dao
interface PlayActivityDao {
    @Query("SELECT * FROM play_records ORDER BY playedAt DESC")
    suspend fun records(): List<PlayRecordEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(records: List<PlayRecordEntity>)
    @Query("SELECT * FROM chart_play_counts")
    suspend fun counts(): List<ChartPlayCountEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCounts(counts: List<ChartPlayCountEntity>)
}
