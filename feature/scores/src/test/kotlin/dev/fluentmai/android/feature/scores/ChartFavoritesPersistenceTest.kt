package dev.fluentmai.android.feature.scores

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ChartFavoritesPersistenceTest {
    @Test fun favoritesSurviveStoreRecreationAndRemainDifficultySpecific() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("chart_favorites", 0).edit().clear().commit()
        val store = ChartFavoriteStore(context)
        store.toggle("834:DX:MASTER")
        val reopened = ChartFavoriteStore(context)
        assertTrue("834:DX:MASTER" in reopened.values.value)
        assertFalse("834:DX:EXPERT" in reopened.values.value)
        assertFalse("834:STANDARD:MASTER" in reopened.values.value)
        reopened.toggle("834:DX:MASTER")
        assertTrue(ChartFavoriteStore(context).values.value.isEmpty())
    }
}
