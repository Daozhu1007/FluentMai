package dev.fluentmai.android.feature.scores

import android.graphics.*
import dev.fluentmai.android.core.model.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** One immutable high-resolution image is used in both the preview and full-screen view. */
internal class B50PosterRenderer {
    private val ink = Color.rgb(44, 53, 83)
    private var headingColor = Color.WHITE
    private val muted = Color.rgb(99, 109, 135)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private lateinit var canvas: Canvas
    private lateinit var images: Map<String, Bitmap>

    fun render(best: MaimaiBestSet, profile: B50PlayerProfile?, background: Bitmap, assets: Map<String, Bitmap>, height: Int = HEIGHT, options: B50DisplayOptions = B50DisplayOptions()): Bitmap {
        images = assets
        val output = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(output)
        // Background fills the target viewport; content keeps its proportions on every device.
        paint.color = Color.WHITE
        if (options.background) bitmap(background, RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()))
        else canvas.drawColor(Color.WHITE)
        headingColor = if (options.background) Color.WHITE else ink
        val contentScale = minOf(1f, (height - 110f) / 2780f)
        canvas.translate((WIDTH - WIDTH * contentScale) / 2, 0f)
        canvas.scale(contentScale, contentScale)
        text("BEST 50", 62f, 101f, 72f, headingColor, bold = true)
        header(best, profile, options)
        text("B35 ${best.oldBest.sumOf { it.rating ?: 0 }}  +  B15 ${best.newBest.sumOf { it.rating ?: 0 }}  =  ${best.rating}",
            62f, 410f, 28f, headingColor, bold = true)
        section("旧版本Best35", best.oldBest, 521f, 551f, 35, Color.rgb(45, 173, 199))
        section("当前版本Best15", best.newBest, 2111f, 2141f, 15, Color.rgb(158, 116, 218))
        return output
    }

    private fun header(best: MaimaiBestSet, player: B50PlayerProfile?, options: B50DisplayOptions) {
        val panel = RectF(60f, 137f, 1440f, 359f)
        rounded(panel, Color.argb(215, 255, 255, 255), 20f, shadow = true)
        if (options.nameplate) player?.plateArtwork?.let { asset(it, panel, radius = 20f) }
        val avatarRect = RectF(78f, 152f, 270f, 344f)
        rounded(avatarRect, Color.WHITE, 14f)
        val avatar = player?.iconArtwork?.let(images::get)
        if (avatar != null) bitmap(avatar, avatarRect, radius = 14f)
        else centeredText("♪", avatarRect.centerX(), 280f, 88f, Color.rgb(98, 198, 206), 180f)

        val rating = best.rating
        val ratingRect = RectF(300f, 151f, 595f, 237f)
        if (asset(B50Assets.rating(rating), ratingRect)) {
            rating.toString().padStart(5, '0').forEachIndexed { index, digit ->
                centeredText(digit.toString(), 436.3f + index * 31.5f, 206f, 33.5f, Color.WHITE, 29f)
            }
        } else {
            rounded(ratingRect, ratingFallbackColor(rating), 12f)
            centeredText("Rating $rating", ratingRect.centerX(), 207f, 30f, Color.WHITE, 275f)
        }
        if (options.rank) player?.classRank?.let { asset(B50Assets.rank(it), RectF(615f, 157f, 735f, 223f)) }

        val nameRect = RectF(300f, 239f, 1030f, 294f)
        rounded(nameRect, Color.WHITE, 7f)
        val course = player?.courseRank?.takeIf { options.course }
        text(player?.name ?: "本地玩家", 312f, 280f, 37f, ink, bold = true, maxWidth = if (course != null) 488f else 698f)
        if (course != null) asset(B50Assets.course(course), RectF(820f, 240f, 1020f, 291f))

        if (options.trophy && player?.trophy != null) {
            val trophyRect = RectF(300f, 300f, 1030f, 343f)
            if (!asset(B50Assets.trophy(player.trophyColor), trophyRect)) rounded(trophyRect, Color.rgb(231, 229, 248), 10f)
            val artwork = images[B50Assets.trophy(player.trophyColor)]
            val available = artwork?.let { minOf(trophyRect.width(), trophyRect.height() * it.width / it.height) - 24f } ?: 680f
            centeredText(player.trophy, trophyRect.centerX(), 329f, 23f, ink, available)
        }
    }

    private fun ratingFallbackColor(rating: Int): Int = when (posterRatingColor(rating)) {
        "rainbow" -> Color.rgb(171, 86, 200)
        "platinum" -> Color.rgb(172, 183, 200)
        "gold" -> Color.rgb(199, 155, 36)
        "silver" -> Color.rgb(133, 150, 163)
        "bronze" -> Color.rgb(161, 102, 60)
        "purple" -> Color.rgb(125, 66, 173)
        "red" -> Color.rgb(208, 54, 67)
        "orange" -> Color.rgb(223, 150, 32)
        "green" -> Color.rgb(54, 147, 71)
        "blue" -> Color.rgb(55, 127, 192)
        else -> Color.rgb(102, 115, 126)
    }

    private fun section(label: String, scores: List<MaimaiRatedScore>, headerY: Float, startY: Float, capacity: Int, accent: Int) {
        text(label, 60f, headerY, 31f, headingColor, bold = true)
        // A rhythm marker and tapered ribbon separate the two sets without extra labels.
        val lineX = 330f
        star(lineX + 18, headerY - 12, 12f, headingColor)
        paint.shader = LinearGradient(lineX + 44, 0f, 1440f, 0f, headingColor, Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(lineX + 44, headerY - 16, 1440f, headerY - 9), 4f, 4f, paint)
        paint.shader = null
        repeat(capacity) { index ->
            val x = 60f + (index % 5) * 278f
            val y = startY + (index / 5) * 216f
            card(scores.getOrNull(index), index + 1, x, y)
        }
    }

    private fun card(item: MaimaiRatedScore?, rank: Int, x: Float, y: Float) {
        val rect = RectF(x, y, x + 264, y + 208)
        rounded(rect, Color.WHITE, 20f, shadow = true)
        if (item == null) {
            text("#${rank.toString().padStart(2, '0')}", x + 16, y + 33, 20f, muted)
            text("暂无成绩", x + 76, y + 113, 24f, muted)
            return
        }
        val score = item.score
        val accent = when (score.difficulty) {
            Difficulty.BASIC -> Color.rgb(47, 158, 68)
            Difficulty.ADVANCED -> Color.rgb(217, 72, 15)
            Difficulty.EXPERT -> Color.rgb(224, 49, 49)
            Difficulty.MASTER -> Color.rgb(123, 44, 191)
            Difficulty.RE_MASTER -> Color.rgb(197, 166, 224)
        }
        val saved = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, 20f, 20f, Path.Direction.CW) })
        paint.shader = LinearGradient(x, y, x + 264, y + 50, accent, lighten(accent), Shader.TileMode.CLAMP)
        canvas.drawRect(x, y, x + 264, y + 42, paint)
        paint.shader = null
        canvas.restoreToCount(saved)
        text("#${rank.toString().padStart(2, '0')}", x + 12, y + 29, 20f, Color.WHITE, bold = true)
        val difficulty = if (score.difficulty == Difficulty.RE_MASTER) "Re:MASTER" else score.difficulty.name
        text(difficulty, x + 58, y + 28, 19f, Color.WHITE, bold = true)
        rounded(RectF(x + 192, y + 8, x + 252, y + 34), Color.WHITE, 13f)
        paint.textSize = 18f
        val badgeBaseline = y + 21 - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        centeredText(if (score.songType == SongType.DX) "DX" else "标准", x + 222, badgeBaseline, 18f, accent, 54f)
        val cover = RectF(x + 10, y + 52, x + 90, y + 132)
        val id = item.chart?.songId ?: score.songId
        if (id == null || !asset(B50Assets.jacket(id), cover, radius = 10f)) {
            rounded(cover, Color.rgb(231, 233, 247), 10f)
            text("♪", x + 34, y + 107, 36f, muted)
        }
        title(score.title, x + 100, y + 73, 153f)
        text(String.format(Locale.US, "%.4f%%", score.achievement), x + 100, y + 127, 23f, ink, bold = true, maxWidth = 154f)
        rounded(RectF(x + 10, y + 137, x + 254, y + 164), Color.rgb(242, 244, 252), 12f)
        text(item.chart?.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "--", x + 19, y + 158, 21f, accent, bold = true)
        val ratingLabel = item.rating?.toString() ?: "--"
        paint.textSize = 21f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        text(ratingLabel, x + 245 - paint.measureText(ratingLabel), y + 158, 21f, ink, bold = true)
        val stars = posterDxStars(score.dxScore, item.chart?.notes?.total)
        if (stars == null) text("DX ★ --", x + 13, y + 193, 17f, muted)
        else repeat(5) { starIndex -> star(x + 22 + starIndex * 22, y + 185, 9.5f, if (starIndex < stars) Color.rgb(247, 177, 36) else Color.rgb(221, 225, 236)) }
        status(score.fc, RectF(x + 168, y + 163, x + 208, y + 203))
        status(score.fs, RectF(x + 213, y + 163, x + 253, y + 203))
    }

    private fun status(raw: String?, rect: RectF) {
        val url = B50Assets.status(raw)
        if (url == null || !asset(url, rect)) {
            paint.color = Color.rgb(221, 225, 236)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * .30f, paint)
            // Known statuses remain legible if their artwork cannot be downloaded.
            if (url != null) centeredText(raw.orEmpty().uppercase(), rect.centerX(), rect.centerY() + 4, 10f, muted, rect.width() - 3)
        }
    }

    private fun title(value: String, x: Float, baseline: Float, width: Float) {
        paint.textSize = 20f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val count = paint.breakText(value, true, width, null)
        text(value.take(count), x, baseline, 20f, ink, bold = true)
        if (count < value.length) text(value.drop(count), x, baseline + 24, 20f, ink, bold = true, maxWidth = width)
    }

    private fun rounded(rect: RectF, color: Int, radius: Float, shadow: Boolean = false) {
        paint.color = color
        paint.shader = null
        if (shadow) paint.setShadowLayer(9f, 0f, 5f, Color.argb(42, 73, 65, 135))
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.clearShadowLayer()
    }

    private fun asset(url: String, rect: RectF, crop: Boolean = false, radius: Float = 0f): Boolean {
        val bitmap = images[url] ?: return false
        bitmap(bitmap, rect, crop, radius)
        return true
    }

    private fun bitmap(bitmap: Bitmap, rect: RectF, crop: Boolean = false, radius: Float = 0f) {
        val saved = canvas.save()
        if (radius > 0) canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        if (crop) canvas.clipRect(rect)
        val scale = if (crop) maxOf(rect.width() / bitmap.width, rect.height() / bitmap.height) else minOf(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), paint)
        canvas.restoreToCount(saved)
    }

    private fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE) {
        paint.color = color
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        var displayed = value.replace('\n', ' ')
        if (paint.measureText(displayed) > maxWidth) {
            val count = paint.breakText(displayed, true, (maxWidth - paint.measureText("…")).coerceAtLeast(0f), null)
            displayed = displayed.take(count) + "…"
        }
        canvas.drawText(displayed, x, baseline, paint)
    }

    private fun centeredText(value: String, centerX: Float, baseline: Float, size: Float, color: Int, maxWidth: Float) {
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val count = if (paint.measureText(value) <= maxWidth) value.length
            else paint.breakText(value, true, (maxWidth - paint.measureText("…")).coerceAtLeast(0f), null)
        val label = value.take(count) + if (count < value.length) "…" else ""
        text(label, centerX - paint.measureText(label) / 2, baseline, size, color, bold = true)
    }

    private fun star(cx: Float, cy: Float, radius: Float, color: Int) {
        val path = Path()
        repeat(10) { index ->
            val angle = (index * Math.PI / 5 - Math.PI / 2)
            val r = if (index % 2 == 0) radius else radius * .45f
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun lighten(color: Int) = Color.rgb((Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2)
    companion object { const val WIDTH = 1500; const val HEIGHT = 2665 }
}
