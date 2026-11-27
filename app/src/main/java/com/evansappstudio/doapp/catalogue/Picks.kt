package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// Pure first-run picks logic: grouping, pre-tick and first-due spreading. No
// Android imports (CorePurityTest).

object Picks {
    /** At most this many items per area show before "Show more". */
    const val SHOWN_PER_AREA = 4

    /** Pre-ticking never goes past this, even for an Unlimited user, so first run stays short. */
    const val PRE_TICK_CAP = 5

    /** Highest priority first, file order breaking ties. */
    fun ordered(items: List<LocalItem>): List<LocalItem> =
        items.withIndex().sortedWith(compareByDescending<IndexedValue<LocalItem>> { it.value.item.priority }.thenBy { it.index })
            .map { it.value }

    /**
     * Items for [areas] (all eight when empty), grouped by area in [areaOrder]
     * order, highest priority first, items already [added] left out.
     */
    fun grouped(
        items: List<LocalItem>,
        areas: Set<String>,
        added: Set<String>,
        areaOrder: List<String>,
    ): List<Pair<String, List<LocalItem>>> {
        val wanted = if (areas.isEmpty()) areaOrder.toSet() else areas
        return areaOrder.filter { it in wanted }.mapNotNull { area ->
            val inArea = ordered(items.filter { it.item.category == area && it.id !in added })
            if (inArea.isEmpty()) null else area to inArea
        }
    }

    /**
     * Free slots left: the cap minus active reminders (which includes the first
     * reminder), never below zero. Null cap means no cap.
     */
    fun freeSlots(activeCount: Int, cap: Int?): Int? {
        if (cap == null || cap <= 0) return null
        return (cap - activeCount).coerceAtLeast(0)
    }

    /**
     * Which items start ticked: the highest-priority ones across [items], never a
     * `needsDate` item, never more than the free slots left. An [unlocked] user
     * gets the top [PRE_TICK_CAP] whatever is active, the same cap as a new free user.
     */
    fun preTicked(items: List<LocalItem>, activeCount: Int, unlocked: Boolean, cap: Int?): Set<String> {
        val limit = if (unlocked) {
            PRE_TICK_CAP
        } else {
            minOf(PRE_TICK_CAP, freeSlots(activeCount, cap) ?: PRE_TICK_CAP)
        }
        if (limit <= 0) return emptySet()
        return ordered(items.filter { !it.item.needsDate }).take(limit).map { it.id }.toSet()
    }

    /** The counter: free slots left after [ticked] more, or null when there is no cap. */
    fun freeLeft(activeCount: Int, ticked: Int, unlocked: Boolean, cap: Int?): Int? {
        if (unlocked) return null
        val slots = freeSlots(activeCount, cap) ?: return null
        return slots - ticked
    }

    /** True when adding [ticked] would pass the free cap for a free user (so the paywall opens first). */
    fun overCap(activeCount: Int, ticked: Int, unlocked: Boolean, cap: Int?): Boolean {
        if (unlocked || cap == null || cap <= 0) return false
        return activeCount + ticked > cap
    }
}

/** How one added item becomes a reminder's due and repeat. */
data class PlannedAdd(
    val item: LocalItem,
    val dueAt: Long,
    val rule: RepeatRule?,
)

object FirstDue {
    /**
     * First dues for a batch, in order, so they do not all land at once:
     * - `needsDate` items: the date the user picked, at the suggested time (an item with no
     *   picked date is left out).
     * - from-due-date items with a suggested month: the 1st of the next such month at the
     *   suggested time (next year when this year's has passed).
     * - every other item (from-completion, or from-due-date with no month): the i-th of them
     *   at its suggested time i days from tomorrow, i starting at 0.
     */
    fun plan(
        items: List<LocalItem>,
        now: Long,
        zone: ZoneId,
        pickedDates: Map<String, LocalDate> = emptyMap(),
    ): List<PlannedAdd> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var spread = 0
        return items.mapNotNull { local ->
            val item = local.item
            val time = LocalTime.of(item.suggestedMinute / 60, item.suggestedMinute % 60)
            val date = when {
                item.needsDate -> pickedDates[item.id] ?: return@mapNotNull null
                item.repeatFrom == RepeatFrom.DUE_DATE && item.suggestedMonth != null ->
                    nextMonthStart(item.suggestedMonth, time, now, zone)
                else -> today.plusDays(1L + spread++)
            }
            val dueAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            PlannedAdd(local, dueAt, rule(local, date))
        }
    }

    /** The 1st of [month] at [time], this year if that is still ahead of [now], else next year. */
    fun nextMonthStart(month: Int, time: LocalTime, now: Long, zone: ZoneId): LocalDate {
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        val thisYear = LocalDate.of(year, month, 1)
        val at = LocalDateTime.of(thisYear, time).atZone(zone).toInstant().toEpochMilli()
        return if (at > now) thisYear else thisYear.plusYears(1)
    }

    /** The item's repeat (after state rules). Monthly from-due-date repeats keep the first due's day. */
    fun rule(local: LocalItem, firstDue: LocalDate): RepeatRule? {
        val interval = local.interval
        val from = local.item.repeatFrom
        val monthOn = if (interval.unit == RepeatUnit.MONTH && from == RepeatFrom.DUE_DATE) {
            MonthOn.DayOfMonth(firstDue.dayOfMonth)
        } else {
            null
        }
        val weekDays = if (interval.unit == RepeatUnit.WEEK && from == RepeatFrom.DUE_DATE) {
            listOf(WeekDay.fromDayOfWeek(firstDue.dayOfWeek))
        } else {
            emptyList()
        }
        return RepeatRule.of(interval.every, interval.unit, from, weekDays, monthOn)
    }
}
