package dev.fluentmai.android.core.model

/** Shareable aggregate facts only. Execution ownership and credentials never enter this model. */
enum class DiagnosticImportMode { WECHAT_OAUTH, MANUAL_COOKIE }
enum class DiagnosticOutcome { COMPLETE, PARTIAL, FAILED }
enum class DiagnosticTermination { FINISHED, AUTH_REJECTED, CANCELLED, INTERRUPTED }
enum class DiagnosticObservation { UNOBSERVED, COMPLETE, FAILED, INCOMPLETE }
enum class DiagnosticStage {
    LOGIN_HOME, CALLBACK_PROCESSING, SONG_CATALOG, RECENT_RECORDS, BASIC, ADVANCED, EXPERT, MASTER, RE_MASTER,
    SUPPLEMENTAL, PC_CAPTURE, PARSING, DATABASE_PERSISTENCE,
}
enum class DiagnosticAuthEvent {
    REQUESTED, CAPTURE_READY, AUTHORIZE_GENERATED, CLIPBOARD_READY, WECHAT_LAUNCH_DISPATCHED,
    CALLBACK_CAPTURED, AUTHENTICATED_HOME, AUTH_REJECTED,
}
enum class DiagnosticRequestLabel {
    AUTH_CALLBACK, HOME, BASIC, ADVANCED, EXPERT, MASTER, RE_MASTER, RATING_TARGET,
    RECENT_PAGE, PC_RANKING, MUSIC_DETAIL, PLAYER_COLLECTION, OTHER,
}
enum class DiagnosticRequestCategory { AUTH_AUTHORIZE, AUTH_CALLBACK, LOGIN_HOME, SCORE_PAGE, SUPPLEMENTAL_PAGE }
enum class DiagnosticFailure {
    AUTHORIZATION_REJECTED, CAPTURE, AUTHORIZE_GENERATION, NETWORK, REQUEST,
    PARSING, PERSISTENCE, SONG_CATALOG, DIFFICULTY, SUPPLEMENTAL, RECENT_RECORDS, PC_CAPTURE,
    CANCELLED, PROCESS_INTERRUPTED,
}
data class DiagnosticStageTiming(
    val stage: DiagnosticStage,
    val observation: DiagnosticObservation = DiagnosticObservation.UNOBSERVED,
    val durationMs: Long? = null,
    /** Excludes observed child scopes. Only exclusive values may be added to the total. */
    val exclusiveDurationMs: Long? = null,
    val operations: Long = 0,
    val recoveryRetries: Long = 0,
)
data class DiagnosticRequestStats(
    val stage: DiagnosticStage,
    val category: DiagnosticRequestCategory,
    val label: DiagnosticRequestLabel,
    val attempts: Long = 0,
    val retries: Long = 0,
    val successfulRequests: Long = 0,
    val failedRequests: Long = 0,
    val attemptDurationMs: Long = 0,
    val lastHttpStatus: Int? = null,
    val responseChars: Long? = null,
    val httpStatusCounts: List<DiagnosticHttpStatusCount> = emptyList(),
)
data class DiagnosticHttpStatusCount(val status: Int, val attempts: Long)
data class DiagnosticCounters(
    val parsed: Long? = null,
    val inserted: Long? = null,
    val updated: Long? = null,
    val quarantined: Long? = null,
    val rejected: Long? = null,
    val skippedDuplicate: Long? = null,
    val recentRecords: Long? = null,
    val playCountCharts: Long? = null,
)
data class DiagnosticAuthMilestone(val event: DiagnosticAuthEvent, val elapsedMs: Long?)
data class ImportDiagnosticReport(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val mode: DiagnosticImportMode,
    val outcome: DiagnosticOutcome?,
    val termination: DiagnosticTermination,
    /** Begins at callback processing / manual import execution, includes login and client/database cleanup. */
    val totalDurationMs: Long?,
    /** Service admission to termination; excludes permission UI before service admission. */
    val executionDurationMs: Long?,
    val authorizationWaitDurationMs: Long?,
    val unattributedDurationMs: Long?,
    val stages: List<DiagnosticStageTiming>,
    val requests: List<DiagnosticRequestStats>,
    val counters: DiagnosticCounters = DiagnosticCounters(),
    val failures: Set<DiagnosticFailure> = emptySet(),
    /** Quick-auth offsets from its own Requested event; separate from stage durations. */
    val authMilestones: List<DiagnosticAuthMilestone> = emptyList(),
    val metricsBounded: Boolean = false,
)
