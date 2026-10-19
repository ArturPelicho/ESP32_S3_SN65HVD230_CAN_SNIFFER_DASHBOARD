package pt.artur.motadash.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import pt.artur.motadash.FollowMode
import pt.artur.motadash.LatLon
import pt.artur.motadash.Link
import pt.artur.motadash.Track
import pt.artur.motadash.TrackFollower
import pt.artur.motadash.TrackProgress
import pt.artur.motadash.TrackStore
import pt.artur.motadash.MapStore
import pt.artur.motadash.MapStyle
import pt.artur.motadash.MapTheme

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
    var gestureAt = 0L
    /** Zoom picked by hand (buttons or pinch) and until when it beats auto zoom. */
    var userZoom: Double? = null
    var manualUntil = 0L
    private var autoLevel = 17.0
    private var zoomInSince = 0L
    private var first = true

    fun applyStyle(json: String) {
        val m = map ?: run { styleJson = null; pending = json; return }
        if (json == styleJson) return
        styleJson = json
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            s.addImage("me-arrow", arrow)
            s.addImage("radar", radar)
            s.addImage("trk-arrow", trackArrow)
            style = s
            drawMarker()
            tracks?.let { s.getSourceAs<GeoJsonSource>("tracks")?.setGeoJson(it) }
        }
    }

    fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        onFollow(on)
    }

    private fun drawMarker() {
        val loc = fix ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
        f.addNumberProperty("bearing", heading)
        style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(f)
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
        if (!follow && !gesturing && speedKmh > 10 && now - gestureAt > 10_000) setFollow(true)

        // Auto zoom: out at once when speeding up, back in only after 8 s slower.
        val target = autoZoomFor(speedKmh)
        when {
            first || target < autoLevel -> { autoLevel = target; zoomInSince = 0 }
            target > autoLevel + 0.05 -> {
                if (zoomInSince == 0L) zoomInSince = now
                if (now - zoomInSince > 8_000) { autoLevel = target; zoomInSince = 0 }
            }
            else -> zoomInSince = 0
        }
        if (autoZoom && userZoom != null && now > manualUntil) userZoom = null
        if (!follow || gesturing) return

        val zoom = userZoom ?: if (autoZoom) autoLevel else if (first) 15.0 else m.cameraPosition.zoom
        val h = view?.height ?: 0
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom)
            .bearing(if (mode == FollowMode.NORTH) 0.0 else heading.toDouble())
            .tilt(if (mode == FollowMode.THREE_D) tiltFor(speedKmh) else 0.0)
            // Marker in the lower part of the screen so more road ahead is visible.
            .padding(0.0, if (mode == FollowMode.NORTH) 0.0 else h * 0.4, 0.0, 0.0)
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), if (first) 1200 else durationMs, false)
        first = false
    }

    /** Speed limit of the road under the bike (OpenStreetMap maxspeed), null when unknown. */
    var limit: Int? = null
        private set
    private var limitMiss = 0
    var camera: CameraAlert? = null
        private set
    private var lastCameraScan = 0L

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
    fun setTracks(list: List<Track>, activeId: String?, reversed: Boolean) {
        val fs = mutableListOf<Feature>()
        for (t in list.filter { it.visible }) {
            val active = t.id == activeId
            for (l in t.lines) {
                val pts = l.map { Point.fromLngLat(it.lon, it.lat) }.let { if (active && reversed) it.reversed() else it }
                fs += Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                    addStringProperty("kind", "line"); addStringProperty("color", t.color)
                    addNumberProperty("w", t.width); addBooleanProperty("active", active)
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

    fun zoomBy(step: Double) {
        val m = map ?: return
        userZoom = ((userZoom ?: m.cameraPosition.zoom) + step).coerceIn(3.0, 19.5)
        manualUntil = SystemClock.elapsedRealtime() + 10_000
        if (follow) update(300) else m.animateCamera(CameraUpdateFactory.zoomTo(userZoom!!), 300)
    }
}

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

/** 0..180 degrees between two bearings. */
private fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

/** Zoom by speed: close in town, wider on the open road. */
private fun autoZoomFor(kmh: Double): Double = when {
    kmh <= 35 -> 17.0
    kmh <= 50 -> 16.3
    kmh < 90 -> 16.0 - (kmh - 50) / 40 * 1.4
    else -> 14.6
}

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
    var mapBearing by remember { mutableFloatStateOf(0f) }
    val tracks by TrackStore.tracks.collectAsStateWithLifecycle()
    val activeId by TrackStore.active.collectAsStateWithLifecycle()
    val reversed by TrackStore.reversed.collectAsStateWithLifecycle()
    val trackMessage by TrackStore.message.collectAsStateWithLifecycle()
    val activeTrack = tracks.firstOrNull { it.id == activeId }
    val follower = remember(activeTrack?.id, activeTrack?.lines, reversed) { activeTrack?.let { TrackFollower(it, reversed) } }
    var progress by remember { mutableStateOf<TrackProgress?>(null) }
    var minute by remember { mutableLongStateOf(0L) }
    val holder = remember {
        MapHolder(arrowBitmap(), radarBitmap(), trackArrowBitmap()).also { it.mode = mode; it.autoZoom = autoZoom; it.onFollow = { on -> follow = on } }
    }

    LaunchedEffect(Unit) {
        MapStore.load(ctx)
        TrackStore.load(ctx)
        while (true) { delay(60_000); minute++ }
    }

    // GPS of the phone, while the map is on screen and the app is in front (works without the board).
    // Keyed on resume so a permission granted later, or a trip to the background, re-registers.
    val lifeState by lifecycle.currentStateAsState()
    val resumed = lifeState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(resumed) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val allowed = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!resumed || lm == null || !allowed) return@DisposableEffect onDispose {}
        val listener = LocationListener { loc ->
            // Satellites win, but a network fix takes over once the last satellite fix is ~10 s old.
            val cur = fix
            if (loc.provider == LocationManager.GPS_PROVIDER || cur == null ||
                cur.provider != LocationManager.GPS_PROVIDER || ageMs(cur) > 10_000) fix = loc
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

    val beeper = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { beeper?.release() } }

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

    val night = when (theme) {
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
        MapTheme.AUTO -> minute.let { MapStyle.isNight(fix?.latitude ?: 39.5, fix?.longitude ?: -8.0) }
    }
    val enabled = installed.filter { it.enabled }
    val styleJson = remember(enabled, night, lang) { MapStyle.build(enabled, night, lang) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                        m.addOnCameraMoveListener { mapBearing = m.cameraPosition.bearing.toFloat() }
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
                        holder.pending?.let { holder.applyStyle(it) } ?: holder.applyStyle(styleJson)
                        holder.update(0)
                    }
                    bindLifecycle(mv, lifecycle)
                }
            },
        )

        LaunchedEffect(styleJson) { holder.applyStyle(styleJson) }
        LaunchedEffect(tracks, activeId, reversed) {
            holder.setTracks(tracks, activeId, reversed)
            if (follower == null) { progress = null; holder.setJoin(null) }
        }
        LaunchedEffect(trackMessage) {
            if (trackMessage != null && !showTracks) { delay(6000); TrackStore.clearMessage() }
        }
        LaunchedEffect(fix) {
            val f = fix ?: return@LaunchedEffect
            holder.fix = f
            holder.update(900)
            holder.scan()
            progress = follower?.locate(f.latitude, f.longitude)
            holder.setJoin(progress?.takeIf { it.offM > 30 }?.nearest)
            limit = holder.limit
            val cam = holder.camera
            if (cam != null && cam.key != camera?.key && cameraBeep) beeper?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            camera = cam
        }
        LaunchedEffect(fix?.latitude?.toInt(), fix?.longitude?.toInt()) {
            // Magnetic declination is about -1° to -3° in Portugal; recompute once per degree moved.
            fix?.let { compass.declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()).declination }
        }

        // Zoom buttons and speed, bottom left.
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton("+") { holder.zoomBy(1.0) }
            MapButton("−") { holder.zoomBy(-1.0) }
            Row(
                Modifier.background(Color(0xCC0B0E12), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val over = limit != null && speed != null && speed > limit!! + 2
                Column {
                    Text(speed?.let { "%.0f".format(it) } ?: "--", color = if (over) Palette.red else Palette.text,
                        fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("km/h", color = Palette.label, fontSize = 12.sp)
                }
                limit?.let { LimitSign(it) }
            }
        }

        // View buttons, right side: follow mode (▲ / 3D / N), follow, theme, maps.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            MapButton(mode.button, active = mode != FollowMode.NORTH) {
                mode = mode.next(); MapStore.setFollowMode(ctx, mode); holder.mode = mode
                holder.setFollow(true); holder.update(600)
            }
            MapButton("◎", active = follow) { holder.setFollow(true); holder.update(600) }
            MapButton(
                when (theme) { MapTheme.AUTO -> "AUTO"; MapTheme.DAY -> "DIA"; MapTheme.NIGHT -> "NOITE" },
                small = true,
            ) {
                theme = MapTheme.entries[(theme.ordinal + 1) % MapTheme.entries.size]; MapStore.setTheme(ctx, theme)
            }
            MapButton("☰") { showMaps = true }
        }

        // GPS quality, top left; tap for the GPS panel.
        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            val acc = fix?.takeIf { it.hasAccuracy() }?.accuracy
            val col = when {
                fix == null || acc == null -> Palette.red
                acc <= 10 -> Palette.green
                acc <= 25 -> Palette.orange
                else -> Palette.red
            }
            Text(
                if (fix == null) "GPS sem sinal" else "GPS ${gnss.used}/${gnss.visible}" + (acc?.let { " · ±%.0f m".format(it) } ?: ""),
                color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xCC0B0E12), RoundedCornerShape(8.dp))
                    .clickable { showGps = !showGps }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (showGps) GpsPanel(fix, gnss, speed)
            if (!showGps && abs(wrap180(mapBearing)) > 1f) NorthNeedle(mapBearing)
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
                Text("RADAR", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${(cam.meters + 25) / 50 * 50} m", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                cam.kmh?.let { LimitSign(it, small = true) }
            }
        }
        if (activeTrack != null) TrackBox(activeTrack, progress)
        }

        // Tracks button, top right.
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            MapButton("GPX", active = activeTrack != null, small = true) { showTracks = true }
        }
        trackMessage?.takeIf { !showTracks }?.let {
            Text(it, color = Palette.text, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    .background(Color(0xE60B0E12), RoundedCornerShape(10.dp)).padding(12.dp))
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
                Text(if (installed.isEmpty()) "Ainda não há mapas no telemóvel" else "Os mapas estão todos desligados",
                    color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Descarrega Portugal com Wi-Fi e o mapa funciona sem rede.", color = Palette.label, fontSize = 14.sp)
                Button(onClick = { showMaps = true }) { Text("Mapas") }
            }
        }

        if (showTracks) {
            TracksPanel(
                onShow = { t -> showTracks = false; holder.showTrack(t) },
                onClose = { showTracks = false; TrackStore.clearMessage() },
            )
        }

        if (showMaps) {
            MapsPanel(
                theme = theme, lang = lang, mode = mode, autoZoom = autoZoom,
                onTheme = { theme = it; MapStore.setTheme(ctx, it) },
                onLang = { lang = it; MapStore.setLanguage(ctx, it) },
                onMode = { mode = it; MapStore.setFollowMode(ctx, it); holder.mode = it; holder.update(600) },
                onAutoZoom = { autoZoom = it; MapStore.setAutoZoom(ctx, it); holder.autoZoom = it; holder.userZoom = null },
                cameraBeep = cameraBeep,
                onCameraBeep = { cameraBeep = it; MapStore.setCameraBeep(ctx, it) },
                onClose = { showMaps = false },
            )
        }
    }
}

/** Followed track: distance left, and how far off it the bike is. */
@Composable
private fun TrackBox(t: Track, p: TrackProgress?) {
    val off = p != null && p.offM > 50
    Column(
        Modifier.background(if (off) Palette.orange else Color(0xE60B0E12), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t.name, color = if (off) Color.Black else Palette.label, fontSize = 12.sp, maxLines = 1)
        when {
            p == null -> Text("À espera do GPS", color = Palette.text, fontSize = 16.sp)
            p.offM > 1000 -> Text("Trajeto a ${km(p.offM)}", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Faltam ${km(p.leftM)}", color = if (off) Color.Black else Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (off) Text("fora ${p.offM.toInt()} m", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun km(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(m / 1000)

/** Where north is on the rotated map; not touchable. */
@Composable
private fun NorthNeedle(bearing: Float) {
    Box(
        Modifier.padding(top = 8.dp).size(44.dp).background(Color(0xCC0B0E12), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp).rotate(-bearing)) {
            val w = size.width
            val h = size.height
            val north = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2, h * 0.08f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close()
            }
            val south = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2, h * 0.92f); lineTo(w * 0.68f, h / 2); lineTo(w * 0.32f, h / 2); close()
            }
            drawPath(north, Color(0xFFE2463C))
            drawPath(south, Color(0xFFDDE2E8))
        }
    }
}

/** Round road sign with the speed limit. */
@Composable
private fun LimitSign(kmh: Int, small: Boolean = false) {
    Box(
        Modifier.size(if (small) 40.dp else 54.dp)
            .background(Color.White, CircleShape)
            .border(if (small) 4.dp else 6.dp, Color(0xFFE2463C), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$kmh", color = Color.Black, fontSize = if (small) 15.sp else if (kmh >= 100) 18.sp else 22.sp,
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
                    else -> "Outros"
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
            .background(Color(0xE60B0E12), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("GPS do telemóvel", color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (fix == null) {
            Text("À espera do primeiro sinal…", color = Palette.label, fontSize = 13.sp)
        } else {
            KeyValue("Latitude", "%.6f".format(fix.latitude))
            KeyValue("Longitude", "%.6f".format(fix.longitude))
            KeyValue("Precisão", if (fix.hasAccuracy()) "±%.0f m".format(fix.accuracy) else "--")
            KeyValue("Altitude", if (fix.hasAltitude()) "%.0f m".format(fix.altitude) +
                (if (fix.hasVerticalAccuracy()) " ±%.0f".format(fix.verticalAccuracyMeters) else "") else "--")
            KeyValue("Velocidade", speed?.let { "%.0f km/h".format(it) } ?: "--")
            KeyValue("Rumo", if (fix.hasBearing()) "%.0f°".format(fix.bearing) else "--")
            KeyValue("Idade do sinal", "%.0f s".format((now - fix.elapsedRealtimeNanos) / 1e9).replace("-", ""))
            KeyValue("Fonte", if (fix.provider == LocationManager.GPS_PROVIDER) "satélites" else "rede")
        }
        KeyValue("Satélites", "${gnss.used} em uso de ${gnss.visible}")
        for ((name, vu) in gnss.byName) KeyValue("  $name", "${vu.second}/${vu.first}")
        if (gnss.bestCn0 > 0) KeyValue("Melhor sinal", "%.0f dB-Hz".format(gnss.bestCn0))
    }
}

@Composable
private fun MapButton(label: String, active: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp)
            .background(Color(0xE60B0E12), RoundedCornerShape(14.dp))
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
