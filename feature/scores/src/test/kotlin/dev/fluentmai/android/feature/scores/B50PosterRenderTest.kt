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
            val drawableDir = File(root, "feature/scores/src/main/res/drawable-nodpi")
            assets[B50Assets.rating(best.rating)] = BitmapFactory.decodeFile(File(drawableDir, "b50_rating_${posterRatingColor(best.rating)}.png").path)
            assets[B50Assets.trophy("rainbow")] = BitmapFactory.decodeFile(File(drawableDir, "b50_trophy_rainbow.png").path)
            System.getenv("B50_PREVIEW_ASSETS")?.let { path ->
                mapOf(
                    "jacket.png" to B50Assets.jacket(834),
                    "plate.png" to B50Assets.plate(6101), "course.webp" to B50Assets.course(23),
                    "rank.webp" to B50Assets.rank(25),
                    "icon.png" to B50Assets.icon(1), "frame.png" to B50Assets.frame(1),
                    "app.webp" to B50Assets.status("app")!!, "fsdp.webp" to B50Assets.status("fsdp")!!,
                ).forEach { (file, url) -> BitmapFactory.decodeFile(File(path, file).path)?.let { assets[url] = it } }
                assets[B50Assets.jacket(834)]?.let { cover -> charts.forEach { assets[B50Assets.jacket(it.songId)] = cover } }
            }
            val profile = B50PlayerProfile("测试玩家 · FluentMai", 1, 6101, 1, "舞萌 DX · 测试称号", "rainbow", 23, 25)
            val bitmap = B50PosterRenderer().render(best, profile, background, assets)
            assertEquals(1500, bitmap.width)
            assertEquals(2665, bitmap.height)
            assertFalse(bitmap.sameAs(background))
            val backgroundCorner = bitmap.getPixel(10, 10)
            File(output, "b50-$variant.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hidden = B50PosterRenderer().render(best, profile, background, assets,
                options = B50DisplayOptions(false, false, false, false, false))
            // Disabling a collection must never replace the selected day/night poster art.
            assertEquals(backgroundCorner, hidden.getPixel(10, 10))
            if (variant == "night") File(output, "b50-collections-hidden.png").outputStream().use { hidden.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
            assets.values.distinct().forEach { it.recycle() }
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

    @Test fun bundledFramesDecodeOfflineAndCoverEveryRatingTier() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val folder = File(root, "feature/scores/src/main/res/drawable-nodpi")
        assertEquals(11, B50BundledArtwork.ratings.size)
        assertEquals(5, B50BundledArtwork.trophies.size)
        for ((type, colors) in listOf("rating" to B50BundledArtwork.ratings.keys, "trophy" to B50BundledArtwork.trophies.keys)) {
            for (color in colors) {
                val decoded = BitmapFactory.decodeFile(File(folder, "b50_${type}_$color.png").path)
                assertNotNull("$type $color must decode without any network access", decoded)
                assertTrue(decoded.width > 0 && decoded.height > 0)
                decoded.recycle()
            }
        }
    }

    @Test fun transparentStatusPaddingIsRemovedButVisibleLetteringIsPreserved() {
        val icon = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.GREEN }
        android.graphics.Canvas(icon).drawRect(8f, 12f, 74f, 70f, paint)
        assertEquals(android.graphics.Rect(8, 12, 74, 70), artworkVisibleBounds(icon))
        icon.eraseColor(android.graphics.Color.TRANSPARENT)
        assertEquals(android.graphics.Rect(0, 0, 80, 80), artworkVisibleBounds(icon))
        icon.recycle()
    }

    @Test fun collectionBackgroundIsIndependentOfNameplateAndPosterArt() {
        val background = Bitmap.createBitmap(1500, 2665, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val collection = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val best = posterBestSet(emptyList(), emptyList(), emptyList())
        val player = B50PlayerProfile("Player", null, null, 42, null, null, null, null)
        fun rendered(show: Boolean) = B50PosterRenderer().render(best, player, background,
            mapOf(B50Assets.frame(42) to collection), options = B50DisplayOptions(background = show))
        val shown = rendered(true); val hidden = rendered(false)
        assertEquals(android.graphics.Color.BLUE, shown.getPixel(0, 0))
        assertEquals(android.graphics.Color.BLUE, hidden.getPixel(0, 0))
        // Uncovered strip at the bottom right of the collection panel.
        assertEquals(android.graphics.Color.MAGENTA, shown.getPixel(1200, 388))
        assertEquals(android.graphics.Color.WHITE, hidden.getPixel(1200, 388))
        listOf(shown, hidden, background, collection).forEach { it.recycle() }
    }
}
