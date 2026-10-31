package it.bilar.sentinel

import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

@Composable
fun TripMap(tripId: Long, points: List<TrackPoint>) {
    val accentHex = "#%06X".format(MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var fittedTrip by remember { mutableStateOf<Long?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    val view = remember(context) {
        object : MapView(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            onCreate(null)
            addOnDidFailLoadingMapListener { loadError = true }
            getMapAsync { loadedMap ->
                if (!alive) return@getMapAsync
                map = loadedMap
                loadedMap.prefetchZoomDelta = 0
                loadedMap.uiSettings.isLogoEnabled = false
                loadedMap.uiSettings.isAttributionEnabled = false
                loadedMap.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")) { style ->
                    if (!alive) return@setStyle
                    style.addSource(GeoJsonSource("route", emptyFeatures()))
                    style.addLayer(LineLayer("route-halo", "route").withProperties(lineColor(accentHex), lineWidth(12f), lineOpacity(.18f), lineCap("round"), lineJoin("round")))
                    style.addLayer(LineLayer("route-line", "route").withProperties(lineColor(accentHex), lineWidth(5f), lineCap("round"), lineJoin("round")))
                    for ((name, color) in listOf("start" to accentHex, "finish" to "#F2F4F0")) {
                        style.addSource(GeoJsonSource(name, emptyFeatures()))
                        style.addLayer(CircleLayer("$name-dot", name).withProperties(circleRadius(6f), circleColor(color), circleStrokeColor("#10151A"), circleStrokeWidth(2f)))
                    }
                    loadedStyle = style
                }
            }
        }
    }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose {
            alive = false
            lifecycle.removeObserver(observer)
            view.onPause(); view.onStop(); view.onDestroy()
        }
    }
    LaunchedEffect(loadedStyle, accentHex) {
        loadedStyle?.getLayerAs<LineLayer>("route-halo")?.setProperties(lineColor(accentHex))
        loadedStyle?.getLayerAs<LineLayer>("route-line")?.setProperties(lineColor(accentHex))
        loadedStyle?.getLayerAs<CircleLayer>("start-dot")?.setProperties(circleColor(accentHex))
    }
    LaunchedEffect(loadedStyle, tripId, points) {
        val style = loadedStyle ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeFeatures(points))
        style.getSourceAs<GeoJsonSource>("start")?.setGeoJson(pointFeature(points.firstOrNull()))
        style.getSourceAs<GeoJsonSource>("finish")?.setGeoJson(pointFeature(points.lastOrNull()))
        if (points.isNotEmpty() && fittedTrip != tripId) {
            if (points.size == 1) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].latitude, points[0].longitude), 16.0))
            else map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points.map { LatLng(it.latitude, it.longitude) }).build(), 55))
            fittedTrip = tripId
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)))
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("© OpenStreetMap" to "https://www.openstreetmap.org/copyright",
            "© OpenMapTiles" to "https://openmaptiles.org/",
            "OpenFreeMap" to "https://openfreemap.org/").forEach { (name, url) ->
            Text(name, color = Color(0xFF89939D), fontSize = 9.sp,
                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(vertical = 5.dp))
        }
    }
    val accuracy = remember(points) { points.map { it.accuracy }.filter { it.isFinite() && it >= 0 }.sorted().let { it.getOrNull(it.size / 2) } }
    accuracy?.let { Text(appText(R.string.message_58,it.toInt()), color = Color(0xFF89939D), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
    if (loadError || LocalDebugVisible.current) Text(if (loadError) appText(R.string.map_unavailable_your_route_remains_saved_on_the_phone) else appText(R.string.dark_map_online_cartography), color = Color(0xFF89939D), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
}

private fun emptyFeatures() = """{"type":"FeatureCollection","features":[]}"""
private fun feature(geometry: JSONObject) = JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", geometry)
private fun pointFeature(point: TrackPoint?): String {
    if (point == null) return emptyFeatures()
    return feature(JSONObject().put("type", "Point").put("coordinates", JSONArray().put(point.longitude).put(point.latitude))).toString()
}
private fun routeFeatures(points: List<TrackPoint>): String {
    val features = JSONArray()
    points.groupBy { it.segment }.values.filter { it.size > 1 }.forEach { segment ->
        val coordinates = JSONArray()
        segment.forEach { coordinates.put(JSONArray().put(it.longitude).put(it.latitude)) }
        features.put(feature(JSONObject().put("type", "LineString").put("coordinates", coordinates)))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

