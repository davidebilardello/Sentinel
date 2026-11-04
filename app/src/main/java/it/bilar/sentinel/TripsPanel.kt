package it.bilar.sentinel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TripMuted = Color(0xFF89939D)
private val TripPanel = Color(0xFF151A1F)
private val TripFont = FontFamily(Font(R.font.space_grotesk))
private val MonthFormat get() = DateTimeFormatter.ofPattern("MMMM yyyy", AppLanguage.locale)
private val DayFormat get() = DateTimeFormatter.ofPattern("EEEE d MMMM", AppLanguage.locale)
private val TimeFormat get() = DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale)
private fun tripKm(meters: Double) = String.format(AppLanguage.locale, "%.2f", meters / 1000)
private fun tripCount(count: Int) = AppLanguage.quantity(R.plurals.trip_count,count)
private fun tripDuration(millis: Long): String {
    val minutes = millis / 60000
    if (minutes == 0L) return "${millis / 1000} s"
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

@Composable
fun TripsPanel(model: TripsViewModel, demo: Boolean, onPermission: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val savedHistory by model.trips.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val automatic by model.automatic.collectAsStateWithLifecycle()
    val permission by model.permission.collectAsStateWithLifecycle()
    val savedRoute by model.route.collectAsStateWithLifecycle()
    val selectedId by model.selectedId.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val currentMonth = YearMonth.from(today)
    val demoHistory = remember(today, zone) { DemoTripModena.history(today, zone) }
    var vehicleFilter by rememberSaveable { mutableStateOf("*") }
    val vehicles = remember(savedHistory) { savedHistory.mapNotNull { it.scooter }.distinctBy { it.id }.sortedBy { it.name } }
    LaunchedEffect(vehicles,vehicleFilter) { if(vehicleFilter !in setOf("*","") && vehicles.none { it.id == vehicleFilter }) vehicleFilter = "*" }
    val history = if (demo) demoHistory else savedHistory.filter { vehicleFilter == "*" || (it.scooter?.id ?: "") == vehicleFilter }
    var monthText by rememberSaveable(demo) { mutableStateOf(currentMonth.toString()) }
    val month = YearMonth.parse(monthText)
    var overview by rememberSaveable { mutableStateOf(true) }
    var openedTripId by rememberSaveable(demo) { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<TripSummary?>(null) }
    val stats = remember(history, month, zone) { tripStatistics(history, month, zone) }
    val days = remember(stats.trips, zone) { tripsByDay(stats.trips, zone) }
    val selected = history.firstOrNull { it.id == openedTripId }
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = .14f), selectedLabelColor = accent, labelColor = TripMuted)
    LaunchedEffect(demo, selected?.id, selected?.pointCount) { if (!demo) selected?.let { model.select(it.id) } }

    Text(appText(R.string.your_roads), fontFamily = TripFont, fontSize = 29.sp)
    Text(if (demo) appText(R.string.demo_mode_simulated_routes_in_modena) else appText(R.string.your_movement_day_after_day),
        color = if (demo) accent else TripMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    if(!demo) {
        var filterOpen by remember { mutableStateOf(false) }
        val filters = listOf("*" to appText(R.string.all_scooters),"" to appText(R.string.unassigned_trips))+vehicles.map { it.id to it.name }
        Box {
            OutlinedButton(onClick = { filterOpen = true }) { Text((filters.firstOrNull { it.first == vehicleFilter } ?: filters.first()).second + " ▾") }
            DropdownMenu(expanded = filterOpen,onDismissRequest = { filterOpen = false }) {
                filters.forEach { (id,label) -> DropdownMenuItem(text = { Text(label) },onClick = { vehicleFilter = id; openedTripId = null; filterOpen = false }) }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { monthText = month.minusMonths(1).toString() }, modifier = Modifier.size(48.dp).semantics { contentDescription = appText(R.string.previous_month) }, contentPadding = PaddingValues(0.dp)) { Text("‹", fontSize = 28.sp) }
        Text(month.format(MonthFormat).replaceFirstChar { it.titlecase(AppLanguage.locale) }, fontFamily = TripFont, fontSize = 16.sp,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < currentMonth, modifier = Modifier.size(48.dp).semantics { contentDescription = appText(R.string.next_month) }, contentPadding = PaddingValues(0.dp)) { Text("›", fontSize = 28.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(overview, { overview = true }, label = { Text(appText(R.string.statistics)) }, colors = chipColors)
        FilterChip(!overview, { overview = false }, label = { Text(appText(R.string.message_63,stats.trips.size)) }, colors = chipColors)
        if (month != currentMonth) TextButton(onClick = { monthText = currentMonth.toString() }) { Text(appText(R.string.this_month), fontSize = 10.sp) }
    }
    Spacer(Modifier.height(12.dp))

    if (overview) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = .12f), TripPanel))).padding(21.dp)) {
            Text(appText(R.string.distance_this_month), color = TripMuted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(tripKm(stats.meters), fontFamily = TripFont, fontSize = 48.sp, color = accent)
                Text(" km", color = TripMuted, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(appText(R.string.message_64,tripCount(stats.trips.size),stats.activeDays,if (stats.activeDays == 1) appText(R.string.day) else appText(R.string.days)), color = TripMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatisticCard(appText(R.string.riding_time), tripDuration(stats.durationMillis), Modifier.weight(1f))
            StatisticCard(appText(R.string.average_distance), "${tripKm(stats.averageMeters)} km", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatisticCard(appText(R.string.average_speed), stats.averageKmh?.let { String.format(AppLanguage.locale, "%.1f km/h", it) } ?: "—", Modifier.weight(1f))
            StatisticCard(appText(R.string.longest_trip), "${tripKm(stats.longestMeters)} km", Modifier.weight(1f))
        }
        Text(appText(R.string.average_over_trip_time_including_short_stops_distances_estimated_by_gp), fontSize = 10.sp, color = TripMuted, modifier = Modifier.padding(top = 9.dp))
        Spacer(Modifier.height(20.dp))
        MonthlyChart(history, currentMonth, month, zone, onMonth = { monthText = it.toString() })
        TextButton(onClick = { overview = false }, modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.explore_this_month_s_trips)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TripPanel).padding(17.dp)) {
            Text(appText(R.string.your_history), fontSize = 9.sp, letterSpacing = 1.5.sp, color = TripMuted)
            Text("${tripKm(history.sumOf { it.meters })} km · ${tripCount(history.size)} ${if (history.size == 1) appText(R.string.total_2) else appText(R.string.total_3)}", fontFamily = TripFont, fontSize = 18.sp, modifier = Modifier.padding(top = 5.dp))
        }
    } else if (days.isEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TripPanel).padding(23.dp)) {
            Text(appText(R.string.no_trips_this_month), fontFamily = TripFont, fontSize = 19.sp)
            Text(appText(R.string.ride_with_your_scooter_connected_your_route_is_recorded_automatically), color = TripMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    } else {
        Text(appText(R.string.routes_by_day), fontFamily = TripFont, fontSize = 21.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (date, trips) ->
                item(key = "day-$date") {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(when (date) { today -> appText(R.string.today); today.minusDays(1) -> appText(R.string.yesterday); else -> date.format(DayFormat).replaceFirstChar { it.titlecase(AppLanguage.locale) } }, fontSize = 15.sp, fontFamily = TripFont)
                        Text("${tripCount(trips.size)} · ${tripKm(trips.sumOf { it.meters })} km", color = TripMuted, fontSize = 11.sp)
                    }
                }
                items(trips, key = { it.id }) { trip ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TripPanel)
                        .border(1.dp, Color(0xFF283038), RoundedCornerShape(16.dp))
                        .clickable { openedTripId = trip.id }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (demo) appText(R.string.modena_historic_centre) else appText(R.string.message_70,Instant.ofEpochMilli(trip.startedAt).atZone(zone).format(TimeFormat)), fontSize = 13.sp)
                            Text("${Instant.ofEpochMilli(trip.startedAt).atZone(zone).format(TimeFormat)} · ${if (trip.endedAt == null) appText(R.string.recording_trip) else tripDuration(trip.durationMillis)}", color = TripMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            if (trip.interrupted) Text(appText(R.string.recovered_trip), color = Color(0xFFFFC970), fontSize = 10.sp)
                            if(!demo) Text(trip.scooter?.name ?: appText(R.string.unassigned_scooter),color = TripMuted,fontSize = 10.sp)
                        }
                        Text("${tripKm(trip.meters)} km  ›", color = accent, fontFamily = TripFont, fontSize = 16.sp)
                    }
                }
            }
        }
        Text(appText(R.string.tap_a_trip_to_open_the_map_and_details), color = TripMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
    }

    if (!demo) {
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TripPanel).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appText(R.string.automatic_recording), fontSize = 14.sp)
                    Text(appText(R.string.while_connected_to_your_scooter), fontSize = 10.sp, color = TripMuted)
                }
                Switch(checked = automatic, onCheckedChange = { model.setAutomatic(it); if (it && !permission) onPermission() })
            }
            Text(AppLanguage.translate(status.message), color = if (status.recording) accent else TripMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            status.accuracyMeters?.let { Text(appText(R.string.message_58,it.toInt()), color = TripMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
            if (!permission && automatic) TextButton(onClick = onPermission) { Text(appText(R.string.allow_gps)) }
        }
        if (LocalDebugVisible.current) Text(appText(R.string.starts_after_about_15_m_and_8_s_above_5_km_h_ends_after_2_minutes_stop), fontSize = 10.sp, color = TripMuted, modifier = Modifier.padding(top = 10.dp))
    }
    error?.let { if (!demo) Text(it, color = Color(0xFFFF9E83), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
    Text(if (demo) appText(R.string.simulated_data_to_explore_the_app_real_trips_remain_separate) else appText(R.string.routes_stay_on_your_phone_internet_is_needed_for_the_map), color = TripMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp))

    if (selected != null) Dialog(onDismissRequest = { openedTripId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp), color = TripPanel) {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text(if (demo) appText(R.string.modena_historic_centre) else selected.localDate(zone).format(DayFormat), fontFamily = TripFont, fontSize = 21.sp)
                Text(if (demo) appText(R.string.demo_route_simulated_track) else if (selected.endedAt == null) appText(R.string.recording_trip) else appText(R.string.your_route), color = accent, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                if(!demo) Text(selected.scooter?.name ?: appText(R.string.unassigned_scooter),color = TripMuted,fontSize = 12.sp)
                Text("${tripKm(selected.meters)} km · ${tripDuration(selected.durationMillis)}", color = TripMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                val route = if (demo) DemoTripModena.pointsFor(selected) else if (selectedId == selected.id) savedRoute else emptyList()
                val speedProfile = remember(route) { TripSpeed.profile(route) }
                if (route.size >= 2) TripMap(selected.id, route, speedProfile?.pointSpeeds) else Text(appText(R.string.loading_route), color = TripMuted, fontSize = 12.sp)
                if (route.size >= 2) speedProfile?.let { TripSpeedSection(route, it) }
                if (selected.interrupted) Text(appText(R.string.trip_recovered_after_the_app_was_interrupted), color = TripMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (!demo && selected.endedAt != null) TextButton(onClick = { deleting = selected }) { Text(appText(R.string.delete), color = Color(0xFFFF9E83)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { openedTripId = null }) { Text(appText(R.string.close)) }
                }
            }
        }
    }
    deleting?.let { trip -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(appText(R.string.delete_this_trip)) },
        text = { Text(appText(R.string.message_75,tripKm(trip.meters))) },
        confirmButton = { TextButton(onClick = { model.delete(trip.id); deleting = null; openedTripId = null }) { Text(appText(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(appText(R.string.cancel)) } }) }
}

@Composable
private fun StatisticCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(TripPanel).padding(15.dp)) {
        Text(label, fontSize = 9.sp, color = TripMuted)
        Text(value, fontFamily = TripFont, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MonthlyChart(history: List<TripSummary>, current: YearMonth, selected: YearMonth, zone: ZoneId, onMonth: (YearMonth) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val end = if (selected < current.minusMonths(5)) selected.plusMonths(5) else current
    val months = remember(history, end, zone) { (5L downTo 0L).map { offset ->
        val month = end.minusMonths(offset)
        month to tripStatistics(history, month, zone).meters
    } }
    val max = months.maxOf { it.second }.coerceAtLeast(1.0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TripPanel).padding(18.dp)) {
        Text(appText(R.string.distance_by_month), fontFamily = TripFont, fontSize = 18.sp)
        Text(appText(R.string.tap_a_bar_to_explore_the_month), fontSize = 10.sp, color = TripMuted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            months.forEach { (month, meters) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onMonth(month) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tripKm(meters), fontSize = 9.sp, color = if (month == selected) accent else TripMuted)
                    Box(Modifier.height(88.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.width(22.dp).height((meters / max * 80).toFloat().coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (month == selected) accent else accent.copy(alpha = .28f)))
                    }
                    Text(month.format(DateTimeFormatter.ofPattern("MMM", AppLanguage.locale)), fontSize = 10.sp, color = if (month == selected) accent else TripMuted, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
}
