package dev.fluentmai.android.feature.scores

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ZoomableB50Test {
    @Test fun longPosterPansToEdgesAndSmallPosterStaysCentred() {
        assertEquals(Offset(0f, -400f), constrainPosterOffset(Offset(20f, -900f), Size(300f, 1000f), Size(300f, 600f)))
        assertEquals(Offset(50f, 100f), constrainPosterOffset(Offset(-1000f, -1000f), Size(200f, 400f), Size(300f, 600f)))
        assertEquals(Offset(-300f, 0f), constrainPosterOffset(Offset(-900f, 30f), Size(600f, 1200f), Size(300f, 600f)))
    }
}
