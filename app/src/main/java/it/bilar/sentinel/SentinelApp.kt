package it.bilar.sentinel

import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription

private val Ink = Color(0xFF0B0E11)
private val Panel = Color(0xFF151A1F)
private val Muted = Color(0xFF89939D)
private val White = Color(0xFFF2F4F0)
private val Line = Color(0xFF283038)
private val DisplayFont = FontFamily(Font(R.font.space_grotesk))
private fun number(value: Float, decimals: Int = 1) = String.format(AppLanguage.locale, "%.${decimals}f", value)

@Composable
fun SentinelApp(
    state: ScooterUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemo: () -> Unit,
    onSend: (Setting, Int) -> Unit,
    onDevice: (ScooterDevice) -> Unit,
    onStopScan: () -> Unit,
    onClearLogs: () -> Unit,
    onScooter: (String) -> Unit,
    onAddJeep: () -> Unit,
    onRemoveScooter: () -> Unit,
    onDebug: (Boolean) -> Unit,
    onExitDemo: () -> Unit,
    onAddYouFsProfile: (ByteArray) -> Boolean,
    onYouFsQuery: () -> Unit,
    onYouFsSend: (Int, Any) -> Unit,
    tripsModel: TripsViewModel,
    onTripPermission: () -> Unit,
    onErrorShown: (String) -> Unit = {}
) {
    val context = LocalContext.current
    LaunchedEffect(state.userError) {
        state.userError?.let { error ->
            Toast.makeText(context,AppLanguage.translate(error),Toast.LENGTH_LONG).show()
            onErrorShown(error)
        }
    }
    val accent by animateColorAsState(if (state.family == ScooterFamily.YOUFS_TUYA) Color(0xFFFF4D5A) else Color(0xFFCAFF52), tween(360), label = "scooterAccent")
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Ink,
        primaryContainer = accent.copy(alpha = .18f), onPrimaryContainer = accent,
        secondary = accent, secondaryContainer = accent.copy(alpha = .14f), onSecondaryContainer = accent, tertiary = accent,
        background = Ink, surface = Panel, onSurface = White, outline = Line),
        typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides White, LocalDebugVisible provides state.debugEnabled) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var accountOpen by rememberSaveable { mutableStateOf(false) }
        var transferMode by rememberSaveable { mutableStateOf<Int?>(null) }
        var googleOpen by rememberSaveable { mutableStateOf(false) }
        var backupOpen by rememberSaveable { mutableStateOf(false) }
        val telemetry = state.telemetry
        val scroll = rememberScrollState()
        LaunchedEffect(tab) { scroll.scrollTo(0) }
        LaunchedEffect(state.debugEnabled) { if (!state.debugEnabled && tab == 3) tab = 0 }
        BackHandler(enabled = tab == 3) { tab = 0 }
        Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_sentinel),contentDescription = appText(R.string.sentinel_logo),
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                    Text("SENTINEL", Modifier.padding(start = 11.dp).weight(1f), fontFamily = DisplayFont,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 3.sp)
                    if (state.demo) StatusPill("DEMO", Color(0xFFFFC970))
                    IconButton(onClick = { settingsOpen = true }, modifier = Modifier.semantics { contentDescription = appText(R.string.settings) }) {
                        Text("⚙", color = Muted, fontSize = 26.sp)
                    }
                }
                Spacer(Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Overline(appText(R.string.your_garage))
                        Text(if (state.demo) "Jeep Sentinel" else state.activeScooter.name, fontFamily = DisplayFont, fontSize = if (state.family == ScooterFamily.SENTINEL) 35.sp else 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if(!state.demo) Button(onClick = if(state.connected) onDisconnect else onConnect,
                        enabled = !state.connecting && !state.scanning,
                        modifier = Modifier.padding(start = 12.dp).widthIn(max = 154.dp).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),contentPadding = PaddingValues(horizontal = 12.dp,vertical = 10.dp)) {
                        if(state.connecting || state.scanning) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth = 2.dp)
                        else Glyph(if(state.connected) "power" else "bluetooth",Ink,Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if(state.connecting) appText(R.string.connecting) else if(state.scanning) appText(R.string.searching)
                            else if(state.connected) appText(R.string.disconnect_2) else appText(R.string.connect),fontSize = 11.sp)
                    }
                }
                AnimatedContent(targetState = tab, label = "page") { page ->
                    Column {
                        if (page == 0) {
                            ScooterHero(state.headlightOn, state.connected || state.demo, state.family,
                                if(state.fresh) state.telemetry?.speedKmh ?: 0f else 0f)
                            RideDashboard(state)
                            Spacer(Modifier.height(22.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(appText(R.string.quick_controls), fontFamily = DisplayFont, fontSize = 21.sp, modifier = Modifier.weight(1f))
                                Text("LIVE", color = if (state.fresh) accent else Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ControlTile(appText(R.string.lights), if (!state.connected && !state.demo) appText(R.string.waiting) else if (state.headlightOn) appText(R.string.on) else appText(R.string.off), "light", state.headlightOn,
                                    state.canCommand, Modifier.weight(1f)) { onSend(Setting.LIGHTS, if (state.headlightOn) 0 else 1) }
                                ControlTile(appText(R.string.motor), if (telemetry == null) appText(R.string.waiting) else if (telemetry.locked) appText(R.string.locked) else appText(R.string.unlocked), "lock", telemetry?.locked == true,
                                    state.canCommand && telemetry?.speedKmh == 0f, Modifier.weight(1f)) { onSend(Setting.LOCK, if (telemetry?.locked == true) 0 else 1) }
                            }
                            Spacer(Modifier.height(18.dp))
                            QuickRideModes(state,onSend,onYouFsSend)
                            if (state.debugEnabled) OutlinedButton(onClick = { tab = 3 }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                                Glyph("pulse", Muted, Modifier.size(17.dp))
                                Text(appText(R.string.diagnostics), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                        } else if (page == 1) {
                            Spacer(Modifier.height(24.dp))
                            if (state.family == ScooterFamily.YOUFS_TUYA) YouFsControlPanel(state, onYouFsQuery, onYouFsSend) else SettingsPanel(state, onSend)
                        } else if (page == 3) {
                            Spacer(Modifier.height(24.dp))
                            TextButton(onClick = { tab = 0 }) { Text(appText(R.string.back_to_garage)) }
                            Diagnostics(state, onClearLogs)
                        } else {
                            Spacer(Modifier.height(24.dp))
                            TripsPanel(tripsModel, state.demo, onTripPermission)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (state.debugEnabled) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.busy || state.connecting) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                    else Box(Modifier.size(6.dp).background(if (state.fresh) accent else Muted, CircleShape))
                    Text(AppLanguage.translate(state.message), Modifier.padding(start = 10.dp), fontSize = 11.sp, color = Muted, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(15.dp))
            }
            Row(Modifier.fillMaxWidth().border(width = 0.5.dp, color = Line).padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(appText(R.string.garage) to "scooter", appText(R.string.controls) to "tune", appText(R.string.trips) to "route").forEachIndexed { i, (label, icon) ->
                    TextButton(onClick = { tab = i }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 5.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Glyph(icon, if (tab == i) accent else Muted, Modifier.size(19.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(label, color = if (tab == i) accent else Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (settingsOpen) AppSettingsPanel(state, onClose = { settingsOpen = false }, onSelect = onScooter,
            onAddJeep = onAddJeep, onAccount = { accountOpen = true }, onRemove = onRemoveScooter, onDebug = onDebug,
            onDemo = onDemo, onExitDemo = onExitDemo, onDiagnose = { tab = 3 },
            onExport = { transferMode = 1 }, onImport = { transferMode = 0 }, onGoogle = { googleOpen = true },onBackup = { backupOpen = true })
        if (accountOpen) YouFsLoginPanel(onClose = { accountOpen = false }, onAdd = onAddYouFsProfile)
        transferMode?.let { mode -> ScooterTransferPanel(mode == 1,state.activeScooter,onClose = { transferMode = null },onImport = onAddYouFsProfile) }
        if(googleOpen) GoogleSyncPanel(onClose = { googleOpen = false })
        if(backupOpen) TripBackupPanel(onClose = { backupOpen = false })
        if (!state.automaticSearch && (state.scanning || state.devices.isNotEmpty())) {
            AlertDialog(onDismissRequest = onStopScan,
                title = { Text(if (state.family == ScooterFamily.YOUFS_TUYA) appText(R.string.find_your_youfs_a) else appText(R.string.find_your_sentinel), fontFamily = DisplayFont) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.family == ScooterFamily.YOUFS_TUYA) appText(R.string.turn_on_your_scooter_and_close_youfs_a_and_the_web_app_device_identity) else appText(R.string.turn_on_your_scooter_and_keep_it_nearby_close_other_connected_bluetoot), color = Muted)
                    state.devices.forEach { device ->
                        OutlinedButton(onClick = { onDevice(device) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) { Text(device.name); if (state.debugEnabled) Text(device.address, fontSize = 10.sp, color = Muted) }
                            if (state.debugEnabled) Text("${device.rssi} dBm", fontSize = 10.sp)
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = onStopScan) { Text(appText(R.string.close_search)) } })
        }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.08f)).border(1.dp, color.copy(alpha = 0.22f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(label, Modifier.padding(start = 6.dp), color = color, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Overline(text: String) { Text(text, color = Muted, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun ScooterHero(lights: Boolean, active: Boolean, family: ScooterFamily, speedKmh: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "ambient")
    val glow by transition.animateFloat(0.14f, 0.27f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "glow")
    Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(266.dp).clip(RoundedCornerShape(24.dp))) {
        Text(if (family == ScooterFamily.YOUFS_TUYA) "YFS" else "2xe", Modifier.align(Alignment.Center).offset(y = (-18).dp), fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold, fontSize = 158.sp, color = Color(0xFF171D21), letterSpacing = (-13).sp)
        Canvas(Modifier.fillMaxSize()) {
            drawOval(Brush.radialGradient(listOf(accent.copy(alpha = glow), Color.Transparent), center = Offset(size.width * .53f, size.height * .77f), radius = size.width * .44f),
                topLeft = Offset(size.width * .08f, size.height * .65f), size = Size(size.width * .84f, size.height * .27f))
        }
        ScooterScene(lights, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize(),if(active) speedKmh else 0f)
        if (lights && active) StatusPill(appText(R.string.lights_on), accent)
    }
        Row(Modifier.fillMaxWidth().height(24.dp),horizontalArrangement = Arrangement.Center,verticalAlignment = Alignment.CenterVertically) {
            Glyph("orbit", Muted, Modifier.size(13.dp))
            Text(appText(R.string.drag_to_rotate), fontSize = 8.sp, letterSpacing = 1.2.sp, color = Muted)
        }
    }
}

@Composable
internal fun ScooterScene(lights: Boolean, accent: Color, modifier: Modifier, speedKmh: Float = 0f) {
    var modelError by remember { mutableStateOf(false) }
    if (modelError) {
        Text(appText(R.string.message_3d_model_is_unavailable_on_this_device), modifier.padding(24.dp), color = Muted, fontSize = 11.sp)
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScooterSceneView(context).apply {
                try {
                    initialize()
                } catch (_: Exception) { modelError = true }
            }
        },
        update = { scene ->
            scene.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            scene.contentDescription = appText(R.string.message_3d_scooter_on_a_road_horizontally_rotatable_headlight) + if(lights) appText(R.string.on_2) else appText(R.string.off_2)
            scene.motion.targetSpeedKmh = speedKmh
            updateScooterAppearance(scene, lights, accent)
        },
        onRelease = { scene -> scene.onFrame = null; scene.destroy() }
    )
}

@Composable
private fun RideDashboard(state: ScooterUiState) {
    val accent = MaterialTheme.colorScheme.primary
    val t = state.telemetry
    val speed by animateFloatAsState(t?.displaySpeed ?: 0f, tween(850), label = "speed")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(26.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(appText(R.string.your_ride_in_real_time))
            Spacer(Modifier.weight(1f))
            Text(if (state.demo) appText(R.string.simulation) else if (state.fresh) "● LIVE" else appText(R.string.waiting_2), color = if (state.fresh) accent else Muted, fontSize = 8.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (t != null) number(speed) else "—", fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 66.sp, letterSpacing = (-4).sp)
                    Text(t?.speedUnit ?: "km/h", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
                }
                Text(appText(R.string.speed), fontSize = 8.sp, color = Muted, letterSpacing = 2.sp)
            }
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Line, 140f, 260f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(accent, 140f, 260f * ((t?.battery ?: 0) / 100f), false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph("bolt", accent, Modifier.size(18.dp))
                    Text(if (t != null) "${t.battery}%" else "—", fontFamily = DisplayFont, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(32) { i -> Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (t != null && i < (speed / 30f * 32).toInt()) accent else Line)) }
        }
        HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(appText(R.string.mode), if (state.family == ScooterFamily.YOUFS_TUYA) state.youfsSchema.find { it.code == "mode" }?.let { dp ->
                dp.options.getOrNull((state.youfsValues[dp.id]?.value as? Number)?.toInt() ?: -1)?.uppercase()
            } ?: "—" else t?.gearName ?: "—", "", accent)
            Metric(appText(R.string.session), if (t != null) number(t.sessionKm * t.distanceFactor) else "—", t?.distanceUnit ?: "km")
            Metric(appText(R.string.total), if (t != null) number(t.totalKm * t.distanceFactor) else "—", t?.distanceUnit ?: "km")
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, color: Color = White) {
    Column { Overline(label); Spacer(Modifier.height(5.dp)); Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontFamily = DisplayFont, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = color)
        Text(" $unit", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
    } }
}

@Composable
private fun ControlTile(title: String, subtitle: String, icon: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(if (active) accent.copy(alpha = 0.12f) else Panel, label = "tile")
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(background).border(1.dp, if (active) accent.copy(alpha = 0.45f) else Line, RoundedCornerShape(20.dp))
        .clickable(enabled = enabled, onClickLabel = title, onClick = onClick).padding(17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, if (active) accent else Muted, Modifier.size(22.dp))
            Spacer(Modifier.weight(1f))
            Text(if (active) "ON" else "OFF", color = if (active) accent else Muted, fontSize = 9.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontFamily = DisplayFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = if (enabled) White else Muted)
        Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun ModeButtons(options: List<String>, selection: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().selectableGroup().clip(RoundedCornerShape(18.dp)).background(Panel).padding(5.dp),horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEachIndexed { index,label ->
            val active = index == selection
            Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp))
                .selectable(selected = active,enabled = enabled,role = Role.RadioButton,onClick = { if(!active) onSelect(index) })
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = appText(if(active) R.string.selected_option else R.string.unselected_option)
                },
                shape = RoundedCornerShape(13.dp),color = if(active) accent.copy(alpha = if(enabled) 1f else .3f) else Panel,
                contentColor = if(!enabled) Muted else if(active) Ink else Muted) {
                Box(Modifier.padding(horizontal = 4.dp,vertical = 12.dp),contentAlignment = Alignment.Center) {
                    Text(label,fontSize = if(options.size > 3) 10.sp else 11.sp,fontWeight = FontWeight.Bold,maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun QuickRideModes(state: ScooterUiState, onSend: (Setting,Int) -> Unit, onYouFsSend: (Int,Any) -> Unit) {
    if(state.family == ScooterFamily.YOUFS_TUYA) {
        val mode = state.youfsSchema.singleOrNull { it.code == "mode" && it.type == "enum" && it.writable } ?: return
        if(mode.options.size !in 1..4) return
        Text(appText(R.string.riding_mode),fontFamily = DisplayFont,fontSize = 16.sp,modifier = Modifier.padding(bottom = 10.dp))
        ModeButtons(mode.options.map { youfsOptionName(it).uppercase(AppLanguage.locale) },mode.selectedOption(state),state.canCommand) { onYouFsSend(mode.id,mode.options[it]) }
    } else {
        Text(appText(R.string.riding_mode),fontFamily = DisplayFont,fontSize = 16.sp,modifier = Modifier.padding(bottom = 10.dp))
        ModeButtons(listOf("ECO","DRIVE","SPORT"),state.telemetry?.gear?.minus(1),state.canCommand) { onSend(Setting.GEAR,it+1) }
    }
}

@Composable
private fun SettingsPanel(state: ScooterUiState, onSend: (Setting, Int) -> Unit) {
    val t = state.telemetry
    Text(appText(R.string.choose_your_pace), fontFamily = DisplayFont, fontSize = 27.sp)
    Text(appText(R.string.every_setting_is_confirmed_by_the_sentinel), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
    ModeButtons(listOf("ECO","DRIVE","SPORT"),t?.gear?.minus(1),state.canCommand) { onSend(Setting.GEAR,it+1) }
    Spacer(Modifier.height(22.dp))
    SettingRow(appText(R.string.lights), appText(R.string.front_and_rear), "light", t?.lights == true, state.canCommand) { onSend(Setting.LIGHTS, if (it) 1 else 0) }
    SettingRow(appText(R.string.cruise_control), appText(R.string.maintain_a_steady_speed), "gauge", t?.cruise == true, state.canCommand) { onSend(Setting.CRUISE, if (it) 1 else 0) }
    SettingRow(appText(R.string.zero_start), appText(R.string.start_without_pushing), "bolt", t?.zeroStart == true, state.canCommand) { onSend(Setting.ZERO_START, if (it) 1 else 0) }
    SettingRow(appText(R.string.imperial_units), if (t?.mph == true) appText(R.string.mph_and_miles) else appText(R.string.km_h_and_kilometres), "orbit", t?.mph == true, state.canCommand) { onSend(Setting.UNIT, if (it) 1 else 0) }
    SettingRow(appText(R.string.motor_lock), appText(R.string.available_only_at_zero_speed), "lock", t?.locked == true, state.canCommand && t?.speedKmh == 0f) { onSend(Setting.LOCK, if (it) 1 else 0) }
}

@Composable
internal fun SettingRow(title: String, subtitle: String, icon: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).background(Panel, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Glyph(icon, if (checked) accent else Muted, Modifier.size(21.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontFamily = DisplayFont, fontSize = 16.sp)
            Text(subtitle, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun Diagnostics(state: ScooterUiState, onClear: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val t = state.telemetry
    if (state.family == ScooterFamily.YOUFS_TUYA) {
        YouFsInspectionPanel(state.inspection)
        return
    }
    Text(appText(R.string.everything_under_control), fontFamily = DisplayFont, fontSize = 26.sp)
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Glyph("pulse", if (t?.errors == 0) accent else Muted, Modifier.size(23.dp)); Spacer(Modifier.width(12.dp));
            Text(if (t == null) appText(R.string.waiting_for_the_device) else if (t.errors == 0) appText(R.string.no_errors_detected) else AppLanguage.quantity(R.plurals.error_count,t.failures.size), fontFamily = DisplayFont, fontSize = 17.sp) }
        t?.failures?.forEach { Text("• ${AppLanguage.translate(it)}", color = Color(0xFFFF9E83), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Line)
        Text(appText(R.string.controller_states), color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(t?.controllerStates?.joinToString(" · ") { AppLanguage.translate(it) }?.ifEmpty { appText(R.string.no_active_state) } ?: "—", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(appText(R.string.message_47,if (t == null) "—" else if (t.rearLight) appText(R.string.on_3) else appText(R.string.off_3)), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Text(appText(R.string.message_48,t?.rideTimeRaw ?: "—"), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(appText(R.string.bluetooth_traffic), fontFamily = DisplayFont, fontSize = 19.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(appText(R.string.clear), fontSize = 11.sp) }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF080B0D)).verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (state.logs.isEmpty()) Text(appText(R.string.waiting_for_data), color = Muted, fontSize = 10.sp)
        state.logs.forEach { entry -> Text(entry.message + if (entry.count > 1) " ×${entry.count}" else "", color = if (entry.message.startsWith("TX")) accent else Muted,
            fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
internal fun Glyph(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x,y), Offset(x2,y2), 1.6f, StrokeCap.Round)
            fun path(vararg points: Pair<Float,Float>) {
                val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first,it.second) } }
                drawPath(p, tint, style = Stroke(1.6f, cap = StrokeCap.Round))
            }
            when (kind) {
                "bolt" -> path(13f to 2f, 5f to 13f, 11f to 13f, 10f to 22f, 19f to 10f, 13f to 10f, 13f to 2f)
                "bluetooth" -> { path(12f to 2f,18f to 7f,6f to 17f); path(6f to 7f,18f to 17f,12f to 22f,12f to 2f) }
                "light" -> { drawCircle(tint,6f,Offset(12f,9f),style=Stroke(1.6f)); line(9f,15f,9f,19f); line(15f,15f,15f,19f); line(9f,20f,15f,20f); line(11f,23f,13f,23f) }
                "lock" -> { drawRoundRect(tint,Offset(5f,10f),Size(14f,11f),androidx.compose.ui.geometry.CornerRadius(2f),style=Stroke(1.6f)); drawArc(tint,180f,180f,false,Offset(8f,2f),Size(8f,12f),style=Stroke(1.6f)); line(12f,14f,12f,17f) }
                "power" -> { drawArc(tint,-55f,290f,false,Offset(3f,3f),Size(18f,18f),style=Stroke(1.6f,cap=StrokeCap.Round)); line(12f,1f,12f,11f) }
                "scooter" -> { drawCircle(tint,3f,Offset(5f,19f),style=Stroke(1.5f)); drawCircle(tint,3f,Offset(20f,19f),style=Stroke(1.5f)); path(5f to 19f,14f to 19f,19f to 4f,15f to 4f); line(19f,4f,20f,16f) }
                "tune" -> { for (y in listOf(5f,12f,19f)) line(3f,y,21f,y); for ((x,y) in listOf(8f to 5f,16f to 12f,10f to 19f)) drawCircle(tint,2.4f,Offset(x,y),style=Stroke(2f)) }
                "pulse" -> path(1f to 12f,6f to 12f,9f to 4f,14f to 21f,17f to 12f,23f to 12f)
                "route" -> { drawCircle(tint,2f,Offset(5f,5f),style=Stroke(1.6f)); drawCircle(tint,2f,Offset(19f,19f),style=Stroke(1.6f)); path(8f to 5f,18f to 5f,18f to 11f,6f to 11f,6f to 19f,16f to 19f) }
                "gauge" -> { drawArc(tint,160f,220f,false,Offset(3f,4f),Size(18f,18f),style=Stroke(1.6f)); line(12f,13f,17f,7f); drawCircle(tint,2f,Offset(12f,13f)) }
                else -> { drawOval(tint,Offset(2f,6f),Size(20f,12f),style=Stroke(1.6f)); drawCircle(tint,2f,Offset(12f,12f)); line(19f,4f,21f,8f) }
            }
        }
    }
}
