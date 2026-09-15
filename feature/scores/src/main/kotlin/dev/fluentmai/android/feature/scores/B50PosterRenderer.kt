package dev.fluentmai.android.feature.scores

import android.graphics.*
import dev.fluentmai.android.core.model.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** One immutable high-resolution image is used in both the preview and gallery export. */
internal class B50PosterRenderer {
    private val ink = Color.rgb(44, 53, 83)
    private var headingColor = Color.WHITE
    private val muted = Color.rgb(99, 109, 135)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private lateinit var canvas: Canvas
    private lateinit var images: Map<String, Bitmap>
    private val visibleBounds = mutableMapOf<Bitmap, Rect>()

    fun render(best: MaimaiBestSet, profile: B50PlayerProfile?, background: Bitmap, assets: Map<String, Bitmap>, height: Int = HEIGHT, options: B50DisplayOptions = B50DisplayOptions()): Bitmap {
        images = assets
        visibleBounds.clear()
        val output = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        // The selected poster artwork is independent of the player's collection frame.
        paint.color = Color.WHITE
        bitmap(background, RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()))
        headingColor = Color.WHITE
        val contentScale = minOf(1f, (height - 240f) / 2544f)
        canvas.translate((WIDTH - WIDTH * contentScale) / 2, 0f)
        canvas.scale(contentScale, contentScale)
        text("BEST 50", 62f, 101f, 72f, headingColor, bold = true)
        header(best, profile, options)
        section("旧版本Best35", best.oldBest, 480f, 504f, 35, Color.rgb(45, 173, 199))
        section("当前版本Best15", best.newBest, 1935f, 1960f, 15, Color.rgb(158, 116, 218))
        return output
    }

    private fun header(best: MaimaiBestSet, player: B50PlayerProfile?, options: B50DisplayOptions) {
        val collectionPanel = RectF(60f, 137f, 1440f, 440f)
        rounded(collectionPanel, Color.WHITE, 20f, shadow = true)
        if (options.background) player?.frameArtwork?.let { asset(it, collectionPanel, crop = true, radius = 20f) }
        // Nameplate surrounds all identity fields, like the in-game player card.
        val panel = RectF(80f, 154f, 1420f, 370f)
        rounded(panel, Color.argb(210, 255, 255, 255), 12f)
        if (options.nameplate) player?.plateArtwork?.let { asset(it, panel, radius = 12f) }
        val avatarRect = RectF(96f, 168f, 284f, 356f)
        rounded(avatarRect, Color.WHITE, 14f)
        val avatar = player?.iconArtwork?.let(images::get)
        if (avatar != null) bitmap(avatar, avatarRect, radius = 14f)
        else centeredText("♪", avatarRect.centerX(), 280f, 88f, Color.rgb(98, 198, 206), 180f)

        val rating = best.rating
        val ratingRect = RectF(306f, 176f, 520f, 176f + 214f * 86f / 296f)
        val ratingArtwork = images[B50Assets.rating(rating)]
        if (ratingArtwork != null) {
            // Preserve the complete official artwork, including the left-hand logo.
            bitmap(ratingArtwork, ratingRect)
            val scale = ratingRect.width() / 296f
            rating.toString().padStart(5, '0').forEachIndexed { index, digit ->
                val centerX = ratingRect.left + (136.3f + index * 31.5f) * scale
                centeredText(digit.toString(), centerX, ratingRect.top + 55f * scale, 33.5f * scale, Color.rgb(255, 245, 177), 29f * scale)
            }
        } else {
            rounded(ratingRect, ratingFallbackColor(rating), 12f)
            centeredText(rating.toString(), ratingRect.centerX(), 216f, 26f, Color.WHITE, 190f)
        }
        if (options.rank) player?.classRank?.let { asset(B50Assets.rank(it), RectF(534f, 180f, 626f, 235f)) }

        val nameRect = RectF(306f, 240f, 866f, 293f)
        rounded(nameRect, Color.WHITE, 7f)
        val course = player?.courseRank?.takeIf { options.course }
        val nameWidth = if (course != null) 396f else 536f
        val name = ellipsized(player?.name ?: "本地玩家", 33f, nameWidth)
        text(name, 318f, 280f, 33f, ink, bold = true)
        if (course != null) {
            val courseX = 318f + paint.measureText(name) + 12f
            asset(B50Assets.course(course), RectF(courseX, 241f, courseX + 128f, 292f), trim = true)
        }

        if (options.trophy && player?.trophy != null) {
            val trophyRect = RectF(306f, 300f, 866f, 343f)
            val artwork = images[B50Assets.trophy(player.trophyColor)]
            if (artwork != null) banner(artwork, trophyRect)
            else rounded(trophyRect, Color.rgb(231, 229, 248), 10f)
            centeredText(player.trophy, trophyRect.centerX(), 329f, 23f, ink, trophyRect.width() - 32f)
        }
        rounded(RectF(80f, 389f, 910f, 427f), Color.argb(225, 255, 255, 255), 12f)
        text("B35 ${best.oldBest.sumOf { it.rating ?: 0 }}  +  B15 ${best.newBest.sumOf { it.rating ?: 0 }}  =  ${best.rating}",
            96f, 417f, 26f, ink, bold = true)
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
            val y = startY + (index / 5) * 198f
            card(scores.getOrNull(index), index + 1, x, y)
        }
    }

    private fun card(item: MaimaiRatedScore?, rank: Int, x: Float, y: Float) {
        val rect = RectF(x, y, x + 264, y + 188)
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
        canvas.drawRect(x, y, x + 264, y + 36, paint)
        paint.shader = null
        canvas.restoreToCount(saved)
        text("#${rank.toString().padStart(2, '0')}", x + 12, y + 26, 20f, Color.WHITE, bold = true)
        val difficulty = if (score.difficulty == Difficulty.RE_MASTER) "Re:MASTER" else score.difficulty.name
        text(difficulty, x + 58, y + 25, 19f, Color.WHITE, bold = true)
        rounded(RectF(x + 192, y + 5, x + 252, y + 31), Color.WHITE, 13f)
        paint.textSize = 18f
        val badgeBaseline = y + 18 - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        centeredText(if (score.songType == SongType.DX) "DX" else "标准", x + 222, badgeBaseline, 18f, accent, 54f)
        val cover = RectF(x + 10, y + 44, x + 84, y + 118)
        val id = item.chart?.songId ?: score.songId
        if (id == null || !asset(B50Assets.jacket(id), cover, radius = 10f)) {
            rounded(cover, Color.rgb(231, 233, 247), 10f)
            text("♪", x + 34, y + 107, 36f, muted)
        }
        title(score.title, x + 94, y + 63, 160f)
        text(String.format(Locale.US, "%.4f%%", score.achievement), x + 94, y + 115, 23f, ink, bold = true, maxWidth = 160f)
        rounded(RectF(x + 10, y + 123, x + 254, y + 148), Color.rgb(242, 244, 252), 12f)
        text(item.chart?.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "--", x + 19, y + 143, 21f, accent, bold = true)
        val ratingLabel = item.rating?.toString() ?: "--"
        paint.textSize = 21f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        text(ratingLabel, x + 245 - paint.measureText(ratingLabel), y + 143, 21f, ink, bold = true)
        val stars = posterDxStars(score.dxScore, item.chart?.notes?.total)
        if (stars == null) text("DX ★ --", x + 13, y + 174, 17f, muted)
        else repeat(5) { starIndex -> star(x + 22 + starIndex * 22, y + 167, 9.5f, if (starIndex < stars) Color.rgb(247, 177, 36) else Color.rgb(221, 225, 236)) }
        status(score.fc, RectF(x + 177, y + 150, x + 211, y + 184))
        status(score.fs, RectF(x + 218, y + 150, x + 252, y + 184))
    }

    private fun status(raw: String?, rect: RectF) {
        val url = B50Assets.status(raw)
        if (url == null || !asset(url, rect, trim = true)) {
            paint.color = Color.rgb(221, 225, 236)
            canvas.drawCircle(rect.centerX(), rect.centerY(), 9f, paint)
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

    private fun asset(url: String, rect: RectF, crop: Boolean = false, radius: Float = 0f, trim: Boolean = false): Boolean {
        val bitmap = images[url] ?: return false
        bitmap(bitmap, rect, crop, radius, if (trim) visibleBounds.getOrPut(bitmap) { artworkVisibleBounds(bitmap) } else null)
        return true
    }

    private fun bitmap(bitmap: Bitmap, rect: RectF, crop: Boolean = false, radius: Float = 0f, source: Rect? = null) {
        val saved = canvas.save()
        if (radius > 0) canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        if (crop) canvas.clipRect(rect)
        val sourceWidth = source?.width() ?: bitmap.width
        val sourceHeight = source?.height() ?: bitmap.height
        val scale = if (crop) maxOf(rect.width() / sourceWidth, rect.height() / sourceHeight) else minOf(rect.width() / sourceWidth, rect.height() / sourceHeight)
        val w = sourceWidth * scale
        val h = sourceHeight * scale
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, source, RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2), paint)
        canvas.restoreToCount(saved)
    }

    /** Stretch only a banner's flat middle; its rounded end caps keep their shape. */
    private fun banner(bitmap: Bitmap, rect: RectF) {
        val cap = bitmap.height / 2
        val scaledCap = rect.height() / 2
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, Rect(0, 0, cap, bitmap.height), RectF(rect.left, rect.top, rect.left + scaledCap, rect.bottom), paint)
        canvas.drawBitmap(bitmap, Rect(cap, 0, bitmap.width - cap, bitmap.height), RectF(rect.left + scaledCap, rect.top, rect.right - scaledCap, rect.bottom), paint)
        canvas.drawBitmap(bitmap, Rect(bitmap.width - cap, 0, bitmap.width, bitmap.height), RectF(rect.right - scaledCap, rect.top, rect.right, rect.bottom), paint)
    }

    private fun ellipsized(value: String, size: Float, width: Float): String {
        paint.textSize = size
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val singleLine = value.replace('\n', ' ')
        if (paint.measureText(singleLine) <= width) return singleLine
        return singleLine.take(paint.breakText(singleLine, true, (width - paint.measureText("…")).coerceAtLeast(0f), null)) + "…"
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

/** Ignore transparent padding and faint compression fringes without cropping status lettering. */
internal fun artworkVisibleBounds(bitmap: Bitmap): Rect {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    pixels.forEachIndexed { index, pixel ->
        if (Color.alpha(pixel) >= 24) {
            val x = index % bitmap.width; val y = index / bitmap.width
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
    }
    return if (right < left) Rect(0, 0, bitmap.width, bitmap.height) else Rect(left, top, right + 1, bottom + 1)
}
