package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DivingFishFitParserTest {
    @Test fun keepsDifficultySlotsAndSeparatesStandardAndDx() {
        val parsed = DivingFishFitParser.parse("""{"charts":{
            "8":[{}, {"fit_diff":null}, {"fit_diff":12.12345}],
            "10008":[{"fit_diff":5.67}, {}, {}, {"fit_diff":14.321}],
            "100001":[{"fit_diff":9.9}], "9":[{"fit_diff":0},{"fit_diff":-1}]
        }}""")
        assertEquals(3, parsed.size)
        assertEquals(12.12345, parsed[fittedChartKey(8, SongType.STANDARD, 2)]!!, 0.000001)
        assertEquals(14.321, parsed[fittedChartKey(8, SongType.DX, 3)]!!, 0.000001)
        assertFalse(parsed.containsKey(fittedChartKey(8, SongType.STANDARD, 0)))
    }
}
