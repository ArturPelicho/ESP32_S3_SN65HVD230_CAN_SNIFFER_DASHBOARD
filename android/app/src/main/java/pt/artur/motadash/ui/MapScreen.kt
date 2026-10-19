package pt.artur.motadash.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.atan2
import kotlinx.coroutines.delay
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import pt.artur.motadash.FollowMode
import pt.artur.motadash.Link
import pt.artur.motadash.MapStore
import pt.artur.motadash.MapStyle
import pt.artur.motadash.MapTheme

/** Keeps the MapLibre objects between recompositions and drives the camera. */
private class MapHolder(private val arrow: Bitmap) {
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
            style = s
            drawMarker()
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

    fun zoomBy(step: Double) {
        val m = map ?: return
        userZoom = ((userZoom ?: m.cameraPosition.zoom) + step).coerceIn(3.0, 19.5)
        manualUntil = SystemClock.elapsedRealtime() + 10_000
        if (follow) update(300) else m.animateCamera(CameraUpdateFactory.zoomTo(userZoom!!), 300)
    }
}

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
    var minute by remember { mutableLongStateOf(0L) }
    val holder = remember {
        MapHolder(arrowBitmap()).also { it.mode = mode; it.autoZoom = autoZoom; it.onFollow = { on -> follow = on } }
    }

    LaunchedEffect(Unit) {
        MapStore.load(ctx)
        while (true) { delay(60_000); minute++ }
    }

    // GPS of the phone, while the map is on screen (works without the board).
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = LocationListener { loc ->
            if (loc.provider == LocationManager.GPS_PROVIDER || fix == null ||
                fix?.provider != LocationManager.GPS_PROVIDER) fix = loc
        }
        try {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { fix = it }
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            if (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
        onDispose { lm?.removeUpdates(listener) }
    }

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
    val phoneSpeed = fix?.takeIf { it.hasSpeed() }?.let { loc ->
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
                        m.uiSettings.isCompassEnabled = true
                        m.uiSettings.setCompassGravity(Gravity.TOP or Gravity.START)
                        m.uiSettings.setCompassMargins(24, 24, 0, 0)
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
        LaunchedEffect(fix) {
            val f = fix ?: return@LaunchedEffect
            holder.fix = f
            holder.update(900)
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
            Column(
                Modifier.background(Color(0xCC0B0E12), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(speed?.let { "%.0f".format(it) } ?: "--", color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("km/h", color = Palette.label, fontSize = 12.sp)
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

        if (showMaps) {
            MapsPanel(
                theme = theme, lang = lang, mode = mode, autoZoom = autoZoom,
                onTheme = { theme = it; MapStore.setTheme(ctx, it) },
                onLang = { lang = it; MapStore.setLanguage(ctx, it) },
                onMode = { mode = it; MapStore.setFollowMode(ctx, it); holder.mode = it; holder.update(600) },
                onAutoZoom = { autoZoom = it; MapStore.setAutoZoom(ctx, it); holder.autoZoom = it; holder.userZoom = null },
                onClose = { showMaps = false },
            )
        }
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
