package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.backup.JsonParseException
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatUnit

// The pure Stuff You Should Do catalogue model and parser. No Android imports:
// this file is in CorePurityTest's list. The loader that reads the asset is
// CatalogueLoader.kt on the Android side.

/** Do's alert names. Stored as today's names until Spec 14 renames them in code. */
enum class CatalogueAlert(val stored: String) {
    NORMAL("STANDARD"),
    LOUD("CRITICAL"),
    ALARM("PROMINENT"),
    ;

    companion object {
        fun fromName(raw: String?): CatalogueAlert? = entries.firstOrNull { it.name == raw }
    }
}

data class CatalogueInterval(val every: Int, val unit: RepeatUnit)

/** One state's override. A null field keeps the item's own value. */
data class StateRule(
    val interval: CatalogueInterval? = null,
    val why: String? = null,
    val title: String? = null,
)

data class CatalogueItem(
    val id: String,
    val version: Int,
    val category: String,
    val title: String,
    val why: String,
    val interval: CatalogueInterval,
    val repeatFrom: RepeatFrom,
    /** 1 to 12, or null. */
    val suggestedMonth: Int?,
    /** Minutes after midnight, local time. */
    val suggestedMinute: Int,
    val alert: CatalogueAlert,
    /** "*", a country ("AU") or a state ("AU-WA"). */
    val regions: List<String>,
    /** Shows the "Check your state" tag. */
    val stateVaries: Boolean = false,
    val stateRules: Map<String, StateRule> = emptyMap(),
    val hiddenIn: List<String> = emptyList(),
    val needsDate: Boolean = false,
    /** 0 to 100. */
    val priority: Int = 50,
    val related: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val health: Boolean = false,
)

data class Catalogue(
    val schema: Int,
    val catalogueVersion: Int,
    val items: List<CatalogueItem>,
) {
    private val byId: Map<String, CatalogueItem> = items.associateBy { it.id }

    fun item(id: String?): CatalogueItem? = if (id == null) null else byId[id]

    companion object {
        val EMPTY = Catalogue(schema = 0, catalogueVersion = 0, items = emptyList())
    }
}

/** What the parser kept and what it skipped, with a short reason per skipped item. */
data class CatalogueParse(
    val catalogue: Catalogue,
    val skipped: List<String>,
)

object CatalogueParser {
    const val SUPPORTED_SCHEMA = 1

    /**
     * A malformed item is skipped (its reason is in [CatalogueParse.skipped]) and the
     * rest load. A file that is not JSON, or has no items array, gives an empty catalogue.
     * A repeated id keeps the first. A `related` id that names no item is dropped.
     */
    fun parse(text: String): CatalogueParse {
        val root = try {
            parseJson(text, allowFractions = true) as? JsonValue.Obj
        } catch (error: JsonParseException) {
            null
        } ?: return CatalogueParse(Catalogue.EMPTY, listOf("file: not a JSON object"))
        val schema = intOf(root.fields["schema"]) ?: 0
        if (schema != SUPPORTED_SCHEMA) {
            return CatalogueParse(Catalogue.EMPTY, listOf("file: unsupported schema $schema"))
        }
        val version = intOf(root.fields["catalogueVersion"]) ?: 0
        val array = root.fields["items"] as? JsonValue.Arr
            ?: return CatalogueParse(Catalogue(schema, version, emptyList()), listOf("file: no items"))
        val skipped = ArrayList<String>()
        val kept = LinkedHashMap<String, CatalogueItem>()
        array.items.forEachIndexed { index, value ->
            val result = item(value)
            when {
                result.isFailure -> skipped += "item $index: ${result.exceptionOrNull()?.message}"
                kept.containsKey(result.getOrThrow().id) -> skipped += "item $index: duplicate id ${result.getOrThrow().id}"
                else -> kept[result.getOrThrow().id] = result.getOrThrow()
            }
        }
        val items = kept.values.map { item ->
            val related = item.related.filter { it in kept && it != item.id }
            if (related.size != item.related.size) {
                skipped += "item ${item.id}: dropped unknown related ${item.related - related.toSet()}"
            }
            item.copy(related = related)
        }
        return CatalogueParse(Catalogue(schema, version, items), skipped)
    }

    private fun item(value: JsonValue): Result<CatalogueItem> = runCatching {
        val obj = value as? JsonValue.Obj ?: bad("not an object")
        val f = obj.fields
        val id = str(f["id"]) ?: bad("no id")
        require(ID.matches(id)) { "bad id $id" }
        val category = ReminderCategory.normalize(str(f["category"])) ?: bad("bad category")
        val title = str(f["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: bad("no title")
        val why = str(f["why"])?.trim().orEmpty()
        val interval = interval(f["interval"]) ?: bad("bad interval")
        val from = RepeatFrom.fromName(str(f["repeatFrom"])) ?: bad("bad repeatFrom")
        val month = when (val raw = f["suggestedMonth"]) {
            null, JsonValue.Null -> null
            else -> intOf(raw)?.takeIf { it in 1..12 } ?: bad("bad suggestedMonth")
        }
        val minute = minuteOf(str(f["suggestedTime"])) ?: bad("bad suggestedTime")
        val alert = when (val raw = f["alertLevel"]) {
            null, JsonValue.Null -> CatalogueAlert.NORMAL
            else -> CatalogueAlert.fromName(str(raw)) ?: bad("bad alertLevel")
        }
        val regions = strings(f["regions"]) ?: bad("bad regions")
        require(regions.isNotEmpty()) { "no regions" }
        val rules = when (val raw = f["stateRules"]) {
            null, JsonValue.Null -> emptyMap()
            is JsonValue.Obj -> raw.fields.mapValues { (state, rule) -> stateRule(state, rule) }
            else -> bad("bad stateRules")
        }
        val priority = when (val raw = f["priority"]) {
            null, JsonValue.Null -> 50
            else -> intOf(raw)?.takeIf { it in 0..100 } ?: bad("bad priority")
        }
        CatalogueItem(
            id = id,
            version = intOf(f["version"])?.takeIf { it >= 1 } ?: bad("bad version"),
            category = category,
            title = title,
            why = why,
            interval = interval,
            repeatFrom = from,
            suggestedMonth = month,
            suggestedMinute = minute,
            alert = alert,
            regions = regions,
            stateVaries = bool(f["stateVaries"]),
            stateRules = rules,
            hiddenIn = strings(f["hiddenIn"]).orEmpty(),
            needsDate = bool(f["needsDate"]),
            priority = priority,
            related = strings(f["related"]).orEmpty(),
            keywords = strings(f["keywords"]).orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            health = bool(f["health"]),
        )
    }

    private fun stateRule(state: String, value: JsonValue): StateRule {
        require(STATE.matches(state)) { "bad stateRules key $state" }
        val obj = value as? JsonValue.Obj ?: bad("bad stateRules for $state")
        val interval = obj.fields["interval"]?.let { interval(it) ?: bad("bad interval for $state") }
        return StateRule(
            interval = interval,
            why = str(obj.fields["why"])?.trim()?.takeIf { it.isNotEmpty() },
            title = str(obj.fields["title"])?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun interval(value: JsonValue?): CatalogueInterval? {
        val obj = value as? JsonValue.Obj ?: return null
        val every = intOf(obj.fields["every"])?.takeIf { it in 1..99 } ?: return null
        val unit = when (str(obj.fields["unit"])) {
            "DAY" -> RepeatUnit.DAY
            "WEEK" -> RepeatUnit.WEEK
            "MONTH" -> RepeatUnit.MONTH
            "YEAR" -> RepeatUnit.YEAR
            else -> return null
        }
        return CatalogueInterval(every, unit)
    }

    /** "HH:MM", 24 hour. */
    fun minuteOf(raw: String?): Int? {
        val match = TIME.matchEntire(raw?.trim() ?: return null) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun str(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

    private fun intOf(value: JsonValue?): Int? = (value as? JsonValue.Num)?.value?.takeIf {
        it in Int.MIN_VALUE..Int.MAX_VALUE
    }?.toInt()

    private fun bool(value: JsonValue?): Boolean = (value as? JsonValue.Bool)?.value ?: false

    private fun strings(value: JsonValue?): List<String>? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Arr -> value.items.map { str(it) ?: return null }
        else -> null
    }

    private fun bad(reason: String): Nothing = throw IllegalArgumentException(reason)

    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val STATE = Regex("[A-Z]{2}-[A-Z]{2,3}")
    private val TIME = Regex("(\\d{1,2}):(\\d{2})")
}
