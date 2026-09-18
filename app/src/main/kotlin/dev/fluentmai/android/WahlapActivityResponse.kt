package dev.fluentmai.android

import java.io.IOException
import java.net.URI

/** Deliberately excludes response bodies, query strings and credentials from diagnostics. */
internal class WahlapActivityFetchException(val reason: String, val canRefreshDetail: Boolean = false) : IOException(reason)

internal fun describeWahlapResponse(status: Int, finalUrl: String, body: String): String {
    val uri = runCatching { URI(finalUrl) }.getOrNull()
    return "HTTP $status；最终路径=${uri?.host}${uri?.path}；字符数=${body.length}；" +
        "错误页=${dev.fluentmai.android.core.importer.WahlapActivityParser.hasErrorPage(body)}；" +
        "错误码=${dev.fluentmai.android.core.importer.WahlapActivityParser.errorCode(body) ?: "无"}"
}

internal fun validateActivityResponse(status: Int, finalUrl: String, body: String) {
    if (status !in 200..299) throw WahlapActivityFetchException("HTTP $status")
    val path = runCatching { URI(finalUrl).path }.getOrNull().orEmpty()
    if (path.contains("oauth") || path.contains("login", true) || body.contains("请在微信客户端打开") || body.contains("登录失败"))
        throw WahlapActivityFetchException("登录状态失效或微信身份校验失败，请重新捕获授权")
    // Test actual error elements, not a substring in a script or stylesheet.
    if (dev.fluentmai.android.core.importer.WahlapActivityParser.hasErrorPage(body))
        throw WahlapActivityFetchException("官方返回错误页面（HTTP $status）" +
            dev.fluentmai.android.core.importer.WahlapActivityParser.errorCode(body)?.let { "，错误码 $it" }.orEmpty(),
            canRefreshDetail = true)
}
