package dev.fluentmai.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportDiagnosticsTest {
    @Test fun removesWholeCookieHeadersUrlsAndStandaloneCredentialsButKeepsStackAndHttpStatus() {
        val raw = """
            Cookie: _t=cookieSecret; userId=playerSecret; another=otherSecret
            Authorization: Bearer bearerSecret
            https://maimai.wahlap.com/maimai-mobile/record/musicDetail/?idx=urlSecret
            idx=idxSecret; _t=sessionSecret; userId=userSecret; token=tokenSecret
            "access_token":"jsonSecret"
            HTTP 200；错误码 1234
        """.trimIndent()
        val error = IOException(raw, IllegalStateException("unexpected page"))
        val report = diagnosticException(error)
        listOf("cookieSecret", "playerSecret", "otherSecret", "bearerSecret", "urlSecret", "idxSecret",
            "sessionSecret", "userSecret", "tokenSecret", "jsonSecret").forEach { assertFalse(it, report.contains(it)) }
        assertTrue(report.contains("HTTP 200"))
        assertTrue(report.contains("错误码 1234"))
        assertTrue(report.contains("unexpected page"))
        assertTrue(report.contains("ImportDiagnosticsTest"))
    }

    @Test fun responseMetadataDoesNotExposeQueryOrPageBody() {
        val report = describeWahlapResponse(200, "https://maimai.wahlap.com/maimai-mobile/record/?idx=secret", "<img src='title_error.png'>错误码：1234 private player info")
        assertTrue(report.contains("错误页=true"))
        assertTrue(report.contains("1234"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("private player info"))
    }

    @Test fun reportsArePerRunAndSanitizedBeforeStorage() {
        val first = ImportDiagnostics("测试")
        first.record("idx=secret")
        first.record("首次失败 HTTP 200")
        assertFalse(first.report().contains("secret"))
        assertTrue(first.report().contains("首次失败"))
        assertFalse(ImportDiagnostics("测试").report().contains("首次失败"))
    }
}
