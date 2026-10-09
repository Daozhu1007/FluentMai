package dev.fluentmai.android

import dev.fluentmai.android.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist serializer and typed decoder. Never serializes object dumps or log text. */
internal object ImportDiagnosticJson {
    const val MAX_BYTES = 48 * 1024
    private const val MAX_DURATION = 7 * 24 * 60 * 60 * 1000L
    private fun number(value: Long?) = value?.coerceIn(0, MAX_DIAGNOSTIC_NUMBER) ?: JSONObject.NULL
    private fun duration(value: Long?) = value?.coerceIn(0, MAX_DURATION) ?: JSONObject.NULL
    private fun JSONObject.n(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key).coerceIn(0, MAX_DIAGNOSTIC_NUMBER)
    private fun <T> array(items: Iterable<T>, encode: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(encode(it)) } }

    fun encode(report: ImportDiagnosticReport): String {
        val stages = report.stages.take(64).associateBy { it.stage }
        val requests = report.requests.take(64).distinctBy { Triple(it.stage, it.category, it.label) }
        val auth = report.authMilestones.take(16).associateBy { it.event }
        val c = report.counters
        fun outside(value: Long?, max: Long) = value != null && value !in 0..max
        val bounded = report.metricsBounded || report.requests.size > 64 || report.stages.size > 64 || report.authMilestones.size > 16 ||
            listOf(report.totalDurationMs, report.executionDurationMs, report.authorizationWaitDurationMs, report.unattributedDurationMs)
                .any { outside(it, MAX_DURATION) } ||
            listOf(c.parsed, c.inserted, c.updated, c.quarantined, c.rejected, c.skippedDuplicate, c.recentRecords, c.playCountCharts)
                .any { outside(it, MAX_DIAGNOSTIC_NUMBER) } ||
            stages.values.any { outside(it.durationMs, MAX_DURATION) || outside(it.exclusiveDurationMs, MAX_DURATION) } ||
            requests.any { it.httpStatusCounts.size > 8 || listOf(it.attempts, it.retries, it.successfulRequests, it.failedRequests, it.responseChars ?: 0)
                .any { n -> n !in 0..MAX_DIAGNOSTIC_NUMBER } }
        val json = JSONObject().apply {
            put("schema_version", 1)
            // Build-owned version only. Even a valid-looking injected string cannot cross this boundary.
            put("app_version", if (report.appVersion == APP_VERSION) APP_VERSION else "UNAVAILABLE")
            put("created_at_epoch_ms", report.createdAtEpochMs.coerceIn(0, 4_102_444_800_000L))
            put("import_mode", report.mode.name)
            put("outcome", report.outcome?.name ?: JSONObject.NULL)
            put("termination", report.termination.name)
            put("total_duration_ms", duration(report.totalDurationMs))
            put("execution_duration_ms", duration(report.executionDurationMs))
            put("authorization_wait_duration_ms", duration(report.authorizationWaitDurationMs))
            put("unattributed_duration_ms", duration(report.unattributedDurationMs))
            put("stages", array(DiagnosticStage.entries) { stage ->
                val t = stages[stage] ?: DiagnosticStageTiming(stage)
                JSONObject().put("stage", stage.name).put("observation", t.observation.name)
                    .put("duration_ms", duration(t.durationMs)).put("exclusive_duration_ms", duration(t.exclusiveDurationMs))
                    .put("operations", number(t.operations)).put("recovery_retry_count", number(t.recoveryRetries))
            })
            put("requests", array(requests) { r ->
                JSONObject().put("stage", r.stage.name).put("category", r.category.name).put("label", r.label.name)
                    .put("attempts", number(r.attempts)).put("retry_count", number(r.retries))
                    .put("successful_requests", number(r.successfulRequests)).put("failed_requests", number(r.failedRequests))
                    .put("attempt_duration_ms", duration(r.attemptDurationMs))
                    .put("last_http_status", r.lastHttpStatus?.takeIf { it in 100..599 } ?: JSONObject.NULL)
                    .put("response_chars", number(r.responseChars))
                    .put("http_status_counts", array(r.httpStatusCounts.take(8).filter { it.status in 100..599 }) {
                        JSONObject().put("status", it.status).put("attempts", number(it.attempts))
                    })
            })
            put("request_observation", if (report.requests.isEmpty()) "UNOBSERVED" else if (bounded ||
                report.termination in setOf(DiagnosticTermination.CANCELLED, DiagnosticTermination.INTERRUPTED)) "INCOMPLETE" else "COMPLETE")
            put("counters", JSONObject().put("parsed", number(c.parsed)).put("inserted", number(c.inserted))
                .put("updated", number(c.updated)).put("quarantined", number(c.quarantined)).put("rejected", number(c.rejected))
                .put("skipped_duplicate", number(c.skippedDuplicate)).put("recent_records", number(c.recentRecords))
                .put("play_count_charts", number(c.playCountCharts)))
            put("failure_categories", JSONArray().apply { report.failures.sortedBy { it.ordinal }.forEach { put(it.name) } })
            put("auth_milestones", array(DiagnosticAuthEvent.entries) { event ->
                JSONObject().put("event", event.name).put("elapsed_ms", duration(auth[event]?.elapsedMs))
            })
            put("metrics_bounded", bounded)
        }
        var encoded = json.toString(2)
        // A worst-case combination of bounded keys/status counters can still exceed the byte cap.
        // Drop only aggregate detail, explicitly marking incomplete metrics; never fail import cleanup.
        val requestDetails = json.getJSONArray("requests")
        while (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES && requestDetails.length() > 0) {
            requestDetails.remove(requestDetails.length() - 1)
            json.put("metrics_bounded", true).put("request_observation", "INCOMPLETE")
            encoded = json.toString(2)
        }
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return encoded
    }

    fun decode(json: String): ImportDiagnosticReport {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(json)
        require(root.getInt("schema_version") == 1)
        val stageArray = root.getJSONArray("stages")
        val requestArray = root.getJSONArray("requests")
        val milestoneArray = root.getJSONArray("auth_milestones")
        val failureArray = root.getJSONArray("failure_categories")
        require(stageArray.length() <= 64 && requestArray.length() <= 64 && milestoneArray.length() <= 16 && failureArray.length() <= DiagnosticFailure.entries.size)
        val c = root.getJSONObject("counters")
        return ImportDiagnosticReport(appVersion = root.getString("app_version").takeIf { it == APP_VERSION } ?: "UNAVAILABLE",
            createdAtEpochMs = root.getLong("created_at_epoch_ms").coerceIn(0, 4_102_444_800_000L),
            mode = DiagnosticImportMode.valueOf(root.getString("import_mode")),
            outcome = if (root.isNull("outcome")) null else DiagnosticOutcome.valueOf(root.getString("outcome")),
            termination = DiagnosticTermination.valueOf(root.getString("termination")),
            totalDurationMs = root.n("total_duration_ms"), executionDurationMs = root.n("execution_duration_ms"),
            authorizationWaitDurationMs = root.n("authorization_wait_duration_ms"), unattributedDurationMs = root.n("unattributed_duration_ms"),
            stages = (0 until stageArray.length()).map { i -> stageArray.getJSONObject(i).let { s ->
                DiagnosticStageTiming(DiagnosticStage.valueOf(s.getString("stage")), DiagnosticObservation.valueOf(s.getString("observation")),
                    s.n("duration_ms"), s.n("exclusive_duration_ms"), s.n("operations") ?: 0, s.n("recovery_retry_count") ?: 0)
            } }, requests = (0 until requestArray.length()).map { i -> requestArray.getJSONObject(i).let { r ->
                DiagnosticRequestStats(DiagnosticStage.valueOf(r.getString("stage")), DiagnosticRequestCategory.valueOf(r.getString("category")),
                    DiagnosticRequestLabel.valueOf(r.getString("label")), r.n("attempts") ?: 0, r.n("retry_count") ?: 0,
                    r.n("successful_requests") ?: 0, r.n("failed_requests") ?: 0, r.n("attempt_duration_ms") ?: 0,
                    r.n("last_http_status")?.toInt()?.takeIf { it in 100..599 }, r.n("response_chars"),
                    r.optJSONArray("http_status_counts")?.let { statuses ->
                        require(statuses.length() <= 8)
                        (0 until statuses.length()).map { index -> statuses.getJSONObject(index).let { s ->
                            DiagnosticHttpStatusCount(s.getInt("status"), s.n("attempts") ?: 0)
                        } }.filter { it.status in 100..599 }
                    }.orEmpty())
            } }, counters = DiagnosticCounters(c.n("parsed"), c.n("inserted"), c.n("updated"), c.n("quarantined"), c.n("rejected"),
                c.n("skipped_duplicate"), c.n("recent_records"), c.n("play_count_charts")),
            failures = (0 until failureArray.length()).map { DiagnosticFailure.valueOf(failureArray.getString(it)) }.toSet(),
            authMilestones = (0 until milestoneArray.length()).map { i -> milestoneArray.getJSONObject(i).let {
                DiagnosticAuthMilestone(DiagnosticAuthEvent.valueOf(it.getString("event")), it.n("elapsed_ms"))
            } }, metricsBounded = root.getBoolean("metrics_bounded"))
    }
}
