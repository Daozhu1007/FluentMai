package dev.fluentmai.android.feature.scores

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal fun constrainPosterOffset(offset: Offset, image: Size, viewport: Size): Offset {
    fun axis(value: Float, content: Float, available: Float) =
        if (content <= available) (available - content) / 2f else value.coerceIn(available - content, 0f)
    return Offset(axis(offset.x, image.width, viewport.width), axis(offset.y, image.height, viewport.height))
}

/** Gestures affect only the preview; gallery export always uses the complete bitmap. */
@Composable
internal fun ZoomableB50(bitmap: Bitmap, resetRequest: Int, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ratio = bitmap.height.toFloat() / bitmap.width
    LaunchedEffect(resetRequest, viewport) { scale = 1f; offset = Offset.Zero }
    Canvas(modifier.clipToBounds().onSizeChanged { viewport = it }
        .semantics { contentDescription = "B50 大图：双指缩放，拖动查看" }
        .pointerInput(ratio) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val view = Size(size.width.toFloat(), size.height.toFloat())
                val baseHeight = view.width * ratio
                if (baseHeight > 0) {
                    val next = (scale * zoom).coerceIn(minOf(1f, view.height / baseHeight), 6f)
                    offset = constrainPosterOffset(centroid - (centroid - offset) * (next / scale) + pan,
                        Size(view.width * next, baseHeight * next), view)
                    scale = next
                }
            }
        }) {
        val displayed = Size(size.width * scale, size.width * ratio * scale)
        val position = constrainPosterOffset(offset, displayed, size)
        drawImage(image, dstOffset = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
            dstSize = IntSize(displayed.width.roundToInt().coerceAtLeast(1), displayed.height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High)
    }
}
