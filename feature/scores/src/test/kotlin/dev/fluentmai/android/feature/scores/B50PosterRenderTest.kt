package dev.fluentmai.android.feature.scores

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.fluentmai.android.core.model.MaimaiMajorVersion
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B50PosterRenderTest {
    @Test fun rendersAllFiftySlotsAndBothBackgroundsWithoutCrashing() {
        val charts = (1..60).map(B50PosterTest::chart)
        val best = posterBestSet(charts.map(B50PosterTest::score), charts, listOf(MaimaiMajorVersion(25500, "舞萌DX 2026")))
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val output = File(root, "build/test-artifacts/b50-preview").apply { mkdirs() }
        for (variant in listOf("day", "night")) {
            val background = BitmapFactory.decodeFile(File(root, "feature/scores/src/main/res/drawable-nodpi/b50_background_$variant.png").path)
            // Optional real public assets for visual QA, never required by the test or fetched by CI.
            val assets = mutableMapOf<String, Bitmap>()
            System.getenv("B50_PREVIEW_ASSETS")?.let { path ->
                mapOf(
                    "jacket.png" to B50Assets.jacket(834), "rating.png" to B50Assets.rating(15000),
                    "plate.png" to B50Assets.plate(6101), "course.webp" to B50Assets.course(23),
                    "rank.webp" to B50Assets.rank(25),
                    "icon.png" to B50Assets.icon(1), "frame.png" to B50Assets.frame(1),
                    "app.webp" to B50Assets.status("app")!!, "fsdp.webp" to B50Assets.status("fsdp")!!,
                    "trophy.png" to B50Assets.trophy("rainbow"),
                ).forEach { (file, url) -> BitmapFactory.decodeFile(File(path, file).path)?.let { assets[url] = it } }
                assets[B50Assets.jacket(834)]?.let { cover -> charts.forEach { assets[B50Assets.jacket(it.songId)] = cover } }
            }
            val profile = B50PlayerProfile("测试玩家 · FluentMai", 1, 6101, 1, "舞萌 DX · 测试称号", "rainbow", 23, 25)
            val bitmap = B50PosterRenderer().render(best, profile, background, assets)
            assertEquals(1500, bitmap.width)
            assertEquals(2665, bitmap.height)
            assertFalse(bitmap.sameAs(background))
            File(output, "b50-$variant.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hidden = B50PosterRenderer().render(best, profile, background, assets,
                options = B50DisplayOptions(false, false, false, false, false))
            assertEquals(android.graphics.Color.WHITE, hidden.getPixel(0, 0))
            if (variant == "night") File(output, "b50-white-hidden.png").outputStream().use { hidden.compress(Bitmap.CompressFormat.PNG, 100, it) }
            hidden.recycle()
            if (variant == "night") {
                for (height in listOf(2665)) {
                    val responsive = B50PosterRenderer().render(best, profile, background, assets, height)
                    assertEquals(1500, responsive.width)
                    assertEquals(height, responsive.height)
                    File(output, "b50-screen-$height.png").outputStream().use { responsive.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    responsive.recycle()
                }
            }
            background.recycle()
        }
        val background = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val empty = posterBestSet(emptyList(), emptyList(), emptyList())
        val emptyBitmap = B50PosterRenderer().render(empty, null, background, emptyMap())
        assertEquals(1500, emptyBitmap.width)
        emptyBitmap.recycle()
        // Missing FC/FS markers have neutral circles, not misleading status text.
        val chart = B50PosterTest.chart(1)
        val noStatus = B50PosterTest.score(chart).copy(fc = null, fs = null)
        val missing = B50PosterRenderer().render(posterBestSet(listOf(noStatus), listOf(chart), emptyList()), null, background, emptyMap())
        File(output, "b50-missing-status.png").outputStream().use { missing.compress(Bitmap.CompressFormat.PNG, 100, it) }
        missing.recycle()
        background.recycle()
    }
}
