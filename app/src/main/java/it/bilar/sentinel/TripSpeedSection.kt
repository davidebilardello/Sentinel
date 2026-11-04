package it.bilar.sentinel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

private val SpeedMuted = Color(0xFF89939D)
private val SpeedPanel = Color(0xFF151A1F)
private val SpeedCard = Color(0xFF1C2228)
private val SpeedGrid = Color(0xFF283038)
private val SpeedInk = Color(0xFFF2F4F0)
private val SpeedFont = FontFamily(Font(R.font.space_grotesk))
private fun kmh(value: Double) = String.format(AppLanguage.locale, "%.1f", value)

@Composable
fun TripSpeedSection(points: List<TrackPoint>, profile: TripSpeedProfile) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(appText(R.string.speed_section), fontFamily = SpeedFont, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedFigure(appText(R.string.max_speed), "${kmh(profile.maxKmh)} km/h", Modifier.weight(1f))
            SpeedFigure(appText(R.string.moving_average_speed), profile.movingAverageKmh?.let { "${kmh(it)} km/h" } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        SpeedChart(points, profile)
        SpeedLegend(Modifier.padding(top = 14.dp))
        Text(appText(if (profile.source == SpeedSource.SCOOTER) R.string.speed_from_scooter else R.string.speed_from_gps),
            color = SpeedMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
        Text(appText(R.string.speed_chart_hint), color = SpeedMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SpeedFigure(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(SpeedCard).padding(13.dp)) {
        Text(label, fontSize = 9.sp, color = SpeedMuted, letterSpacing = 1.sp)
        Text(value, fontFamily = SpeedFont, fontSize = 19.sp, color = SpeedInk, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Speed over time. The line wears the same speed colours as the map, so the two read together. */
@Composable
private fun SpeedChart(points: List<TrackPoint>, profile: TripSpeedProfile) {
    val speeds = profile.pointSpeeds
    val measurer = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    val timeFormat = remember(AppLanguage.locale) { DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale) }
    var touchX by remember(points) { mutableStateOf<Float?>(null) }
    val ceiling = max(25.0, ceil(profile.maxKmh / 5) * 5)
    val start = points.first().time
    val span = (points.last().time - start).coerceAtLeast(1L)
    val axisStyle = TextStyle(color = SpeedMuted, fontSize = 9.sp)
    val tipStyle = TextStyle(color = SpeedInk, fontSize = 11.sp, fontFamily = SpeedFont)
    val description = appText(R.string.speed_chart_description, kmh(profile.maxKmh))

    Canvas(Modifier.fillMaxWidth().height(150.dp)
        .semantics { contentDescription = description }
        .pointerInput(points) {
            detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null })
        }
        .pointerInput(points) {
            detectHorizontalDragGestures(onDragStart = { touchX = it.x }, onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { change, _ ->
                touchX = change.position.x; change.consume()
            }
        }) {
        val left = 26.dp.toPx(); val top = 26.dp.toPx(); val bottom = size.height - 2.dp.toPx()
        val width = size.width - left
        fun x(time: Long) = left + (time - start).toFloat() / span * width
        fun y(value: Double) = bottom - (value / ceiling).toFloat() * (bottom - top)

        var grid = 0.0
        while (grid <= ceiling + .01) {
            drawLine(SpeedGrid, Offset(left, y(grid)), Offset(size.width, y(grid)), 1.dp.toPx())
            val label = measurer.measure(grid.toInt().toString(), axisStyle)
            drawText(label, topLeft = Offset(0f, y(grid) - label.size.height / 2f))
            grid += 10.0
        }

        val brush = Brush.verticalGradient(
            colorStops = TripSpeed.stops.reversed().map { (value, color) -> (1 - value / TripSpeed.stops.last().first).toFloat() to Color(color) }.toTypedArray(),
            startY = y(TripSpeed.stops.last().first), endY = y(0.0))
        val line = Path(); val area = Path()
        var runStart = -1; var runLast = -1
        fun closeRun() {
            if (runStart >= 0 && runLast > runStart) {
                area.lineTo(x(points[runLast].time), bottom); area.lineTo(x(points[runStart].time), bottom); area.close()
            }
            runStart = -1; runLast = -1
        }
        for (i in points.indices) {
            val value = speeds[i]
            val continues = runLast >= 0 && runLast == i - 1 && TripSpeed.connected(points[i - 1], points[i])
            if (value == null || !continues) closeRun()
            if (value == null) continue
            val point = Offset(x(points[i].time), y(value))
            if (runStart < 0) { runStart = i; line.moveTo(point.x, point.y); area.moveTo(point.x, point.y) }
            else { line.lineTo(point.x, point.y); area.lineTo(point.x, point.y) }
            runLast = i
        }
        closeRun()
        drawPath(area, brush, alpha = .16f)
        drawPath(line, brush, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val touch = touchX ?: return@Canvas
        val time = start + ((touch - left) / width * span).toLong().coerceIn(0L, span)
        val index = points.indices.filter { speeds[it] != null }.minByOrNull { kotlin.math.abs(points[it].time - time) } ?: return@Canvas
        val value = speeds[index]!!
        val px = x(points[index].time); val py = y(value)
        drawLine(SpeedMuted, Offset(px, top - 4.dp.toPx()), Offset(px, bottom), 1.dp.toPx())
        drawCircle(SpeedPanel, 6.dp.toPx(), Offset(px, py))
        drawCircle(Color(TripSpeed.color(value)), 4.dp.toPx(), Offset(px, py))
        val tip = measurer.measure("${kmh(value)} km/h · ${Instant.ofEpochMilli(points[index].time).atZone(zone).format(timeFormat)}", tipStyle)
        val pad = 6.dp.toPx()
        val boxWidth = tip.size.width + pad * 2
        val boxLeft = (px - boxWidth / 2).coerceIn(left, size.width - boxWidth)
        drawRoundRect(SpeedCard, Offset(boxLeft, 0f), Size(boxWidth, tip.size.height + pad), CornerRadius(6.dp.toPx()))
        drawText(tip, topLeft = Offset(boxLeft + pad, pad / 2))
    }
    Row(Modifier.fillMaxWidth().padding(start = 26.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(Instant.ofEpochMilli(start).atZone(zone).format(timeFormat), color = SpeedMuted, fontSize = 9.sp)
        Text(Instant.ofEpochMilli(points.last().time).atZone(zone).format(timeFormat), color = SpeedMuted, fontSize = 9.sp)
    }
}

@Composable
private fun SpeedLegend(modifier: Modifier) {
    val top = TripSpeed.stops.last().first
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(
            colorStops = TripSpeed.stops.map { (value, color) -> (value / top).toFloat() to Color(color) }.toTypedArray())))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val labelWidth = 44.dp
            TripSpeed.stops.forEachIndexed { index, (value, _) ->
                val last = index == TripSpeed.stops.lastIndex
                val text = if (last) "${value.toInt()}+ km/h" else value.toInt().toString()
                val offset = when (index) {
                    0 -> 0.dp
                    TripSpeed.stops.lastIndex -> maxWidth - labelWidth
                    else -> maxWidth * (value / top).toFloat() - labelWidth / 2
                }
                Text(text, color = SpeedMuted, fontSize = 9.sp, modifier = Modifier.width(labelWidth).offset(x = offset),
                    textAlign = when (index) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; TripSpeed.stops.lastIndex -> androidx.compose.ui.text.style.TextAlign.End
                        else -> androidx.compose.ui.text.style.TextAlign.Center })
            }
        }
    }
}
