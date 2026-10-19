package pt.artur.motadash.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
import kotlinx.coroutines.delay
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
import pt.artur.motadash.Link
import pt.artur.motadash.MapStore
import pt.artur.motadash.MapStyle
import pt.artur.motadash.MapTheme

/** Keeps the MapLibre objects between recompositions. */
private class MapHolder(private val arrow: Bitmap) {
    var map: MapLibreMap? = null
    var style: Style? = null
    private var styleJson: String? = null
    private var lastFix: Location? = null
    private var lastBearing = 0f

    fun applyStyle(json: String) {
        val m = map ?: run { styleJson = null; pending = json; return }
        if (json == styleJson) return
        styleJson = json
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            s.addImage("me-arrow", arrow)
            style = s
            lastFix?.let { showPosition(it) }
        }
    }

    var pending: String? = null

    fun showPosition(loc: Location) {
        lastFix = loc
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) lastBearing = loc.bearing
        val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
        f.addNumberProperty("bearing", lastBearing)
        style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(f)
    }

    fun follow(northUp: Boolean, animate: Boolean) {
        val m = map ?: return
        val loc = lastFix ?: return
        val pos = CameraPosition.Builder(m.cameraPosition)
            .target(LatLng(loc.latitude, loc.longitude))
            .bearing(if (northUp) 0.0 else lastBearing.toDouble())
            .build()
        if (animate) m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900, false)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }
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
    var showMaps by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var northUp by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var minute by remember { mutableLongStateOf(0L) }
    val holder = remember { MapHolder(arrowBitmap()) }

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
                    mv.onCreate(null)
                    mv.getMapAsync { m ->
                        holder.map = m
                        m.uiSettings.isLogoEnabled = false
                        m.uiSettings.isAttributionEnabled = false
                        m.uiSettings.isCompassEnabled = true
                        m.uiSettings.setCompassMargins(0, 120, 24, 0)
                        m.cameraPosition = CameraPosition.Builder().target(LatLng(39.6, -8.0)).zoom(6.0).build()
                        m.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
                        }
                        holder.pending?.let { holder.applyStyle(it) } ?: holder.applyStyle(styleJson)
                        fix?.let { holder.showPosition(it); holder.follow(northUp, animate = false) }
                    }
                    bindLifecycle(mv, lifecycle)
                }
            },
        )

        LaunchedEffect(styleJson) { holder.applyStyle(styleJson) }
        LaunchedEffect(fix, follow, northUp) {
            val f = fix ?: return@LaunchedEffect
            holder.showPosition(f)
            if (follow) holder.follow(northUp, animate = true)
        }
        var firstZoom by remember { mutableStateOf(true) }
        LaunchedEffect(fix != null) {
            // First fix: zoom in from the whole-country view.
            if (fix != null && firstZoom) {
                firstZoom = false
                holder.map?.let { m ->
                    m.easeCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(m.cameraPosition).target(LatLng(fix!!.latitude, fix!!.longitude)).zoom(15.0).build()), 1200)
                }
            }
        }

        // Speed, bottom left.
        val speed = telemetry?.displaySpeed ?: fix?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6 }
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp)
                .background(Color(0xCC0B0E12), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(speed?.let { "%.0f".format(it) } ?: "--", color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = Palette.label, fontSize = 12.sp)
        }

        // Buttons, right side.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            MapButton("+") { holder.map?.animateCamera(CameraUpdateFactory.zoomIn()) }
            MapButton("−") { holder.map?.animateCamera(CameraUpdateFactory.zoomOut()) }
            MapButton(if (northUp) "N" else "▲", active = !northUp) { northUp = !northUp }
            MapButton("◎", active = follow) { follow = true; holder.follow(northUp, animate = true) }
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
                theme = theme, lang = lang,
                onTheme = { theme = it; MapStore.setTheme(ctx, it) },
                onLang = { lang = it; MapStore.setLanguage(ctx, it) },
                onClose = { showMaps = false },
            )
        }
    }
}

@Composable
private fun MapButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp)
            .background(Color(0xE60B0E12), RoundedCornerShape(14.dp))
            .border(2.dp, if (active) Palette.cyan else Palette.frame, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.cyan else Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
