package dev.fluentmai.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceBoundaryContractTest {
    @Test
    fun appKeepsSensitivePersistenceInsideDedicatedBoundaries() {
        val activity = source("app/src/main/kotlin/dev/fluentmai/android/MainActivity.kt")
        val tokenStore = source("app/src/main/kotlin/dev/fluentmai/android/UploadTokenStore.kt")
        val authClient = source("app/src/main/kotlin/dev/fluentmai/android/WahlapHttpScorePageClient.kt")
        val cookieClient = source("app/src/main/kotlin/dev/fluentmai/android/WahlapManualCookieScorePageClient.kt")

        listOf(
            "getSharedPreferences(",
            "CachedWahlapScorePage",
            "replaceLatestWahlapScorePages",
            ".writeText(",
        ).forEach { forbidden ->
            assertFalse("MainActivity must not contain $forbidden", activity.contains(forbidden))
        }
        assertFalse(authClient.contains("debugPageSink"))
        assertFalse(authClient.contains("supplementalPageSink"))
        assertFalse(cookieClient.contains("debugPageSink"))
        assertFalse(cookieClient.contains("supplementalPageSink"))
        assertFalse("Upload tokens must not be stored as plaintext", tokenStore.contains("putString(preferenceKey, value)"))
        assertTrue(tokenStore.contains("AndroidKeyStore"))
        assertTrue(tokenStore.contains("AES/GCM/NoPadding"))
    }

    @Test
    fun databaseHasNoDestructiveFallbackOrRawPageDao() {
        val database = source(
            "core/database/src/main/kotlin/dev/fluentmai/android/core/database/FluentMaiDatabase.kt",
        )
        val repository = source(
            "core/database/src/main/kotlin/dev/fluentmai/android/core/database/FluentMaiRepository.kt",
        )

        assertFalse(database.contains("fallbackToDestructiveMigration"))
        assertFalse(database.contains("wahlapScorePageDao"))
        assertFalse(repository.contains("WahlapScorePage"))
    }

    private fun source(relativePath: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val file = generateSequence(start) { it.parentFile }
            .take(8)
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
        return requireNotNull(file) { "Unable to locate source file: $relativePath" }.readText()
    }
}
