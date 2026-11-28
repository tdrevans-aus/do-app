package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.MonthNth
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.MonthTarget
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPresets
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToLong

/**
 * On-device English date, time, offset and repeat parser.
 * One match or none. Numeric dates are day/month. There is no network and no ML.
 * Repeat phrases are a Do addition mapped only onto Spec 4 rules.
 */
object DateParser {
    data class Match(
        val start: Int,
        val end: Int,
        val matched: String,
        val title: String,
        val dueAt: Long,
        val rule: RepeatRule?,
    )

    fun parse(text: String, nowMillis: Long, draftDueMillis: Long, zone: ZoneId): Match? {
        if (text.isEmpty()) return null
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val poisons = mutableListOf<IntRange>()
        val found = mutableListOf<Found>()
        for (start in tokens.indices) {
            val attempt = attemptAt(tokens, start) ?: continue
            val charStart = tokens[start].start
            val charEnd = tokens[attempt.endToken - 1].end
            if (!attempt.valid || attempt.bits == null) {
                poisons += charStart until charEnd
                continue
            }
            val due = resolve(attempt.bits, nowMillis, draftDueMillis, zone) ?: continue
            found += Found(
                start = charStart,
                end = charEnd,
                dueAt = due,
                rule = finalizeRule(attempt.bits, draftDueMillis, zone),
            )
        }
        val viable = found.filter { item ->
            poisons.none { poison -> item.start >= poison.first && item.start < poison.last + 1 }
        }
        // Longest span wins. An equal span keeps the earlier one.
        // maxWith(descending) would keep the shortest, so sort and take the first.
        val best = viable.sortedWith(
            compareByDescending<Found> { it.end - it.start }.thenBy { it.start },
        ).firstOrNull() ?: return null
        return Match(
            start = best.start,
            end = best.end,
            matched = text.substring(best.start, best.end),
            title = leftover(text, best.start, best.end),
            dueAt = best.dueAt,
            rule = best.rule,
        )
    }

    private fun finalizeRule(bits: Bits, draftMillis: Long, zone: ZoneId): RepeatRule? {
        if (!bits.domFromDraft) return bits.rule
        val day = Instant.ofEpochMilli(draftMillis).atZone(zone).dayOfMonth
        return RepeatRule.of(
            interval = 1,
            unit = RepeatUnit.MONTH,
            from = RepeatFrom.DUE_DATE,
            monthOn = MonthOn.DayOfMonth(day),
        )
    }

    /** Spec 4 debug token. Completion rules add `from COMPLETION`. */
    fun debugRule(rule: RepeatRule?): String {
        if (rule == null) return "OFF"
        val base = rule.token()
        return if (rule.from == RepeatFrom.COMPLETION) "$base from COMPLETION" else base
    }

    private data class Found(
        val start: Int,
        val end: Int,
        val dueAt: Long,
        val rule: RepeatRule?,
    )

    private data class Tok(val raw: String, val start: Int, val end: Int) {
        val norm: String = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val compact: String = norm.replace(" ", "")
    }

    private enum class DateKind { NONE, TODAY, TOMORROW, WEEKDAY, MONTH_DAY, DOM }

    private enum class AnchorKind { NONE, DAYS, DOM, NTH, MONTH_DAY }

    private enum class PieceKind { REPEAT, OFFSET, DATE, TIME }

    private enum class Connector { AT, ON, IN }

    private class Bits {
        var time: LocalTime? = null
        var dateKind: DateKind = DateKind.NONE
        var weekday: DayOfWeek? = null
        var nextWeekday: Boolean = false
        var month: Int? = null
        var day: Int? = null
        var year: Int? = null
        var explicitYear: Boolean = false
        var offset: OffsetSpec? = null
        var rule: RepeatRule? = null
        var anchor: AnchorKind = AnchorKind.NONE
        var anchorDays: List<WeekDay> = emptyList()
        var anchorDom: Int? = null
        var domFromDraft: Boolean = false
        var anchorNth: MonthOn.Nth? = null
        var invalid: Boolean = false

        fun isBlank(): Boolean =
            time == null && dateKind == DateKind.NONE && offset == null && rule == null && !invalid
    }

    private data class OffsetSpec(
        val years: Long = 0,
        val months: Long = 0,
        val weeks: Long = 0,
        val days: Long = 0,
        val seconds: Long = 0,
    ) {
        fun isEmpty(): Boolean = years == 0L && months == 0L && weeks == 0L && days == 0L && seconds == 0L

        operator fun plus(other: OffsetSpec): OffsetSpec = OffsetSpec(
            years + other.years,
            months + other.months,
            weeks + other.weeks,
            days + other.days,
            seconds + other.seconds,
        )
    }

    private class Attempt(val endToken: Int, val valid: Boolean, val bits: Bits?)

    private data class Piece(val next: Int, val kind: PieceKind, val apply: (Bits) -> Unit)

    private fun attemptAt(tokens: List<Tok>, start: Int): Attempt? {
        val bits = Bits()
        var index = start
        var first = true
        while (index < tokens.size) {
            val connector = connectorOf(tokens[index])
            val pieceIndex = if (connector != null) index + 1 else index
            if (connector != null && pieceIndex >= tokens.size) break
            val piece = nextPiece(tokens, bits, pieceIndex) ?: break
            if (piece.next <= pieceIndex) break
            if (connector != null && !connectorFits(connector, piece.kind)) break
            if (!first && connector == null && piece.kind == PieceKind.REPEAT) break
            piece.apply(bits)
            index = piece.next
            first = false
            if (bits.invalid) break
        }
        if (index == start || bits.isBlank()) return null
        val valid = !bits.invalid && (bits.time != null || bits.dateKind != DateKind.NONE || bits.offset != null || bits.rule != null)
        return Attempt(index, valid, bits)
    }

    private fun nextPiece(tokens: List<Tok>, bits: Bits, index: Int): Piece? {
        if (bits.rule == null && !bits.invalid) {
            parseRepeat(tokens, index)?.let { repeat ->
                return Piece(repeat.next, PieceKind.REPEAT) { target ->
                    if (repeat.invalid) {
                        target.invalid = true
                    } else {
                        target.rule = repeat.rule
                        target.anchor = repeat.anchor
                        target.anchorDays = repeat.days
                        target.anchorDom = repeat.dom
                        target.domFromDraft = repeat.domFromDraft
                        target.anchorNth = repeat.nth
                        if (repeat.month != null && repeat.day != null) {
                            target.month = repeat.month
                            target.day = repeat.day
                        }
                    }
                }
            }
            unsupportedRepeatEnd(tokens, index)?.let { end ->
                return Piece(end, PieceKind.REPEAT) { it.invalid = true }
            }
        }
        if (bits.isBlank()) {
            parseOffset(tokens, index)?.let { offset ->
                return Piece(offset.next, PieceKind.OFFSET) { it.offset = offset.spec }
            }
        }
        if (bits.dateKind == DateKind.NONE && bits.anchor == AnchorKind.NONE) {
            parseDate(tokens, index)?.let { date ->
                return Piece(date.next, PieceKind.DATE) { target ->
                    target.dateKind = date.kind
                    target.weekday = date.weekday
                    target.nextWeekday = date.nextWeekday
                    target.month = date.month
                    target.day = date.day
                    target.year = date.year
                    target.explicitYear = date.explicitYear
                }
            }
        }
        if (bits.time == null) {
            phraseTime(tokens, index)?.let { (next, time) ->
                return Piece(next, PieceKind.TIME) { it.time = time }
            }
            parseTime(tokens, index)?.let { time ->
                return Piece(index + 1, PieceKind.TIME) { it.time = time }
            }
        }
        return null
    }

    private fun connectorOf(token: Tok): Connector? = when (token.norm) {
        "at" -> Connector.AT
        "on" -> Connector.ON
        "in" -> Connector.IN
        else -> null
    }

    private fun connectorFits(connector: Connector, kind: PieceKind): Boolean = when (connector) {
        Connector.AT -> kind == PieceKind.TIME
        Connector.ON -> kind == PieceKind.DATE
        Connector.IN -> kind == PieceKind.OFFSET
    }

    private data class RepeatParse(
        val next: Int,
        val invalid: Boolean = false,
        val rule: RepeatRule? = null,
        val anchor: AnchorKind = AnchorKind.NONE,
        val days: List<WeekDay> = emptyList(),
        val dom: Int? = null,
        val domFromDraft: Boolean = false,
        val nth: MonthOn.Nth? = null,
        val month: Int? = null,
        val day: Int? = null,
    )

    private fun parseRepeat(tokens: List<Tok>, index: Int): RepeatParse? {
        parseFromCompletion(tokens, index)?.let { return it }
        parseEveryAfterCompletion(tokens, index)?.let { return it }
        parseEveryOnDays(tokens, index)?.let { return it }
        parseFixedWord(tokens, index)?.let { return it }
        parseEveryWeekdayName(tokens, index)?.let { return it }
        parsePluralWeekday(tokens, index)?.let { return it }
        parseWeeklyOn(tokens, index)?.let { return it }
        parseEveryMonthDay(tokens, index)?.let { return it }
        parseEveryOrdinal(tokens, index)?.let { return it }
        parseOrdinalEachMonth(tokens, index)?.let { return it }
        parseNthEachMonth(tokens, index)?.let { return it }
        parseLastWeekdayOfMonth(tokens, index)?.let { return it }
        parseFirstLastDay(tokens, index)?.let { return it }
        parseMonthlyOn(tokens, index)?.let { return it }
        parseBareMonthly(tokens, index)?.let { return it }
        parseSimpleEvery(tokens, index)?.let { return it }
        return null
    }

    private data class EveryN(val count: Int, val unit: RepeatUnit, val next: Int)

    private fun parseEveryN(tokens: List<Tok>, index: Int): EveryN? {
        if (wordAt(tokens, index) != "every") return null
        val second = tokens.getOrNull(index + 1) ?: return null
        val unitWord = unitOf(second.norm)
        if (unitWord != null) {
            return EveryN(1, unitWord, index + 2)
        }
        val count = intNumber(second) ?: return null
        val unit = tokens.getOrNull(index + 2)?.let { unitOf(it.norm) } ?: return null
        if (count !in RepeatRule.MIN_INTERVAL..RepeatRule.MAX_INTERVAL) return null
        return EveryN(count, unit, index + 3)
    }

    private fun parseFromCompletion(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "from" || wordAt(tokens, index + 1) != "completion") return null
        val every = parseEveryN(tokens, index + 2) ?: return null
        if (every.unit == RepeatUnit.WEEK) return null
        val rule = RepeatRule.of(every.count, every.unit, RepeatFrom.COMPLETION) ?: return null
        return RepeatParse(next = every.next, rule = rule)
    }

    private fun parseEveryAfterCompletion(tokens: List<Tok>, index: Int): RepeatParse? {
        val every = parseEveryN(tokens, index) ?: return null
        if (wordAt(tokens, every.next) != "after" || wordAt(tokens, every.next + 1) != "completion") {
            return null
        }
        if (every.unit == RepeatUnit.WEEK) return null
        val rule = RepeatRule.of(every.count, every.unit, RepeatFrom.COMPLETION) ?: return null
        return RepeatParse(next = every.next + 2, rule = rule)
    }

    private fun parseEveryOnDays(tokens: List<Tok>, index: Int): RepeatParse? {
        val every = parseEveryN(tokens, index) ?: return null
        if (wordAt(tokens, every.next) != "on") return null
        val days = parseDayList(tokens, every.next + 1) ?: return null
        val incompatible = every.unit == RepeatUnit.HOUR ||
            every.unit == RepeatUnit.MINUTE ||
            every.unit == RepeatUnit.DAY ||
            every.unit == RepeatUnit.YEAR ||
            every.unit == RepeatUnit.MONTH
        if (incompatible || every.unit != RepeatUnit.WEEK) {
            return RepeatParse(next = days.second, invalid = true)
        }
        val rule = RepeatRule.of(every.count, RepeatUnit.WEEK, RepeatFrom.DUE_DATE, days.first) ?: return null
        return RepeatParse(
            next = days.second,
            rule = rule,
            anchor = AnchorKind.DAYS,
            days = days.first,
        )
    }

    private fun parseFixedWord(tokens: List<Tok>, index: Int): RepeatParse? {
        return when (wordAt(tokens, index)) {
            "daily" -> plain(index + 1, 1, RepeatUnit.DAY)
            "hourly" -> plain(index + 1, 1, RepeatUnit.HOUR)
            "yearly" -> plain(index + 1, 1, RepeatUnit.YEAR)
            "weekdays" -> weekdays(index + 1)
            else -> {
                if (wordAt(tokens, index) == "every" && wordAt(tokens, index + 1) == "weekday") {
                    weekdays(index + 2)
                } else {
                    null
                }
            }
        }
    }

    private fun parseEveryWeekdayName(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "every") return null
        val token = tokens.getOrNull(index + 1) ?: return null
        if (token.norm == "weekday" || token.norm == "weekdays") return null
        if (unitOf(token.norm) != null) return null
        val day = weekdayWord(token.norm) ?: return null
        if (isPluralWeekday(token.norm)) return null
        return oneWeekday(index + 2, day)
    }

    private fun parsePluralWeekday(tokens: List<Tok>, index: Int): RepeatParse? {
        val token = tokens.getOrNull(index) ?: return null
        if (!isPluralWeekday(token.norm)) return null
        val day = weekdayWord(token.norm) ?: return null
        return oneWeekday(index + 1, day)
    }

    private fun parseWeeklyOn(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "weekly" || wordAt(tokens, index + 1) != "on") return null
        val days = parseDayList(tokens, index + 2) ?: return null
        val rule = RepeatRule.of(1, RepeatUnit.WEEK, RepeatFrom.DUE_DATE, days.first) ?: return null
        return RepeatParse(days.second, rule = rule, anchor = AnchorKind.DAYS, days = days.first)
    }

    private fun parseEveryMonthDay(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "every") return null
        val day = tokens.getOrNull(index + 1)?.let { dayNumber(it) } ?: return null
        val month = tokens.getOrNull(index + 2)?.let { monthNumber(it) } ?: return null
        if (!validDate(month, day, null)) return null
        val rule = RepeatRule.of(1, RepeatUnit.YEAR, RepeatFrom.DUE_DATE) ?: return null
        return RepeatParse(
            next = index + 3,
            rule = rule,
            anchor = AnchorKind.MONTH_DAY,
            month = month,
            day = day,
        )
    }

    private fun parseEveryOrdinal(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "every") return null
        val token = tokens.getOrNull(index + 1) ?: return null
        val day = ordinalNumber(token) ?: return null
        if (unitOf(token.norm) != null) return null
        if (tokens.getOrNull(index + 2)?.let { monthNumber(it) } != null) return null
        return domRule(index + 2, day)
    }

    private fun parseOrdinalEachMonth(tokens: List<Tok>, index: Int): RepeatParse? {
        var cursor = index
        if (wordAt(tokens, cursor) == "the") cursor++
        val day = tokens.getOrNull(cursor)?.let { ordinalNumber(it) } ?: return null
        if (wordAt(tokens, cursor + 1) != "each" || wordAt(tokens, cursor + 2) != "month") return null
        return domRule(cursor + 3, day)
    }

    private fun parseNthEachMonth(tokens: List<Tok>, index: Int): RepeatParse? {
        val which = nthWord(tokens.getOrNull(index)) ?: return null
        val target = tokens.getOrNull(index + 1)?.let { monthTarget(it.norm) } ?: return null
        if (target == MonthTarget.DAY || target == MonthTarget.WEEKDAY) return null
        val tail = when {
            wordAt(tokens, index + 2) == "each" && wordAt(tokens, index + 3) == "month" -> index + 4
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "the" &&
                wordAt(tokens, index + 4) == "month" -> index + 5
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "month" -> index + 4
            else -> return null
        }
        return nthRule(tail, which, target)
    }

    private fun parseLastWeekdayOfMonth(tokens: List<Tok>, index: Int): RepeatParse? {
        if (wordAt(tokens, index) != "last") return null
        val target = tokens.getOrNull(index + 1)?.let { monthTarget(it.norm) } ?: return null
        if (target == MonthTarget.DAY || target == MonthTarget.WEEKDAY) return null
        val tail = when {
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "the" &&
                wordAt(tokens, index + 4) == "month" -> index + 5
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "month" -> index + 4
            else -> return null
        }
        return nthRule(tail, MonthNth.LAST, target)
    }

    private fun parseFirstLastDay(tokens: List<Tok>, index: Int): RepeatParse? {
        val which = when (wordAt(tokens, index)) {
            "first" -> MonthNth.FIRST
            "last" -> MonthNth.LAST
            else -> nthWord(tokens.getOrNull(index))
        } ?: return null
        if (which != MonthNth.FIRST && which != MonthNth.LAST) return null
        if (wordAt(tokens, index + 1) != "day") return null
        val tail = when {
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "the" &&
                wordAt(tokens, index + 4) == "month" -> index + 5
            wordAt(tokens, index + 2) == "of" && wordAt(tokens, index + 3) == "month" -> index + 4
            else -> return null
        }
        return nthRule(tail, which, MonthTarget.DAY)
    }

    private fun parseMonthlyOn(tokens: List<Tok>, index: Int): RepeatParse? {
        val head = monthlyHead(tokens, index) ?: return null
        if (wordAt(tokens, head) != "on") return null
        var cursor = head + 1
        if (wordAt(tokens, cursor) == "the") cursor++
        if (wordAt(tokens, cursor) == "last") {
            val target = tokens.getOrNull(cursor + 1)?.let { monthTarget(it.norm) } ?: return null
            if (target == MonthTarget.DAY || target == MonthTarget.WEEKDAY) return null
            return nthRule(cursor + 2, MonthNth.LAST, target)
        }
        val which = nthWord(tokens.getOrNull(cursor))
        val dayToken = tokens.getOrNull(cursor)
        if (which != null && dayToken != null) {
            val target = tokens.getOrNull(cursor + 1)?.let { monthTarget(it.norm) }
            if (target != null && target != MonthTarget.DAY && target != MonthTarget.WEEKDAY) {
                return nthRule(cursor + 2, which, target)
            }
            val dom = ordinalNumber(dayToken)
            if (dom != null && target == null) return domRule(cursor + 1, dom)
        }
        val dom = dayToken?.let { ordinalNumber(it) } ?: dayToken?.let { dayNumber(it) } ?: return null
        return domRule(cursor + 1, dom)
    }

    private fun parseBareMonthly(tokens: List<Tok>, index: Int): RepeatParse? {
        val head = monthlyHead(tokens, index) ?: return null
        if (wordAt(tokens, head) == "on") return null
        return RepeatParse(
            next = head,
            rule = RepeatRule.of(
                1,
                RepeatUnit.MONTH,
                RepeatFrom.DUE_DATE,
                monthOn = MonthOn.DayOfMonth(1),
            ),
            anchor = AnchorKind.DOM,
            domFromDraft = true,
        )
    }

    private fun parseSimpleEvery(tokens: List<Tok>, index: Int): RepeatParse? {
        val every = parseEveryN(tokens, index) ?: return null
        if (wordAt(tokens, every.next) == "on") return null
        // "every day after work" is a repeat then a time (Spec 12); any other "after" stays unsupported.
        if (wordAt(tokens, every.next) == "after" && phraseTime(tokens, every.next) == null) return null
        return when (every.unit) {
            RepeatUnit.MINUTE, RepeatUnit.HOUR -> plain(every.next, every.count, every.unit)
            RepeatUnit.DAY -> if (every.count == 1) plain(every.next, 1, RepeatUnit.DAY) else null
            RepeatUnit.YEAR -> if (every.count == 1) plain(every.next, 1, RepeatUnit.YEAR) else null
            RepeatUnit.WEEK, RepeatUnit.MONTH -> null
        }
    }

    private fun monthlyHead(tokens: List<Tok>, index: Int): Int? = when {
        wordAt(tokens, index) == "monthly" -> index + 1
        wordAt(tokens, index) == "every" && wordAt(tokens, index + 1) == "month" -> index + 2
        else -> null
    }

    private fun plain(next: Int, count: Int, unit: RepeatUnit): RepeatParse? {
        val rule = RepeatRule.of(count, unit, RepeatFrom.DUE_DATE) ?: return null
        return RepeatParse(next = next, rule = rule)
    }

    private fun weekdays(next: Int): RepeatParse {
        val days = WeekDay.WEEKDAYS
        return RepeatParse(
            next = next,
            rule = RepeatPresets.weekdays(),
            anchor = AnchorKind.DAYS,
            days = days,
        )
    }

    private fun oneWeekday(next: Int, day: WeekDay): RepeatParse? {
        val rule = RepeatRule.of(1, RepeatUnit.WEEK, RepeatFrom.DUE_DATE, listOf(day)) ?: return null
        return RepeatParse(next = next, rule = rule, anchor = AnchorKind.DAYS, days = listOf(day))
    }

    private fun domRule(next: Int, day: Int): RepeatParse? {
        if (day !in 1..31) return null
        val rule = RepeatRule.of(
            1,
            RepeatUnit.MONTH,
            RepeatFrom.DUE_DATE,
            monthOn = MonthOn.DayOfMonth(day),
        ) ?: return null
        return RepeatParse(next = next, rule = rule, anchor = AnchorKind.DOM, dom = day)
    }

    private fun nthRule(next: Int, which: MonthNth, target: MonthTarget): RepeatParse? {
        val on = MonthOn.Nth(which, target)
        val rule = RepeatRule.of(1, RepeatUnit.MONTH, RepeatFrom.DUE_DATE, monthOn = on) ?: return null
        return RepeatParse(next = next, rule = rule, anchor = AnchorKind.NTH, nth = on)
    }

    private fun unsupportedRepeatEnd(tokens: List<Tok>, index: Int): Int? {
        val head = wordAt(tokens, index) ?: return null
        if (head == "weekly") return index + 1
        if (head == "every") {
            val every = parseEveryN(tokens, index)
            if (every != null) {
                var end = every.next
                if (wordAt(tokens, end) == "on") {
                    end++
                    parseDayList(tokens, end)?.let { end = it.second }
                }
                if (wordAt(tokens, end) == "after") {
                    end++
                    while (end < tokens.size && end < index + 8 && connectorOf(tokens[end]) == null &&
                        parseTime(tokens, end) == null &&
                        wordAt(tokens, end) != "today" &&
                        wordAt(tokens, end) != "tomorrow"
                    ) {
                        end++
                    }
                }
                return end
            }
        }
        return null
    }

    private data class DayList(val first: List<WeekDay>, val second: Int)

    private fun parseDayList(tokens: List<Tok>, index: Int): DayList? {
        if (wordAt(tokens, index) == "weekdays" || wordAt(tokens, index) == "weekday") {
            return DayList(WeekDay.WEEKDAYS, index + 1)
        }
        val days = mutableListOf<WeekDay>()
        var cursor = index
        while (cursor < tokens.size) {
            if (wordAt(tokens, cursor) == "and") {
                cursor++
                continue
            }
            val day = tokens.getOrNull(cursor)?.let { weekdayWord(it.norm) } ?: break
            if (days.contains(day)) break
            days += day
            cursor++
        }
        if (days.isEmpty()) return null
        return DayList(days, cursor)
    }

    private data class OffsetParse(val next: Int, val spec: OffsetSpec)

    private fun parseOffset(tokens: List<Tok>, index: Int): OffsetParse? {
        var cursor = index
        var spec = OffsetSpec()
        var any = false
        while (cursor < tokens.size) {
            val part = oneDuration(tokens, cursor) ?: break
            if (part.spec.isEmpty()) break
            spec += part.spec
            cursor = part.next
            any = true
        }
        if (!any) return null
        return OffsetParse(cursor, spec)
    }

    private data class DurationPart(val next: Int, val spec: OffsetSpec)

    private fun oneDuration(tokens: List<Tok>, index: Int): DurationPart? {
        val token = tokens.getOrNull(index) ?: return null
        val compact = Regex("""^(\d+(?:\.\d+)?)([hm])$""").matchEntire(token.compact)
        if (compact != null) {
            val value = compact.groupValues[1].toDouble()
            val spec = when (compact.groupValues[2]) {
                "h" -> elapsedHours(value)
                "m" -> elapsedMinutes(value)
                else -> null
            } ?: return null
            return DurationPart(index + 1, spec)
        }
        val value = numberValue(token) ?: return null
        val unit = tokens.getOrNull(index + 1)?.norm?.let { unitName(it) } ?: return null
        val spec = durationSpec(value, unit) ?: return null
        return DurationPart(index + 2, spec)
    }

    private fun durationSpec(value: Double, unit: String): OffsetSpec? {
        val whole = value.roundToLong()
        val integral = kotlin.math.abs(value - whole) < 0.000_001
        return when (unit) {
            "minute" -> elapsedMinutes(value)
            "hour" -> elapsedHours(value)
            "day" -> if (integral && whole in 1..999) OffsetSpec(days = whole) else null
            "week" -> if (integral && whole in 1..999) OffsetSpec(weeks = whole) else null
            "month" -> if (integral && whole in 1..999) OffsetSpec(months = whole) else null
            "year" -> if (integral && whole in 1..999) OffsetSpec(years = whole) else null
            else -> null
        }
    }

    private fun elapsedHours(value: Double): OffsetSpec? {
        if (value <= 0.0) return null
        return OffsetSpec(seconds = (value * 3600.0).roundToLong())
    }

    private fun elapsedMinutes(value: Double): OffsetSpec? {
        if (value <= 0.0) return null
        return OffsetSpec(seconds = (value * 60.0).roundToLong())
    }

    private data class DateParse(
        val next: Int,
        val kind: DateKind,
        val weekday: DayOfWeek? = null,
        val nextWeekday: Boolean = false,
        val month: Int? = null,
        val day: Int? = null,
        val year: Int? = null,
        val explicitYear: Boolean = false,
    )

    private fun parseDate(tokens: List<Tok>, index: Int): DateParse? {
        val token = tokens.getOrNull(index) ?: return null
        parseIso(token)?.let { return it.copy(next = index + 1) }
        if (looksNumeric(token)) {
            return parseNumeric(token)?.copy(next = index + 1)
        }
        when (token.norm) {
            "today" -> return DateParse(index + 1, DateKind.TODAY)
            "tomorrow" -> return DateParse(index + 1, DateKind.TOMORROW)
        }
        if (token.norm == "next") {
            val day = tokens.getOrNull(index + 1)?.let { singularWeekday(it.norm) } ?: return null
            return DateParse(index + 2, DateKind.WEEKDAY, weekday = day.dayOfWeek(), nextWeekday = true)
        }
        singularWeekday(token.norm)?.let { day ->
            return DateParse(index + 1, DateKind.WEEKDAY, weekday = day.dayOfWeek())
        }
        if (token.norm == "the") {
            val day = tokens.getOrNull(index + 1)?.let { ordinalNumber(it) } ?: return null
            return theDay(tokens, index + 2, day)
        }
        monthNumber(token)?.let { month ->
            val day = tokens.getOrNull(index + 1)?.let { dayNumber(it) } ?: return null
            return monthDay(tokens, index + 2, month, day)
        }
        dayNumber(token)?.let { day ->
            val month = tokens.getOrNull(index + 1)?.let { monthNumber(it) } ?: return null
            return monthDay(tokens, index + 2, month, day)
        }
        return null
    }

    private fun theDay(tokens: List<Tok>, index: Int, day: Int): DateParse? {
        var cursor = index
        if (wordAt(tokens, cursor) == "of") cursor++
        val month = tokens.getOrNull(cursor)?.let { monthNumber(it) }
        if (month == null) {
            if (day !in 1..31) return null
            return DateParse(index, DateKind.DOM, day = day)
        }
        return monthDay(tokens, cursor + 1, month, day)
    }

    private fun monthDay(tokens: List<Tok>, index: Int, month: Int, day: Int): DateParse? {
        val year = tokens.getOrNull(index)?.let { yearNumber(it) }
        if (!validDate(month, day, year)) return null
        return DateParse(
            next = if (year == null) index else index + 1,
            kind = DateKind.MONTH_DAY,
            month = month,
            day = day,
            year = year,
            explicitYear = year != null,
        )
    }

    private fun parseIso(token: Tok): DateParse? {
        val match = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(token.compact) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (!validDate(month, day, year)) return null
        return DateParse(
            next = 0,
            kind = DateKind.MONTH_DAY,
            month = month,
            day = day,
            year = year,
            explicitYear = true,
        )
    }

    private fun parseNumeric(token: Tok): DateParse? {
        val match = Regex("""^(\d{1,2})[./-](\d{1,2})(?:[./-](\d{4}))?$""").matchEntire(token.compact)
            ?: return null
        val day = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val year = match.groupValues[3].toIntOrNull()
        if (month !in 1..12 || day !in 1..31) return null
        if (!validDate(month, day, year)) return null
        return DateParse(
            next = 0,
            kind = DateKind.MONTH_DAY,
            month = month,
            day = day,
            year = year,
            explicitYear = year != null,
        )
    }

    /** Spec 12: "after work" is 5:30 pm, so a shared "Friday after work" gets a time. */
    private fun phraseTime(tokens: List<Tok>, index: Int): Pair<Int, LocalTime>? {
        if (wordAt(tokens, index) == "after" && wordAt(tokens, index + 1) == "work") {
            return index + 2 to AFTER_WORK
        }
        return null
    }

    private val AFTER_WORK: LocalTime = LocalTime.of(17, 30)

    private fun parseTime(tokens: List<Tok>, index: Int): LocalTime? {
        val token = tokens.getOrNull(index) ?: return null
        when (token.norm) {
            "noon" -> return LocalTime.NOON
            "midnight" -> return LocalTime.MIDNIGHT
        }
        val ampm = Regex("""^(\d{1,2})(?::(\d{2}))?((?:a\.?m\.?)|(?:p\.?m\.?)|[ap])$""").matchEntire(token.compact)
        if (ampm != null) {
            val hour = ampm.groupValues[1].toInt()
            val minute = ampm.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 1..12 || minute !in 0..59) return null
            val pm = ampm.groupValues[3].startsWith("p")
            val normalized = when {
                pm && hour < 12 -> hour + 12
                !pm && hour == 12 -> 0
                else -> hour
            }
            return LocalTime.of(normalized, minute)
        }
        val clock = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(token.compact) ?: return null
        val hour = clock.groupValues[1].toInt()
        val minute = clock.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun resolve(bits: Bits, nowMillis: Long, draftMillis: Long, zone: ZoneId): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val draft = Instant.ofEpochMilli(draftMillis).atZone(zone)
        val offset = bits.offset
        if (offset != null && bits.time == null && bits.dateKind == DateKind.NONE && bits.anchor == AnchorKind.NONE) {
            return applyOffset(now, offset)
        }
        val time = bits.time ?: draft.toLocalTime()
        if (bits.rule != null && bits.anchor == AnchorKind.NONE && bits.time == null && bits.dateKind == DateKind.NONE) {
            return draftMillis
        }
        val date = when {
            bits.anchor != AnchorKind.NONE && bits.dateKind == DateKind.NONE -> anchorDate(bits, time, now, draft, zone)
            else -> calendarDate(bits, time, now, zone)
        } ?: return null
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    private fun calendarDate(bits: Bits, time: LocalTime, now: ZonedDateTime, zone: ZoneId): LocalDate? {
        return when (bits.dateKind) {
            DateKind.NONE -> {
                if (bits.time == null) null else nextTimeOfDay(now.toLocalDate(), time, now, zone)
            }
            DateKind.TODAY -> nextTimeOfDay(now.toLocalDate(), time, now, zone)
            DateKind.TOMORROW -> now.toLocalDate().plusDays(1)
            DateKind.WEEKDAY -> {
                val wanted = bits.weekday ?: return null
                var date = now.toLocalDate()
                if (bits.nextWeekday && date.dayOfWeek == wanted) date = date.plusDays(1)
                while (date.dayOfWeek != wanted) date = date.plusDays(1)
                val zoned = ZonedDateTime.of(date, time, zone)
                if (zoned.toInstant().isAfter(now.toInstant())) date else date.plusWeeks(1)
            }
            DateKind.MONTH_DAY -> {
                val month = bits.month ?: return null
                val day = bits.day ?: return null
                val year = bits.year ?: now.year
                var date = safeDate(year, month, day) ?: return null
                val zoned = ZonedDateTime.of(date, time, zone)
                if (!bits.explicitYear && !zoned.toInstant().isAfter(now.toInstant())) {
                    date = safeDate(year + 1, month, day) ?: return null
                }
                date
            }
            DateKind.DOM -> {
                val day = bits.day ?: return null
                nextDom(day, time, now, zone)
            }
        }
    }

    private fun anchorDate(
        bits: Bits,
        time: LocalTime,
        now: ZonedDateTime,
        draft: ZonedDateTime,
        zone: ZoneId,
    ): LocalDate? {
        return when (bits.anchor) {
            AnchorKind.NONE -> null
            AnchorKind.DAYS -> {
                val days = bits.anchorDays.map { it.dayOfWeek() }.toSet()
                if (days.isEmpty()) return null
                nextSelectedDay(days, time, now, zone)
            }
            AnchorKind.DOM -> {
                val day = if (bits.domFromDraft) draft.dayOfMonth else bits.anchorDom
                if (day == null) null else nextDom(day, time, now, zone)
            }
            AnchorKind.NTH -> {
                val nth = bits.anchorNth ?: return null
                nextNth(nth, time, now, zone)
            }
            AnchorKind.MONTH_DAY -> {
                val month = bits.month ?: return null
                val day = bits.day ?: return null
                var date = safeDate(now.year, month, day) ?: return null
                val zoned = ZonedDateTime.of(date, time, zone)
                if (!zoned.toInstant().isAfter(now.toInstant())) {
                    date = safeDate(now.year + 1, month, day) ?: return null
                }
                date
            }
        }
    }

    private fun nextTimeOfDay(start: LocalDate, time: LocalTime, now: ZonedDateTime, zone: ZoneId): LocalDate {
        val today = ZonedDateTime.of(start, time, zone)
        return if (today.toInstant().isAfter(now.toInstant())) start else start.plusDays(1)
    }

    private fun nextSelectedDay(
        days: Set<DayOfWeek>,
        time: LocalTime,
        now: ZonedDateTime,
        zone: ZoneId,
    ): LocalDate {
        var date = now.toLocalDate()
        repeat(14) {
            if (date.dayOfWeek in days) {
                val zoned = ZonedDateTime.of(date, time, zone)
                if (zoned.toInstant().isAfter(now.toInstant())) return date
            }
            date = date.plusDays(1)
        }
        return date
    }

    private fun nextDom(day: Int, time: LocalTime, now: ZonedDateTime, zone: ZoneId): LocalDate? {
        var month = YearMonth.from(now)
        repeat(24) {
            if (day in 1..month.lengthOfMonth()) {
                val date = month.atDay(day)
                val zoned = ZonedDateTime.of(date, time, zone)
                if (zoned.toInstant().isAfter(now.toInstant())) return date
            }
            month = month.plusMonths(1)
        }
        return null
    }

    private fun nextNth(nth: MonthOn.Nth, time: LocalTime, now: ZonedDateTime, zone: ZoneId): LocalDate? {
        var month = YearMonth.from(now)
        repeat(24) {
            val date = nthDate(month, nth.which, nth.target)
            val zoned = ZonedDateTime.of(date, time, zone)
            if (zoned.toInstant().isAfter(now.toInstant())) return date
            month = month.plusMonths(1)
        }
        return null
    }

    private fun nthDate(yearMonth: YearMonth, which: MonthNth, target: MonthTarget): LocalDate {
        return when (target) {
            MonthTarget.DAY -> if (which == MonthNth.LAST) yearMonth.atEndOfMonth() else yearMonth.atDay(1)
            MonthTarget.WEEKDAY -> if (which == MonthNth.LAST) lastWeekday(yearMonth) else firstWeekday(yearMonth)
            else -> {
                val day = target.weekDay()?.dayOfWeek() ?: DayOfWeek.MONDAY
                if (which == MonthNth.LAST) {
                    yearMonth.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
                } else {
                    val ordinal = when (which) {
                        MonthNth.FIRST -> 1
                        MonthNth.SECOND -> 2
                        MonthNth.THIRD -> 3
                        MonthNth.FOURTH -> 4
                        MonthNth.LAST -> 1
                    }
                    yearMonth.atDay(1).with(TemporalAdjusters.nextOrSame(day)).plusWeeks((ordinal - 1).toLong())
                }
            }
        }
    }

    private fun firstWeekday(yearMonth: YearMonth): LocalDate {
        var date = yearMonth.atDay(1)
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.plusDays(1)
        }
        return date
    }

    private fun lastWeekday(yearMonth: YearMonth): LocalDate {
        var date = yearMonth.atEndOfMonth()
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.minusDays(1)
        }
        return date
    }

    private fun applyOffset(now: ZonedDateTime, offset: OffsetSpec): Long {
        return now.plusYears(offset.years)
            .plusMonths(offset.months)
            .plusWeeks(offset.weeks)
            .plusDays(offset.days)
            .plusSeconds(offset.seconds)
            .toInstant()
            .toEpochMilli()
    }

    private fun tokenize(text: String): List<Tok> {
        val tokens = mutableListOf<Tok>()
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespace()) {
                index++
                continue
            }
            val token = nextToken(text, index)
            if (token == null) {
                index++
                continue
            }
            tokens += token
            index = token.end
        }
        return tokens
    }

    private fun nextToken(text: String, index: Int): Tok? {
        val rest = text.substring(index)
        matchRegex(rest, """\d{4}-\d{2}-\d{2}""")?.let { return tok(it, index) }
        matchRegex(rest, """\d{1,2}[./-]\d{1,2}(?:[./-]\d{4})?""")?.let { lexeme ->
            val dotted = lexeme.contains('.') && !lexeme.contains('/') && !lexeme.contains('-')
            if (!(dotted && followedByUnit(text, index + lexeme.length))) return tok(lexeme, index)
        }
        matchRegex(rest, """\d{1,2}(?::\d{2})?\s*(?:a\.?m\.?|p\.?m\.?|[ap])(?![A-Za-z0-9])""")?.let { return tok(it, index) }
        matchRegex(rest, """\d{1,2}:\d{2}""")?.let { return tok(it, index) }
        matchRegex(rest, """\d+(?:\.\d+)?[hm]\b""")?.let { return tok(it, index) }
        matchRegex(rest, """\d+(?:\.\d+)?(?:st|nd|rd|th)?""")?.let { return tok(it, index) }
        matchRegex(rest, """[A-Za-z]+(?:'[A-Za-z]+)?""")?.let { return tok(it, index) }
        return null
    }

    private fun followedByUnit(text: String, index: Int): Boolean {
        var cursor = index
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        val word = matchRegex(text.substring(cursor), """[A-Za-z]+""") ?: return false
        return unitName(word.lowercase(Locale.ROOT)) != null
    }

    private fun matchRegex(text: String, pattern: String): String? {
        val match = Regex("^$pattern", RegexOption.IGNORE_CASE).find(text) ?: return null
        return match.value
    }

    private fun tok(lexeme: String, index: Int) = Tok(lexeme, index, index + lexeme.length)

    private fun leftover(text: String, start: Int, end: Int): String {
        val left = text.substring(0, start).trim()
        val right = text.substring(end).trim()
        return listOf(left, right).filter { it.isNotEmpty() }.joinToString(" ")
            .replace(Regex("\\s+"), " ")
    }

    private fun wordAt(tokens: List<Tok>, index: Int): String? = tokens.getOrNull(index)?.norm

    private fun looksNumeric(token: Tok): Boolean =
        Regex("""^\d{1,2}[./-]\d{1,2}(?:[./-]\d{4})?$""").matches(token.compact)

    private fun numberValue(token: Tok): Double? {
        val match = Regex("""^(\d+(?:\.\d+)?)(?:st|nd|rd|th)?$""").matchEntire(token.compact) ?: return null
        if (token.compact.any { it.isLetter() }) return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun intNumber(token: Tok): Int? {
        val value = numberValue(token) ?: return null
        val whole = value.roundToLong()
        if (kotlin.math.abs(value - whole) > 0.000_001 || whole !in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) {
            return null
        }
        return whole.toInt()
    }

    private fun dayNumber(token: Tok): Int? {
        val ordinal = ordinalNumber(token)
        if (ordinal != null) return ordinal
        val value = intNumber(token) ?: return null
        return value.takeIf { it in 1..31 }
    }

    private fun ordinalNumber(token: Tok): Int? {
        namedNth(token.norm)?.let { nth ->
            return when (nth) {
                MonthNth.FIRST -> 1
                MonthNth.SECOND -> 2
                MonthNth.THIRD -> 3
                MonthNth.FOURTH -> 4
                MonthNth.LAST -> null
            }
        }
        val match = Regex("""^(\d+)(?:st|nd|rd|th)$""").matchEntire(token.compact) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 }
    }

    private fun yearNumber(token: Tok): Int? {
        if (!Regex("""^\d{4}$""").matches(token.compact)) return null
        return token.compact.toIntOrNull()
    }

    private fun nthWord(token: Tok?): MonthNth? {
        if (token == null) return null
        namedNth(token.norm)?.let { return it }
        return when (ordinalNumber(token)) {
            1 -> MonthNth.FIRST
            2 -> MonthNth.SECOND
            3 -> MonthNth.THIRD
            4 -> MonthNth.FOURTH
            else -> null
        }
    }

    private fun namedNth(word: String): MonthNth? = when (word) {
        "first" -> MonthNth.FIRST
        "second" -> MonthNth.SECOND
        "third" -> MonthNth.THIRD
        "fourth" -> MonthNth.FOURTH
        "last" -> MonthNth.LAST
        else -> null
    }

    private fun unitOf(word: String): RepeatUnit? = when (unitName(word)) {
        "minute" -> RepeatUnit.MINUTE
        "hour" -> RepeatUnit.HOUR
        "day" -> RepeatUnit.DAY
        "week" -> RepeatUnit.WEEK
        "month" -> RepeatUnit.MONTH
        "year" -> RepeatUnit.YEAR
        else -> null
    }

    private fun unitName(word: String): String? = when (word) {
        "m", "min", "mins", "minute", "minutes" -> "minute"
        "h", "hr", "hrs", "hour", "hours" -> "hour"
        "day", "days" -> "day"
        "week", "weeks" -> "week"
        "month", "months" -> "month"
        "year", "years" -> "year"
        else -> null
    }

    private fun weekdayWord(word: String): WeekDay? = WEEKDAYS[word]

    private fun singularWeekday(word: String): WeekDay? {
        if (isPluralWeekday(word)) return null
        return WEEKDAYS[word]
    }

    private fun isPluralWeekday(word: String): Boolean = word in PLURALS

    private fun monthNumber(token: Tok): Int? = MONTHS[token.norm]

    private fun monthTarget(word: String): MonthTarget? {
        weekdayWord(word)?.let { return MonthTarget.fromWeekDay(it) }
        return when (word) {
            "day" -> MonthTarget.DAY
            "weekday", "weekdays" -> MonthTarget.WEEKDAY
            else -> null
        }
    }

    private fun validDate(month: Int, day: Int, year: Int?): Boolean {
        if (month !in 1..12 || day !in 1..31) return false
        if (year == null) {
            val longest = if (month == 2) 29 else YearMonth.of(2024, month).lengthOfMonth()
            return day <= longest
        }
        return safeDate(year, month, day)?.dayOfMonth == day
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
        if (month !in 1..12) return null
        val yearMonth = YearMonth.of(year, month)
        if (day !in 1..yearMonth.lengthOfMonth()) return null
        return yearMonth.atDay(day)
    }

    private val WEEKDAYS: Map<String, WeekDay> = mapOf(
        "mon" to WeekDay.MON,
        "monday" to WeekDay.MON,
        "mondays" to WeekDay.MON,
        "tue" to WeekDay.TUE,
        "tues" to WeekDay.TUE,
        "tuesday" to WeekDay.TUE,
        "tuesdays" to WeekDay.TUE,
        "wed" to WeekDay.WED,
        "weds" to WeekDay.WED,
        "wednesday" to WeekDay.WED,
        "wednesdays" to WeekDay.WED,
        "thu" to WeekDay.THU,
        "thur" to WeekDay.THU,
        "thurs" to WeekDay.THU,
        "thursday" to WeekDay.THU,
        "thursdays" to WeekDay.THU,
        "fri" to WeekDay.FRI,
        "friday" to WeekDay.FRI,
        "fridays" to WeekDay.FRI,
        "sat" to WeekDay.SAT,
        "saturday" to WeekDay.SAT,
        "saturdays" to WeekDay.SAT,
        "sun" to WeekDay.SUN,
        "sunday" to WeekDay.SUN,
        "sundays" to WeekDay.SUN,
    )

    private val PLURALS = setOf(
        "mondays",
        "tuesdays",
        "wednesdays",
        "thursdays",
        "fridays",
        "saturdays",
        "sundays",
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1,
        "january" to 1,
        "feb" to 2,
        "february" to 2,
        "mar" to 3,
        "march" to 3,
        "apr" to 4,
        "april" to 4,
        "may" to 5,
        "jun" to 6,
        "june" to 6,
        "jul" to 7,
        "july" to 7,
        "aug" to 8,
        "august" to 8,
        "sep" to 9,
        "sept" to 9,
        "september" to 9,
        "oct" to 10,
        "october" to 10,
        "nov" to 11,
        "november" to 11,
        "dec" to 12,
        "december" to 12,
    )
}
