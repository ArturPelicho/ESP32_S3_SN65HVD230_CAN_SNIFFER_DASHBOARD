package pt.artur.motadash.ui

import pt.artur.motadash.DirtKind
import pt.artur.motadash.RoadClass
import pt.artur.motadash.LayerOpts
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.location.GnssStatus
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import pt.artur.motadash.Units
import pt.artur.motadash.RouteStyle
import pt.artur.motadash.RouteOptions
import pt.artur.motadash.Eta
import pt.artur.motadash.Speech
import pt.artur.motadash.NavCorner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableIntStateOf
import pt.artur.motadash.MapLayer
import pt.artur.motadash.RouteWeather
import pt.artur.motadash.Weather
import pt.artur.motadash.WxKind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import pt.artur.motadash.Rejoin
import androidx.compose.runtime.rememberUpdatedState
import pt.artur.motadash.DeadReckoning
import pt.artur.motadash.Place
import pt.artur.motadash.Places
import pt.artur.motadash.DirectLines
import pt.artur.motadash.Trips
import pt.artur.motadash.RideProfile
import pt.artur.motadash.TripRecorder
import pt.artur.motadash.SNAP_M
import pt.artur.motadash.Router
import pt.artur.motadash.Guidance
import pt.artur.motadash.Maneuver
import pt.artur.motadash.TurnKind
import pt.artur.motadash.TurnVoice
import pt.artur.motadash.Note
import pt.artur.motadash.NoteText
import pt.artur.motadash.PacenoteVoice
import pt.artur.motadash.Pacenotes
import pt.artur.motadash.SectionTimes
import pt.artur.motadash.Split
import pt.artur.motadash.Turns
import pt.artur.motadash.TrackMode
import kotlinx.coroutines.withContext
import pt.artur.motadash.ElevProfile
import java.io.File
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import pt.artur.motadash.FollowMode
import pt.artur.motadash.L
import pt.artur.motadash.LatLon
import pt.artur.motadash.Link
import pt.artur.motadash.GpxWriter
import pt.artur.motadash.Planner
import pt.artur.motadash.Track
import pt.artur.motadash.TrackFollower
import pt.artur.motadash.TrackProgress
import pt.artur.motadash.TrackStore
import pt.artur.motadash.MapStore
import pt.artur.motadash.AutoZoom
import pt.artur.motadash.zoomFor
import pt.artur.motadash.MapStyle
import pt.artur.motadash.MapTheme
import pt.artur.motadash.R

/** Keeps the MapLibre objects between recompositions and drives the camera. */
/** A speed camera ahead on the road. */
data class CameraAlert(val meters: Int, val kmh: Int?, val key: String)

private class MapHolder(private val arrow: Bitmap, private val radar: Bitmap, private val trackArrow: Bitmap) {
    var map: MapLibreMap? = null
    var view: MapView? = null
    var style: Style? = null
    private var styleJson: String? = null
    var pending: String? = null

    var fix: Location? = null
    /** Speed shown on screen, already 0 at a standstill. */
    var speedKmh = 0.0
    /** Smoothed compass heading (true north), null without a sensor. */
    var compass: Float? = null
    var mode = FollowMode.THREE_D
    var autoZoom = true
    var follow = true
        private set
    var onFollow: (Boolean) -> Unit = {}
    var heading = 0f
        private set

    var gesturing = false
    /** Moving a waypoint: the map stays where the hand puts it. */
    var editing = false
    var gestureAt = 0L
    /** Zoom picked by hand (buttons or pinch) and until when it beats auto zoom. */
    var userZoom: Double? = null
    var manualUntil = 0L
    private var autoLevel = 17.0
    var azCfg = AutoZoom()
    private var zoomInSince = 0L
    private var first = true
    private var wasFollowing = false

    fun applyStyle(json: String) {
        val m = map ?: run { styleJson = null; pending = json; return }
        if (json == styleJson) return
        styleJson = json
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            s.addImage("me-arrow", arrow)
            s.addImage("radar", radar)
            s.addImage("trk-arrow", trackArrow)
            s.addImage("wx-arrow", windArrow, true)
            style = s
            wx?.let { s.getSourceAs<GeoJsonSource>("wx")?.setGeoJson(it) }
            radarShown = null; showRadar()
            show3dBuildings()
            drawMarker()
            tracks?.let { s.getSourceAs<GeoJsonSource>("tracks")?.setGeoJson(it) }
        }
    }

    private val windArrow = windArrowBitmap()
    private var wx: FeatureCollection? = null
    /** Rain radar tiles to show (null: none), and the ones on the map now. */
    var radarTiles: String? = null
        set(v) { field = v; showRadar() }
    private var radarShown: String? = null

    /** The weather layer's points; empty to clear it. */
    fun setWeather(fc: FeatureCollection) {
        wx = fc
        style?.getSourceAs<GeoJsonSource>("wx")?.setGeoJson(fc)
    }

    /** The radar picture under the map's labels, swapped when a newer one comes. */
    private fun showRadar() {
        val s = style ?: return
        val want = radarTiles
        if (want == radarShown) return
        s.getLayer("rain")?.let { s.removeLayer(it) }
        s.getSource("rain")?.let { s.removeSource(it) }
        radarShown = want
        if (want == null) return
        // RainViewer's free pictures go to zoom 7; closer in they are enlarged.
        s.addSource(RasterSource("rain", TileSet("2.2.0", want).apply { setMaxZoom(7f) }, 256))
        val l = RasterLayer("rain", "rain").withProperties(PropertyFactory.rasterOpacity(0.55f))
        val below = s.layers.firstOrNull { it is SymbolLayer }?.id
        if (below != null) s.addLayerBelow(l, below) else s.addLayer(l)
    }

    /** Buildings in volume only in the 3D follow mode. */
    /** The 3D buildings layer is on (layers button); they show only in the 3D view. */
    var buildings3d = true

    fun show3dBuildings() {
        val s = style ?: return
        val v = if (mode == FollowMode.THREE_D && buildings3d) Property.VISIBLE else Property.NONE
        for (l in s.layers) if (l.id.startsWith("building-3d-")) l.setProperties(PropertyFactory.visibility(v))
    }

    fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        onFollow(on)
    }

    /** Point on the followed line where the bike is taken to be (a little off it), or null. */
    var ghost: LatLon? = null

    private fun drawMarker() {
        val loc = fix ?: return
        drawMe()
        val g = ghost?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addNumberProperty("bearing", heading) } }
        style?.getSourceAs<GeoJsonSource>("ghost")?.setGeoJson(g?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyList()))
        drawTools()
    }

    // ---- Smooth movement between GPS fixes ----
    // The arrow (and the camera following it) runs at frame rate towards where the bike should be now:
    // from the last fix, at its speed, along the line ahead (the followed route or track, or the road),
    // else straight on. Each new fix pulls it back quickly but smoothly (a first-order follower, no jumps).

    /** The line ahead of the bike from about where it is (route, track or road), for the prediction; null: straight on. */
    var ahead: List<LatLon>? = null
        set(v) { field = v; aheadCum = v?.let { l -> DoubleArray(l.size).also { c -> for (i in 1 until l.size) c[i] = c[i - 1] + pt.artur.motadash.metres(l[i - 1], l[i]) } } }
    private var aheadCum: DoubleArray? = null
    private var shown: LatLon? = null
    private var shownHeading = 0f
    private var lastFrameNs = 0L
    private var camZoom = Double.NaN
    private var camTilt = 0.0
    private var targetZoom = 15.0
    private var targetTilt = 0.0
    private var targetPad = 0.0
    private var cameraFreeAt = 0L
    private var lastCam: CameraPosition? = null

    /** Where the bike should be now, from the last fix. */
    private fun predicted(loc: Location, nowNs: Long): LatLon {
        val here = LatLon(loc.latitude, loc.longitude)
        val v = if (loc.hasSpeed() && loc.speed > 1.5f) loc.speed.toDouble() else return here
        val age = ((nowNs - loc.elapsedRealtimeNanos) / 1e9).coerceIn(0.0, 2.5)
        val go = v * age
        val l = ahead; val c = aheadCum
        if (l != null && c != null && l.size >= 2 && c.last() > 1) {
            val d = go.coerceAtMost(c.last())
            val i = c.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }
            val t = ((d - c[i - 1]) / (c[i] - c[i - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            return LatLon(l[i - 1].lat + (l[i].lat - l[i - 1].lat) * t, l[i - 1].lon + (l[i].lon - l[i - 1].lon) * t)
        }
        val b = Math.toRadians(if (loc.hasBearing()) loc.bearing.toDouble() else heading.toDouble())
        return LatLon(here.lat + go * cos(b) / 110_540.0, here.lon + go * kotlin.math.sin(b) / (111_320.0 * cos(Math.toRadians(here.lat))))
    }

    private fun drawMe() {
        val p = shown ?: fix?.let { LatLon(it.latitude, it.longitude) } ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
        f.addNumberProperty("bearing", shownHeading)
        style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(f)
    }

    /** One display frame: move the arrow, and the camera when following. */
    fun frame() {
        val loc = fix ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastFrameNs == 0L) 0.0 else ((now - lastFrameNs) / 1e9).coerceIn(0.0, 0.5)
        lastFrameNs = now
        val target = predicted(loc, now)
        val cur = shown
        shown = if (cur == null || pt.artur.motadash.metres(cur, target) > 250) target else {
            val a = 1 - kotlin.math.exp(-dt / 0.3)
            LatLon(cur.lat + (target.lat - cur.lat) * a, cur.lon + (target.lon - cur.lon) * a)
        }
        val ah = 1 - kotlin.math.exp(-dt / 0.35)
        shownHeading = ((shownHeading + (((heading - shownHeading + 540f) % 360f) - 180f) * ah.toFloat()) + 360f) % 360f
        drawMe()

        val m = map ?: return
        if (!follow || gesturing || SystemClock.elapsedRealtime() < cameraFreeAt || camZoom.isNaN()) return
        val az = 1 - kotlin.math.exp(-dt / 0.6)
        camZoom += (targetZoom - camZoom) * az
        camTilt += (targetTilt - camTilt) * az
        val s = shown ?: return
        val pos = CameraPosition.Builder()
            .target(LatLng(s.lat, s.lon)).zoom(camZoom)
            .bearing(if (mode == FollowMode.NORTH) 0.0 else shownHeading.toDouble())
            .tilt(camTilt).padding(0.0, targetPad, 0.0, 0.0).build()
        // Standing still nothing changes: no need to redraw the map.
        val last = lastCam
        if (last != null && abs(last.target!!.latitude - s.lat) < 1e-7 && abs(last.target!!.longitude - s.lon) < 1e-7 &&
            abs(last.zoom - camZoom) < 0.002 && abs(last.bearing - pos.bearing) < 0.05 && abs(last.tilt - camTilt) < 0.05) return
        lastCam = pos
        m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    /** Straight lines from the bike to these points ([DirectLines]). */
    var directLines: List<Place> = emptyList()
    /** Points of a distance being measured, the last leg running to the middle of the screen; null when not measuring. */
    var measure: List<LatLon>? = null
    /** Measured so far, up to the middle of the screen (shown in the measuring bar). */
    val measured = mutableDoubleStateOf(0.0)

    /** Direct lines and the distance being measured, with their labels. */
    fun drawTools() {
        val src = style?.getSourceAs<GeoJsonSource>("tools") ?: return
        val fs = ArrayList<Feature>()
        val loc = fix
        if (loc != null) {
            val here = LatLon(loc.latitude, loc.longitude)
            for (d in directLines) {
                val to = LatLon(d.lat, d.lon)
                fs += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(here.lon, here.lat), Point.fromLngLat(to.lon, to.lat))))
                    .apply { addStringProperty("kind", "dline") }
                val label = km(pt.artur.motadash.metres(here, to)) + " · " + pt.artur.motadash.bearing(here, to).let { ((it % 360 + 360) % 360).roundToInt() % 360 } + "°"
                fs += Feature.fromGeometry(Point.fromLngLat(to.lon, to.lat)).apply {
                    addStringProperty("kind", "dend"); addStringProperty("did", d.id)
                    addStringProperty("label", if (d.name.isEmpty()) label else d.name + "\n" + label)
                }
            }
        }
        measure?.let { pts ->
            val all = centre()?.let { pts + it } ?: pts
            if (all.size >= 2) fs += Feature.fromGeometry(LineString.fromLngLats(all.map { Point.fromLngLat(it.lon, it.lat) }))
                .apply { addStringProperty("kind", "measure") }
            var sum = 0.0
            all.forEachIndexed { i, p ->
                if (i > 0) sum += pt.artur.motadash.metres(all[i - 1], p)
                if (i < pts.size) fs += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("kind", "mpt"); if (i > 0) addStringProperty("label", km(sum))
                }
            }
            measured.doubleValue = sum
        }
        src.setGeoJson(FeatureCollection.fromFeatures(fs))
    }

    /** Heading: GPS course while riding, compass when stopped or slow. */
    private fun pickHeading() {
        val loc = fix
        heading = when {
            loc != null && speedKmh > 5 && loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f -> loc.bearing
            compass != null -> compass!!
            else -> heading
        }
    }

    fun update(durationMs: Int) {
        pickHeading()
        drawMarker()
        val m = map ?: return
        val loc = fix ?: return
        val now = SystemClock.elapsedRealtime()
        if (!follow && !gesturing && !editing && speedKmh > 10 && now - gestureAt > 10_000) setFollow(true)

        // Auto zoom: out at once when speeding up, back in only after 8 s slower.
        val target = azCfg.zoomFor(speedKmh)
        when {
            first || target < autoLevel -> { autoLevel = target; zoomInSince = 0 }
            target > autoLevel + 0.05 -> {
                if (zoomInSince == 0L) zoomInSince = now
                if (now - zoomInSince > 8_000) { autoLevel = target; zoomInSince = 0 }
            }
            else -> zoomInSince = 0
        }
        if (autoZoom && userZoom != null && now > manualUntil) userZoom = null
        if (!follow || gesturing) { wasFollowing = false; return }

        val zoom = userZoom ?: if (autoZoom) autoLevel else if (first || camZoom.isNaN()) 15.0 else camZoom
        val h = view?.height ?: 0
        targetZoom = zoom
        targetTilt = if (mode == FollowMode.THREE_D) tiltFor(speedKmh) else 0.0
        // Marker in the lower part of the screen so more road ahead is visible.
        targetPad = if (mode == FollowMode.NORTH) 0.0 else h * 0.4
        // The frames move the camera from here on; a first or a re-follow glides there once.
        if (first || camZoom.isNaN() || !wasFollowing) {
            val pos = CameraPosition.Builder()
                .target(LatLng(loc.latitude, loc.longitude)).zoom(zoom)
                .bearing(if (mode == FollowMode.NORTH) 0.0 else heading.toDouble())
                .tilt(targetTilt).padding(0.0, targetPad, 0.0, 0.0).build()
            val ms = if (first) 1200 else maxOf(durationMs, 400)
            m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), ms, false)
            camZoom = zoom; camTilt = targetTilt; shownHeading = heading; lastCam = null
            cameraFreeAt = now + ms + 50
        }
        wasFollowing = true
        first = false
    }

    /** Speed limit of the road under the bike (OpenStreetMap maxspeed), null when unknown. */
    var limit: Int? = null
        private set
    private var limitMiss = 0
    var camera: CameraAlert? = null
        private set
    private var lastCameraScan = 0L

    /**
     * The map's road under [p] (within 25 m) going about [bearing], from [p] onward to the end of the
     * drawn piece; null when there is none. For dead reckoning in tunnels with nothing being followed.
     */
    fun roadAhead(p: LatLon, bearing: Double): List<LatLon>? {
        val m = map ?: return null
        val st = style ?: return null
        val ids = st.layers.filter { l ->
            l is LineLayer && l.sourceLayer == "roads" && (l.id.startsWith("road-") || l.id.startsWith("track-"))
        }.map { it.id }
        if (ids.isEmpty()) return null
        val at = Location("dr").apply { latitude = p.lat; longitude = p.lon }
        val s = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
        val r = 48f
        var best: List<LatLon>? = null
        var bestScore = Double.MAX_VALUE
        for (f in m.queryRenderedFeatures(RectF(s.x - r, s.y - r, s.x + r, s.y + r), *ids.toTypedArray())) {
            val parts: List<List<Point>> = when (val g = f.geometry()) {
                is LineString -> listOf(g.coordinates())
                is MultiLineString -> g.coordinates()
                else -> continue
            }
            for (line in parts) for (i in 0 until line.size - 1) {
                val (d, segBearing) = segment(at, line[i], line[i + 1])
                if (d > 25) continue
                for (forward in listOf(true, false)) {
                    val diff = angleDiff(if (forward) segBearing else segBearing + 180, bearing)
                    if (diff > 50) continue
                    val score = d + diff * 0.5
                    if (score >= bestScore) continue
                    val a = LatLon(line[i].latitude(), line[i].longitude())
                    val b = LatLon(line[i + 1].latitude(), line[i + 1].longitude())
                    val proj = nearestOn(p, a, b)
                    val rest = if (forward) (i + 1 until line.size).map { line[it] } else (i downTo 0).map { line[it] }
                    val out = listOf(proj) + rest.map { LatLon(it.latitude(), it.longitude()) }
                    var len = 0.0
                    for (k in 1 until out.size) len += pt.artur.motadash.metres(out[k - 1], out[k])
                    if (len < 5) continue // the very end of this piece: the next one carries on
                    bestScore = score; best = out
                }
            }
        }
        return best
    }

    /** Looks up the speed limit under the bike and speed cameras ahead; call on each GPS fix. */
    fun scan() {
        val m = map ?: return
        val st = style ?: return
        val loc = fix ?: return
        val hitLayers = st.layers.map { it.id }.filter { it.startsWith("limit-hit-") }
        if (hitLayers.isEmpty()) { limit = null; camera = null; return }

        // Limit: the nearest tagged road within 30 m, preferring the one going our way.
        val p = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
        val r = 48f
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        for (f in m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), *hitLayers.toTypedArray())) {
            val parts: List<List<Point>> = when (val g = f.geometry()) {
                is LineString -> listOf(g.coordinates())
                is MultiLineString -> g.coordinates()
                else -> continue
            }
            for (line in parts) for (i in 0 until line.size - 1) {
                val (d, segBearing) = segment(loc, line[i], line[i + 1])
                if (d > 30) continue
                val diff = angleDiff(segBearing, heading.toDouble())
                val forward = diff <= 90
                val across = minOf(diff, 180 - diff)
                val score = d + if (speedKmh > 10) across * 0.5 else 0.0
                val k = num(f, "kmh") ?: num(f, if (forward) "kmh_f" else "kmh_b")
                if (k != null && score < bestScore) { bestScore = score; best = k }
            }
        }
        if (best != null) { limit = best; limitMiss = 0 } else if (++limitMiss > 6) limit = null

        // Cameras: every 2 s, the nearest one ahead within 600 m while riding.
        val now = SystemClock.elapsedRealtime()
        if (now - lastCameraScan < 2_000) return
        lastCameraScan = now
        if (speedKmh < 20) { camera = null; return }
        var found: CameraAlert? = null
        for (src in st.sources.filter { it.id.startsWith("lm-") }) {
            val vs = src as? VectorSource ?: continue
            for (f in vs.querySourceFeatures(arrayOf("cameras"), Expression.literal(true))) {
                val pt = f.geometry() as? Point ?: continue
                val (dx, dy) = metres(loc, pt)
                val d = sqrt(dx * dx + dy * dy)
                if (d > 600) continue
                val to = Math.toDegrees(atan2(dx, dy))
                if (angleDiff(to, heading.toDouble()) > 35) continue
                if (found == null || d < found.meters) {
                    found = CameraAlert(d.toInt(), num(f, "kmh"), "%.5f,%.5f".format(pt.latitude(), pt.longitude()))
                }
            }
        }
        camera = found
    }

    private var tracks: FeatureCollection? = null

    /** Draws the visible tracks; the followed one gets arrows in the riding direction. */
    /**
     * Tracks, planned trajetos and favourites. The followed one is cut where the bike is ([follower],
     * [doneM]): the part already ridden is drawn faint.
     */
    fun setTracks(list: List<Track>, activeId: String?, reversed: Boolean, places: List<Place> = emptyList(), dim: Boolean = false,
                  follower: TrackFollower? = null, doneM: Double? = null) {
        val fs = mutableListOf<Feature>()
        for (pl in places) {
            fs += Feature.fromGeometry(Point.fromLngLat(pl.lon, pl.lat)).apply {
                addStringProperty("kind", "fav"); addStringProperty("pid", pl.id); addStringProperty("name", pl.name)
            }
        }
        for (t in list.filter { it.visible }) {
            val active = t.id == activeId
            val pieces = if (active && follower != null && doneM != null) follower.pieces(doneM)
                else t.lines.mapIndexed { leg, l -> Triple(leg, if (active && reversed) l.reversed() else l, false) }
            for ((leg, l, done) in pieces) {
                if (l.size < 2) continue
                fs += Feature.fromGeometry(LineString.fromLngLats(l.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addStringProperty("kind", "line"); addStringProperty("color", t.color); addStringProperty("tid", t.id)
                    addNumberProperty("w", t.width); addBooleanProperty("active", active)
                    // Nav is taking another way: the track stays, faint and thin.
                    if (active && dim) addBooleanProperty("dim", true)
                    // Already ridden: faint behind the bike.
                    if (done) addBooleanProperty("done", true)
                    if (t.plan.isNotEmpty()) addNumberProperty("leg", leg)
                }
            }
            t.plan.forEachIndexed { i, p ->
                fs += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("kind", "rtept"); addStringProperty("color", t.color); addStringProperty("tid", t.id)
                    addNumberProperty("n", i + 1)
                }
            }
            for (w in t.waypoints) {
                fs += Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).apply {
                    addStringProperty("kind", "wpt"); addStringProperty("color", t.color); addStringProperty("name", w.name)
                }
            }
        }
        val fc = FeatureCollection.fromFeatures(fs)
        tracks = fc
        style?.getSourceAs<GeoJsonSource>("tracks")?.setGeoJson(fc)
    }

    /** Dashed line from the bike to the nearest point of the track, or nothing. */
    fun setJoin(to: LatLon?) {
        val loc = fix
        val src = style?.getSourceAs<GeoJsonSource>("join") ?: return
        if (to == null || loc == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(listOf(
            Point.fromLngLat(loc.longitude, loc.latitude), Point.fromLngLat(to.lon, to.lat)))))
    }

    /**
     * The road route back to the track: Nav's new way, thick in the track's colour ([guide] false), or
     * Track mode's thin dashed guide to the nearest point ([guide] true).
     */
    fun setRoute(r: List<LatLon>, guide: Boolean, color: String?) {
        val src = style?.getSourceAs<GeoJsonSource>("join") ?: return
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(it.lon, it.lat) })).apply {
            if (!guide) { addStringProperty("kind", "route"); addStringProperty("color", color ?: "#2f9bff") }
        })
    }

    /** Stops following and fits the whole track on screen. */
    fun showTrack(t: Track) {
        val m = map ?: return
        val pts = t.lines.flatten().map { LatLng(it.lat, it.lon) } + t.waypoints.map { LatLng(it.lat, it.lon) }
        if (pts.isEmpty()) return
        setFollow(false)
        gestureAt = SystemClock.elapsedRealtime() + 20_000     // leave it on screen a while before following again
        if (pts.size == 1) { m.easeCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15.0), 800); return }
        val b = LatLngBounds.Builder().includes(pts).build()
        m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, 80, 80, 80, 80), 800)
    }

    /** Puts p in the middle of the screen, flat, for moving a waypoint. */
    fun centreOn(p: LatLon) {
        val m = map ?: return
        setFollow(false)
        val pos = CameraPosition.Builder().target(LatLng(p.lat, p.lon))
            .zoom(maxOf(m.cameraPosition.zoom, 15.0)).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 600)
    }

    /** The map point under the middle of the screen (where the moving waypoint's pin is). */
    fun centre(): LatLon? {
        val m = map ?: return null
        val v = view ?: return null
        val ll = m.projection.fromScreenLocation(PointF(v.width / 2f, v.height / 2f))
        return LatLon(ll.latitude, ll.longitude)
    }

    /** What is under a long press: a planned waypoint first, then a track line, else the map. */
    fun pick(ll: LatLng): MapMenu {
        val m = map ?: return MapMenu(0f, 0f, LatLon(ll.latitude, ll.longitude))
        val p = m.projection.toScreenLocation(ll)
        fun near(layer: String, r: Float) = if (style?.getLayer(layer) == null) emptyList()
            else m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), layer)
        val at = LatLon(ll.latitude, ll.longitude)
        near("tool-pt", 30f).firstOrNull { it.hasNonNullValueForProperty("did") }?.let { f ->
            return MapMenu(p.x, p.y, at, line = f.getStringProperty("did"))
        }
        near("fav", 30f).firstOrNull { it.hasNonNullValueForProperty("pid") }?.let { f ->
            return MapMenu(p.x, p.y, at, place = f.getStringProperty("pid"))
        }
        near("rte-pt", 36f).firstOrNull { it.hasNonNullValueForProperty("tid") }?.let { f ->
            return MapMenu(p.x, p.y, at, tid = f.getStringProperty("tid"), point = (num(f, "n") ?: 1) - 1)
        }
        near("trk-line", 28f).firstOrNull { it.hasNonNullValueForProperty("tid") }?.let { f ->
            return MapMenu(p.x, p.y, at, tid = f.getStringProperty("tid"), leg = num(f, "leg"))
        }
        return MapMenu(p.x, p.y, at)
    }

    fun zoomBy(step: Double) {
        val m = map ?: return
        userZoom = ((userZoom ?: m.cameraPosition.zoom) + step).coerceIn(3.0, 19.5)
        manualUntil = SystemClock.elapsedRealtime() + 10_000
        if (follow) update(300) else m.animateCamera(CameraUpdateFactory.zoomTo(userZoom!!), 300)
    }
}

/** Long-press menu: where it opens and what is under the finger (a track, one of its waypoints, or the map). */
private data class MapMenu(
    val x: Float, val y: Float, val at: LatLon,
    val tid: String? = null,
    val point: Int? = null,      // a waypoint of a planned trajeto
    val leg: Int? = null,        // the line between waypoints leg and leg + 1
    val place: String? = null,   // a favourite place
    val line: String? = null,    // a direct line's end
)

/** A waypoint being moved: it sits under the pin in the middle of the screen. */
private data class Moving(val tid: String, val point: Int)

private fun num(f: Feature, key: String): Int? =
    if (f.hasNonNullValueForProperty(key)) f.getNumberProperty(key)?.toInt() else null

/** Metres east and north of [p] from the fix (flat-earth, fine over a few km). */
private fun metres(loc: Location, p: Point): Pair<Double, Double> {
    val dx = (p.longitude() - loc.longitude) * 111_320.0 * cos(Math.toRadians(loc.latitude))
    val dy = (p.latitude() - loc.latitude) * 110_540.0
    return dx to dy
}

/** Distance from the fix to segment a-b in metres, and the segment's bearing in degrees. */
private fun segment(loc: Location, a: Point, b: Point): Pair<Double, Double> {
    val (ax, ay) = metres(loc, a)
    val (bx, by) = metres(loc, b)
    val vx = bx - ax
    val vy = by - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * vx
    val cy = ay + t * vy
    return sqrt(cx * cx + cy * cy) to Math.toDegrees(atan2(vx, vy))
}

/** The point of segment a-b nearest to p (flat approximation, fine over a few hundred metres). */
private fun nearestOn(p: LatLon, a: LatLon, b: LatLon): LatLon {
    val k = cos(Math.toRadians(p.lat))
    val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
    val vx = (b.lon - a.lon) * k; val vy = b.lat - a.lat
    val len2 = vx * vx + vy * vy
    val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
    return LatLon(a.lat + t * vy, a.lon + t * (b.lon - a.lon))
}

/** 0..180 degrees between two bearings. */
private fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

/** 3D tilt: steep when stopped, flatter at speed. */
private fun tiltFor(kmh: Double): Double = 60.0 - kmh.coerceIn(0.0, 90.0) / 90.0 * 22.0

/** Phone GPS reports 1-3 km/h of noise when still; show 0 below that noise. */
private fun stillFiltered(kmh: Double?, accKmh: Double? = null): Double? =
    kmh?.let { if (it < maxOf(3.0, accKmh ?: 0.0)) 0.0 else it }

/**
 * Compass from the rotation vector sensor, for the heading when stopped.
 * Works with the phone flat or upright on the handlebar: it takes the direction
 * of the screen's top edge and of the phone's back, whichever is more level.
 */
private class Compass(private val ctx: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val rot = FloatArray(9)
    private var smooth: Float? = null
    var declination = 0f

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val r = ctx.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        // Screen "up" in device axes for the current display rotation.
        val up = when (r) {
            Surface.ROTATION_90 -> floatArrayOf(-1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(0f, 1f, 0f)
        }
        val back = floatArrayOf(0f, 0f, -1f)
        // rot maps device axes to world (east, north, up); add the level parts of both directions.
        var east = 0f
        var north = 0f
        for (v in arrayOf(up, back)) {
            east += rot[0] * v[0] + rot[1] * v[1] + rot[2] * v[2]
            north += rot[3] * v[0] + rot[4] * v[1] + rot[5] * v[2]
        }
        if (east * east + north * north < 0.01f) return
        val raw = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat() + declination
        val s = smooth
        smooth = if (s == null) raw else s + 0.15f * wrap180(raw - s)
        onHeading((smooth!! + 360f) % 360f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

private fun wrap180(d: Float): Float = ((d + 540f) % 360f) - 180f

/** Chevron drawn along the followed track. */
private fun trackArrowBitmap(): Bitmap {
    val s = 48
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    // Points east (along the line); MapLibre turns it with the line.
    val p = Path().apply { moveTo(14f, 8f); lineTo(36f, 24f); lineTo(14f, 40f) }
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B0E12.toInt(); style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    })
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    })
    return b
}

/** The weather layer's points: wind arrow, and the values chosen, at the time now. */
private fun wxFeatures(s: List<Weather.Series>, off: Set<WxKind>): FeatureCollection {
    val now = System.currentTimeMillis()
    return FeatureCollection.fromFeatures(s.mapNotNull { x ->
        val w = x.at(now) ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(x.lon, x.lat)).apply {
            val lines = ArrayList<String>()
            val kmh = w.windKmh; val from = w.windFrom
            if (WxKind.WIND !in off && kmh != null && from != null) {
                addNumberProperty("rot", (from + 180) % 360); addNumberProperty("kmh", kmh)
                val gust = w.gustKmh?.takeIf { it > kmh + 10 }?.let { "–%.0f".format(Units.speed(it.toDouble())) } ?: ""
                lines += "%.0f".format(Units.speed(kmh.toDouble())) + gust + " " + Units.speedUnit
            }
            w.tempC?.takeIf { WxKind.TEMP !in off }?.let { lines += "%.0f%s".format(Units.temp(it.toDouble()), Units.tempUnit) }
            if (WxKind.RAIN !in off && w.rainMmH >= RouteWeather.WET) lines += "%.1f mm/h".format(w.rainMmH)
            w.uv?.takeIf { WxKind.UV !in off && it >= 1 }?.let { lines += "UV %.0f".format(it) }
            if (lines.isNotEmpty()) addStringProperty("label", lines.joinToString("\n"))
        }
    })
}

/** Wind arrow (tinted by the style), pointing north before rotation: where the wind blows to. */
private fun windArrowBitmap(): Bitmap {
    val s = 48
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Path().apply {
        moveTo(24f, 3f); lineTo(40f, 22f); lineTo(29f, 22f); lineTo(29f, 45f); lineTo(19f, 45f); lineTo(19f, 22f); lineTo(8f, 22f); close()
    }
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL })
    return b
}

/** Speed camera mark: red disc with a white camera. */
private fun radarBitmap(): Bitmap {
    val s = 64
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 1, fill)
    fill.color = 0xFFE2463C.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 5, fill)
    fill.color = 0xFFFFFFFF.toInt(); c.drawRoundRect(14f, 22f, 50f, 46f, 5f, 5f, fill)
    c.drawRect(24f, 17f, 36f, 23f, fill)
    fill.color = 0xFFE2463C.toInt(); c.drawCircle(32f, 34f, 7f, fill)
    return b
}

/** The arrow that marks the bike, pointing north before rotation. */
private fun arrowBitmap(): Bitmap {
    val s = 96
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Path().apply {
        moveTo(s / 2f, 6f); lineTo(s - 14f, s - 10f); lineTo(s / 2f, s - 30f); lineTo(14f, s - 10f); close()
    }
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F9BFF.toInt(); style = Paint.Style.FILL })
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND
    })
    return b
}

@SuppressLint("MissingPermission") // location permission is asked for at app start
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val installed by MapStore.installed.collectAsStateWithLifecycle()
    val telemetry by Link.telemetry.collectAsStateWithLifecycle()
    var theme by remember { mutableStateOf(MapStore.theme(ctx)) }
    var lang by remember { mutableStateOf(MapStore.language(ctx)) }
    var mode by remember { mutableStateOf(MapStore.followMode(ctx)) }
    var autoZoom by remember { mutableStateOf(MapStore.autoZoom(ctx)) }
    var showMaps by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var camera by remember { mutableStateOf<CameraAlert?>(null) }
    var cameraBeep by remember { mutableStateOf(MapStore.cameraBeep(ctx)) }
    var gnss by remember { mutableStateOf(Gnss()) }
    var showGps by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    val trip by TripRecorder.state.collectAsStateWithLifecycle()
    val places by Places.list.collectAsStateWithLifecycle()
    var newPlace by remember { mutableStateOf<LatLon?>(null) }
    val directLines by DirectLines.list.collectAsStateWithLifecycle()
    /** Points of the distance being measured (the last leg runs to the cross in the middle), or null. */
    var measuring by remember { mutableStateOf<List<LatLon>?>(null) }
    var stopTrip by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var layerDetail by remember { mutableStateOf<MapLayer?>(null) }
    var layers by remember { mutableStateOf(MapStore.layers(ctx)) }
    var themeTick by remember { mutableIntStateOf(0) }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    var mapTilt by remember { mutableFloatStateOf(0f) }
    val contourStep by MapStore.contourStepFlow.collectAsStateWithLifecycle()
    val layerOpts by MapStore.layerOptsFlow.collectAsStateWithLifecycle()
    val azCfg by MapStore.autoZoomFlow.collectAsStateWithLifecycle()
    val tracks by TrackStore.tracks.collectAsStateWithLifecycle()
    val activeId by TrackStore.active.collectAsStateWithLifecycle()
    val reversed by TrackStore.reversed.collectAsStateWithLifecycle()
    val trackMessage by TrackStore.message.collectAsStateWithLifecycle()
    val activeTrack = tracks.firstOrNull { it.id == activeId }
    val follower = remember(activeTrack?.id, activeTrack?.lines, reversed) { activeTrack?.let { TrackFollower(it, reversed) } }
    val rejoin = remember(activeTrack?.id, activeTrack?.lines, reversed) { activeTrack?.let { Rejoin(it, reversed) } }
    // Heights along the followed track (its own, or read from the downloaded relief), for the profile strip.
    var profile by remember { mutableStateOf<ElevProfile?>(null) }
    LaunchedEffect(activeTrack?.id, activeTrack?.lines, reversed, installed) {
        val t = activeTrack
        profile = if (t == null) null else withContext(Dispatchers.IO) {
            runCatching { ElevProfile.of(t, reversed, installed.filter { it.enabled }) }.getOrNull()
        }
    }
    // Without a track, the ride so far (height and distance), so the strip is never empty.
    val ridden by RideProfile.profile.collectAsStateWithLifecycle()
    LaunchedEffect(installed) {
        val files = installed.filter { it.enabled }.mapNotNull { it.shade }
        withContext(Dispatchers.IO) { RideProfile.useRelief(files) }
    }
    var progress by remember { mutableStateOf<TrackProgress?>(null) }
    // Turns along the followed track, and the next one ahead.
    var guide by remember { mutableStateOf<Guidance?>(null) }
    LaunchedEffect(activeTrack?.id, activeTrack?.lines, activeTrack?.turns, reversed) {
        val t = activeTrack
        guide = if (t == null) null else withContext(Dispatchers.Default) {
            runCatching {
                // Reversed, the router's turns point the wrong way: read them from the line instead.
                Guidance(if (reversed) t.lines.reversed().map { it.reversed() } else t.lines, if (reversed) emptyList() else t.turns)
            }.getOrNull()
        }
    }
    var nextTurn by remember { mutableStateOf<Pair<Maneuver, Double>?>(null) }
    val turnVoice = remember { TurnVoice() }
    // Track mode: rally pacenotes from the track's shape and heights, and each section timed against the best.
    var pacenotes by remember { mutableStateOf<Pacenotes?>(null) }
    LaunchedEffect(activeTrack?.id, activeTrack?.lines, reversed, installed) {
        val t = activeTrack
        pacenotes = if (t == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val heights = runCatching {
                    ElevProfile.of(t, reversed, installed.filter { it.enabled }, samples = (t.lengthM / 10).toInt().coerceIn(50, 20000))
                }.getOrNull()
                Pacenotes(if (reversed) t.lines.reversed().map { it.reversed() } else t.lines, heights)
            }.getOrNull()
        }
    }
    var nextNote by remember { mutableStateOf<Pair<Note, Double>?>(null) }
    val noteVoice = remember { PacenoteVoice() }
    val sections = remember(follower, pacenotes?.notes?.size) {
        val t = activeTrack
        if (t == null || follower == null || pacenotes == null) null else runCatching { SectionTimes(ctx, t, reversed, follower.totalM, pacenotes) }.getOrNull()
    }
    var split by remember { mutableStateOf<Split?>(null) }
    LaunchedEffect(split) { if (split != null) { delay(8000); split = null } }
    // Nav mode works out a road route back to the track; Track mode only shows the way to the nearest point.
    var trackMode by remember { mutableStateOf(MapStore.trackMode(ctx)) }
    var rejoinTick by remember { mutableIntStateOf(0) }
    var navDim by remember { mutableStateOf(false) }
    var navCorner by remember { mutableStateOf(MapStore.navCorner(ctx)) }
    val eta = remember { Eta() }
    var arrival by remember { mutableStateOf<Long?>(null) }
    /** Next waypoint of a planned trajeto (its number) and the arrival time there. */
    var wpArrival by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val navScope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<MapMenu?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }
    var renaming by remember { mutableStateOf<Track?>(null) }
    var saving by remember { mutableStateOf<Track?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val t = saving
        saving = null
        if (uri != null && t != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(GpxWriter.write(t).toByteArray()) } }.isSuccess
            Toast.makeText(ctx, if (ok) ctx.getString(R.string.map_file_saved, t.name) else ctx.getString(R.string.map_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
    var minute by remember { mutableLongStateOf(0L) }
    val holder = remember {
        MapHolder(arrowBitmap(), radarBitmap(), trackArrowBitmap()).also { it.mode = mode; it.autoZoom = autoZoom; it.onFollow = { on -> follow = on } }
    }
    LaunchedEffect(azCfg) { holder.azCfg = azCfg }

    // Weather along the followed line, for the progress bar: fetched again every 15 minutes, kept for when there is no network.
    val routeWx = remember(activeTrack?.id, reversed, follower) {
        if (activeTrack == null || follower == null || follower.totalM < 2000) null
        else RouteWeather(activeTrack.id + (if (reversed) "-r" else "") + "-" + follower.totalM.toLong(), follower.totalM) { follower.pointAt(it) }
    }
    var wxTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(routeWx) {
        val w = routeWx ?: return@LaunchedEffect
        while (true) {
            if (MapStore.wxOnBar(ctx) && w.refresh(ctx)) wxTick++
            delay(if (w.ready) 15 * 60_000L else 2 * 60_000L)
        }
    }
    // The weather layer: a grid over what the map shows, fetched again when the map moves on or every 15 minutes.
    val wxOn = MapLayer.WEATHER in layers
    LaunchedEffect(wxOn, layerOpts.wxOff) {
        if (!wxOn) { holder.setWeather(FeatureCollection.fromFeatures(emptyList())); holder.radarTiles = null; return@LaunchedEffect }
        var lastAt = 0L; var triedAt = 0L; var lastC: LatLng? = null; var lastSpan = 0.0; var radarAt = 0L
        var series: List<Weather.Series>? = null
        while (true) {
            val b = holder.map?.projection?.visibleRegion?.latLngBounds
            val now = System.currentTimeMillis()
            if (b != null && now - triedAt > 60_000L) {
                val c = b.center
                val span = b.latitudeSpan.coerceAtLeast(0.01)
                val lc = lastC
                val moved = lc == null || abs(c.latitude - lc.latitude) > lastSpan / 3 || abs(c.longitude - lc.longitude) > b.longitudeSpan / 3 ||
                    span > lastSpan * 1.8 || span < lastSpan / 1.8
                if (moved || now - lastAt > 15 * 60_000L) {
                    triedAt = now
                    val pts = ArrayList<LatLon>()
                    for (r in 0 until 3) for (k in 0 until 4)
                        pts += LatLon(b.latitudeSouth + span * (r + 0.5) / 3, b.longitudeWest + b.longitudeSpan * (k + 0.5) / 4)
                    Weather.fetch(ctx, "area", pts)?.let { series = it; lastAt = now; lastC = c; lastSpan = span }
                }
            }
            series?.let { holder.setWeather(wxFeatures(it, layerOpts.wxOff)) }
            if (WxKind.RAIN !in layerOpts.wxOff) {
                if (now - radarAt > 10 * 60_000L) Weather.radarTiles()?.let { holder.radarTiles = it; radarAt = now }
            } else holder.radarTiles = null
            delay(20_000)
        }
    }

    LaunchedEffect(Unit) {
        MapStore.load(ctx)
        TrackStore.load(ctx)
        Places.load(ctx)
        DirectLines.load(ctx)
        while (true) { delay(60_000); minute++ }
    }

    // No GPS for a moment while riding (tunnel): the position moves on along the followed line.
    val dr = remember { DeadReckoning() }
    val drPath by rememberUpdatedState(
        rejoin?.route?.takeIf { trackMode == TrackMode.NAV }
            ?: activeTrack?.lines?.let { ls -> if (reversed) ls.reversed().flatMap { it.reversed() } else ls.flatten() }
    )
    val drKmh by rememberUpdatedState(telemetry?.displaySpeed)
    // GPS of the phone, while the map is on screen and the app is in front (works without the board).
    // Keyed on resume so a permission granted later, or a trip to the background, re-registers.
    val lifeState by lifecycle.currentStateAsState()
    val resumed = lifeState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed) {
        while (resumed) {
            delay(1000)
            dr.tick(drPath, drKmh) { p, b -> holder.roadAhead(p, b) }?.let { fix = it }
        }
    }

    DisposableEffect(resumed) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val allowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!resumed || lm == null || !allowed) return@DisposableEffect onDispose {}
        val listener = LocationListener { loc ->
            Trips.onFix(loc)
            // Satellites win, but a network fix takes over once the last satellite fix is ~10 s old
            // (not over a dead-reckoned position: in a tunnel the estimate is better).
            val cur = fix
            // A fix hundreds of metres from the last one in a second is a glitch: ignored for a while.
            if (loc.provider == LocationManager.GPS_PROVIDER && !dr.real(loc)) return@LocationListener
            if (loc.provider == LocationManager.GPS_PROVIDER || cur == null ||
                (cur.provider != LocationManager.GPS_PROVIDER && cur.provider != DeadReckoning.PROVIDER) || ageMs(cur) > 10_000) fix = loc
        }
        try {
            if (fix == null) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { fix = it }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        // Satellites, for the GPS panel.
        val gnssCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) { gnss = Gnss.of(status) }
            override fun onStopped() { gnss = Gnss() }
        }
        try { lm.registerGnssStatusCallback(gnssCb, Handler(Looper.getMainLooper())) } catch (_: Exception) {}
        onDispose { lm.removeUpdates(listener); lm.unregisterGnssStatusCallback(gnssCb) }
    }

    // Map downloads finish in the background; pick them up here too, not only on the maps panel.
    LaunchedEffect(Unit) { while (true) { MapStore.pollDownloads(ctx); delay(3000) } }


    // Compass, so the arrow and the map turn with the bike when stopped.
    val compass = remember {
        var lastCam = 0L
        Compass(ctx) { h ->
            holder.compass = h
            val now = SystemClock.elapsedRealtime()
            if (holder.speedKmh <= 5 && now - lastCam > 200) { lastCam = now; holder.update(250) }
        }
    }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) sm.registerListener(compass, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(compass) }
    }

    // Speed: board first (wheel, gear), else phone GPS; 0 when standing still.
    // A fix older than ~5 s (tunnel, last known position) says nothing about the speed now.
    val phoneSpeed = fix?.takeIf { it.hasSpeed() && ageMs(it) < 5_000 }?.let { loc ->
        stillFiltered(loc.speed * 3.6, if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond * 3.6 else null)
    }
    val speed = stillFiltered(telemetry?.displaySpeed) ?: phoneSpeed
    SideEffect { holder.speedKmh = speed ?: 0.0 }

    // Linked to the app theme, the map is dark exactly when the app is; otherwise its own day/night choice.
    val linked = ThemeStore.linkMap(ctx)
    val night = if (linked) Palette.dark else when (theme) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> minute.let { MapStyle.isNight(fix?.latitude ?: 39.5, fix?.longitude ?: -8.0) }
    }
    val enabled = installed.filter { it.enabled }
    val styleJson = remember(enabled, night, lang, layers, contourStep, layerOpts) {
        MapStyle.appLang = pt.artur.motadash.AppLocale.tag(ctx).ifEmpty { java.util.Locale.getDefault().toLanguageTag() }.substringBefore('-')
        MapStyle.build(enabled, night, lang, layers, contourStep, layerOpts)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                MapLibre.getInstance(c)
                MapView(c).also { mv ->
                    holder.view = mv
                    mv.onCreate(null)
                    mv.getMapAsync { m ->
                        holder.map = m
                        m.uiSettings.isLogoEnabled = false
                        m.uiSettings.isAttributionEnabled = false
                        // Our own north needle (below) only shows the direction; MapLibre's one reset the map on touch.
                        m.uiSettings.isCompassEnabled = false
                        m.addOnCameraMoveListener {
                            // The camera moves every frame while following: only real changes redraw the screen.
                            val b = m.cameraPosition.bearing.toFloat(); val tl = m.cameraPosition.tilt.toFloat()
                            if (abs(((b - mapBearing + 540f) % 360f) - 180f) > 1f) mapBearing = b
                            if (abs(tl - mapTilt) > 1f) mapTilt = tl
                            if (holder.measure != null) holder.drawTools()
                        }
                        m.cameraPosition = CameraPosition.Builder().target(LatLng(39.6, -8.0)).zoom(6.0).build()
                        fun touched() { holder.gestureAt = SystemClock.elapsedRealtime() }
                        // Dragging or turning the map stops following; pinching only overrides the zoom.
                        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(d: MoveGestureDetector) { holder.gesturing = true; touched(); holder.setFollow(false) }
                            override fun onMove(d: MoveGestureDetector) {}
                            override fun onMoveEnd(d: MoveGestureDetector) { holder.gesturing = false; touched() }
                        })
                        m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                            override fun onRotateBegin(d: RotateGestureDetector) { holder.gesturing = true; touched(); holder.setFollow(false) }
                            override fun onRotate(d: RotateGestureDetector) {}
                            override fun onRotateEnd(d: RotateGestureDetector) { holder.gesturing = false; touched() }
                        })
                        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                            override fun onScaleBegin(d: StandardScaleGestureDetector) { holder.gesturing = true; touched() }
                            override fun onScale(d: StandardScaleGestureDetector) {}
                            override fun onScaleEnd(d: StandardScaleGestureDetector) {
                                holder.gesturing = false; touched()
                                holder.userZoom = m.cameraPosition.zoom
                                holder.manualUntil = SystemClock.elapsedRealtime() + 10_000
                            }
                        })
                        // Long press: waypoint menu (add, move, delete) or the trajeto's menu.
                        // While measuring, a long press adds the point pressed instead.
                        m.addOnMapLongClickListener { ll ->
                            holder.setFollow(false); touched()
                            val ms = measuring
                            if (ms != null) { measuring = ms + LatLon(ll.latitude, ll.longitude); return@addOnMapLongClickListener true }
                            menu = holder.pick(ll)
                            true
                        }
                        holder.pending?.let { holder.applyStyle(it) } ?: holder.applyStyle(styleJson)
                        holder.update(0)
                    }
                    bindLifecycle(mv, lifecycle)
                }
            },
        )

        LaunchedEffect(styleJson) { holder.applyStyle(styleJson) }
        // The smooth arrow: ~30 frames a second while the map is on screen.
        LaunchedEffect(holder) {
            var last = 0L
            while (true) {
                withFrameMillis { t -> if (t - last >= 30) { last = t; holder.frame() } }
            }
        }
        LaunchedEffect(directLines) { holder.directLines = directLines; holder.drawTools() }
        LaunchedEffect(measuring) {
            holder.measure = measuring
            holder.editing = measuring != null || moving != null
            holder.drawTools()
        }
        // Redrawn as the bike moves along the followed one (every 20 m), so the ridden part fades behind it.
        val doneStep = progress?.takeIf { it.offM <= SNAP_M * 2 }?.doneM?.let { (it / 20).toInt() }
        LaunchedEffect(tracks, activeId, reversed, places, navDim, doneStep, follower) {
            holder.setTracks(tracks, activeId, reversed, places, navDim, follower, doneStep?.let { it * 20.0 })
            if (follower == null) { progress = null; holder.setJoin(null) }
        }
        // Route type or toggles changed: the planned trajeto being followed is worked out again.
        LaunchedEffect(Unit) {
            RouteOptions.changes.drop(1).collectLatest {
                delay(1200)
                val id = TrackStore.active.value ?: return@collectLatest
                if ((TrackStore.tracks.value.firstOrNull { it.id == id }?.plan?.size ?: 0) >= 2) Planner.replan(ctx, id)
            }
        }
        LaunchedEffect(trackMessage) {
            if (trackMessage != null && !showTracks) { delay(6000); TrackStore.clearMessage() }
        }
        LaunchedEffect(fix) {
            val f = fix ?: return@LaunchedEffect
            holder.fix = f
            ThemeStore.lat = f.latitude; ThemeStore.lon = f.longitude
            Places.here = LatLon(f.latitude, f.longitude)
            holder.scan()
            progress = follower?.locate(f.latitude, f.longitude)
            val p = progress
            val rj = rejoin
            // Off the track, a road route: in Nav it becomes the way to go (the track fades), in Track
            // it is a thin dashed guide to the nearest point of the track, which stays the line.
            val guideOnly = trackMode == TrackMode.TRACK
            if (p != null && rj != null) {
                val here = LatLon(f.latitude, f.longitude)
                rj.step(p, here, SystemClock.elapsedRealtime(), trackMode)?.let { to ->
                    navScope.launch {
                        val r = withContext(Dispatchers.IO) { Router.route(ctx, here, to) }
                        rj.routed(r)
                        rj.route?.let { holder.setRoute(it, guideOnly, activeTrack?.color) }
                        rejoinTick++
                    }
                }
                val route = rj.route
                if (route != null) holder.setRoute(route, guideOnly, activeTrack?.color) else holder.setJoin(p.takeIf { it.offM > SNAP_M }?.nearest)
            } else holder.setJoin(p?.takeIf { it.offM > SNAP_M }?.nearest)
            navDim = !guideOnly && rj?.route != null
            rejoinTick++
            val back = rejoin?.takeIf { it.route != null }
            val navBack = back?.takeIf { trackMode == TrackMode.NAV }
            arrival = if (p == null || follower == null || activeTrack == null || (navBack == null && p.offM > 1000)) null else {
                // On a route back: what is left of it, then the track from where it meets it.
                val fromM = navBack?.targetM ?: p.doneM
                val routeSecs = navBack?.routeSecsLeft
                val timed = follower.timed && (navBack == null || routeSecs != null)
                val left = if (timed) (routeSecs ?: 0.0) + (follower.secsLeft(fromM) ?: 0.0)
                    else (navBack?.routeLeftM ?: 0.0) + (follower.totalM - fromM).coerceAtLeast(0.0)
                eta.update(activeTrack.id to reversed, follower to navBack?.route, timed, left, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            }
            // The next waypoint, at the same pace as the whole way.
            wpArrival = arrival?.let { end ->
                if (follower == null || activeTrack == null || reversed || activeTrack.plan.size < 3 || p == null) return@let null
                val fromM = navBack?.targetM ?: p.doneM
                val (leg, legEndM) = follower.legEnd(fromM) ?: return@let null
                val timed = follower.timed && (navBack == null || navBack.routeSecsLeft != null)
                val head = if (timed) navBack?.routeSecsLeft ?: 0.0 else navBack?.routeLeftM ?: 0.0
                val all = if (timed) head + (follower.secsLeft(fromM) ?: 0.0) else head + (follower.totalM - fromM)
                val part = if (timed) head + (follower.secsLeft(fromM) ?: 0.0) - (follower.secsLeft(legEndM) ?: 0.0) else head + (legEndM - fromM)
                if (all <= 0) return@let null
                val now = System.currentTimeMillis()
                (leg + 2) to now + ((end - now) * (part / all)).toLong()
            }
            // A few metres off the line still counts as on it: a faint arrow shows where.
            val snap = back?.routeSnap ?: p?.takeIf { it.offM <= SNAP_M }
            holder.ghost = snap?.takeIf { it.offM > 6 }?.nearest
            // What lies ahead, for the smooth arrow between fixes: the next 300 m of the followed line,
            // or with nothing followed the road under the bike.
            holder.ahead = when {
                back != null -> null
                p != null && follower != null && p.offM <= 15 -> (0..30).map { follower.pointAt(p.doneM + it * 10.0) }
                activeTrack == null && f.hasBearing() && f.speed * 3.6 > 15 -> holder.roadAhead(LatLon(f.latitude, f.longitude), f.bearing.toDouble())
                else -> null
            }
            holder.update(900)
            val backGuide = back?.guide
            val backDone = back?.routeDoneM
            nextTurn = when {
                p == null -> null
                backGuide != null && backDone != null -> backGuide.next(backDone)
                p.offM <= 50 -> guide?.next(p.doneM)
                else -> null
            }
            val pn = pacenotes
            val noting = trackMode == TrackMode.TRACK && MapStore.pacenotes(ctx) && pn != null && p != null && p.offM <= 50 && back == null
            nextNote = if (noting) pn!!.next(p!!.doneM) else null
            if (noting) noteVoice.step(ctx, pn!!, p!!.doneM, f.speed) else turnVoice.step(ctx, nextTurn, f.speed)
            if (p != null && MapStore.sectionTimes(ctx)) sections?.step(p.doneM, p.offM, f.elapsedRealtimeNanos / 1e9)?.let { s ->
                split = s
                s.faster?.let { if (MapStore.turnVoice(ctx)) Speech.say(ctx, L.s(if (it) R.string.split_fast else R.string.split_slow)) }
            }
            limit = holder.limit
            val cam = holder.camera
            if (cam != null && cam.key != camera?.key && cameraBeep) Speech.beep(ctx)
            camera = cam
        }
        LaunchedEffect(fix?.latitude?.toInt(), fix?.longitude?.toInt()) {
            // Magnetic declination is about -1° to -3° in Portugal; recompute once per degree moved.
            fix?.let { compass.declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()).declination }
        }

        // The button bar along one edge (settings), arranged in as many lines as it needs.
        val side = LayoutStore.barSide(ctx)
        val buttons = LayoutStore.buttons(ctx)
        val vertical = side == BarSide.RIGHT || side == BarSide.LEFT
        val perLine = ((((if (vertical) maxHeight else maxWidth).value - 12f) / 60f).toInt()).coerceAtLeast(1)
        val lines = (buttons.size + perLine - 1) / perLine
        val thick = (lines * 60 + 12).dp
        val inset = PaddingValues(
            start = if (side == BarSide.LEFT) thick else 0.dp, end = if (side == BarSide.RIGHT) thick else 0.dp,
            top = if (side == BarSide.TOP) thick else 0.dp, bottom = if (side == BarSide.BOTTOM) thick else 0.dp,
        )
        val barAlign = when (side) {
            BarSide.RIGHT -> Alignment.CenterEnd
            BarSide.LEFT -> Alignment.CenterStart
            BarSide.TOP -> Alignment.TopCenter
            BarSide.BOTTOM -> Alignment.BottomCenter
        }

        // The rider's elements (speed by default; any Painel element can join it), clear of the bar.
        WidgetBoard(LayoutStore.MAP,
            widgetData(speed = speed, limit = limit, lat = fix?.latitude, lon = fix?.longitude,
                altM = fix?.takeIf { it.hasAltitude() }?.altitude, bearing = mapBearing, tilt = mapTilt,
                profile = if (activeTrack != null) profile else ridden,
                arrival = arrival.takeIf { activeTrack != null && MapStore.etaOnBar(ctx) },
                wx = routeWx?.takeIf { activeTrack != null && MapStore.wxOnBar(ctx) && it.ready }?.let { w ->
                    wxTick
                    progress?.doneM?.let { d -> w.ahead(d, System.currentTimeMillis(), arrival, (fix?.speed ?: 0f).toDouble()) }
                },
                doneM = if (activeTrack != null) progress?.doneM else ridden?.totalM?.toDouble()),
            modifier = Modifier.fillMaxSize().padding(inset).padding(6.dp))

        @Composable
        fun BarButton(b: MapCtl) = when (b) {
            MapCtl.SETTINGS -> ScreenSettingsButton(Modifier.size(52.dp)) { showSheet = true }
            MapCtl.MODE -> MapButton(mode.button, active = mode != FollowMode.NORTH) {
                mode = mode.next(); MapStore.setFollowMode(ctx, mode); holder.mode = mode; holder.show3dBuildings()
                holder.setFollow(true); holder.update(600)
            }
            MapCtl.FOLLOW -> MapButton("◎", active = follow) { moving = null; measuring = null; holder.editing = false; holder.setFollow(true); holder.update(600) }
            MapCtl.ZOOM_IN -> MapButton("+") { holder.zoomBy(1.0) }
            MapCtl.ZOOM_OUT -> MapButton("−") { holder.zoomBy(-1.0) }
            MapCtl.GPS -> GpsButton(fix, gnss, active = showGps) { showGps = !showGps }
            MapCtl.DAYNIGHT -> {
                themeTick
                val m = if (linked) when (ThemeStore.mode(ctx)) {
                    ThemeMode.AUTO -> MapTheme.AUTO; ThemeMode.DAY -> MapTheme.DAY; ThemeMode.NIGHT -> MapTheme.NIGHT
                } else theme
                MapButton(when (m) {
                    MapTheme.AUTO -> stringResource(R.string.map_theme_auto)
                    MapTheme.DAY -> stringResource(R.string.map_theme_day)
                    MapTheme.NIGHT -> stringResource(R.string.map_theme_night)
                }, small = true) {
                    if (linked) { ThemeStore.cycleMode(ctx); themeTick++ }
                    else { theme = MapTheme.entries[(theme.ordinal + 1) % MapTheme.entries.size]; MapStore.setTheme(ctx, theme) }
                }
            }
            MapCtl.LAYERS -> LayersButton(active = showLayers) { showLayers = !showLayers }
            MapCtl.GPX -> MapButton("GPX", active = activeTrack != null, small = true) { showTracks = true }
            MapCtl.REC -> MapButton(if (trip != null) "● " + km(trip!!.distanceM) else "REC", active = trip != null, small = true) {
                if (trip == null) {
                    TripRecorder.start(ctx)
                    Toast.makeText(ctx, ctx.getString(R.string.trip_started), Toast.LENGTH_SHORT).show()
                } else stopTrip = true
            }
            MapCtl.MAPS -> MapButton("☰") { showMaps = true }
        }

        // Full lines on the outside, the remainder on the inner line.
        val chunks = buttons.chunked(perLine).let { if (side == BarSide.RIGHT || side == BarSide.BOTTOM) it.reversed() else it }
        if (vertical) {
            Row(Modifier.align(barAlign).fillMaxHeight().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunks.forEach { line ->
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        line.forEach { BarButton(it) }
                    }
                }
            }
        } else {
            Column(Modifier.align(barAlign).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chunks.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        line.forEach { BarButton(it) }
                    }
                }
            }
        }

        // GPS details and the layers list open next to the bar.
        val panelAlign = if (side == BarSide.LEFT) Alignment.TopStart else if (side == BarSide.RIGHT) Alignment.TopEnd else Alignment.TopStart
        if (showGps) Box(Modifier.align(panelAlign).padding(inset).padding(10.dp)) { GpsPanel(fix, gnss, speed) }
        if (showLayers) {
            Column(
                Modifier.align(barAlign).padding(inset).padding(10.dp)
                    .background(Palette.overlay, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val detail = layerDetail
                if (detail != null) LayerOptions(detail, layerOpts, contourStep,
                    zoom = holder.map?.cameraPosition?.zoom ?: 14.0,
                    onShadeLive = { v -> holder.style?.layers?.filter { it.id.startsWith("hillshade-") }?.forEach {
                        val k = v / 100f
                        it.setProperties(PropertyFactory.hillshadeExaggeration(Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(6, (0.95f * k).coerceAtMost(1f)), Expression.stop(14, 0.7f * k))))
                    } },
                    onBack = { layerDetail = null })
                else {
                    Text(stringResource(R.string.map_layers), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    MapLayer.entries.forEach { l ->
                        LayerChip(l.label, l in layers, l.hasOptions, onClick = {
                            val on = l !in layers
                            MapStore.setLayer(ctx, l, on)
                            layers = if (on) layers + l else layers - l
                            if (l == MapLayer.BUILDINGS_3D) { holder.buildings3d = on; holder.show3dBuildings() }
                        }, onLong = { if (l.hasOptions) layerDetail = l })
                    }
                    Text(stringResource(R.string.map_layers_long), color = Palette.label, fontSize = 12.sp)
                    if (enabled.none { it.shade != null || it.contours != null }) {
                        Text(stringResource(R.string.map_layers_hint), color = Palette.label, fontSize = 12.sp)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        camera?.let { cam ->
            Row(
                Modifier
                    .background(Palette.red, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.map_speed_camera), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(Units.short(cam.meters.toDouble(), 50), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                cam.kmh?.let { LimitSign(it, small = true) }
            }
        }
        moving?.let { mv ->
            Row(
                Modifier.background(Palette.overlay, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.map_moving_waypoint, mv.point + 1), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Button(onClick = {
                    holder.centre()?.let { Planner.move(ctx, mv.tid, mv.point, it) }
                    moving = null; holder.editing = false
                }) { Text(stringResource(R.string.map_fix_here)) }
                OutlinedButton(onClick = { moving = null; holder.editing = false }) { Text(stringResource(R.string.map_cancel)) }
            }
        }
        measuring?.let { pts ->
            MeasureBar(holder,
                onAdd = { holder.centre()?.let { measuring = pts + it } },
                onUndo = { measuring = if (pts.size > 1) pts.dropLast(1) else null },
                onClose = { measuring = null })
        }
        }

        // Turn banner and track box in a corner (map ⚙), clear of the road ahead in the middle.
        val corner = navCorner
        Column(
            Modifier.align(when (corner) {
                NavCorner.TOP_LEFT -> Alignment.TopStart
                NavCorner.TOP_RIGHT -> Alignment.TopEnd
                NavCorner.BOTTOM_LEFT -> Alignment.BottomStart
                NavCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
                NavCorner.TOP -> Alignment.TopCenter
            }).padding(inset).padding(horizontal = 8.dp, vertical = if (corner == NavCorner.TOP && camera != null) 70.dp else 10.dp),
            horizontalAlignment = when (corner) {
                NavCorner.TOP_LEFT, NavCorner.BOTTOM_LEFT -> Alignment.Start
                NavCorner.TOP_RIGHT, NavCorner.BOTTOM_RIGHT -> Alignment.End
                NavCorner.TOP -> Alignment.CenterHorizontally
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activeTrack != null && moving == null && MapStore.turnBanner(ctx)) {
                val nn = nextNote
                if (nn != null) NoteBanner(nn, pacenotes) else nextTurn?.let { TurnBanner(it) }
            }
            if (activeTrack != null && moving == null) split?.let { SplitChip(it) }
            if (activeTrack != null && moving == null) TrackBox(activeTrack, progress, trackMode, rejoin, rejoinTick,
                arrival.takeIf { !(MapStore.etaOnBar(ctx) && Widget.PROFILE in LayoutStore.shown(ctx, LayoutStore.MAP, isLandscape()) && profile != null) },
                wpArrival.takeIf { MapStore.etaWaypoint(ctx) }) { m ->
                trackMode = m
                MapStore.setTrackMode(ctx, m)
            }
        }

        // Cross in the middle while measuring: the last leg runs to it as the map slides under.
        if (measuring != null) {
            Canvas(Modifier.align(Alignment.Center).size(56.dp)) {
                val c = center
                drawLine(Color.Black, c.copy(x = 0f), c.copy(x = size.width), strokeWidth = 5f)
                drawLine(Color.Black, c.copy(y = 0f), c.copy(y = size.height), strokeWidth = 5f)
                drawLine(MEASURE_COLOR, c.copy(x = 0f), c.copy(x = size.width), strokeWidth = 2.5f)
                drawLine(MEASURE_COLOR, c.copy(y = 0f), c.copy(y = size.height), strokeWidth = 2.5f)
            }
        }

        // Pin in the middle of the screen while moving a waypoint; the map slides under it.
        moving?.let { mv ->
            val color = tracks.firstOrNull { it.id == mv.tid }?.color?.let { parseColor(it) } ?: Palette.cyan
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val c = center
                    drawLine(Color.Black, c.copy(x = 0f), c.copy(x = size.width), strokeWidth = 5f)
                    drawLine(Color.Black, c.copy(y = 0f), c.copy(y = size.height), strokeWidth = 5f)
                    drawLine(Color.White, c.copy(x = 0f), c.copy(x = size.width), strokeWidth = 2.5f)
                    drawLine(Color.White, c.copy(y = 0f), c.copy(y = size.height), strokeWidth = 2.5f)
                }
                Box(Modifier.size(30.dp).background(color, CircleShape).border(2.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("${mv.point + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        menu?.let { mm ->
            Box(Modifier.offset { IntOffset(mm.x.toInt(), mm.y.toInt()) }) {
                MapMenuItems(
                    mm, tracks, activeTrack, moving, fix?.let { LatLon(it.latitude, it.longitude) },
                    onDismiss = { menu = null },
                    onMoveStart = { t, k ->
                        moving = Moving(t.id, k); holder.editing = true; holder.centreOn(t.plan[k])
                    },
                    onMoveFix = { mv ->
                        holder.centre()?.let { Planner.move(ctx, mv.tid, mv.point, it) }
                        moving = null; holder.editing = false
                    },
                    onMoveCancel = { moving = null; holder.editing = false },
                    onRename = { renaming = it },
                    onSave = { saving = it; save.launch(GpxWriter.fileName(it)) },
                    onShare = { shareGpx(ctx, it) },
                    onNewPlace = { newPlace = it },
                    onMeasure = { at -> measuring = listOf(at); holder.drawTools() },
                )
            }
        }

        newPlace?.let { at ->
            var text by remember(at) { mutableStateOf(if (places.isEmpty()) ctx.getString(R.string.place_home) else ctx.getString(R.string.place_default, places.size + 1)) }
            AlertDialog(
                onDismissRequest = { newPlace = null },
                title = { Text(stringResource(R.string.place_add)) },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
                confirmButton = {
                    Button(onClick = { Places.add(ctx, text.trim().ifEmpty { "★" }, at); newPlace = null }) { Text(stringResource(R.string.map_save)) }
                },
                dismissButton = { OutlinedButton(onClick = { newPlace = null }) { Text(stringResource(R.string.map_cancel)) } },
            )
        }

        if (stopTrip) trip?.let { r ->
            AlertDialog(
                onDismissRequest = { stopTrip = false },
                title = { Text(stringResource(R.string.trip_stop_q)) },
                text = { Text(stringResource(R.string.trip_so_far, km(r.distanceM), tripTime(r.startedMs))) },
                confirmButton = {
                    Button(onClick = {
                        stopTrip = false
                        val t = TripRecorder.stop(ctx)
                        Toast.makeText(ctx, if (t != null) ctx.getString(R.string.trip_saved, t.name) else ctx.getString(R.string.trip_too_short), Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.trip_stop)) }
                },
                dismissButton = { OutlinedButton(onClick = { stopTrip = false }) { Text(stringResource(R.string.trip_keep)) } },
            )
        }

        renaming?.let { t ->
            var text by remember(t.id) { mutableStateOf(t.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.map_rename_title)) },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
                confirmButton = {
                    Button(onClick = {
                        TrackStore.change(ctx, t.id) { it.copy(name = text.trim().ifEmpty { it.name }) }
                        renaming = null
                    }) { Text(stringResource(R.string.map_save)) }
                },
                dismissButton = { OutlinedButton(onClick = { renaming = null }) { Text(stringResource(R.string.map_cancel)) } },
            )
        }

        if (fix?.provider == DeadReckoning.PROVIDER) {
            Text(stringResource(R.string.map_dr), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(Palette.orange, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }

        trackMessage?.takeIf { !showTracks }?.let {
            Text(it, color = Palette.text, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    .background(Palette.overlay, RoundedCornerShape(10.dp)).padding(12.dp))
        }

        Text(
            "© OpenStreetMap · Protomaps" + if (enabled.any { it.contours != null }) " · Copernicus DEM" else "",
            color = if (night) Palette.label else Color(0xFF4A515A), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )

        if (enabled.isEmpty() && !showMaps) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Palette.panel, RoundedCornerShape(14.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (installed.isEmpty()) stringResource(R.string.map_no_maps) else stringResource(R.string.map_all_off),
                    color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.map_download_hint), color = Palette.label, fontSize = 14.sp)
                Button(onClick = { showMaps = true }) { Text(stringResource(R.string.map_maps)) }
            }
        }

        // Opened from the app's settings.
        LaunchedEffect(Unit) {
            MapStore.openPanel.collect { p ->
                when (p) { "maps" -> showMaps = true; "gpx" -> showTracks = true }
                if (p != null) MapStore.openPanel.value = null
            }
        }
        LaunchedEffect(Unit) {
            MapStore.showTrack.collect { id ->
                if (id == null) return@collect
                // Wait for the map to be up before moving it.
                repeat(50) { if (holder.map == null) delay(100) }
                TrackStore.tracks.value.firstOrNull { it.id == id }?.let { holder.showTrack(it) }
                MapStore.showTrack.value = null
            }
        }

        if (showTracks) {
            TracksPanel(
                onShow = { t -> showTracks = false; holder.showTrack(t) },
                onClose = { showTracks = false; TrackStore.clearMessage() },
            )
        }

        if (showSheet) {
            WidgetSheet(LayoutStore.MAP, stringResource(R.string.w_map_title), onClose = { showSheet = false }) {
                BarSettings()
                OutlinedButton(onClick = { showSheet = false; showMaps = true }) { Text(stringResource(R.string.w_map_settings)) }
            }
        }

        if (showMaps) {
            MapsPanel(
                theme = theme, lang = lang, mode = mode, autoZoom = autoZoom,
                onTheme = { theme = it; MapStore.setTheme(ctx, it) },
                onLang = { lang = it; MapStore.setLanguage(ctx, it) },
                onMode = { mode = it; MapStore.setFollowMode(ctx, it); holder.mode = it; holder.show3dBuildings(); holder.update(600) },
                onAutoZoom = { autoZoom = it; MapStore.setAutoZoom(ctx, it); holder.autoZoom = it; holder.userZoom = null },
                cameraBeep = cameraBeep,
                onCameraBeep = { cameraBeep = it; MapStore.setCameraBeep(ctx, it) },
                navCorner = navCorner,
                onNavCorner = { navCorner = it; MapStore.setNavCorner(ctx, it) },
                onClose = { showMaps = false },
            )
        }
    }
}

/** The long-press menu: add a waypoint, move or delete one, or work on the whole trajeto. */
@Composable
private fun MapMenuItems(
    mm: MapMenu,
    tracks: List<Track>,
    current: Track?,
    moving: Moving?,
    here: LatLon?,
    onDismiss: () -> Unit,
    onMoveStart: (Track, Int) -> Unit,
    onMoveFix: (Moving) -> Unit,
    onMoveCancel: () -> Unit,
    onRename: (Track) -> Unit,
    onSave: (Track) -> Unit,
    onShare: (Track) -> Unit,
    onNewPlace: (LatLon) -> Unit,
    onMeasure: (LatLon) -> Unit,
) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val place = mm.place?.let { id -> Places.list.value.firstOrNull { it.id == id } }
    val line = mm.line?.let { id -> DirectLines.list.value.firstOrNull { it.id == id } }
    var looks by remember { mutableStateOf(false) }
    val t = tracks.firstOrNull { it.id == mm.tid }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        when {
            moving != null -> {
                MenuItem(stringResource(R.string.map_fix_waypoint_q, moving.point + 1), onDismiss) { onMoveFix(moving) }
                MenuItem(stringResource(R.string.map_cancel), onDismiss) { onMoveCancel() }
            }
            line != null -> {
                val to = LatLon(line.lat, line.lon)
                if (here != null) MenuItem(stringResource(R.string.map_go_there), onDismiss) { Planner.goThere(ctx, here, to, line.name.ifEmpty { null }) }
                MenuItem(stringResource(R.string.measure_from_here), onDismiss) { onMeasure(to) }
                MenuItem(stringResource(R.string.dline_remove), onDismiss) { DirectLines.delete(ctx, line.id) }
                if (DirectLines.list.value.size > 1) MenuItem(stringResource(R.string.dline_remove_all), onDismiss) { DirectLines.clear(ctx) }
            }
            place != null -> {
                Text("★ " + place.name, color = Palette.label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                if (here != null) MenuItem(stringResource(R.string.place_go), onDismiss) { Planner.goThere(ctx, here, LatLon(place.lat, place.lon), place.name) }
                MenuItem(stringResource(R.string.dline_add), onDismiss) { DirectLines.add(ctx, LatLon(place.lat, place.lon), place.name) }
                MenuItem(stringResource(R.string.measure_from_here), onDismiss) { onMeasure(LatLon(place.lat, place.lon)) }
                if (confirmDelete) MenuItem(stringResource(R.string.place_confirm_delete, place.name), onDismiss) { Places.delete(ctx, place.id) }
                else DropdownMenuItem(text = { Text(stringResource(R.string.place_delete), fontSize = 16.sp, color = Palette.red) }, onClick = { confirmDelete = true })
            }
            t != null && mm.point != null && mm.point in t.plan.indices -> {
                val n = mm.point + 1
                Text(stringResource(R.string.map_waypoint_of, n, t.name), color = Palette.label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                MenuItem(stringResource(R.string.map_move_waypoint, n), onDismiss) { onMoveStart(t, mm.point) }
                MenuItem(stringResource(R.string.map_delete_waypoint, n), onDismiss) { Planner.remove(ctx, t.id, mm.point) }
                if (current?.id != t.id) MenuItem(stringResource(R.string.map_continue_route), onDismiss) { TrackStore.setActive(ctx, t.id) }
            }
            t != null -> {
                Text(t.name, color = Palette.label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                // Delete first: in landscape a long menu runs off the screen.
                if (confirmDelete) {
                    MenuItem(stringResource(R.string.map_confirm_delete, t.name), onDismiss) { TrackStore.delete(ctx, t.id) }
                } else {
                    DropdownMenuItem(text = { Text(stringResource(R.string.map_delete_route), fontSize = 16.sp, color = Palette.red) },
                        onClick = { confirmDelete = true })
                }
                if (mm.leg != null && mm.leg in 0 until t.plan.size - 1) {
                    MenuItem(stringResource(R.string.map_insert_waypoint, mm.leg + 2), onDismiss) { Planner.insert(ctx, t.id, mm.leg, mm.at) }
                }
                if (current?.id != t.id) MenuItem(stringResource(R.string.map_follow_route), onDismiss) { TrackStore.setActive(ctx, t.id); TrackStore.setReversed(ctx, false) }
                MenuItem(stringResource(R.string.map_reverse), onDismiss) { Planner.reverse(ctx, t.id) }
                MenuItem(stringResource(R.string.map_rename), onDismiss) { onRename(t) }
                if (looks) {
                    // Colour and thickness, right in the menu; the menu stays open to see the change.
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackStore.COLORS.forEach { c ->
                            Box(
                                Modifier.size(30.dp).background(parseColor(c), CircleShape)
                                    .border(3.dp, if (t.color == c) Color.White else Color.Transparent, CircleShape)
                                    .clickable { TrackStore.change(ctx, t.id) { it.copy(color = c) } }
                            )
                        }
                    }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackStore.WIDTHS.forEach { (w, label) ->
                            FilterChip(selected = t.width == w, onClick = { TrackStore.change(ctx, t.id) { it.copy(width = w) } },
                                label = { Text(label) })
                        }
                    }
                } else {
                    DropdownMenuItem(text = { Text(stringResource(R.string.map_change_look), fontSize = 16.sp) }, onClick = { looks = true })
                }
                MenuItem(stringResource(R.string.map_save_copy), onDismiss) {
                    TrackStore.duplicate(ctx, t.id)?.let { Toast.makeText(ctx, ctx.getString(R.string.map_copy_saved, it.name), Toast.LENGTH_SHORT).show() }
                }
                MenuItem(stringResource(R.string.map_save_gpx), onDismiss) { onSave(t) }
                MenuItem(stringResource(R.string.map_share_gpx), onDismiss) { onShare(t) }
            }
            else -> {
                if (current != null && current.plan.isNotEmpty()) {
                    MenuItem(stringResource(R.string.map_add_waypoint, current.plan.size + 1, current.name), onDismiss) { Planner.add(ctx, current.id, mm.at) }
                }
                if (here != null) MenuItem(stringResource(R.string.map_go_there), onDismiss) { Planner.goThere(ctx, here, mm.at) }
                MenuItem(stringResource(R.string.place_add), onDismiss) { onNewPlace(mm.at) }
                MenuItem(stringResource(R.string.map_new_route), onDismiss) { Planner.start(ctx, mm.at) }
                MenuItem(stringResource(R.string.measure_from_here), onDismiss) { onMeasure(mm.at) }
                MenuItem(stringResource(R.string.dline_add), onDismiss) { DirectLines.add(ctx, mm.at) }
            }
        }
    }
}

private val MEASURE_COLOR = Color(0xFFFF9100)

/** Distance measured so far (to the cross in the middle), with add, undo and close. */
@Composable
private fun MeasureBar(holder: MapHolder, onAdd: () -> Unit, onUndo: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.background(Palette.overlay, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📏 " + km(holder.measured.doubleValue), color = MEASURE_COLOR, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onAdd) { Text("+ " + stringResource(R.string.measure_point)) }
            OutlinedButton(onClick = onUndo) { Text("↶") }
            OutlinedButton(onClick = onClose) { Text("✕") }
        }
        Text(stringResource(R.string.measure_hint), color = Palette.label, fontSize = 12.sp)
    }
}

@Composable
private fun MenuItem(label: String, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 16.sp) }, onClick = { onDismiss(); action() })
}

/** Sends the track as a GPX file to another app (WhatsApp, e-mail, Drive...). */
private fun shareGpx(ctx: Context, t: Track) {
    runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, GpxWriter.fileName(t)).apply { writeText(GpxWriter.write(t)) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, t.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.map_share_title, t.name)))
    }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.map_share_failed), Toast.LENGTH_SHORT).show() }
}

/** Next turn: big arrow, distance, what to do. */
@Composable
private fun TurnBanner(next: Pair<Maneuver, Double>) {
    val (m, dist) = next
    Banner(Turns.arrow(m), (m.exit.takeIf { m.kind == TurnKind.ROUNDABOUT && it > 0 })?.toString(), dist, Turns.phrase(m), dist < 150)
}

/** Next pacenote, the way a co-driver would call it (with the ones linked to it). */
@Composable
private fun NoteBanner(next: Pair<Note, Double>, pn: Pacenotes?) {
    val ctx = LocalContext.current
    val (n, dist) = next
    val group = pn?.notes?.indexOf(n)?.takeIf { it >= 0 }?.let { pn.group(it) } ?: listOf(n)
    Banner(NoteText.arrow(n), n.grade.takeIf { n.kind == pt.artur.motadash.NoteKind.CORNER }?.toString(), dist, NoteText.call(ctx, group, null), dist < 80)
}

/** A section just finished: its time, and how it compares with the best. */
@Composable
private fun SplitChip(s: Split) {
    val d = s.delta
    val col = when (s.faster) { true -> Color(0xFF2E7D32); false -> Color(0xFFC62828); null -> Color(0xF0101010) }
    val txt = if (d == null) stringResource(R.string.split_first, s.section, SectionTimes.clock(s.secs))
        else stringResource(R.string.split_delta, s.section, SectionTimes.delta(d)) + "  ·  " + stringResource(if (s.faster == true) R.string.split_fast else R.string.split_slow)
    Text(txt, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(col, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Banner(arrow: String, badge: String?, dist: Double, text: String, soon: Boolean) {
    // White on near black reads in full sun; the turn close by gets a thick yellow frame, not a fill.
    val ink = Color.White
    Row(
        Modifier.background(Color(0xF0101010), RoundedCornerShape(12.dp))
            .border(if (soon) 4.dp else 0.dp, if (soon) Color(0xFFFFD400) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(arrow, color = if (soon) Color(0xFFFFD400) else ink, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            if (badge != null) Text(badge, color = Color(0xFFFFD400), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(Turns.shown(dist), color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(text, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Followed track: distance left, how far off it the bike is, and the follow mode; long press for the options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackBox(t: Track, p: TrackProgress?, mode: TrackMode, rj: Rejoin?, tick: Int, arrival: Long?, wp: Pair<Int, Long>?, onMode: (TrackMode) -> Unit) {
    tick
    val ctx = LocalContext.current
    val off = p != null && p.offM > 50
    val route = if (mode == TrackMode.NAV) rj?.route else null
    val warn = off && route == null
    var menu by remember { mutableStateOf(false) }
    Box {
    if (menu) TrackOptionsMenu(t, mode, onMode) { menu = false }
    Column(
        Modifier.background(if (warn) Palette.orange else Palette.overlay, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { Toast.makeText(ctx, ctx.getString(R.string.track_box_long_press), Toast.LENGTH_SHORT).show() },
                onLongClick = { menu = true },
            ).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mode.badge, color = if (warn) Color.Black else Palette.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.border(1.dp, if (warn) Color.Black else Palette.cyan, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
            Text(t.name, color = if (warn) Color.Black else Palette.label, fontSize = 12.sp, maxLines = 1)
            // One tap silences the spoken turns (and what is being said now); again to bring them back.
            var voice by remember { mutableStateOf(MapStore.turnVoice(ctx)) }
            Text(if (voice) "🔊" else "🔇", fontSize = 18.sp, modifier = Modifier.clickable {
                voice = !voice; MapStore.setTurnVoice(ctx, voice); if (!voice) Speech.stop()
            }.padding(start = 4.dp))
        }
        when {
            p == null -> Text(stringResource(R.string.map_waiting_gps), color = Palette.text, fontSize = 16.sp)
            route != null && rj != null -> {
                val back = rj.routeLeftM ?: 0.0
                val total = back + (p.doneM + p.leftM - rj.targetM).coerceAtLeast(0.0)
                Text(stringResource(R.string.map_left, km(total)), color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val left = km(back)
                Text(when (rj.goal) {
                    Rejoin.Goal.END -> stringResource(R.string.nav_to_end, left)
                    Rejoin.Goal.WAYPOINT -> stringResource(R.string.nav_to_waypoint, left, rj.goalWp)
                    Rejoin.Goal.TRACK -> stringResource(R.string.nav_rejoin, left)
                }, color = Palette.cyan, fontSize = 14.sp)
            }
            p.offM > 1000 -> Text(stringResource(R.string.map_route_away, km(p.offM)), color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.map_left, km(p.leftM)), color = if (off) Color.Black else Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (off) Text(stringResource(R.string.map_off_route, p.offM.toInt()), color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (wp != null && p != null) {
            Text(stringResource(R.string.nav_eta_wp, wp.first, clockOf(wp.second)), color = if (warn) Color.Black else Palette.label, fontSize = 14.sp)
        }
        if (arrival != null && p != null) {
            Text(stringResource(R.string.nav_eta, clockOf(arrival)), color = if (warn) Color.Black else Palette.label, fontSize = 14.sp)
        }
        val planning by Planner.busy.collectAsStateWithLifecycle()
        if (t.id in planning || (mode == TrackMode.NAV && rj?.busy == true)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = if (warn) Color.Black else Palette.cyan, strokeWidth = 2.dp)
                Text(stringResource(if (t.id in planning) R.string.route_recalc else R.string.nav_recalc),
                    color = if (warn) Color.Black else Palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else if (mode == TrackMode.NAV && warn && rj?.failed == true) {
            Text(stringResource(R.string.nav_no_net), color = Color.Black, fontSize = 12.sp)
        }
    }
    }
}

/** The track box's long-press menu: follow mode, route type and what to avoid, recalculate. */
@Composable
private fun TrackOptionsMenu(t: Track, mode: TrackMode, onMode: (TrackMode) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(RouteOptions.style(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    // The route options are a page of their own, so the first page stays short enough to read.
    var routePage by remember { mutableStateOf(false) }
    tick
    @Composable
    fun header(@androidx.annotation.StringRes res: Int) =
        Text(stringResource(res), color = Palette.label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    @Composable
    fun check(label: String, on: Boolean, enabled: Boolean = true, action: () -> Unit) =
        DropdownMenuItem(text = { Text((if (on) "✓  " else "     ") + label, fontSize = 16.sp) }, enabled = enabled, onClick = action)
    if (routePage) DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("‹  " + stringResource(R.string.route_title), color = Palette.amber, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            onClick = { routePage = false })
        HorizontalDivider()
        RouteStyle.entries.forEach { s -> check(s.label, style == s) { style = s; RouteOptions.setStyle(ctx, s) } }
        HorizontalDivider()
        val fun_ = style == RouteStyle.FUN
        check(stringResource(R.string.route_keep_main), RouteOptions.keepMain(ctx), !fun_) { RouteOptions.setKeepMain(ctx, !RouteOptions.keepMain(ctx)); tick++ }
        check(stringResource(R.string.route_avoid_motorways), RouteOptions.avoidMotorways(ctx)) { RouteOptions.setAvoidMotorways(ctx, !RouteOptions.avoidMotorways(ctx)); tick++ }
        check(stringResource(R.string.route_avoid_toll), RouteOptions.avoidToll(ctx)) { RouteOptions.setAvoidToll(ctx, !RouteOptions.avoidToll(ctx)); tick++ }
        check(stringResource(R.string.route_avoid_unpaved), RouteOptions.avoidUnpaved(ctx)) { RouteOptions.setAvoidUnpaved(ctx, !RouteOptions.avoidUnpaved(ctx)); tick++ }
        check(stringResource(R.string.route_bypass_towns), RouteOptions.bypassTowns(ctx)) { RouteOptions.setBypassTowns(ctx, !RouteOptions.bypassTowns(ctx)); tick++ }
        if (fun_) {
            // Each tap: the next allowed detour.
            val d = RouteOptions.funDetour(ctx)
            DropdownMenuItem(text = { Text("     " + stringResource(R.string.route_fun_detour) + ": +$d%", fontSize = 16.sp) }, onClick = {
                val all = RouteOptions.DETOURS
                RouteOptions.setFunDetour(ctx, all[(all.indexOf(d) + 1).mod(all.size)]); tick++
            })
        }
        if (t.plan.size >= 2) {
            HorizontalDivider()
            MenuItem(stringResource(R.string.route_replan_trip), onDismiss) { Planner.replan(ctx, t.id) }
        }
    } else DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("■  " + stringResource(R.string.nav_stop), color = Palette.red, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            onClick = { onDismiss(); Speech.stop(); TrackStore.setActive(ctx, null) })
        check(stringResource(R.string.turn_voice), MapStore.turnVoice(ctx)) {
            val on = !MapStore.turnVoice(ctx); MapStore.setTurnVoice(ctx, on); if (!on) Speech.stop(); tick++
        }
        check(stringResource(R.string.eta_waypoint), MapStore.etaWaypoint(ctx)) { MapStore.setEtaWaypoint(ctx, !MapStore.etaWaypoint(ctx)); tick++ }
        check(stringResource(R.string.eta_on_bar), MapStore.etaOnBar(ctx)) { MapStore.setEtaOnBar(ctx, !MapStore.etaOnBar(ctx)); tick++ }
        check(stringResource(R.string.wx_on_bar), MapStore.wxOnBar(ctx)) { MapStore.setWxOnBar(ctx, !MapStore.wxOnBar(ctx)); tick++ }
        check(stringResource(R.string.pacenotes), MapStore.pacenotes(ctx)) { MapStore.setPacenotes(ctx, !MapStore.pacenotes(ctx)); tick++ }
        check(stringResource(R.string.pacenote_numbers), MapStore.pacenoteNumbers(ctx), MapStore.pacenotes(ctx)) { MapStore.setPacenoteNumbers(ctx, !MapStore.pacenoteNumbers(ctx)); tick++ }
        if (MapStore.pacenotes(ctx)) {
            // Each tap: the next lead time.
            val l = MapStore.pacenoteLead(ctx)
            DropdownMenuItem(text = { Text("     " + stringResource(R.string.pacenote_lead) + ": $l s", fontSize = 16.sp) }, onClick = {
                val all = MapStore.PACENOTE_LEADS
                MapStore.setPacenoteLead(ctx, all[(all.indexOf(l) + 1).mod(all.size)]); tick++
            })
        }
        check(stringResource(R.string.section_times), MapStore.sectionTimes(ctx)) { MapStore.setSectionTimes(ctx, !MapStore.sectionTimes(ctx)); tick++ }
        HorizontalDivider()
        header(R.string.track_mode_title)
        TrackMode.entries.forEach { m -> check(m.label, mode == m) { onMode(m); onDismiss() } }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("     " + stringResource(R.string.route_title) + ": " + style.label + "  ›", fontSize = 16.sp) },
            onClick = { routePage = true })
    }
}

/** A time of day as the phone shows it (14:05, 2:05 PM). */
internal fun clockOf(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))

/** How long a recording has run, as h:mm. */
internal fun tripTime(startedMs: Long): String {
    val min = ((System.currentTimeMillis() - startedMs) / 60_000).coerceAtLeast(0)
    return "%d:%02d".format(min / 60, min % 60)
}

private fun km(m: Double): String = Units.short(m)

/** Round road sign with the speed limit. */
@Composable
internal fun LimitSign(kmh: Int, small: Boolean = false, scale: Float = 1f) {
    Box(
        Modifier.size((if (small) 40 else 54).dp * scale)
            .background(Color.White, CircleShape)
            .border((if (small) 4 else 6).dp * scale, Color(0xFFE2463C), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val shown = Units.limit(kmh)
        Text("$shown", color = Color.Black, fontSize = (if (small) 15 else if (shown >= 100) 18 else 22).sp * scale,
            fontWeight = FontWeight.Bold)
    }
}

/** Satellites seen and used, per constellation. */
data class Gnss(val visible: Int = 0, val used: Int = 0, val byName: Map<String, Pair<Int, Int>> = emptyMap(), val bestCn0: Float = 0f) {
    companion object {
        fun of(s: GnssStatus): Gnss {
            val by = LinkedHashMap<String, Pair<Int, Int>>()
            var used = 0
            var best = 0f
            for (i in 0 until s.satelliteCount) {
                val name = when (s.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"
                    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    else -> L.s(R.string.map_gnss_other)
                }
                val u = s.usedInFix(i)
                if (u) used++
                best = maxOf(best, s.getCn0DbHz(i))
                val (v0, u0) = by[name] ?: (0 to 0)
                by[name] = (v0 + 1) to (u0 + if (u) 1 else 0)
            }
            return Gnss(s.satelliteCount, used, by, best)
        }
    }
}

@Composable
private fun GpsPanel(fix: Location?, gnss: Gnss, speed: Double?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtimeNanos() } }
    Column(
        Modifier.padding(top = 6.dp).width(250.dp)
            .background(Palette.overlay, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.map_gps_title), color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (fix == null) {
            Text(stringResource(R.string.map_gps_waiting_first), color = Palette.label, fontSize = 13.sp)
        } else {
            KeyValue(stringResource(R.string.map_gps_latitude), "%.6f".format(fix.latitude))
            KeyValue(stringResource(R.string.map_gps_longitude), "%.6f".format(fix.longitude))
            KeyValue(stringResource(R.string.map_gps_accuracy), if (fix.hasAccuracy()) "±%.0f m".format(fix.accuracy) else "--")
            KeyValue(stringResource(R.string.map_gps_altitude), if (fix.hasAltitude()) "%.0f %s".format(Units.alt(fix.altitude), Units.altUnit) +
                (if (fix.hasVerticalAccuracy()) " ±%.0f".format(fix.verticalAccuracyMeters) else "") else "--")
            KeyValue(stringResource(R.string.map_gps_speed), speed?.let { "%.0f %s".format(Units.speed(it), Units.speedUnit) } ?: "--")
            KeyValue(stringResource(R.string.map_gps_bearing), if (fix.hasBearing()) "%.0f°".format(fix.bearing) else "--")
            KeyValue(stringResource(R.string.map_gps_age), "%.0f s".format((now - fix.elapsedRealtimeNanos) / 1e9).replace("-", ""))
            KeyValue(stringResource(R.string.map_gps_source), if (fix.provider == LocationManager.GPS_PROVIDER) stringResource(R.string.map_gps_src_satellites) else stringResource(R.string.map_gps_src_network))
        }
        KeyValue(stringResource(R.string.map_gps_satellites), stringResource(R.string.map_gps_sats_used, gnss.used, gnss.visible))
        for ((name, vu) in gnss.byName) KeyValue("  $name", "${vu.second}/${vu.first}")
        if (gnss.bestCn0 > 0) KeyValue(stringResource(R.string.map_gps_best), "%.0f dB-Hz".format(gnss.bestCn0))
    }
}

/** GPS quality at a glance (satellites used, accuracy); tap for the GPS panel. */
@Composable
private fun GpsButton(fix: Location?, gnss: Gnss, active: Boolean, onClick: () -> Unit) {
    val acc = fix?.takeIf { it.hasAccuracy() }?.accuracy
    val col = when {
        fix == null || acc == null -> Palette.red
        acc <= 10 -> Palette.green
        acc <= 25 -> Palette.orange
        else -> Palette.red
    }
    Column(
        Modifier.size(52.dp).background(Palette.overlay, RoundedCornerShape(14.dp))
            .border(2.dp, if (active) Palette.cyan else col, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("GPS", color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(if (fix == null) "--" else acc?.let { "±%.0f".format(it) } ?: "${gnss.used}", color = Palette.label, fontSize = 11.sp)
    }
}

/** Two stacked map sheets with the word under them, so it reads as "what the map shows". */
@Composable
private fun LayersButton(active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.size(52.dp).background(Palette.overlay, RoundedCornerShape(14.dp))
            .border(2.dp, if (active) Palette.cyan else Palette.frame, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val c = if (active) Palette.cyan else Palette.text
        Canvas(Modifier.size(width = 30.dp, height = 24.dp)) {
            val w = size.width
            val h = size.height
            fun sheet(y: Float) = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2, y - h * 0.26f); lineTo(w * 0.96f, y); lineTo(w / 2, y + h * 0.26f); lineTo(w * 0.04f, y); close()
            }
            // The lower sheet peeks out under the top one.
            drawPath(sheet(h * 0.66f), c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
            drawPath(sheet(h * 0.36f), Palette.overlay)
            drawPath(sheet(h * 0.36f), c)
        }
        Text(stringResource(R.string.map_layers), color = Palette.label, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun MapButton(label: String, active: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp)
            .background(Palette.overlay, RoundedCornerShape(14.dp))
            .border(2.dp, if (active) Palette.cyan else Palette.frame, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.cyan else Palette.text, fontSize = if (small) 12.sp else 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** MapView needs the activity's lifecycle calls to draw and to free its GL resources. */
private fun bindLifecycle(mv: MapView, lifecycle: Lifecycle) {
    var started = false
    var resumed = false
    fun sync(state: Lifecycle.State) {
        val wantStart = state.isAtLeast(Lifecycle.State.STARTED)
        val wantResume = state.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed && !wantResume) { mv.onPause(); resumed = false }
        if (started && !wantStart) { mv.onStop(); started = false }
        if (!started && wantStart) { mv.onStart(); started = true }
        if (!resumed && wantResume) { mv.onResume(); resumed = true }
    }
    val observer = LifecycleEventObserver { _, _ -> sync(lifecycle.currentState) }
    sync(lifecycle.currentState)
    lifecycle.addObserver(observer)
    mv.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) {}
        override fun onViewDetachedFromWindow(v: android.view.View) {
            lifecycle.removeObserver(observer)
            sync(Lifecycle.State.CREATED)
            mv.onDestroy()
        }
    })
}

/** File size for the Mapas list. */
internal fun mb(bytes: Long): String = if (bytes <= 0) "?" else "%.0f MB".format(bytes / 1_048_576.0)

/** How old a fix is, in milliseconds. */
private fun ageMs(loc: Location): Long =
    (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000

/** A layer in the layers list: tap switches it, long press opens its options (⋯ when it has some). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerChip(label: String, on: Boolean, options: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) Palette.amber.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (on) Palette.amber else Palette.label, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (on) "✓" else " ", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.text, fontSize = 15.sp)
        if (options) Text("⋯", color = Palette.label, fontSize = 15.sp)
    }
}

/** A layer's own options, in place of the layers list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerOptions(l: MapLayer, o: LayerOpts, contourStep: Int, zoom: Double, onShadeLive: (Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun set(n: LayerOpts) = MapStore.setLayerOpts(ctx, n)
    Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.map_layers), color = Palette.amber, fontSize = 15.sp) }
        }
        Text(l.label, color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        when (l) {
            MapLayer.SHADE -> {
                var v by remember { mutableFloatStateOf(o.shade.toFloat()) }
                Text(stringResource(R.string.layer_shade_strength, v.toInt()), color = Palette.text, fontSize = 14.sp)
                Slider(value = v, onValueChange = { v = it; onShadeLive(it.toInt()) }, valueRange = 10f..100f,
                    onValueChangeFinished = { set(o.copy(shade = v.toInt())) }, modifier = Modifier.width(280.dp))
            }
            MapLayer.CONTOURS -> {
                val z = kotlin.math.floor(zoom).toInt().coerceIn(8, 19)
                val cur = o.contourAt[z] ?: MapStyle.defaultStep(contourStep, z)
                Text(stringResource(R.string.layer_contour_at_zoom, z), color = Palette.text, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1, 10, 20, 50, 100).forEach { st ->
                        FilterChip(selected = cur == st, onClick = { set(o.copy(contourAt = o.contourAt + (z to st))) },
                            label = { Text(if (st < 0) stringResource(R.string.layer_contour_none) else stringResource(R.string.mapopts_contour_m, st)) })
                    }
                }
                if (o.contourAt.isNotEmpty()) TextButton(onClick = { set(o.copy(contourAt = emptyMap())) }) {
                    Text(stringResource(R.string.layer_contour_reset), color = Palette.amber)
                }
                Text(stringResource(R.string.layer_contour_hint), color = Palette.label, fontSize = 12.sp)
            }
            MapLayer.ROADS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoadClass.entries.forEach { r ->
                    FilterChip(selected = r !in o.roadsOff, onClick = {
                        set(o.copy(roadsOff = if (r in o.roadsOff) o.roadsOff - r else o.roadsOff + r))
                    }, label = { Text(r.label) })
                }
            }
            MapLayer.DIRT -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DirtKind.entries.forEach { d ->
                    FilterChip(selected = d !in o.dirtOff, onClick = {
                        set(o.copy(dirtOff = if (d in o.dirtOff) o.dirtOff - d else o.dirtOff + d))
                    }, label = { Text(d.label) }, leadingIcon = {
                        Box(Modifier.size(width = 18.dp, height = 4.dp).background(Color(d.color)))
                    })
                }
            }
            MapLayer.WEATHER -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WxKind.entries.forEach { k ->
                        FilterChip(selected = k !in o.wxOff, onClick = {
                            set(o.copy(wxOff = if (k in o.wxOff) o.wxOff - k else o.wxOff + k))
                        }, label = { Text(k.label) })
                    }
                }
                Text(stringResource(R.string.layer_weather_hint), color = Palette.label, fontSize = 12.sp)
            }
            else -> {}
        }
    }
}
