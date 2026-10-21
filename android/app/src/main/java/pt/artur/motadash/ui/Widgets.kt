package pt.artur.motadash.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import pt.artur.motadash.Units
import pt.artur.motadash.Trip
import pt.artur.motadash.Trips
import pt.artur.motadash.Attitude
import pt.artur.motadash.ElevProfile
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
 * Everything the rider can show on the Painel or on the Mapa. Each takes [cols] x [rows] slots of the
 * screen's grid ([SHIFT] is a strip along the top instead). [refW] x [refH] (dp) is the box its text is
 * drawn for at scale 1; a bigger or smaller slot scales it.
 */
enum class Widget(@StringRes val res: Int, val refW: Float, val refH: Float, val cols: Int, val rows: Int) {
    CLUSTER(R.string.w_cluster, 320f, 320f, 3, 5),
    SHIFT(R.string.w_shift, 400f, 16f, 0, 0),
    SPEED(R.string.w_speed, 160f, 64f, 2, 1),
    GEAR(R.string.w_gear, 80f, 80f, 1, 1),
    RPM(R.string.w_rpm, 130f, 60f, 1, 1),
    FUEL(R.string.w_fuel, 280f, 110f, 2, 2),
    RANGE(R.string.w_range, 140f, 70f, 1, 1),
    CHT(R.string.w_cht, 280f, 70f, 2, 1),
    FUEL_NOW(R.string.w_fuel_now, 140f, 70f, 1, 1),
    FUEL_AVG(R.string.w_fuel_avg, 140f, 70f, 1, 1),
    CLOCK(R.string.w_clock, 120f, 50f, 1, 1),
    LINK(R.string.w_link, 120f, 50f, 1, 1),
    BATTERY(R.string.w_battery, 140f, 70f, 1, 1),
    AIR(R.string.w_air, 140f, 70f, 1, 1),
    THROTTLE(R.string.w_throttle, 220f, 50f, 2, 1),
    TRIP(R.string.w_trip, 220f, 90f, 2, 2),
    TRIP_A(R.string.w_trip_a, 220f, 90f, 2, 2),
    TRIP_B(R.string.w_trip_b, 220f, 90f, 2, 2),
    ODO(R.string.w_odo, 160f, 70f, 1, 1),
    COORDS(R.string.w_coords, 190f, 50f, 2, 1),
    ALTITUDE(R.string.w_altitude, 140f, 70f, 1, 1),
    FAULTS(R.string.w_faults, 280f, 56f, 2, 1),
    NORTH(R.string.w_north, 120f, 120f, 1, 2),
    ATTITUDE(R.string.w_attitude, 240f, 120f, 2, 2),
    /** Map only: the followed track's height profile, a strip along the bottom. */
    PROFILE(R.string.w_profile, 400f, 64f, 0, 0),
}

/** Slot size; Medium is 120 x 60 dp. */
enum class SlotSize(val k: Float, @StringRes val res: Int) {
    SMALL(0.8f, R.string.w_size_s), MEDIUM(1f, R.string.w_size_m), LARGE(1.3f, R.string.w_size_l)
}

/** Where the map's buttons sit; they arrange themselves along that edge. */
enum class BarSide(@StringRes val res: Int) {
    RIGHT(R.string.w_bar_right), LEFT(R.string.w_bar_left), TOP(R.string.w_bar_top), BOTTOM(R.string.w_bar_bottom)
}

/**
 * The map's buttons, in bar order; the bar fills its outer line first. Settings can't be hidden
 * (it is the way back to the others).
 */
enum class MapCtl(@StringRes val res: Int, val hideable: Boolean = true) {
    SETTINGS(R.string.w_btn_settings, hideable = false),
    MODE(R.string.w_btn_mode), FOLLOW(R.string.w_btn_follow), ZOOM_IN(R.string.w_btn_zoom_in), ZOOM_OUT(R.string.w_btn_zoom_out),
    GPS(R.string.w_btn_gps), DAYNIGHT(R.string.w_btn_daynight),
    LAYERS(R.string.w_btn_layers), GPX(R.string.w_btn_gpx), REC(R.string.w_btn_rec), MAPS(R.string.w_btn_maps),
}

/** What the elements show; each screen fills it from the board and, on the map, the phone's GPS. */
class WidgetData(
    val t: Telemetry?, val status: LinkStatus, val gps: GpsInfo, val dtc: DtcState,
    val blinkOn: Boolean, val now: Long, val tankCapacityL: Double,
    val speed: Double?, val limit: Int?, val lat: Double?, val lon: Double?, val altM: Double?,
    /** The map's rotation and tilt (degrees), for the north element; null off the map (it uses the phone's compass). */
    val bearing: Float? = null, val tilt: Float? = null,
    /** The followed track's heights and how far along it the bike is, for the profile strip. */
    val profile: ElevProfile? = null, val doneM: Double? = null,
)

/** Board data plus a 4 Hz blink and a clock; [speed] null means the board's speed. */
@Composable
fun widgetData(
    speed: Double? = null, limit: Int? = null, lat: Double? = null, lon: Double? = null, altM: Double? = null,
    bearing: Float? = null, tilt: Float? = null, profile: ElevProfile? = null, doneM: Double? = null,
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
        speed ?: t?.displaySpeed, limit, lat ?: gps.lat, lon ?: gps.lon, altM ?: gps.altM, bearing, tilt, profile, doneM,
    )
}

/** A rectangle of slots: column, row, width and height in slots. */
data class Slot(val c: Int, val r: Int, val w: Int, val h: Int) {
    fun overlaps(o: Slot) = c < o.c + o.w && o.c < c + w && r < o.r + o.h && o.r < r + h
}

/**
 * Which elements each screen shows (in order), the slot each one sits in per slot size, the slot size,
 * the see-through level, and the map's button bar. Kept in the app's preferences, per screen and orientation.
 */
object LayoutStore {
    const val DASH = "dash"
    const val MAP = "map"

    /** Bumped on every change; reading it makes composables redraw on changes. */
    private var version by mutableIntStateOf(0)
    private val movingState = mutableStateMapOf<String, Boolean>()
    /** Shown elements the board found no room for, per screen (the settings say so). */
    val unplaced = mutableStateMapOf<String, Set<Widget>>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun orient(land: Boolean) = if (land) "land" else "port"
    private fun changed() { version++ }

    /** The screen's layout as it was when move mode started, for Revert. */
    private val snapshot = HashMap<String, Map<String, String>>()
    private fun layoutKey(screen: String, k: String) = k.startsWith("slots_${screen}_") || k.startsWith("shown_${screen}_")

    fun moving(screen: String) = movingState[screen] == true

    fun startMoving(ctx: Context, screen: String) {
        snapshot[screen] = prefs(ctx).all.filter { (k, v) -> layoutKey(screen, k) && v is String }.mapValues { it.value as String }
        movingState[screen] = true
    }

    /** Keeps the new places (they are saved as they change). */
    fun saveMoving(screen: String) { movingState[screen] = false; snapshot.remove(screen) }

    /** Puts every element back where it was when move mode started. */
    fun revertMoving(ctx: Context, screen: String) {
        val old = snapshot.remove(screen)
        if (old != null) {
            val e = prefs(ctx).edit()
            prefs(ctx).all.keys.filter { layoutKey(screen, it) }.forEach { e.remove(it) }
            old.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
            changed()
        }
        movingState[screen] = false
    }

    fun shown(ctx: Context, screen: String, land: Boolean): List<Widget> {
        version
        val key = "shown_${screen}_${orient(land)}"
        val s = prefs(ctx).getString(key, null) ?: return defaultShown(screen, land)
        val list = s.split(',').mapNotNull { n -> Widget.entries.firstOrNull { it.name == n } }
        // The track profile came later: it joins maps set up before it, once.
        if (screen == MAP && !prefs(ctx).getBoolean("${key}_profile", false)) {
            val l = list - Widget.PROFILE + Widget.PROFILE
            prefs(ctx).edit().putBoolean("${key}_profile", true).putString(key, l.joinToString(",") { it.name }).apply()
            return l
        }
        return list
    }

    private fun setShown(ctx: Context, screen: String, land: Boolean, list: List<Widget>) {
        prefs(ctx).edit().putString("shown_${screen}_${orient(land)}", list.joinToString(",") { it.name }).apply()
        changed()
    }

    /** On: joins at the end and takes the first free slot. */
    fun toggle(ctx: Context, screen: String, land: Boolean, w: Widget, on: Boolean) {
        val cur = shown(ctx, screen, land)
        setShown(ctx, screen, land, if (on) cur - w + w else cur - w)
    }

    fun size(ctx: Context, screen: String): SlotSize {
        version
        return SlotSize.entries.firstOrNull { it.name == prefs(ctx).getString("size_$screen", null) } ?: SlotSize.MEDIUM
    }
    fun setSize(ctx: Context, screen: String, s: SlotSize) { prefs(ctx).edit().putString("size_$screen", s.name).apply(); changed() }

    /** Opacity of the elements' backgrounds, 0.2 (almost see-through) to 1. */
    fun alpha(ctx: Context, screen: String): Float {
        version
        return prefs(ctx).getFloat("alpha_$screen", if (screen == MAP) 0.8f else 1f)
    }
    fun setAlpha(ctx: Context, screen: String, a: Float) { prefs(ctx).edit().putFloat("alpha_$screen", a).apply(); changed() }

    fun positions(ctx: Context, screen: String, land: Boolean, size: SlotSize): Map<Widget, Pair<Int, Int>> {
        version
        val s = prefs(ctx).getString("slots_${screen}_${orient(land)}_${size.name}", null)
            ?: return if (size == SlotSize.MEDIUM) defaultPositions(screen, land) else emptyMap()
        return runCatching {
            val o = JSONObject(s)
            val out = HashMap<Widget, Pair<Int, Int>>()
            o.keys().forEach { n ->
                val w = Widget.entries.firstOrNull { it.name == n } ?: return@forEach
                val a = o.getJSONArray(n)
                out[w] = a.getInt(0) to a.getInt(1)
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun setPositions(ctx: Context, screen: String, land: Boolean, size: SlotSize, p: Map<Widget, Pair<Int, Int>>) {
        val o = JSONObject()
        p.forEach { (w, cr) -> o.put(w.name, JSONArray(listOf(cr.first, cr.second))) }
        prefs(ctx).edit().putString("slots_${screen}_${orient(land)}_${size.name}", o.toString()).apply()
        changed()
    }

    /** Back to the original elements and places for this orientation (size and see-through stay). */
    fun reset(ctx: Context, screen: String, land: Boolean) {
        val e = prefs(ctx).edit().remove("shown_${screen}_${orient(land)}")
        SlotSize.entries.forEach { e.remove("slots_${screen}_${orient(land)}_${it.name}") }
        e.apply()
        changed()
    }

    fun barSide(ctx: Context): BarSide {
        version
        return BarSide.entries.firstOrNull { it.name == prefs(ctx).getString("bar_side", null) } ?: BarSide.RIGHT
    }
    fun setBarSide(ctx: Context, s: BarSide) { prefs(ctx).edit().putString("bar_side", s.name).apply(); changed() }

    fun buttons(ctx: Context): List<MapCtl> {
        version
        val hidden = prefs(ctx).getStringSet("bar_hidden", emptySet()) ?: emptySet()
        return MapCtl.entries.filter { !it.hideable || it.name !in hidden }
    }
    fun setButton(ctx: Context, b: MapCtl, on: Boolean) {
        val hidden = (prefs(ctx).getStringSet("bar_hidden", emptySet()) ?: emptySet()).toMutableSet()
        if (on) hidden -= b.name else hidden += b.name
        prefs(ctx).edit().putStringSet("bar_hidden", hidden).apply()
        changed()
    }

    fun defaultShown(screen: String, land: Boolean): List<Widget> = when {
        screen == MAP -> listOf(Widget.SPEED, Widget.PROFILE)
        land -> listOf(Widget.SHIFT, Widget.CLUSTER, Widget.FUEL, Widget.CHT, Widget.FUEL_NOW, Widget.FUEL_AVG, Widget.FAULTS,
            Widget.CLOCK, Widget.LINK, Widget.BATTERY, Widget.AIR, Widget.THROTTLE, Widget.TRIP)
        else -> listOf(Widget.SHIFT, Widget.CLUSTER, Widget.FUEL, Widget.CLOCK, Widget.BATTERY, Widget.CHT, Widget.AIR,
            Widget.FUEL_NOW, Widget.FUEL_AVG, Widget.LINK, Widget.TRIP, Widget.FAULTS)
    }

    /** The original Painel on a medium grid (7 x 5 landscape, 3 wide portrait); anything that doesn't fit flows. */
    private fun defaultPositions(screen: String, land: Boolean): Map<Widget, Pair<Int, Int>> = when {
        screen == MAP -> emptyMap()
        land -> mapOf(
            Widget.CLUSTER to (0 to 0), Widget.FUEL to (3 to 0), Widget.CHT to (3 to 2), Widget.FUEL_NOW to (3 to 3),
            Widget.FUEL_AVG to (4 to 3), Widget.FAULTS to (3 to 4), Widget.CLOCK to (5 to 0), Widget.LINK to (6 to 0),
            Widget.BATTERY to (5 to 1), Widget.AIR to (6 to 1), Widget.THROTTLE to (5 to 2), Widget.TRIP to (5 to 3),
        )
        else -> mapOf(
            Widget.CLUSTER to (0 to 0), Widget.FUEL to (0 to 5), Widget.CLOCK to (2 to 5), Widget.BATTERY to (2 to 6),
            Widget.CHT to (0 to 7), Widget.AIR to (2 to 7), Widget.FUEL_NOW to (0 to 8), Widget.FUEL_AVG to (1 to 8),
            Widget.LINK to (2 to 8), Widget.TRIP to (0 to 9), Widget.FAULTS to (0 to 11),
        )
    }
}

/**
 * Puts each element in a slot: its saved place if still free and inside the grid, else the first free
 * one (top-left first, or from the bottom row up on the map so the middle stays clear).
 */
fun arrange(order: List<Widget>, saved: Map<Widget, Pair<Int, Int>>, cols: Int, rows: Int, bottomUp: Boolean): Map<Widget, Slot> {
    val out = LinkedHashMap<Widget, Slot>()
    fun free(s: Slot) = s.c >= 0 && s.r >= 0 && s.c + s.w <= cols && s.r + s.h <= rows && out.values.none { it.overlaps(s) }
    val items = order.filter { it.cols > 0 }
    fun span(w: Widget) = w.cols.coerceAtMost(cols) to w.rows.coerceAtMost(rows)
    for (w in items) {
        val (c, r) = saved[w] ?: continue
        val (sw, sh) = span(w)
        val s = Slot(c, r, sw, sh)
        if (free(s)) out[w] = s
    }
    for (w in items) {
        if (w in out) continue
        val (sw, sh) = span(w)
        val rowOrder = if (bottomUp) (rows - sh downTo 0) else (0..rows - sh)
        search@ for (r in rowOrder) for (c in 0..cols - sw) {
            val s = Slot(c, r, sw, sh)
            if (free(s)) { out[w] = s; break@search }
        }
    }
    return out
}

/** Dropping [w] at [to]: onto free slots it moves, onto one other element they swap if both fit; else null. */
fun dropped(slots: Map<Widget, Slot>, w: Widget, to: Pair<Int, Int>, cols: Int, rows: Int): Map<Widget, Slot>? {
    val from = slots[w] ?: return null
    val target = Slot(to.first, to.second, from.w, from.h)
    if (target.c == from.c && target.r == from.r) return null
    if (target.c < 0 || target.r < 0 || target.c + target.w > cols || target.r + target.h > rows) return null
    val hit = slots.filter { (o, s) -> o != w && s.overlaps(target) }
    return when (hit.size) {
        0 -> slots + (w to target)
        1 -> {
            val (o, os) = hit.entries.first()
            val back = Slot(from.c, from.r, os.w, os.h)
            val rest = slots - w - o
            val ok = back.c + back.w <= cols && back.r + back.h <= rows &&
                rest.values.none { it.overlaps(target) || it.overlaps(back) } && !back.overlaps(target)
            if (ok) slots + (w to target) + (o to back) else null
        }
        else -> null
    }
}

@Composable
fun isLandscape() = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The screen's elements in their slots. In move mode the slots show, an element follows the finger
 * and snaps into the slot it is dropped on (swapping with what was there); touches elsewhere still
 * reach what is underneath (the map).
 */
@Composable
fun WidgetBoard(screen: String, data: WidgetData, modifier: Modifier = Modifier, drawShift: Boolean = true) {
    val ctx = LocalContext.current
    val land = isLandscape()
    val size = LayoutStore.size(ctx, screen)
    val alpha = LayoutStore.alpha(ctx, screen)
    val moving = LayoutStore.moving(screen)
    val order = LayoutStore.shown(ctx, screen, land)
    val saved = LayoutStore.positions(ctx, screen, land, size)
    val bg = Palette.panel.copy(alpha = alpha)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val strip = if (drawShift && Widget.SHIFT in order) 18.dp else 0.dp
        val top = if (strip > 0.dp) strip + 4.dp else 0.dp
        val prof = if (screen == LayoutStore.MAP && Widget.PROFILE in order) data.profile else null
        val profH = (64 * size.k).dp
        val gridH = maxHeight - top - if (prof != null) profH + 4.dp else 0.dp
        val cols = (maxWidth.value / (120 * size.k)).toInt().coerceAtLeast(1)
        val rows = (gridH.value / (60 * size.k)).toInt().coerceAtLeast(1)
        val cw = maxWidth / cols
        val ch = gridH / rows
        val cwPx = with(density) { cw.toPx() }
        val chPx = with(density) { ch.toPx() }
        val slots = remember(order, saved, cols, rows) { arrange(order, saved, cols, rows, bottomUp = screen == LayoutStore.MAP) }
        SideEffect {
            val none = order.filter { it.cols > 0 && it !in slots }.toSet()
            if (LayoutStore.unplaced[screen] != none) LayoutStore.unplaced[screen] = none
        }

        if (strip > 0.dp) ShiftLights(data, Modifier.fillMaxWidth().height(strip))
        if (moving) {
            Canvas(Modifier.offset(y = top).size(maxWidth, gridH)) {
                for (r in 0 until rows) for (c in 0 until cols) {
                    drawRoundRect(Palette.frame, Offset(c * cwPx + 4f, r * chPx + 4f), Size(cwPx - 8f, chPx - 8f),
                        CornerRadius(14f), style = Stroke(2f))
                }
            }
        }
        slots.forEach { (w, s) ->
            key(w) {
                var drag by remember { mutableStateOf(Offset.Zero) }
                var dragging by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .offset(x = cw * s.c, y = top + ch * s.r)
                        .size(cw * s.w, ch * s.h)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = drag.x; translationY = drag.y
                            if (dragging) { scaleX = 1.04f; scaleY = 1.04f }
                        }
                        .padding(3.dp)
                        .then(if (moving) Modifier
                            .border(2.dp, if (dragging) Palette.amber else Palette.cyan, RoundedCornerShape(10.dp))
                            .pointerInput(w, s, cols, rows) {
                                detectDragGestures(
                                    onDragStart = { dragging = true },
                                    onDragEnd = {
                                        val to = (s.c + (drag.x / cwPx).roundToInt()).coerceIn(0, cols - s.w) to
                                            (s.r + (drag.y / chPx).roundToInt()).coerceIn(0, rows - s.h)
                                        dropped(slots, w, to, cols, rows)?.let { n ->
                                            LayoutStore.setPositions(ctx, screen, land, size, n.mapValues { it.value.c to it.value.r })
                                        }
                                        drag = Offset.Zero; dragging = false
                                    },
                                    onDragCancel = { drag = Offset.Zero; dragging = false },
                                ) { change, d -> change.consume(); drag += d }
                            } else Modifier),
                ) {
                    WidgetCell(w, data, bg, moving)
                    if (moving) {
                        Text(stringResource(w.res), color = Palette.cyan, fontSize = 10.sp, maxLines = 1,
                            modifier = Modifier.align(Alignment.TopStart).background(Palette.overlay, RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp))
                    }
                }
            }
        }
        if (prof != null) {
            ProfileStrip(prof, data.doneM, Palette.panel.copy(alpha = maxOf(alpha, 0.6f)),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(profH))
        }
        if (moving) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Palette.overlay, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.w_move_mode), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { LayoutStore.saveMoving(screen) }) { Text(stringResource(R.string.w_save)) }
                OutlinedButton(onClick = { LayoutStore.revertMoving(ctx, screen) }) { Text(stringResource(R.string.w_revert)) }
            }
        }
    }
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

/**
 * A screen's settings: its elements, slot size, see-through, move mode and reset; [extra] adds the
 * screen's own settings below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetSheet(screen: String, title: String, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val land = isLandscape()
    val shown = LayoutStore.shown(ctx, screen, land)
    val noRoom = LayoutStore.unplaced[screen] ?: emptySet()
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
                Widget.entries.filter { it != Widget.PROFILE || screen == LayoutStore.MAP }.forEach { w ->
                    val on = w in shown
                    FilterChip(selected = on, onClick = { LayoutStore.toggle(ctx, screen, land, w, !on) },
                        label = { Text(stringResource(w.res) + if (on && w in noRoom) stringResource(R.string.w_no_room) else "") })
                }
            }
            Text(stringResource(if (land) R.string.w_orientation_land else R.string.w_orientation_port),
                color = Palette.label, fontSize = 12.sp)
            if (Widget.ATTITUDE in shown) Text(stringResource(R.string.w_att_hint), color = Palette.label, fontSize = 12.sp)

            Text(stringResource(R.string.w_size), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val size = LayoutStore.size(ctx, screen)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlotSize.entries.forEach { s ->
                    FilterChip(selected = size == s, onClick = { LayoutStore.setSize(ctx, screen, s) }, label = { Text(stringResource(s.res)) })
                }
            }

            val alpha = LayoutStore.alpha(ctx, screen)
            Text(stringResource(R.string.w_alpha, (alpha * 100).roundToInt()), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Slider(value = alpha, onValueChange = { LayoutStore.setAlpha(ctx, screen, (it * 20).roundToInt() / 20f) }, valueRange = 0.2f..1f)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.w_move_mode), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.w_move_mode_hint), color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = LayoutStore.moving(screen), onCheckedChange = {
                    if (it) { LayoutStore.startMoving(ctx, screen); onClose() } else LayoutStore.saveMoving(screen)
                })
            }
            OutlinedButton(onClick = { LayoutStore.reset(ctx, screen, land) }) { Text(stringResource(R.string.w_reset)) }
            extra()
        }
    }
}

/** The map's button bar settings: which edge, and which buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarSettings() {
    val ctx = LocalContext.current
    Text(stringResource(R.string.w_bar), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val side = LayoutStore.barSide(ctx)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BarSide.entries.forEach { s ->
            FilterChip(selected = side == s, onClick = { LayoutStore.setBarSide(ctx, s) }, label = { Text(stringResource(s.res)) })
        }
    }
    val on = LayoutStore.buttons(ctx)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapCtl.entries.filter { it.hideable }.forEach { b ->
            FilterChip(selected = b in on, onClick = { LayoutStore.setButton(ctx, b, b !in on) }, label = { Text(stringResource(b.res)) })
        }
    }
}

// ---- The elements ----

/** One element in its box, text scaled to the box. */
@Composable
private fun WidgetCell(w: Widget, d: WidgetData, bg: Color, editing: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = minOf(maxWidth.value / w.refW, maxHeight.value / w.refH).coerceIn(0.4f, 4f)
        when (w) {
            Widget.CLUSTER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Cluster(d) }
            Widget.SHIFT -> ShiftLights(d, Modifier.fillMaxSize())
            Widget.PROFILE -> {}
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
                    BigValue(fmt(Units.vol(tank), 1), Units.volUnit, Palette.text, u, 26f)
                    BigValue(fmt(Units.dist(t?.rangeKm), 0), Units.distUnit, rangeColor(d), u, 26f)
                }
                Text(stringResource(R.string.dash_range_at, fmt(Units.cons(t?.rangeL100), 2), Units.consUnit), color = Palette.label, fontSize = (11 * u).sp, maxLines = 1)
            }
            Widget.RANGE -> ValueTile(stringResource(R.string.w_range), fmt(Units.dist(d.t?.rangeKm), 0), Units.distUnit, rangeColor(d), bg, u)
            Widget.CHT -> {
                val cht = d.t?.chtC
                val critical = d.t?.chtCritical == true
                Cell(if (critical && d.blinkOn) Palette.red else bg, u) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (critical) R.string.dash_stop_engine else R.string.dash_head_temp),
                            color = if (critical) Palette.text else Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        BigValue(fmt(Units.temp(cht), 0), Units.tempUnit, if (critical) Palette.text else chtColor(cht), u, 24f)
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
                    t.instPerKm -> fmt(Units.cons(t.instant), 1)
                    else -> fmt(Units.flow(t.instant), 1)
                }
                ValueTile(stringResource(if (t?.fuelCut == true) R.string.dash_fuel_cut else R.string.dash_now), v,
                    if (t?.instPerKm == true) Units.consUnit else Units.flowUnit, if (t?.fuelCut == true) Palette.cyan else Palette.yellow, bg, u)
            }
            Widget.FUEL_AVG -> ValueTile(stringResource(R.string.dash_average),
                fmt(if (d.t?.avgPerKm == true) Units.cons(d.t.average) else Units.flow(d.t?.average), 1),
                if (d.t?.avgPerKm == true) Units.consUnit else Units.flowUnit, Palette.text, bg, u)
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
            Widget.AIR -> ValueTile(stringResource(R.string.dash_air), fmt(Units.temp(d.t?.iatC), 0), Units.tempUnit, Palette.text, bg, u)
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
                    BigValue(fmt(Units.dist(t?.distKm), 1), Units.distUnit, Palette.text, u, 20f)
                    BigValue(fmt(Units.vol(t?.usedL), 2), Units.volUnit, Palette.text, u, 20f)
                }
                Text(stringResource(R.string.dash_since_refuel, fmt(Units.dist(t?.fillKm), 0), Units.distUnit, fmt(Units.cons(t?.fillAvg), 2), Units.consUnit),
                    color = Palette.label, fontSize = (11 * u).sp, maxLines = 1)
                if (t != null && !t.gearsComplete) {
                    Text(stringResource(R.string.dash_learning_gears, t.gearsFound, t.learnS), color = Palette.label,
                        fontSize = (10 * u).sp, maxLines = 1)
                }
            }
            Widget.TRIP_A, Widget.TRIP_B -> {
                val trips by Trips.set.collectAsStateWithLifecycle()
                TripCell(stringResource(w.res), if (w == Widget.TRIP_A) trips.a else trips.b, bg, u)
            }
            Widget.ODO -> {
                val trips by Trips.set.collectAsStateWithLifecycle()
                ValueTile(stringResource(R.string.w_odo), fmt(Units.dist(trips.odoM / 1000), 0), Units.distUnit, Palette.text, bg, u)
            }
            Widget.COORDS -> Cell(bg, u, center = true) {
                if (d.lat == null || d.lon == null) Text("--", color = Palette.dim, fontSize = (16 * u).sp)
                else {
                    Text(dms(d.lat, "N", "S"), color = Palette.text, fontSize = (14 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Text(dms(d.lon, "E", "W"), color = Palette.text, fontSize = (14 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
            Widget.ALTITUDE -> ValueTile(stringResource(R.string.w_altitude), fmt(Units.alt(d.altM), 0), Units.altUnit, Palette.text, bg, u)
            Widget.NORTH -> NorthRose(d, bg)
            Widget.ATTITUDE -> AttitudeGauge(bg, u)
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

/** A trip counter: distance, then time moving, average speed, fuel use and top speed. */
@Composable
private fun TripCell(label: String, t: Trip, bg: Color, u: Float) = Cell(bg, u) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label, color = Palette.label, fontSize = (12 * u).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        BigValue(fmt(Units.dist(t.distM / 1000), 1), Units.distUnit, Palette.text, u, 22f)
    }
    val mins = (t.movingS / 60).toInt()
    Text("⏱ %d:%02d   ⌀ %s %s".format(mins / 60, mins % 60, fmt(Units.speed(t.avgKmh), 0), Units.speedUnit),
        color = Palette.text, fontSize = (12 * u).sp, maxLines = 1)
    Text(listOfNotNull(
        t.l100?.let { "⛽ ${fmt(Units.cons(it), 1)} ${Units.consUnit}" },
        "▲ ${fmt(Units.speed(t.maxKmh), 0)} ${Units.speedUnit}",
    ).joinToString("   "), color = Palette.label, fontSize = (11 * u).sp, maxLines = 1)
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
        Modifier.fillMaxSize().background(bg, RoundedCornerShape(10.dp))
            .then(if (Palette.outlined) Modifier.border(1.dp, Palette.frame, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = (10 * u).dp, vertical = (6 * u).dp),
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
            Text(Units.speed(speed)?.let { "%.0f".format(it) } ?: "--", color = if (over) Palette.red else Palette.text,
                fontSize = (40 * u).sp, fontWeight = FontWeight.Bold, lineHeight = (42 * u).sp)
            Text(Units.speedUnit, color = Palette.label, fontSize = (12 * u).sp)
        }
        d.limit?.let { LimitSign(it, scale = u) }
    }
}

/** Compass rose that turns with the view and, on the map in 3D, leans back with it. */
@Composable
private fun NorthRose(d: WidgetData, bg: Color) {
    val att by Attitude.state.collectAsStateWithLifecycle()
    val heading = d.bearing ?: att.azimuth
    val tilt = d.tilt ?: 0f
    val density = LocalDensity.current.density
    val cyan = Palette.cyan
    val text = Palette.text
    val label = Palette.label
    val red = Palette.red
    val paint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) } }
    Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(12.dp)).outline(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Canvas(Modifier.size(side).graphicsLayer { rotationX = tilt; rotationZ = -heading; cameraDistance = 10f * density }) {
                val r = size.minDimension / 2
                val c = center
                drawCircle(cyan.copy(alpha = 0.25f), r * 0.97f, c, style = Stroke(r * 0.03f))
                drawCircle(cyan, r * 0.78f, c, style = Stroke(r * 0.02f))
                for (k in 0 until 72) {
                    val a = Math.toRadians(k * 5.0 - 90.0)
                    val major = k % 6 == 0
                    val r2 = r * (if (k % 18 == 0) 0.80f else if (major) 0.86f else 0.92f)
                    val cos = kotlin.math.cos(a).toFloat()
                    val sin = kotlin.math.sin(a).toFloat()
                    drawLine(if (k == 0) red else if (major) text else label,
                        Offset(c.x + r * 0.97f * cos, c.y + r * 0.97f * sin), Offset(c.x + r2 * cos, c.y + r2 * sin),
                        strokeWidth = if (major) r * 0.03f else r * 0.012f)
                }
                val needle = r * 0.48f
                drawPath(Path().apply { moveTo(c.x, c.y - needle); lineTo(c.x + r * 0.12f, c.y); lineTo(c.x - r * 0.12f, c.y); close() }, red)
                drawPath(Path().apply { moveTo(c.x, c.y + needle); lineTo(c.x + r * 0.12f, c.y); lineTo(c.x - r * 0.12f, c.y); close() }, label)
                paint.textSize = r * 0.2f
                listOf("N", "E", "S", "W").forEachIndexed { i, l ->
                    val a = Math.toRadians(i * 90.0 - 90.0)
                    paint.color = (if (i == 0) red else text).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(l,
                        c.x + r * 0.64f * kotlin.math.cos(a).toFloat(), c.y + r * 0.64f * kotlin.math.sin(a).toFloat() + paint.textSize * 0.36f, paint)
                }
            }
            Text("${(heading.roundToInt() + 360) % 360}°", color = cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Palette.overlay, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp))
        }
    }
}

/** Artificial horizon with the bike's lean and pitch; long press zeroes it with the bike upright. */
@Composable
private fun AttitudeGauge(bg: Color, u: Float) {
    val ctx = LocalContext.current
    val a by Attitude.state.collectAsStateWithLifecycle()
    val sky = Palette.blue.copy(alpha = 0.5f)
    val ground = Palette.amber.copy(alpha = 0.45f)
    val cyan = Palette.cyan
    val text = Palette.text
    val amber = Palette.amber
    Row(
        Modifier.fillMaxSize().background(bg, RoundedCornerShape(12.dp)).outline()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { Attitude.zero(ctx) }) }
            .padding((6 * u).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val r = size.minDimension / 2
            val c = center
            val perDeg = r * 0.8f / 30f
            val off = a.pitch.coerceIn(-30f, 30f) * perDeg
            clipPath(Path().apply { addOval(Rect(c, r * 0.94f)) }) {
                rotate(-a.lean, c) {
                    drawRect(sky, Offset(c.x - 2 * r, c.y - 3 * r + off), Size(4 * r, 3 * r))
                    drawRect(ground, Offset(c.x - 2 * r, c.y + off), Size(4 * r, 3 * r))
                    drawLine(text, Offset(c.x - 2 * r, c.y + off), Offset(c.x + 2 * r, c.y + off), strokeWidth = r * 0.025f)
                    for (k in listOf(-20, -10, 10, 20)) {
                        val y = c.y + off - k * perDeg
                        val half = r * (if (k % 20 == 0) 0.3f else 0.18f)
                        drawLine(text.copy(alpha = 0.8f), Offset(c.x - half, y), Offset(c.x + half, y), strokeWidth = r * 0.015f)
                    }
                }
            }
            drawCircle(cyan, r * 0.95f, c, style = Stroke(r * 0.035f))
            // Lean scale along the top: 0, 15, 30, 45, 60 each way, and the pointer that turns with the horizon.
            for (deg in listOf(-60, -45, -30, -15, 0, 15, 30, 45, 60)) {
                val t = Math.toRadians(deg - 90.0)
                val r1 = r * 0.95f
                val r2 = r * (if (deg % 30 == 0) 0.80f else 0.86f)
                drawLine(cyan, Offset(c.x + r1 * kotlin.math.cos(t).toFloat(), c.y + r1 * kotlin.math.sin(t).toFloat()),
                    Offset(c.x + r2 * kotlin.math.cos(t).toFloat(), c.y + r2 * kotlin.math.sin(t).toFloat()), strokeWidth = r * 0.03f)
            }
            rotate(a.lean, c) {
                drawPath(Path().apply {
                    moveTo(c.x, c.y - r * 0.78f); lineTo(c.x - r * 0.08f, c.y - r * 0.64f); lineTo(c.x + r * 0.08f, c.y - r * 0.64f); close()
                }, amber)
            }
            // The bike, fixed.
            drawLine(amber, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.18f, c.y), strokeWidth = r * 0.06f)
            drawLine(amber, Offset(c.x + r * 0.18f, c.y), Offset(c.x + r * 0.55f, c.y), strokeWidth = r * 0.06f)
            drawCircle(amber, r * 0.05f, c)
        }
        Column(Modifier.weight(1f).padding(start = (10 * u).dp), verticalArrangement = Arrangement.Center) {
            val lean = a.lean.roundToInt()
            Text(stringResource(R.string.w_att_lean), color = Palette.label, fontSize = (11 * u).sp, fontWeight = FontWeight.Bold)
            Text(if (!a.ok) "--" else "${abs(lean)}°" + if (lean > 0) " R" else if (lean < 0) " L" else "",
                color = if (abs(lean) >= 45) Palette.orange else text, fontSize = (30 * u).sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(stringResource(R.string.w_att_pitch) + " " + (if (!a.ok) "--" else "%+d°".format(a.pitch.roundToInt())),
                color = cyan, fontSize = (13 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(stringResource(R.string.w_att_max, a.maxLeft.roundToInt(), a.maxRight.roundToInt()),
                color = Palette.label, fontSize = (11 * u).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/** A thin outline on the OLED scheme, where panels are as black as the background. */
private fun Modifier.outline(): Modifier =
    if (Palette.outlined) border(1.dp, Palette.frame, RoundedCornerShape(12.dp)) else this

/**
 * The followed track's height from start (left) to finish (right), whatever its length: ridden part
 * in cyan, what is left in amber, a marker where the bike is.
 */
@Composable
private fun ProfileStrip(p: ElevProfile, doneM: Double?, bg: Color, modifier: Modifier) {
    val frac = ((doneM ?: 0.0) / p.totalM).toFloat().coerceIn(0f, 1f)
    val here = doneM?.let { p.at(it) }
    val span = (p.maxE - p.minE).coerceAtLeast(20f)
    val lo = p.minE - span * 0.08f
    val hi = lo + span * 1.2f
    Box(modifier.background(bg, RoundedCornerShape(10.dp)).outline().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val cyan = Palette.cyan
        val amber = Palette.amber
        val text = Palette.text
        Canvas(Modifier.fillMaxSize().padding(top = 14.dp)) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = p.distM[i] / p.totalM * w
            fun y(e: Float) = h - (e - lo) / (hi - lo) * h
            val n = p.eleM.size
            val area = Path().apply {
                moveTo(0f, h)
                for (i in 0 until n) lineTo(x(i), y(p.eleM[i]))
                lineTo(w, h); close()
            }
            val line = Path().apply {
                moveTo(0f, y(p.eleM[0]))
                for (i in 1 until n) lineTo(x(i), y(p.eleM[i]))
            }
            val cut = frac * w
            clipRect(right = cut) {
                drawPath(area, cyan.copy(alpha = 0.35f))
                drawPath(line, cyan, style = Stroke(3f))
            }
            clipRect(left = cut) {
                drawPath(area, amber.copy(alpha = 0.25f))
                drawPath(line, amber, style = Stroke(3f))
            }
            if (doneM != null && here != null) {
                drawLine(text, Offset(cut, 0f), Offset(cut, h), strokeWidth = 2f)
                drawCircle(text, 7f, Offset(cut, y(here)))
                drawCircle(cyan, 4f, Offset(cut, y(here)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("▲ %.0f %s  ▼ %.0f %s".format(Units.alt(p.maxE.toDouble()), Units.altUnit, Units.alt(p.minE.toDouble()), Units.altUnit), color = Palette.label, fontSize = 11.sp, maxLines = 1)
            if (here != null) Text("%.0f %s · %d%%".format(Units.alt(here.toDouble()), Units.altUnit, (frac * 100).roundToInt()), color = Palette.text,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(Units.short(p.totalM.toDouble(), 1), color = Palette.label,
                fontSize = 11.sp, maxLines = 1)
        }
    }
}
