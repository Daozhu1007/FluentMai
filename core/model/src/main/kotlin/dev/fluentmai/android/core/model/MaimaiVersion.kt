package dev.fluentmai.android.core.model

data class MaimaiMajorVersion(
    val id: Int,
    val name: String,
)

enum class MaimaiCurrentVersionSource {
    CATALOG_VERSION_TABLE,
    NAMED_CHART_METADATA,
}

data class MaimaiCurrentVersion(
    val majorVersion: MaimaiMajorVersion,
    val source: MaimaiCurrentVersionSource,
    val chartVersionEndExclusive: Int? = null,
)

/**
 * Resolves the operating major version from explicit major-version metadata.
 *
 * Raw song or chart maxima are deliberately not used: remote catalogs may contain
 * unpublished content batches whose numeric version is newer than the operating game.
 */
fun resolveCurrentMaimaiVersion(
    majorVersions: List<MaimaiMajorVersion>,
    charts: List<ChartRecord>,
): MaimaiCurrentVersion? {
    majorVersions
        .asSequence()
        .filter { it.id > 0 && it.name.isNotBlank() }
        .distinctBy { it.id }
        .maxByOrNull { it.id }
        ?.let { version ->
            return operatingVersion(version, majorVersions, MaimaiCurrentVersionSource.CATALOG_VERSION_TABLE)
        }

    val namedVersions = charts
        .asSequence()
        .flatMap { chart ->
            sequenceOf(
                chart.chartVersion to chart.chartVersionName,
                chart.songVersion to chart.songVersionName,
            )
        }
        .mapNotNull { (id, name) ->
            name?.takeIf { id > 0 && it.isNotBlank() }?.let { MaimaiMajorVersion(id, it.trim()) }
        }
        .distinctBy { it.id }

    val named = namedVersions.toList()
    return named.maxByOrNull { it.id }?.let { version ->
        operatingVersion(version, named, MaimaiCurrentVersionSource.NAMED_CHART_METADATA)
    }
}

/** The annual Chinese DX release is the boundary, never a content-update ID. */
private fun operatingVersion(
    latest: MaimaiMajorVersion,
    versions: List<MaimaiMajorVersion>,
    source: MaimaiCurrentVersionSource,
): MaimaiCurrentVersion {
    val year = maimaiAnnualVersionYear(latest.name)
        ?: return MaimaiCurrentVersion(latest.copy(name = latest.name.trim()), source)
    val references = knownMaimaiVersions.map { MaimaiMajorVersion(it.versionId, it.officialName) }
    // Prefer the live catalog; the reference table can supply a launch boundary
    // when a catalog only labels a later update. No future release year is hard-coded.
    val boundaries = versions + references.filter { reference -> versions.none { it.id == reference.id } }
    val launch = boundaries.filter { it.id <= latest.id && maimaiAnnualVersionYear(it.name) == year }
        .minByOrNull { it.id } ?: latest
    val end = boundaries.filter { it.id > latest.id && maimaiAnnualVersionYear(it.name)?.let { y -> y > year } == true }
        .minOfOrNull { it.id } ?: ((latest.id / 1000) + 1) * 1000
    return MaimaiCurrentVersion(launch.copy(name = "舞萌DX $year"), source, end)
}

internal fun maimaiAnnualVersionYear(name: String?): Int? = name?.let {
    Regex("(?:舞萌|maimai)?dx(20[0-9]{2})").find(normalizeMaimaiVersionName(it))
        ?.groupValues?.get(1)?.toIntOrNull()
}

fun normalizeMaimaiVersionName(value: String): String =
    normalizeUnicodeCompatibility(value.trim())
        .lowercase()
        .replace(Regex("[\\s._·・:：-]+"), "")

fun sameMaimaiVersionName(left: String, right: String): Boolean =
    normalizeMaimaiVersionName(left) == normalizeMaimaiVersionName(right)
