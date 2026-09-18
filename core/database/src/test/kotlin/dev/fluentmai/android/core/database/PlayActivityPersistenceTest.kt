package dev.fluentmai.android.core.database

import androidx.room.Room
import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayActivityPersistenceTest {
    @Test fun exactAndUpperBoundRemainDistinctAndFullSyncReplacesBound() = runTest {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FluentMaiDatabase::class.java).build()
        try {
            database.scoreRecordDao().insertAll(listOf(ScoreRecordEntity("score", 1, "Song", "DX", "MASTER", "14", 3, 100.5, 2999, "app", "fsdp", "batch", 100)))
            val repository = FluentMaiRepository(database)
            repository.savePlayCounts(listOf(ChartPlayCount("Song", SongType.DX, Difficulty.MASTER, 4, true)))
            assertNull(repository.scores().single().playCount)
            assertEquals(4, repository.scores().single().playCountUpperBound)
            assertEquals("≤4", repository.playCounts().single().displayText())
            repository.savePlayCounts(listOf(ChartPlayCount("Song", SongType.DX, Difficulty.MASTER, 12)))
            assertEquals(12, repository.scores().single().playCount)
            assertNull(repository.scores().single().playCountUpperBound)
            assertEquals("12", repository.playCounts().single().displayText())
        } finally { database.close() }
    }

    @Test fun sevenToEightMigrationRetainsExistingExactPc() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "pc-bound-migration-${java.util.UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, FluentMaiDatabase::class.java, name).build()
        try {
            database.openHelper.writableDatabase
            database.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE chart_play_counts")
                old.execSQL("CREATE TABLE chart_play_counts (title TEXT NOT NULL, songType TEXT NOT NULL, difficulty TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(title,songType,difficulty))")
                old.execSQL("INSERT INTO chart_play_counts VALUES ('Song','DX','MASTER',71)")
                old.version = 7
            }
            database = Room.databaseBuilder(context, FluentMaiDatabase::class.java, name)
                .addMigrations(FluentMaiDatabase.MIGRATION_7_8).build()
            assertEquals("71", FluentMaiRepository(database).playCounts().single().displayText())
            assertFalse(database.playActivityDao().counts().single().isUpperBound)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun historyDeduplicatesAndPcIsSeparateFromObservedCount() = runTest {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), FluentMaiDatabase::class.java).build()
        try {
            val repository = FluentMaiRepository(database)
            val play = PlayRecord("1", 1, "Song", SongType.DX, Difficulty.MASTER, 12345L, 100.5, null, null, null)
            repository.savePlayRecords(listOf(play, play))
            repository.savePlayRecords(listOf(play))
            assertEquals(listOf(play), repository.playRecords())
            repository.savePlayCounts(listOf(ChartPlayCount("Song", SongType.DX, Difficulty.MASTER, 42)))
            assertEquals(42, database.playActivityDao().counts().single().count)
            repository.savePlayCounts(listOf(ChartPlayCount("Song", SongType.DX, Difficulty.MASTER, 43)))
            assertEquals(43, database.playActivityDao().counts().single().count)
            assertEquals(1, repository.playRecords().size)
        } finally { database.close() }
    }

    @Test fun sixToSevenMigrationPreservesScoresAndRatingAndCreatesNewTables() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "activity-migration-${java.util.UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, FluentMaiDatabase::class.java, name).build()
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO rating_history VALUES ('rating', 100, 16000, 'MANUAL', NULL, 100, 100)")
            database.scoreRecordDao().insertAll(listOf(ScoreRecordEntity("score", 1, "Song", "DX", "MASTER", "14", 3, 100.5, 2999, "app", "fsdp", "batch", 100)))
            database.close()
            // Reopen an actual on-disk v6 database through Room's migration and schema validator.
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE play_records")
                old.execSQL("DROP TABLE chart_play_counts")
                old.version = 6
            }
            database = Room.databaseBuilder(context, FluentMaiDatabase::class.java, name)
                .addMigrations(FluentMaiDatabase.MIGRATION_6_7, FluentMaiDatabase.MIGRATION_7_8).build()
            assertEquals(16000, database.ratingHistoryDao().getAll().single().rating)
            assertEquals(100.5, database.scoreRecordDao().getAll().single().achievement, .00001)
            assertEquals(8, database.openHelper.writableDatabase.version)
            assertTrue(database.playActivityDao().records().isEmpty())
            assertTrue(database.playActivityDao().counts().isEmpty())
            database.playActivityDao().add(listOf(PlayRecordEntity("1", null, "Song", "DX", "MASTER", 100L, null, null, null, null)))
            assertEquals(1, database.playActivityDao().records().size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
