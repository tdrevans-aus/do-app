package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.backup.JsonParseException
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.encodeJson
import com.evansappstudio.doapp.backup.parseJson
import java.time.LocalDate

// Pure rules for offering Stuff You Should Do again after first run. Never a
// notification: the result is at most one card on Today. No Android imports
// (CorePurityTest).

enum class CardKind {
    HEAD_START,
    RELATED,
    SEASONAL,
    ;

    companion object {
        fun fromName(raw: String?): CardKind? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * What the app remembers about cards. Dates are the suggestion date (the debug
 * override when set, else today).
 */
data class SuggestionState(
    /** "Stop suggesting": every card off. The Catalogue tab stays. */
    val stopped: Boolean = false,
    /** The day step 3 or 4 of first run was skipped. Arms the head-start card for the next day. */
    val skippedOn: LocalDate? = null,
    /** The head-start card was followed (the user opened the catalogue from it). */
    val headStartDone: Boolean = false,
    val lastShownOn: Map<CardKind, LocalDate> = emptyMap(),
    val dismissCount: Map<CardKind, Int> = emptyMap(),
    val dismissedOn: Map<CardKind, LocalDate> = emptyMap(),
    /** Catalogue item id to the day its related card was shown (once per item). */
    val relatedShown: Map<String, LocalDate> = emptyMap(),
) {
    fun encode(): String = encodeJson(
        JsonValue.Obj(
            linkedMapOf(
                "stopped" to JsonValue.Bool(stopped),
                "skippedOn" to (skippedOn?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
                "headStartDone" to JsonValue.Bool(headStartDone),
                "lastShownOn" to dates(lastShownOn.mapKeys { it.key.name }),
                "dismissCount" to JsonValue.Obj(dismissCount.entries.associate { it.key.name to JsonValue.Num(it.value.toLong()) }),
                "dismissedOn" to dates(dismissedOn.mapKeys { it.key.name }),
                "relatedShown" to dates(relatedShown),
            ),
        ),
    )

    companion object {
        val DEFAULT = SuggestionState()

        /** Anything unreadable gives the default state. */
        fun decode(text: String?): SuggestionState {
            if (text.isNullOrBlank()) return DEFAULT
            val obj = try {
                parseJson(text) as? JsonValue.Obj
            } catch (error: JsonParseException) {
                null
            } ?: return DEFAULT
            val f = obj.fields
            return SuggestionState(
                stopped = (f["stopped"] as? JsonValue.Bool)?.value ?: false,
                skippedOn = date(f["skippedOn"]),
                headStartDone = (f["headStartDone"] as? JsonValue.Bool)?.value ?: false,
                lastShownOn = kinds(dateMap(f["lastShownOn"])),
                dismissCount = kinds(
                    (f["dismissCount"] as? JsonValue.Obj)?.fields.orEmpty().mapNotNull { (key, value) ->
                        (value as? JsonValue.Num)?.value?.toInt()?.let { key to it }
                    }.toMap(),
                ),
                dismissedOn = kinds(dateMap(f["dismissedOn"])),
                relatedShown = dateMap(f["relatedShown"]),
            )
        }

        private fun dates(map: Map<String, LocalDate>): JsonValue =
            JsonValue.Obj(map.entries.associate { it.key to JsonValue.Str(it.value.toString()) })

        private fun date(value: JsonValue?): LocalDate? = try {
            (value as? JsonValue.Str)?.value?.let { LocalDate.parse(it) }
        } catch (error: RuntimeException) {
            null
        }

        private fun dateMap(value: JsonValue?): Map<String, LocalDate> =
            (value as? JsonValue.Obj)?.fields.orEmpty().mapNotNull { (key, raw) -> date(raw)?.let { key to it } }.toMap()

        private fun <T> kinds(map: Map<String, T>): Map<CardKind, T> =
            map.mapNotNull { (key, value) -> CardKind.fromName(key)?.let { it to value } }.toMap()
    }
}

/** Everything the policy reads. [items] are the catalogue items for the region, localised. */
data class SuggestionInput(
    val today: LocalDate,
    val areas: Set<String>,
    val added: Set<String>,
    /** Catalogue item ids whose reminder has been marked Done at least once, oldest first. */
    val doneItemIds: List<String>,
    val items: List<LocalItem>,
    val state: SuggestionState,
)

data class SuggestionCard(
    val kind: CardKind,
    val items: List<LocalItem>,
    /** For [CardKind.RELATED]: the item that was done. */
    val forItemId: String? = null,
)

/** The card to show (or null) and a short reason for the debug dump. */
data class SuggestionDecision(val card: SuggestionCard?, val reason: String)

object SuggestionPolicy {
    const val FIRST_BACKOFF_DAYS = 14L
    const val LATER_BACKOFF_DAYS = 60L
    const val HEAD_START_ITEMS = 5
    const val SEASONAL_ITEMS = 3

    fun decide(input: SuggestionInput): SuggestionDecision {
        val state = input.state
        if (state.stopped) return SuggestionDecision(null, "stopped")
        val notes = ArrayList<String>()
        for (kind in CardKind.entries) {
            val until = hiddenUntil(state, kind)
            if (until != null && input.today.isBefore(until)) {
                notes += "${kind.name.lowercase()} hidden until $until"
                continue
            }
            val card = when (kind) {
                CardKind.HEAD_START -> headStart(input, notes)
                CardKind.RELATED -> related(input, notes)
                CardKind.SEASONAL -> seasonal(input, notes)
            }
            if (card != null) {
                val why = when (card.kind) {
                    CardKind.HEAD_START -> "head start, first run skipped on ${state.skippedOn}"
                    CardKind.RELATED -> "related to done ${card.forItemId}"
                    CardKind.SEASONAL -> "seasonal for ${input.today.month.name.lowercase()}"
                }
                return SuggestionDecision(card, why)
            }
        }
        return SuggestionDecision(null, notes.joinToString("; ").ifEmpty { "nothing to suggest" })
    }

    /** The day a dismissed card kind can show again: 14 days after the first "Not now", 60 after later ones. */
    fun hiddenUntil(state: SuggestionState, kind: CardKind): LocalDate? {
        val count = state.dismissCount[kind] ?: 0
        val on = state.dismissedOn[kind] ?: return null
        if (count <= 0) return null
        return on.plusDays(if (count == 1) FIRST_BACKOFF_DAYS else LATER_BACKOFF_DAYS)
    }

    /** Record that [card] was on Today on [today]. */
    fun shown(state: SuggestionState, card: SuggestionCard, today: LocalDate): SuggestionState {
        val related = if (card.kind == CardKind.RELATED && card.forItemId != null) {
            state.relatedShown + (card.forItemId to (state.relatedShown[card.forItemId] ?: today))
        } else {
            state.relatedShown
        }
        return state.copy(lastShownOn = state.lastShownOn + (card.kind to today), relatedShown = related)
    }

    /** "Not now". */
    fun dismiss(state: SuggestionState, kind: CardKind, today: LocalDate): SuggestionState = state.copy(
        dismissCount = state.dismissCount + (kind to (state.dismissCount[kind] ?: 0) + 1),
        dismissedOn = state.dismissedOn + (kind to today),
    )

    /** First run step 3 or 4 skipped. */
    fun skipped(state: SuggestionState, today: LocalDate): SuggestionState = state.copy(skippedOn = today)

    fun stop(state: SuggestionState): SuggestionState = state.copy(stopped = true)

    /** The user followed a card (opened the catalogue or added from it). */
    fun followed(state: SuggestionState, kind: CardKind): SuggestionState =
        if (kind == CardKind.HEAD_START) state.copy(headStartDone = true) else state

    private fun inAreas(input: SuggestionInput): List<LocalItem> {
        val areas = input.areas.ifEmpty { ReminderCategory.ALL.toSet() }
        return input.items.filter { it.item.category in areas && it.id !in input.added }
    }

    // Rule 1: skipped in first run, the head-start card from the next day, until followed or anything is added.
    private fun headStart(input: SuggestionInput, notes: MutableList<String>): SuggestionCard? {
        val state = input.state
        val skipped = state.skippedOn ?: return null
        if (state.headStartDone || input.added.isNotEmpty()) return null
        if (!input.today.isAfter(skipped)) {
            notes += "head start from ${skipped.plusDays(1)}"
            return null
        }
        val items = Picks.ordered(inAreas(input)).take(HEAD_START_ITEMS)
        return if (items.isEmpty()) null else SuggestionCard(CardKind.HEAD_START, items)
    }

    // Rule 4: after a catalogue item is first marked Done, one card for its first related item not added, once per item.
    private fun related(input: SuggestionInput, notes: MutableList<String>): SuggestionCard? {
        val byId = input.items.associateBy { it.id }
        for (doneId in input.doneItemIds.distinct()) {
            val shownOn = input.state.relatedShown[doneId]
            if (shownOn != null && shownOn != input.today) continue
            val done = byId[doneId] ?: continue
            val next = done.item.related.firstOrNull { it !in input.added && byId.containsKey(it) } ?: continue
            return SuggestionCard(CardKind.RELATED, listOf(byId.getValue(next)), forItemId = doneId)
        }
        if (input.doneItemIds.isNotEmpty()) notes += "related already offered"
        return null
    }

    // Rule 3: at most once a calendar month, items whose suggested month is this month or next.
    private fun seasonal(input: SuggestionInput, notes: MutableList<String>): SuggestionCard? {
        val last = input.state.lastShownOn[CardKind.SEASONAL]
        if (last != null && last != input.today && last.year == input.today.year && last.month == input.today.month) {
            notes += "seasonal already shown this month on $last"
            return null
        }
        val month = input.today.monthValue
        val next = input.today.plusMonths(1).monthValue
        val items = Picks.ordered(inAreas(input).filter { it.item.suggestedMonth == month || it.item.suggestedMonth == next })
            .take(SEASONAL_ITEMS)
        return if (items.isEmpty()) null else SuggestionCard(CardKind.SEASONAL, items)
    }
}

/** Rule 5: the "Related: ..." line under the Add field. */
object KeywordMatch {
    const val MAX_ITEMS = 2

    /** Lower case words separated by single spaces, apostrophes dropped. */
    fun normalise(text: String): String =
        text.lowercase().replace("'", "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Items whose keywords appear in [title] as whole words, highest priority first. */
    fun matches(title: String, items: List<LocalItem>): List<LocalItem> {
        val text = " ${normalise(title)} "
        if (text.isBlank()) return emptyList()
        return Picks.ordered(
            items.filter { local ->
                local.item.keywords.any { keyword ->
                    val phrase = normalise(keyword)
                    phrase.isNotEmpty() && text.contains(" $phrase ")
                }
            },
        )
    }

    /**
     * Related items for what is being typed: the `related` items of the matching items,
     * not added, not themselves matched, at most [MAX_ITEMS].
     */
    fun related(title: String, items: List<LocalItem>, added: Set<String>): List<LocalItem> {
        val matched = matches(title, items)
        if (matched.isEmpty()) return emptyList()
        val byId = items.associateBy { it.id }
        val matchedIds = matched.map { it.id }.toSet()
        return matched.asSequence()
            .flatMap { it.item.related.asSequence() }
            .distinct()
            .filter { it !in matchedIds && it !in added }
            .mapNotNull { byId[it] }
            .take(MAX_ITEMS)
            .toList()
    }
}
