package com.evansappstudio.doapp.catalogue

// Pure region logic: time zone to state, and which items show where. No Android
// imports (CorePurityTest). No location permission is ever used.

/**
 * Where the catalogue rules come from. [code] is a state ("AU-WA"), a country
 * with no state yet ("AU", for "NSW or ACT" before the one tap), or null for
 * outside Australia, where only "*" items show.
 */
data class Region(val code: String?) {
    val country: String? get() = code?.substringBefore('-')
    val state: String? get() = code?.takeIf { it.contains('-') }

    companion object {
        val OUTSIDE = Region(null)
    }
}

/** How the stored region was set. */
enum class RegionSource {
    AUTO,
    USER,
    ;

    companion object {
        fun fromName(raw: String?): RegionSource? = entries.firstOrNull { it.name == raw }
    }
}

/** A guess from the time zone. [needsChoice] is true only for "NSW or ACT". */
data class RegionGuess(val region: Region, val needsChoice: Boolean = false)

object RegionResolver {
    const val NSW = "AU-NSW"
    const val VIC = "AU-VIC"
    const val QLD = "AU-QLD"
    const val WA = "AU-WA"
    const val SA = "AU-SA"
    const val TAS = "AU-TAS"
    const val ACT = "AU-ACT"
    const val NT = "AU-NT"

    /** The eight states and territories, in the order the Change list shows them. */
    val STATES: List<String> = listOf(NSW, VIC, QLD, WA, SA, TAS, ACT, NT)

    /** Sydney and Lord Howe could be either. */
    val NSW_OR_ACT: List<String> = listOf(NSW, ACT)

    private val ZONES: Map<String, String> = mapOf(
        "Australia/Perth" to WA,
        "Australia/West" to WA,
        "Australia/Eucla" to WA,
        "Australia/Brisbane" to QLD,
        "Australia/Queensland" to QLD,
        "Australia/Lindeman" to QLD,
        "Australia/Adelaide" to SA,
        "Australia/South" to SA,
        "Australia/Darwin" to NT,
        "Australia/North" to NT,
        "Australia/Hobart" to TAS,
        "Australia/Tasmania" to TAS,
        "Australia/Currie" to TAS,
        "Australia/Melbourne" to VIC,
        "Australia/Victoria" to VIC,
        "Australia/Canberra" to ACT,
        "Australia/ACT" to ACT,
        // Broken Hill keeps South Australian time but is in NSW.
        "Australia/Broken_Hill" to NSW,
        "Australia/Yancowinna" to NSW,
    )

    private val EITHER: Set<String> = setOf(
        "Australia/Sydney",
        "Australia/NSW",
        "Australia/Lord_Howe",
        "Australia/LHI",
    )

    fun resolve(zoneId: String?): RegionGuess {
        val zone = zoneId?.trim().orEmpty()
        ZONES[zone]?.let { return RegionGuess(Region(it)) }
        if (zone in EITHER) return RegionGuess(Region("AU"), needsChoice = true)
        return RegionGuess(Region.OUTSIDE)
    }

    /** "AU-WA", "AU" or "none" (outside Australia). Anything else is null. */
    fun parse(raw: String?): Region? {
        val text = raw?.trim() ?: return null
        if (text.equals("none", ignoreCase = true) || text.equals("outside", ignoreCase = true)) return Region.OUTSIDE
        val upper = text.uppercase()
        if (upper == "AU") return Region("AU")
        return if (upper in STATES) Region(upper) else null
    }

    /** The storage token for [region]. */
    fun token(region: Region): String = region.code ?: "none"
}

/** An item as it applies in one region: its state rule merged in. */
data class LocalItem(
    val item: CatalogueItem,
    val title: String,
    val why: String,
    val interval: CatalogueInterval,
) {
    val id: String get() = item.id
}

object CatalogueFilter {
    /** False when the item does not apply in [region]. */
    fun visible(item: CatalogueItem, region: Region): Boolean {
        val state = region.state
        if (state != null && state in item.hiddenIn) return false
        if ("*" in item.regions) return true
        val country = region.country ?: return false
        return country in item.regions || (state != null && state in item.regions)
    }

    /** The item with [region]'s state rule applied (title, why, interval). */
    fun localise(item: CatalogueItem, region: Region): LocalItem {
        val rule = region.state?.let { item.stateRules[it] }
        return LocalItem(
            item = item,
            title = rule?.title ?: item.title,
            why = rule?.why ?: item.why,
            interval = rule?.interval ?: item.interval,
        )
    }

    /** Items that apply in [region], localised, in file order. */
    fun forRegion(catalogue: Catalogue, region: Region): List<LocalItem> =
        catalogue.items.filter { visible(it, region) }.map { localise(it, region) }
}

/** The region in force, how it was set, and whether "NSW or ACT" still needs its one tap. */
data class RegionSetting(
    val region: Region,
    val source: RegionSource,
    val needsChoice: Boolean,
) {
    companion object {
        /**
         * A USER choice is kept as stored. AUTO (or nothing stored, or an unreadable
         * value) is worked out from [zoneId] each time, so a move between zones follows.
         */
        fun effective(storedToken: String?, storedSource: String?, zoneId: String?): RegionSetting {
            val source = RegionSource.fromName(storedSource)
            val stored = RegionResolver.parse(storedToken)
            if (source == RegionSource.USER && stored != null) {
                return RegionSetting(stored, RegionSource.USER, needsChoice = stored.code == "AU")
            }
            val guess = RegionResolver.resolve(zoneId)
            return RegionSetting(guess.region, RegionSource.AUTO, guess.needsChoice)
        }
    }
}
