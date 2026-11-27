package com.evansappstudio.doapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.catalogue.CardKind
import com.evansappstudio.doapp.catalogue.KeywordMatch
import com.evansappstudio.doapp.catalogue.LocalItem
import com.evansappstudio.doapp.catalogue.Picks
import com.evansappstudio.doapp.catalogue.PlannedAdd
import com.evansappstudio.doapp.catalogue.Region
import com.evansappstudio.doapp.catalogue.RegionResolver
import com.evansappstudio.doapp.catalogue.RegionSetting
import com.evansappstudio.doapp.catalogue.SuggestionCard
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// --- Shared pieces -------------------------------------------------------------

@Composable
fun areaLabel(area: String): String = stringResource(
    when (area) {
        "home" -> R.string.sysd_area_home
        "renter" -> R.string.sysd_area_renter
        "car" -> R.string.sysd_area_car
        "kids" -> R.string.sysd_area_kids
        "pets" -> R.string.sysd_area_pets
        "health" -> R.string.sysd_area_health
        "money" -> R.string.sysd_area_money
        else -> R.string.sysd_area_digital
    },
)

@Composable
fun intervalText(item: LocalItem): String {
    val every = item.interval.every
    val base = when (item.interval.unit) {
        RepeatUnit.DAY -> pluralStringResource(R.plurals.sysd_every_day, every, every)
        RepeatUnit.WEEK -> pluralStringResource(R.plurals.sysd_every_week, every, every)
        RepeatUnit.YEAR -> pluralStringResource(R.plurals.sysd_every_year, every, every)
        else -> pluralStringResource(R.plurals.sysd_every_month, every, every)
    }
    return if (item.item.repeatFrom == RepeatFrom.COMPLETION) {
        stringResource(R.string.sysd_from_completion, base)
    } else {
        stringResource(R.string.sysd_from_due, base)
    }
}

@Composable
private fun stateName(code: String, short: Boolean): String = stringResource(
    when (code) {
        RegionResolver.NSW -> if (short) R.string.sysd_short_nsw else R.string.sysd_state_nsw
        RegionResolver.VIC -> if (short) R.string.sysd_short_vic else R.string.sysd_state_vic
        RegionResolver.QLD -> if (short) R.string.sysd_short_qld else R.string.sysd_state_qld
        RegionResolver.WA -> if (short) R.string.sysd_short_wa else R.string.sysd_state_wa
        RegionResolver.SA -> if (short) R.string.sysd_short_sa else R.string.sysd_state_sa
        RegionResolver.TAS -> if (short) R.string.sysd_short_tas else R.string.sysd_state_tas
        RegionResolver.ACT -> if (short) R.string.sysd_short_act else R.string.sysd_state_act
        RegionResolver.NT -> if (short) R.string.sysd_short_nt else R.string.sysd_state_nt
        else -> R.string.sysd_short_au
    },
)

/** "Using WA rules. Change", or "NSW or ACT? Choose". */
@Composable
fun RegionLine(setting: RegionSetting, onChange: (Region?) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val code = setting.region.code
    val text = when {
        setting.needsChoice -> stringResource(R.string.sysd_region_nsw_or_act)
        code == null -> stringResource(R.string.sysd_region_outside)
        else -> stringResource(R.string.sysd_region_line, stateName(code, short = true))
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary, modifier = Modifier.weight(1f, fill = false))
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
            Text(stringResource(if (setting.needsChoice) R.string.sysd_region_choose else R.string.sysd_region_change))
        }
    }
    if (open) {
        val choices = if (setting.needsChoice) {
            RegionResolver.NSW_OR_ACT + RegionResolver.STATES.filter { it !in RegionResolver.NSW_OR_ACT }
        } else {
            RegionResolver.STATES
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.sysd_region_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.sysd_region_body), style = MaterialTheme.typography.bodySmall)
                    choices.forEach { state ->
                        RegionChoice(stateName(state, short = false), selected = state == code) {
                            open = false
                            onChange(Region(state))
                        }
                    }
                    RegionChoice(stringResource(R.string.sysd_region_outside_option), selected = code == null) {
                        open = false
                        onChange(Region.OUTSIDE)
                    }
                    RegionChoice(stringResource(R.string.sysd_region_auto), selected = false) {
                        open = false
                        onChange(null)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RegionChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = DoLook.MinTouch)) {
        Text(
            text = (if (selected) "✓ " else "") + label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The date a `needsDate` item is due. [onPicked] gets null on cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsDateDialog(initial: LocalDate?, onPicked: (LocalDate?) -> Unit) {
    val start = (initial ?: LocalDate.now().plusDays(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = { onPicked(null) },
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                onPicked(millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = { onPicked(null) }) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(
            state = state,
            title = { Text(stringResource(R.string.sysd_pick_date_title), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun ItemText(item: LocalItem, showWhy: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, color = DoLook.Ink)
        if (showWhy && item.why.isNotEmpty()) {
            Text(item.why, style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
        }
        Text(intervalText(item), style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
        if (item.item.stateVaries) {
            Tag(stringResource(R.string.sysd_check_state), DoLook.Secondary, DoLook.Ground)
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sysd_step, step), style = MaterialTheme.typography.labelMedium, color = DoLook.Secondary)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = DoLook.Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary)
    }
}

// --- First run, step 3 ---------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AreasScreen(initial: Set<String>, onNext: (Set<String>) -> Unit, onSkip: () -> Unit) {
    DoLookTheme {
        var picked by rememberSaveable { mutableStateOf(ReminderCategory.ALL.filter { it in initial }) }
        Scaffold(
            containerColor = DoLook.Ground,
            bottomBar = {
                Surface(color = DoLook.Ground) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                            Text(stringResource(R.string.sysd_skip))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { onNext(picked.toSet()) },
                            enabled = picked.isNotEmpty(),
                            modifier = Modifier.heightIn(min = DoLook.MinTouch),
                        ) { Text(stringResource(R.string.sysd_next)) }
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StepHeader(3, stringResource(R.string.sysd_areas_title), stringResource(R.string.sysd_areas_body))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ReminderCategory.ALL.forEach { area ->
                        val on = area in picked
                        FilterChip(
                            selected = on,
                            onClick = { picked = if (on) picked - area else ReminderCategory.ALL.filter { it in picked || it == area } },
                            label = { Text(areaLabel(area), style = MaterialTheme.typography.bodyLarge) },
                            modifier = Modifier.heightIn(min = DoLook.MinTouch),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DoLook.Ink,
                                selectedLabelColor = DoLook.Ground,
                                selectedLeadingIconColor = DoLook.Ground,
                            ),
                        )
                    }
                }
                Text(stringResource(R.string.sysd_privacy), style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
            }
        }
    }
}

// --- First run, step 4 ---------------------------------------------------------

@Composable
fun PicksScreen(
    viewModel: SysdViewModel,
    onAdd: (plans: List<PlannedAdd>, overCap: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    var start by remember { mutableStateOf<PicksStart?>(null) }
    LaunchedEffect(Unit) { start = viewModel.picksStart() }
    val region by viewModel.region.collectAsStateValue()
    val loaded = start
    DoLookTheme {
        if (loaded == null) {
            Surface(color = DoLook.Ground, modifier = Modifier.fillMaxSize()) {}
        } else {
            PicksContent(loaded, region, viewModel, onAdd, onSkip)
        }
    }
}

@Composable
private fun PicksContent(
    start: PicksStart,
    region: RegionSetting,
    viewModel: SysdViewModel,
    onAdd: (plans: List<PlannedAdd>, overCap: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    val ticked = remember { mutableStateMapOf<String, Boolean>().apply { start.preTicked.forEach { put(it, true) } } }
    val dates = remember { mutableStateMapOf<String, LocalDate>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var askDateFor by remember { mutableStateOf<LocalItem?>(null) }
    // A region change after loading re-groups the items; ticks stay by id.
    val items by viewModel.items.collectAsStateValue()
    val areas by viewModel.areas.collectAsStateValue()
    val added by viewModel.added.collectAsStateValue()
    val groups = remember(items, areas, added) { Picks.grouped(items, areas, added, ReminderCategory.ALL) }
    val chosen = groups.flatMap { it.second }.filter { ticked[it.id] == true }
    val count = chosen.size
    val cap = FreeTier.ACTIVE_REMINDER_CAP
    val freeLeft = Picks.freeLeft(start.activeCount, count, start.unlocked, cap)
    val overCap = Picks.overCap(start.activeCount, count, start.unlocked, cap)

    Scaffold(
        containerColor = DoLook.Ground,
        bottomBar = {
            Surface(color = DoLook.Surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val counter = when {
                        freeLeft == null -> pluralStringResource(R.plurals.sysd_selected, count, count)
                        freeLeft >= 0 -> pluralStringResource(R.plurals.sysd_free_left, freeLeft, freeLeft)
                        else -> pluralStringResource(R.plurals.sysd_over_free, -freeLeft, -freeLeft)
                    }
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (overCap) DoLook.Alarm else DoLook.Secondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                            Text(stringResource(R.string.sysd_skip))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { onAdd(viewModel.plan(chosen, dates.toMap()), overCap) },
                            enabled = count > 0,
                            modifier = Modifier.heightIn(min = DoLook.MinTouch),
                        ) {
                            Text(
                                if (count == 0) {
                                    stringResource(R.string.sysd_nothing_ticked)
                                } else {
                                    pluralStringResource(R.plurals.sysd_add_n, count, count)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                    StepHeader(4, stringResource(R.string.sysd_picks_title), stringResource(R.string.sysd_picks_body))
                    RegionLine(region, onChange = viewModel::setRegion)
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(stringResource(R.string.sysd_no_items), modifier = Modifier.padding(16.dp), color = DoLook.Secondary)
                }
            }
            groups.forEach { (area, list) ->
                item(key = "area-$area") {
                    Text(
                        areaLabel(area),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                val open = expanded[area] == true
                val shown = if (open) list else list.take(Picks.SHOWN_PER_AREA)
                items(shown, key = { it.id }) { item ->
                    val on = ticked[item.id] == true
                    PickRow(
                        item = item,
                        ticked = on,
                        date = dates[item.id],
                        onToggle = {
                            if (on) {
                                ticked[item.id] = false
                            } else if (item.item.needsDate && dates[item.id] == null) {
                                askDateFor = item
                            } else {
                                ticked[item.id] = true
                            }
                        },
                        onChangeDate = { askDateFor = item },
                    )
                }
                if (list.size > Picks.SHOWN_PER_AREA) {
                    item(key = "more-$area") {
                        TextButton(
                            onClick = { expanded[area] = !open },
                            modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = DoLook.MinTouch),
                        ) { Text(stringResource(if (open) R.string.sysd_show_less else R.string.sysd_show_more)) }
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
    askDateFor?.let { item ->
        NeedsDateDialog(dates[item.id]) { picked ->
            askDateFor = null
            if (picked != null) {
                dates[item.id] = picked
                ticked[item.id] = true
            }
        }
    }
}

@Composable
private fun PickRow(item: LocalItem, ticked: Boolean, date: LocalDate?, onToggle: () -> Unit, onChangeDate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = DoLook.MinTouch)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            ItemText(item, showWhy = false)
            if (item.item.needsDate) {
                if (date != null && ticked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sysd_due_on, dateFormat.format(date)), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onChangeDate, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                            Text(stringResource(R.string.sysd_change_date))
                        }
                    }
                } else {
                    Text(stringResource(R.string.sysd_needs_date), style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
                }
            }
        }
    }
}

// --- Catalogue tab -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(
    viewModel: SysdViewModel,
    onAdd: (LocalItem, LocalDate?) -> Unit,
    onRemove: (LocalItem) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val items by viewModel.items.collectAsStateValue()
    val added by viewModel.added.collectAsStateValue()
    val region by viewModel.region.collectAsStateValue()
    var query by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf<String?>(null) }
    var askDateFor by remember { mutableStateOf<LocalItem?>(null) }
    val needle = KeywordMatch.normalise(query)
    val shown = items
        .filter { area == null || it.item.category == area }
        .filter { local ->
            needle.isEmpty() ||
                KeywordMatch.normalise(local.title).contains(needle) ||
                local.item.keywords.any { KeywordMatch.normalise(it).contains(needle) }
        }
        .let { list -> ReminderCategory.ALL.flatMap { a -> Picks.ordered(list.filter { it.item.category == a }) } }
    DoLookTheme {
        Scaffold(containerColor = DoLook.Ground, bottomBar = bottomBar) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.sysd_catalogue_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.sysd_name), style = MaterialTheme.typography.bodyMedium, color = DoLook.Secondary)
                        RegionLine(region, onChange = viewModel::setRegion)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.sysd_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = area == null,
                            onClick = { area = null },
                            label = { Text(stringResource(R.string.sysd_all_areas)) },
                            modifier = Modifier.heightIn(min = DoLook.MinTouch),
                        )
                        ReminderCategory.ALL.forEach { a ->
                            FilterChip(
                                selected = area == a,
                                onClick = { area = if (area == a) null else a },
                                label = { Text(areaLabel(a)) },
                                modifier = Modifier.heightIn(min = DoLook.MinTouch),
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item { Text(stringResource(R.string.sysd_no_matches), modifier = Modifier.padding(16.dp), color = DoLook.Secondary) }
                }
                items(shown, key = { it.id }) { item ->
                    val isAdded = item.id in added
                    Surface(
                        color = DoLook.Surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DoLook.Line),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ItemText(item, showWhy = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            if (isAdded) {
                                OutlinedButton(
                                    onClick = { onRemove(item) },
                                    modifier = Modifier.heightIn(min = DoLook.MinTouch),
                                ) { Text("✓ " + stringResource(R.string.sysd_added_item)) }
                            } else {
                                Button(
                                    onClick = { if (item.item.needsDate) askDateFor = item else onAdd(item, null) },
                                    modifier = Modifier.heightIn(min = DoLook.MinTouch),
                                ) { Text(stringResource(R.string.sysd_add_item)) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.padding(16.dp)) }
            }
        }
    }
    askDateFor?.let { item ->
        NeedsDateDialog(null) { picked ->
            askDateFor = null
            if (picked != null) onAdd(item, picked)
        }
    }
}

// --- Today card ----------------------------------------------------------------

/** The one Stuff You Should Do card on Today, below Overdue. */
@Composable
fun TodayCard(
    card: SuggestionCard,
    doneTitle: String?,
    onAdd: (LocalItem, LocalDate?) -> Unit,
    onOpenCatalogue: () -> Unit,
    onNotNow: () -> Unit,
    onStop: () -> Unit,
) {
    var askDateFor by remember { mutableStateOf<LocalItem?>(null) }
    DoLookTheme {
        Surface(
            color = DoLook.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, DoLook.Line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogueChip()
                val (title, body) = when (card.kind) {
                    CardKind.HEAD_START -> stringResource(R.string.sysd_card_head_start) to stringResource(R.string.sysd_card_head_start_body)
                    CardKind.SEASONAL -> stringResource(R.string.sysd_card_seasonal) to stringResource(R.string.sysd_card_seasonal_body)
                    CardKind.RELATED -> stringResource(R.string.sysd_card_related) to
                        stringResource(R.string.sysd_card_related_body, doneTitle.orEmpty())
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = DoLook.Secondary)
                card.items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ItemText(item, showWhy = false, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { if (item.item.needsDate) askDateFor = item else onAdd(item, null) },
                            modifier = Modifier.heightIn(min = DoLook.MinTouch),
                        ) { Text(stringResource(R.string.sysd_add_item)) }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onOpenCatalogue, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                        Text(stringResource(R.string.sysd_card_open))
                    }
                    TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = DoLook.MinTouch)) {
                        Text(stringResource(R.string.sysd_card_not_now))
                    }
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.heightIn(min = DoLook.MinTouch),
                        colors = ButtonDefaults.textButtonColors(contentColor = DoLook.Secondary),
                    ) { Text(stringResource(R.string.sysd_card_stop)) }
                }
            }
        }
    }
    askDateFor?.let { item ->
        NeedsDateDialog(null) { picked ->
            askDateFor = null
            if (picked != null) onAdd(item, picked)
        }
    }
}

/** Rule 5: "Related: ..." under the Add field. Tapping adds per section D. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelatedLine(items: List<LocalItem>, onAdd: (LocalItem, LocalDate?) -> Unit) {
    if (items.isEmpty()) return
    var askDateFor by remember { mutableStateOf<LocalItem?>(null) }
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.sysd_related_line),
            style = MaterialTheme.typography.bodySmall,
            color = DoLook.CatalogueInk,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        items.forEach { item ->
            TextButton(
                onClick = { if (item.item.needsDate) askDateFor = item else onAdd(item, null) },
                modifier = Modifier.heightIn(min = DoLook.MinTouch),
            ) { Text(stringResource(R.string.sysd_add_named, item.title), style = MaterialTheme.typography.bodySmall) }
        }
    }
    askDateFor?.let { item ->
        NeedsDateDialog(null) { picked ->
            askDateFor = null
            if (picked != null) onAdd(item, picked)
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateValue() = collectAsStateWithLifecycle()
