package it.bilar.sentinel

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.text.NumberFormat
import kotlin.math.pow

private val YouFsMuted = Color(0xFF89939D)
private val youfsNames get() = mapOf(
    "blelock_switch" to appText(R.string.vehicle_lock), "speed" to appText(R.string.speed_2), "battery_percentage" to appText(R.string.battery), "endurance_mileage" to appText(R.string.estimated_range),
    "mileage_once" to appText(R.string.session_distance), "ridetime_once" to appText(R.string.session_duration), "avgspeed_once" to appText(R.string.average_speed_2), "headlight_switch" to appText(R.string.headlight),
    "unit_set" to appText(R.string.units), "mileage_total" to appText(R.string.total_distance), "cruise_switch" to appText(R.string.cruise_control), "mode" to appText(R.string.riding_mode),
    "start_mode" to appText(R.string.starting_mode), "energy_recovery_level" to appText(R.string.energy_recovery), "temp_current" to appText(R.string.controller_temperature),
    "battery_status" to appText(R.string.battery_status), "voltage_current" to appText(R.string.voltage), "cur_current" to appText(R.string.current), "power_current" to appText(R.string.power),
    "battery_temp_current" to appText(R.string.battery_temperature), "status" to appText(R.string.status_and_faults), "fault_detection" to appText(R.string.self_diagnosis), "bright_value" to appText(R.string.brightness),
    "temp_value" to appText(R.string.colour_temperature), "work_mode" to appText(R.string.light_mode), "colour_data" to appText(R.string.colour), "scene_data" to appText(R.string.light_scene),
    "music_data" to appText(R.string.music), "mic_music_data" to appText(R.string.microphone), "battery_cycle_times" to appText(R.string.battery_cycles), "battery_lock" to appText(R.string.optional_battery_lock),
    "phone_discon_alarm" to appText(R.string.phone_disconnection_alarm), "speed_limit_1" to appText(R.string.first_gear_speed_limit), "speed_limit_2" to appText(R.string.second_gear_speed_limit),
    "speed_limit_3" to appText(R.string.third_gear_speed_limit), "autopowerofftime" to appText(R.string.automatic_power_off))
private val youfsOptionNames get() = mapOf("walk" to appText(R.string.walk), "eco" to "ECO", "normal" to appText(R.string.normal), "sport" to "SPORT", "zero_start" to appText(R.string.without_pushing),
    "not_zero_start" to appText(R.string.push_to_start), "low" to appText(R.string.low), "middle" to appText(R.string.medium), "high" to appText(R.string.high), "km" to "km", "mile" to appText(R.string.miles),
    "white" to appText(R.string.white), "colour" to appText(R.string.colour), "scene" to appText(R.string.scene), "music" to appText(R.string.music))
private fun YouFsDp.experimental() = id in 29..38 && id != 36 || code == "energy_recovery_level"
private val YouFsFont = FontFamily(Font(R.font.space_grotesk))
internal fun youfsOptionName(option: String) = youfsOptionNames[option] ?: option
internal fun YouFsDp.selectedOption(state: ScooterUiState): Int? = state.youfsValues[id]?.takeIf { it.type == 4 }
    ?.value?.let { it as? Number }?.toInt()?.takeIf { it in options.indices }
private fun YouFsDp.title(debug: Boolean) = (youfsNames[code] ?: if(debug) code else appText(R.string.other_data)) + if(debug) " · DP $id" else ""
private fun YouFsDp.icon() = when(code) {
    "headlight_switch" -> "light"
    "blelock_switch", "battery_lock" -> "lock"
    "cruise_switch" -> "gauge"
    "start_mode" -> "bolt"
    "unit_set" -> "orbit"
    "autopowerofftime" -> "power"
    else -> "tune"
}
private fun YouFsDp.number(raw: Double) = NumberFormat.getNumberInstance(AppLanguage.locale).apply {
    isGroupingUsed = false; maximumFractionDigits = scale
}.format(raw / 10.0.pow(scale))

@Composable
fun YouFsControlPanel(state: ScooterUiState, onQuery: () -> Unit, onSend: (Int, Any) -> Unit) {
    val debug = LocalDebugVisible.current
    Text(appText(R.string.control_your_youfs), fontFamily = YouFsFont,fontSize = 27.sp)
    Text(appText(R.string.settings_confirmed_by_vehicle),color = YouFsMuted,fontSize = 12.sp,modifier = Modifier.padding(top = 6.dp,bottom = 22.dp))
    if (debug) {
        Text(AppLanguage.translate(state.youfsStatus), color = if (state.canCommand) MaterialTheme.colorScheme.primary else Color(0xFFFFC970), fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
        OutlinedButton(onClick = onQuery, enabled = state.connected && !state.busy) { Text(appText(R.string.refresh_data)) }
    }
    val standard = state.youfsSchema.filter { it.writable && !it.experimental() && (debug || it.code != "fault_detection") }
    val order = listOf("mode","headlight_switch","cruise_switch","start_mode","unit_set","blelock_switch")
    standard.sortedBy { order.indexOf(it.code).takeIf { index -> index >= 0 } ?: order.size }.forEach { YouFsDpRow(it, state, onSend) }
    var optional by remember { mutableStateOf(false) }
    val extra = state.youfsSchema.filter { it.writable && it.experimental() }
    if (debug && extra.isNotEmpty()) {
        TextButton(onClick = { optional = !optional }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text((if (optional) "▴" else "▾") + appText(R.string.message_91,extra.size))
        }
        if (optional) {
            Text(appText(R.string.the_schema_does_not_guarantee_hardware_support_dp_37_is_separate_from), color = YouFsMuted, fontSize = 11.sp)
            extra.forEach { YouFsDpRow(it, state, onSend) }
        }
    }
    Text(appText(R.string.vehicle_data), fontSize = 21.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    state.youfsSchema.filter { !it.writable && (debug || it.id in state.youfsValues) }.forEach { YouFsDpRow(it, state, onSend) }
    if (debug) state.youfsValues.filterKeys { id -> state.youfsSchema.none { it.id == id } }.forEach { (id, value) ->
        Text(appText(R.string.message_92,id,value.value.toString()), color = YouFsMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun YouFsDpRow(dp: YouFsDp, state: ScooterUiState, onSend: (Int, Any) -> Unit) {
    val debug = LocalDebugVisible.current
    val received = state.youfsValues[dp.id]
    var input by remember(dp.id, state.activeScooterId) { mutableStateOf("") }
    var edited by remember(dp.id, state.activeScooterId) { mutableStateOf(false) }
    val formatted = when {
        received == null -> if (debug) appText(R.string.state_not_received_support_unverified) else appText(R.string.waiting)
        dp.type == "value" -> (received.value as? Number)?.toDouble()?.let { "${dp.number(it)} ${dp.unit}" } ?: appText(R.string.unexpected_type)
        dp.type == "bool" -> if (received.value == true) appText(R.string.enabled) else appText(R.string.disabled)
        dp.type == "enum" -> dp.selectedOption(state)?.let { youfsOptionName(dp.options[it]) } ?: appText(R.string.unknown_value)
        else -> received.value.toString()
    }
    LaunchedEffect(received, dp.id, state.activeScooterId) {
        if (!edited && received != null) input = when (dp.type) {
            "enum" -> dp.options.getOrNull((received.value as? Number)?.toInt() ?: -1).orEmpty()
            "value" -> ((received.value as? Number)?.toDouble()?.div(10.0.pow(dp.scale)))?.toString().orEmpty()
            else -> received.value.toString()
        }
    }
    val title = dp.title(debug)
    if(!dp.writable) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),verticalAlignment = Alignment.CenterVertically) {
            Text(title,modifier = Modifier.weight(1f).padding(end = 12.dp),color = YouFsMuted,fontSize = 13.sp)
            Text(formatted,fontFamily = YouFsFont,fontSize = 15.sp)
        }
        HorizontalDivider(color = Color(0xFF283038))
        return
    }
    if(dp.type == "bool") {
        SettingRow(title,formatted,dp.icon(),received?.value == true,state.canCommand) { onSend(dp.id,it) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(Color(0xFF151A1F), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(dp.icon(),MaterialTheme.colorScheme.primary,Modifier.size(23.dp))
            Text(title,fontFamily = YouFsFont,fontWeight = FontWeight.Medium,fontSize = 16.sp,modifier = Modifier.padding(start = 12.dp).weight(1f))
        }
        Text(formatted,color = YouFsMuted,fontSize = 11.sp,modifier = Modifier.padding(top = 7.dp,bottom = 10.dp))
        when(dp.type) {
            "enum" -> Column(Modifier.selectableGroup()) {
                dp.options.forEachIndexed { index,option ->
                    val selected = dp.selectedOption(state) == index
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected,enabled = state.canCommand,role = Role.RadioButton,
                            onClick = { if(!selected) onSend(dp.id,option) })
                        .semantics(mergeDescendants = true) {
                            contentDescription = youfsOptionName(option)
                            stateDescription = appText(if(selected) R.string.selected_option else R.string.unselected_option)
                        }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected,onClick = null,enabled = state.canCommand)
                        Text(youfsOptionName(option),fontSize = 14.sp,modifier = Modifier.padding(start = 12.dp),
                            color = if(!state.canCommand) YouFsMuted else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            "value", "string" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input,onValueChange = { input = it; edited = true },enabled = state.canCommand,
                        label = { Text(if(dp.type == "value") "${dp.number(dp.min)} – ${dp.number(dp.max)} ${dp.unit}" else appText(R.string.device_specific_format)) },
                        keyboardOptions = KeyboardOptions(keyboardType = if(dp.type == "value") KeyboardType.Decimal else KeyboardType.Text),
                        modifier = Modifier.weight(1f),singleLine = true)
                    Button(onClick = { onSend(dp.id,if(dp.type == "value") input.replace(',','.') else input); edited = false },
                        enabled = state.canCommand && input.isNotEmpty(),shape = RoundedCornerShape(13.dp)) { Text(appText(R.string.set)) }
                }
            }
        }
    }
}
