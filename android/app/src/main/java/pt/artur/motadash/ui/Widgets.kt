package pt.artur.motadash.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import pt.artur.motadash.DtcState
import pt.artur.motadash.GpsInfo
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.R
import pt.artur.motadash.Telemetry

/**
 * Everything the rider can show on the Painel or on the Mapa. [refW] x [refH] (dp) is the size the
 * element is drawn at scale 1; a bigger or smaller box scales its text with it.
 */
enum class Widget(@StringRes val res: Int, val refW: Float, val refH: Float) {
    CLUSTER(R.string.w_cluster, 320f, 320f),
    SHIFT(R.string.w_shift, 400f, 16f),
    SPEED(R.string.w_speed, 160f, 80f),
    GEAR(R.string.w_gear, 80f, 80f),
    RPM(R.string.w_rpm, 130f, 60f),
    FUEL(R.string.w_fuel, 280f, 110f),
    RANGE(R.string.w_range, 140f, 70f),
    CHT(R.string.w_cht, 280f, 70f),
    FUEL_NOW(R.string.w_fuel_now, 140f, 70f),
    FUEL_AVG(R.string.w_fuel_avg, 140f, 70f),
    CLOCK(R.string.w_clock, 120f, 50f),
    LINK(R.string.w_link, 120f, 50f),
    BATTERY(R.string.w_battery, 120f, 70f),
    AIR(R.string.w_air, 120f, 70f),
    THROTTLE(R.string.w_throttle, 220f, 50f),
    TRIP(R.string.w_trip, 220f, 90f),
    COORDS(R.string.w_coords, 190f, 50f),
    ALTITUDE(R.string.w_altitude, 120f, 70f),
    FAULTS(R.string.w_faults, 280f, 56f),
}

/** Where an element sits, as fractions of its screen (so it fits any phone). */
data class Place(val x: Float, val y: Float, val w: Float, val h: Float)

/** What the elements show; each screen fills it from the board and, on the map, the phone's GPS. */
class WidgetData(
    val t: Telemetry?, val status: LinkStatus, val gps: GpsInfo, val dtc: DtcState,
    val blinkOn: Boolean, val now: Long, val tankCapacityL: Double,
    val speed: Double?, val limit: Int?, val lat: Double?, val lon: Double?, val altM: Double?,
)

/** Board data plus a 4 Hz blink and a clock; [speed] null means the board's speed. */
@Composable
fun widgetData(
    speed: Double? = null, limit: Int? = null, lat: Double? = null, lon: Double? = null, altM: Double? = null,
): WidgetData {
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val status by Link.status.collectAsStateWithLifecycle()
    val gps by Link.gps.collectAsStateWithLifecycle()
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    val params by Link.params.collectAsStateWithLifecycle()
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(250), RepeatMode.Reverse), label = "blink",
    )
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    return WidgetData(
        t, status, gps, dtc, blink > 0.5f, now, params["TANK_DL"]?.value?.div(10.0) ?: 16.0,
        speed ?: t?.displaySpeed, limit, lat ?: gps.lat, lon ?: gps.lon, altM ?: gps.altM,
    )
}

/** Which elements each screen shows, and where, per orientation. Kept in the app's preferences. */
object LayoutStore {
    const val DASH = "dash"
    const val MAP = "map"

    private val loaded = HashMap<String, Map<Widget, Place?>>()
    /** Bumped on every change; reading it in [get] makes composables redraw on changes. */
    private var version by mutableIntStateOf(0)

    private fun key(screen: String, land: Boolean) = "layout_${screen}_${if (land) "land" else "port"}"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Shown elements; a null place means "not placed yet" (the board puts it in the middle). */
    fun get(ctx: Context, screen: String, land: Boolean): Map<Widget, Place?> {
        version
        return loaded.getOrPut(key(screen, land)) {
            prefs(ctx).getString(key(screen, land), null)?.let { parse(it) } ?: defaults(screen, land)
        }
    }

    fun set(ctx: Context, screen: String, land: Boolean, layout: Map<Widget, Place?>) {
        val k = key(screen, land)
        loaded[k] = layout
        version++
        val o = JSONObject()
        layout.forEach { (w, p) ->
            o.put(w.name, p?.let { JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.w.toDouble(), it.h.toDouble())) } ?: JSONObject.NULL)
        }
        prefs(ctx).edit().putString(k, o.toString()).apply()
    }

    fun place(ctx: Context, screen: String, land: Boolean, w: Widget, p: Place) =
        set(ctx, screen, land, get(ctx, screen, land) + (w to p))

    fun toggle(ctx: Context, screen: String, land: Boolean, w: Widget, on: Boolean) {
        val cur = get(ctx, screen, land)
        set(ctx, screen, land, if (on) cur + (w to defaults(screen, land)[w]) else cur - w)
    }

    fun reset(ctx: Context, screen: String, land: Boolean) {
        val k = key(screen, land)
        loaded[k] = defaults(screen, land)
        version++
        prefs(ctx).edit().remove(k).apply()
    }

    private fun parse(s: String): Map<Widget, Place?>? = runCatching {
        val o = JSONObject(s)
        val out = LinkedHashMap<Widget, Place?>()
        o.keys().forEach { name ->
            val w = runCatching { Widget.valueOf(name) }.getOrNull() ?: return@forEach
            val a = o.optJSONArray(name)
            out[w] = a?.let { Place(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) }
        }
        out
    }.getOrNull()

    /** The Painel as it was before it could be changed, and the speed alone on the map. */
    fun defaults(screen: String, land: Boolean): Map<Widget, Place> = when {
        screen == MAP && land -> mapOf(Widget.SPEED to Place(0.09f, 0.79f, 0.2f, 0.2f))
        screen == MAP -> mapOf(Widget.SPEED to Place(0.19f, 0.88f, 0.42f, 0.11f))
        land -> linkedMapOf(
            Widget.SHIFT to Place(0f, 0f, 0.94f, 0.05f),
            Widget.CLUSTER to Place(0f, 0.07f, 0.37f, 0.93f),
            Widget.FUEL to Place(0.38f, 0.07f, 0.35f, 0.3f),
            Widget.CHT to Place(0.38f, 0.39f, 0.35f, 0.21f),
            Widget.FUEL_NOW to Place(0.38f, 0.62f, 0.17f, 0.22f),
            Widget.FUEL_AVG to Place(0.56f, 0.62f, 0.17f, 0.22f),
            Widget.FAULTS to Place(0.38f, 0.86f, 0.35f, 0.14f),
            Widget.CLOCK to Place(0.74f, 0.07f, 0.26f, 0.16f),
            Widget.BATTERY to Place(0.74f, 0.25f, 0.125f, 0.19f),
            Widget.AIR to Place(0.875f, 0.25f, 0.125f, 0.19f),
            Widget.THROTTLE to Place(0.74f, 0.46f, 0.26f, 0.16f),
            Widget.TRIP to Place(0.74f, 0.64f, 0.26f, 0.36f),
        )
        else -> linkedMapOf(
            Widget.SHIFT to Place(0f, 0f, 0.88f, 0.025f),
            Widget.CLUSTER to Place(0f, 0.035f, 1f, 0.43f),
            Widget.FAULTS to Place(0f, 0.47f, 1f, 0.07f),
            Widget.FUEL to Place(0f, 0.55f, 0.5f, 0.16f),
            Widget.CHT to Place(0.5f, 0.55f, 0.5f, 0.16f),
            Widget.FUEL_NOW to Place(0f, 0.72f, 0.25f, 0.12f),
            Widget.FUEL_AVG to Place(0.25f, 0.72f, 0.25f, 0.12f),
            Widget.CLOCK to Place(0.5f, 0.72f, 0.5f, 0.12f),
            Widget.BATTERY to Place(0f, 0.85f, 0.25f, 0.15f),
            Widget.AIR to Place(0.25f, 0.85f, 0.25f, 0.15f),
            Widget.TRIP to Place(0.5f, 0.85f, 0.5f, 0.15f),
        )
    }
}

@Composable
fun isLandscape() = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The screen's elements at their places. While [editing], each one drags to a new place and its
 * corner handle resizes it; touches elsewhere still reach what is underneath (the map).
 */
@Composable
fun WidgetBoard(screen: String, data: WidgetData, editing: Boolean, onMap: Boolean, onLock: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val land = isLandscape()
    val layout = LayoutStore.get(ctx, screen, land)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val bw = with(density) { maxWidth.toPx() }
        val bh = with(density) { maxHeight.toPx() }
        layout.forEach { (w, stored) ->
            val start = stored ?: with(density) {
                val pw = (w.refW.dp.toPx() / bw).coerceAtMost(0.9f)
                val ph = (w.refH.dp.toPx() / bh).coerceAtMost(0.9f)
                Place((1 - pw) / 2, (1 - ph) / 2, pw, ph)
            }
            var p by remember(w, stored, land) { mutableStateOf(start) }
            fun save() = LayoutStore.place(ctx, screen, land, w, snap(p))
            Box(
                Modifier
                    .offset { IntOffset((p.x * bw).roundToInt(), (p.y * bh).roundToInt()) }
                    .size(with(density) { (p.w * bw).toDp() }, with(density) { (p.h * bh).toDp() })
                    .then(if (editing) Modifier
                        .border(2.dp, Palette.cyan, RoundedCornerShape(10.dp))
                        .pointerInput(w, land) {
                            detectDragGestures(onDragEnd = { save() }) { change, d ->
                                change.consume()
                                p = p.copy(x = (p.x + d.x / bw).coerceIn(0f, 1f - p.w), y = (p.y + d.y / bh).coerceIn(0f, 1f - p.h))
                            }
                        } else Modifier),
            ) {
                WidgetCell(w, data, onMap, editing)
                if (editing) {
                    Text(stringResource(w.res), color = Palette.cyan, fontSize = 10.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).background(Palette.overlay, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp))
                    // Resize handle.
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(30.dp)
                            .background(Palette.cyan, RoundedCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                            .pointerInput(w, land) {
                                detectDragGestures(onDragEnd = { save() }) { change, d ->
                                    change.consume()
                                    p = p.copy(w = (p.w + d.x / bw).coerceIn(0.04f, 1f - p.x), h = (p.h + d.y / bh).coerceIn(0.02f, 1f - p.y))
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("⇲", color = Palette.bg, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (editing) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Palette.overlay, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.w_move_hint), color = Palette.text, fontSize = 13.sp)
                Button(onClick = onLock) { Text(stringResource(R.string.w_lock)) }
            }
        }
    }
}

/** Half a percent grid, so elements line up without pixel hunting. */
private fun snap(p: Place): Place {
    fun s(v: Float) = (v * 200).roundToInt() / 200f
    return Place(s(p.x), s(p.y), s(p.w), s(p.h))
}

/** The small fixed button in the corner of a tab that opens its screen settings. */
@Composable
fun ScreenSettingsButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp)
            .background(Palette.overlay, RoundedCornerShape(12.dp))
            .border(1.5.dp, Palette.frame, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("⚙", color = Palette.text, fontSize = 20.sp) }
}

/** Toggles for the screen's elements, move mode, reset; [extra] adds the screen's own settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetSheet(screen: String, title: String, onMove: () -> Unit, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val land = isLandscape()
    val layout = LayoutStore.get(ctx, screen, land)
    Column(
        Modifier.fillMaxSize().background(Palette.sheet)
            // Keep touches off whatever is underneath (the map).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.maps_close)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.w_shown), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Widget.entries.forEach { w ->
                    val on = w in layout
                    FilterChip(selected = on, onClick = { LayoutStore.toggle(ctx, screen, land, w, !on) },
                        label = { Text(stringResource(w.res)) })
                }
            }
            Text(stringResource(if (land) R.string.w_orientation_land else R.string.w_orientation_port),
                color = Palette.label, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMove) { Text(stringResource(R.string.w_move)) }
                OutlinedButton(onClick = { LayoutStore.reset(ctx, screen, land) }) { Text(stringResource(R.string.w_reset)) }
            }
            extra()
        }
    }
}

// ---- The elements ----

/** One element in its box, text scaled to the box. */
@Composable
private fun WidgetCell(w: Widget, d: WidgetData, onMap: Boolean, editing: Boolean) {
    val bg = if (onMap) Palette.overlaySoft else Palette.panel
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = minOf(maxWidth.value / w.refW, maxHeight.value / w.refH).coerceIn(0.4f, 4f)
        when (w) {
            Widget.CLUSTER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Cluster(d) }
            Widget.SHIFT -> ShiftLights(d, Modifier.fillMaxSize())
            Widget.SPEED -> SpeedBox(d, u, bg)
            Widget.GEAR -> Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size((66 * u).dp).border(3.dp, Palette.cyan, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    val g = d.t?.gear ?: 0
                    Text(if (g == 0) "-" else "$g", color = Palette.cyan, fontSize = (46 * u).sp, fontWeight = FontWeight.Bold)
                }
            }
            Widget.RPM -> Cell(bg, u) {
                val shifting = d.t?.shift == true
                Text("${d.t?.rpm ?: "--"}", color = if (shifting && d.blinkOn) Palette.red else Palette.text,
                    fontSize = (28 * u).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("RPM", color = Palette.label, fontSize = (11 * u).sp)
            }
            Widget.FUEL -> Cell(bg, u) {
                val t = d.t
                val tank = t?.tankL
                val frac = tank?.let { (it / d.tankCapacityL).coerceIn(0.0, 1.0) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("E", color = Palette.red, fontSize = (14 * u).sp, fontWeight = FontWeight.Bold)
                    SegmentBar(frac, 10, Modifier.weight(1f).height((22 * u).dp).padding(horizontal = 6.dp)) { i ->
                        if (i < 2) Palette.red else if (i < 3) Palette.orange else Palette.green
                    }
                    Text("F", color = Palette.label, fontSize = (14 * u).sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    BigValue(fmt(tank, 1), "L", Palette.text, u, 26f)
                    BigValue(fmt(t?.rangeKm, 0), "km", rangeColor(d), u, 26f)
                }
                Text(stringResource(R.string.dash_range_at, fmt(t?.rangeL100, 2)), color = Palette.label, fontSize = (11 * u).sp, maxLines = 1)
            }
            Widget.RANGE -> ValueTile(stringResource(R.string.w_range), fmt(d.t?.rangeKm, 0), "km", rangeColor(d), bg, u)
            Widget.CHT -> {
                val cht = d.t?.chtC
                val critical = d.t?.chtCritical == true
                Cell(if (critical && d.blinkOn) Palette.red else bg, u) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (critical) R.string.dash_stop_engine else R.string.dash_head_temp),
                            color = if (critical) Palette.text else Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        BigValue(fmt(cht, 0), "°C", if (critical) Palette.text else chtColor(cht), u, 24f)
                    }
                    val frac = cht?.let { ((it - CHT_MIN) / (CHT_MAX - CHT_MIN)).coerceIn(0.0, 1.0) }
                    SegmentBar(frac, 13, Modifier.fillMaxWidth().height((14 * u).dp)) { i ->
                        chtColor(CHT_MIN + (i + 0.5) * (CHT_MAX - CHT_MIN) / 13)
                    }
                }
            }
            Widget.FUEL_NOW -> {
                val t = d.t
                val v = when {
                    t == null || !t.fuelValid -> "--"
                    t.fuelCut -> "0.0"
                    else -> fmt(t.instant, 1)
                }
                ValueTile(stringResource(if (t?.fuelCut == true) R.string.dash_fuel_cut else R.string.dash_now), v,
                    if (t?.instPerKm == true) "L/100" else "L/h", if (t?.fuelCut == true) Palette.cyan else Palette.yellow, bg, u)
            }
            Widget.FUEL_AVG -> ValueTile(stringResource(R.string.dash_average), fmt(d.t?.average, 1),
                if (d.t?.avgPerKm == true) "L/100" else "L/h", Palette.text, bg, u)
            Widget.CLOCK -> Cell(bg, u, center = true) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d.now)), color = Palette.text,
                    fontSize = (28 * u).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Widget.LINK -> Cell(bg, u, center = true) {
                val on = d.status == LinkStatus.CONNECTED
                Text(stringResource(R.string.dash_bike) + if (on) " ●" else " ○", color = if (on) Palette.green else Palette.orange,
                    fontSize = (13 * u).sp, maxLines = 1)
                Text("GPS ${fmt(d.gps.hz, 0)} Hz", color = if (d.gps.hz >= 1.0) Palette.green else Palette.dim, fontSize = (13 * u).sp)
            }
            Widget.BATTERY -> ValueTile(stringResource(R.string.dash_battery), fmt(d.t?.batteryV, 1), "V", batteryColor(d.t?.batteryV), bg, u)
            Widget.AIR -> ValueTile(stringResource(R.string.dash_air), fmt(d.t?.iatC, 0), "°C", Palette.text, bg, u)
            Widget.THROTTLE -> Cell(bg, u) {
                val thr = d.t?.throttlePct
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.dash_throttle), color = Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold)
                    Text("${fmt(thr, 0)} %", color = Palette.text, fontSize = (14 * u).sp)
                }
                SegmentBar(thr?.div(100.0), 10, Modifier.fillMaxWidth().height((10 * u).dp).padding(top = 2.dp)) { Palette.green }
            }
            Widget.TRIP -> Cell(bg, u) {
                val t = d.t
                Text(stringResource(R.string.dash_trip), color = Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    BigValue(fmt(t?.distKm, 1), "km", Palette.text, u, 20f)
                    BigValue(fmt(t?.usedL, 2), "L", Palette.text, u, 20f)
                }
                Text(stringResource(R.string.dash_since_refuel, fmt(t?.fillKm, 0), fmt(t?.fillAvg, 2)),
                    color = Palette.label, fontSize = (11 * u).sp, maxLines = 1)
                if (t != null && !t.gearsComplete) {
                    Text(stringResource(R.string.dash_learning_gears, t.gearsFound, t.learnS), color = Palette.label,
                        fontSize = (10 * u).sp, maxLines = 1)
                }
            }
            Widget.COORDS -> Cell(bg, u, center = true) {
                if (d.lat == null || d.lon == null) Text("--", color = Palette.dim, fontSize = (16 * u).sp)
                else {
                    Text(dms(d.lat, "N", "S"), color = Palette.text, fontSize = (14 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Text(dms(d.lon, "E", "W"), color = Palette.text, fontSize = (14 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            Widget.ALTITUDE -> ValueTile(stringResource(R.string.w_altitude), fmt(d.altM, 0), "m", Palette.text, bg, u)
            Widget.FAULTS -> {
                val n = d.dtc.total
                if (n > 0 || d.dtc.mil == true) Cell(if (d.blinkOn) Palette.orange else bg, u) {
                    Text(stringResource(R.string.dash_engine_fault), color = Palette.text, fontSize = (13 * u).sp, fontWeight = FontWeight.Bold)
                    Text(if (n > 0) stringResource(R.string.dash_fault_codes, n, d.dtc.groups.values.flatten().take(3).joinToString(" "))
                        else stringResource(R.string.dash_mil_on), color = Palette.text, fontSize = (12 * u).sp, maxLines = 1)
                } else if (editing) Cell(bg.copy(alpha = 0.5f), u, center = true) {
                    Text(stringResource(R.string.w_faults_hint), color = Palette.label, fontSize = (11 * u).sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private const val CHT_MIN = 40.0
private const val CHT_MAX = 170.0

private fun rangeColor(d: WidgetData): Color {
    val r = d.t?.rangeKm
    return when {
        r == null -> Palette.dim
        r < 30 -> if (d.blinkOn) Palette.red else Palette.text
        r < 60 -> Palette.orange
        else -> Palette.green
    }
}

/** 39°12.345' N: what a rider reads out over the phone. */
private fun dms(v: Double, pos: String, neg: String): String {
    val a = abs(v)
    val deg = a.toInt()
    return String.format(Locale.US, "%d°%06.3f' %s", deg, (a - deg) * 60, if (v >= 0) pos else neg)
}

@Composable
private fun Cell(bg: Color, u: Float, center: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = (10 * u).dp, vertical = (6 * u).dp),
        verticalArrangement = if (center) Arrangement.Center else Arrangement.SpaceEvenly,
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) { content() }
}

@Composable
private fun ValueTile(label: String, value: String, unit: String, color: Color, bg: Color, u: Float) = Cell(bg, u) {
    Text(label, color = Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    BigValue(value, unit, color, u, 28f)
}

@Composable
private fun SpeedBox(d: WidgetData, u: Float, bg: Color) {
    val speed = d.speed
    val over = d.limit != null && speed != null && speed > d.limit + 2
    Row(
        Modifier.fillMaxSize().background(bg, RoundedCornerShape(12.dp)).padding(horizontal = (12 * u).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed?.let { "%.0f".format(it) } ?: "--", color = if (over) Palette.red else Palette.text,
                fontSize = (40 * u).sp, fontWeight = FontWeight.Bold, lineHeight = (42 * u).sp)
            Text("km/h", color = Palette.label, fontSize = (12 * u).sp)
        }
        d.limit?.let { LimitSign(it, scale = u) }
    }
}
