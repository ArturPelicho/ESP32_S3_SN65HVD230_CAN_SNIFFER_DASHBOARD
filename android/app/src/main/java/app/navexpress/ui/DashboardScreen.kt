package app.navexpress.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import app.navexpress.Units
import app.navexpress.DtcState
import app.navexpress.GpsInfo
import app.navexpress.L
import app.navexpress.Link
import app.navexpress.LinkStatus
import app.navexpress.R
import app.navexpress.Telemetry

private const val RPM_MAX = 10500f
private const val SHIFT_LIGHTS = 12
private const val SHIFT_LIGHTS_FROM = 6000f

/**
 * The Painel: a top strip with the shift lights and the settings button, and below it the chosen
 * elements in their slots.
 */
@Composable
fun DashboardScreen() {
    val ctx = LocalContext.current
    val d = widgetData()
    var sheet by remember { mutableStateOf(false) }
    val shift = Widget.SHIFT in LayoutStore.shown(ctx, LayoutStore.DASH, isLandscape())
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(18.dp)) { if (shift) ShiftLights(d, Modifier.fillMaxSize()) }
                ScreenSettingsButton(Modifier.size(32.dp)) { sheet = true }
            }
            WidgetBoard(LayoutStore.DASH, d, drawShift = false, modifier = Modifier.fillMaxSize())
        }
        if (sheet) WidgetSheet(LayoutStore.DASH, stringResource(R.string.w_dash_title), onClose = { sheet = false }) {
            Text(stringResource(R.string.w_dash_settings), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            DashSettings()
        }
    }
}

/** Row of LEDs from SHIFT_LIGHTS_FROM up to the shift rpm, all flashing red at it. */
@Composable
internal fun ShiftLights(s: WidgetData, modifier: Modifier) {
    val rpm = (s.t?.rpm ?: 0).toFloat()
    val shiftRpm = (s.t?.shiftRpm ?: 10000).toFloat().coerceAtLeast(SHIFT_LIGHTS_FROM + 1000f)
    val shifting = s.t?.shift == true
    Canvas(modifier) {
        val gap = size.width * 0.012f
        val w = (size.width - gap * (SHIFT_LIGHTS - 1)) / SHIFT_LIGHTS
        for (i in 0 until SHIFT_LIGHTS) {
            val at = SHIFT_LIGHTS_FROM + (shiftRpm - SHIFT_LIGHTS_FROM) * i / SHIFT_LIGHTS
            val base = when {
                i < SHIFT_LIGHTS * 5 / 12 -> Palette.green
                i < SHIFT_LIGHTS * 9 / 12 -> Palette.amber
                else -> Palette.red
            }
            val color = when {
                shifting -> if (s.blinkOn) Palette.red else Palette.dim.copy(alpha = 0.4f)
                rpm >= at -> base
                else -> base.copy(alpha = 0.12f)
            }
            drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(size.height / 3))
        }
    }
}

/** RPM arc with the speed, the gear and the rpm inside. */
@Composable
internal fun Cluster(s: WidgetData) {
    val t = s.t
    val rpm = t?.rpm ?: 0
    val shiftRpm = (t?.shiftRpm ?: 10000).toFloat()
    val shifting = t?.shift == true
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth.value, maxHeight.value)
        val unit = side / 360f // text scales with the cluster
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = size.minDimension * 0.075f
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            val start = 135f
            val sweep = 270f
            drawArc(Palette.dim, start, sweep, false, topLeft, arcSize, style = Stroke(stroke))
            val redFrom = sweep * (shiftRpm / RPM_MAX)
            drawArc(Palette.red.copy(alpha = 0.35f), start + redFrom, sweep - redFrom, false, topLeft, arcSize,
                style = Stroke(stroke))
            val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
            val color = when {
                shifting -> if (s.blinkOn) Palette.red else Palette.text
                rpm >= shiftRpm * 0.85f -> Palette.orange
                else -> Palette.amber
            }
            drawArc(color, start, sweep * frac, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            val cx = size.width / 2
            val cy = size.height / 2
            val r = arcSize.width / 2
            for (k in 0..20) {
                val major = k % 2 == 0
                val a = Math.toRadians((start + sweep * (k * 500f / RPM_MAX)).toDouble())
                val r1 = r - stroke * 0.7f
                val r2 = r - stroke * (if (major) 1.5f else 1.1f)
                drawLine(if (k * 500 >= shiftRpm) Palette.red else Palette.label,
                    Offset(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat()),
                    Offset(cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat()),
                    strokeWidth = if (major) 4f else 2f)
            }
        }
        // Thousands under the ticks.
        for (k in 0..10) {
            val a = Math.toRadians((135f + 270f * (k * 1000f / RPM_MAX)).toDouble())
            val r = side * 0.31f
            Text("$k", color = if (k * 1000 >= shiftRpm) Palette.red else Palette.label,
                fontSize = (15 * unit).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
                    .offset(x = (r * Math.cos(a)).toFloat().dp, y = (r * Math.sin(a)).toFloat().dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val speed = s.speed
            Text(if (speed == null) "--" else Units.speed(speed).toInt().toString(),
                color = if (speed == null) Palette.dim else Palette.text,
                fontSize = (92 * unit).sp, fontWeight = FontWeight.Bold, lineHeight = (92 * unit).sp)
            Text("${Units.speedUnit}  ${speedSourceTag(t)?.let { stringResource(it) } ?: ""}", color = Palette.label, fontSize = (13 * unit).sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = (6 * unit).dp)) {
                Box(
                    Modifier.size((66 * unit).dp).border(3.dp, Palette.cyan, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val gear = t?.gear ?: 0
                    Text(if (gear == 0) "-" else gear.toString(), color = Palette.cyan,
                        fontSize = (46 * unit).sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = (12 * unit).dp)) {
                    Text("$rpm", color = if (shifting && s.blinkOn) Palette.red else Palette.text,
                        fontSize = (26 * unit).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("RPM", color = Palette.label, fontSize = (12 * unit).sp)
                }
            }
            if (s.status != LinkStatus.CONNECTED) {
                Text(statusText(s.status), color = Palette.orange, fontSize = (13 * unit).sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@StringRes
private fun speedSourceTag(t: Telemetry?): Int? {
    if (t == null) return null
    val s = t.speeds
    return when {
        s.getOrNull(1) != null -> R.string.dash_src_wheel
        s.getOrNull(3) != null -> R.string.dash_src_gear
        s.getOrNull(2) != null -> R.string.dash_src_gps
        s.getOrNull(0) != null -> R.string.dash_src_phone_gps
        t.fuelSpeed != null -> R.string.dash_src_average
        else -> null
    }
}

fun statusText(s: LinkStatus): String = L.s(when (s) {
    LinkStatus.STOPPED -> R.string.dash_status_stopped
    LinkStatus.NO_DEVICE -> R.string.dash_status_no_device
    LinkStatus.SCANNING -> R.string.dash_status_scanning
    LinkStatus.WAITING -> R.string.dash_status_waiting
    LinkStatus.CONNECTING -> R.string.dash_status_connecting
    LinkStatus.CONNECTED -> R.string.dash_status_connected
})

@Composable
internal fun BigValue(value: String, unit: String, color: Color, u: Float, size: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = (size * u).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width((3 * u).dp))
        Text(unit, color = Palette.label, fontSize = (12 * u).sp, maxLines = 1, modifier = Modifier.padding(bottom = (3 * u).dp))
    }
}

/** Horizontal bar of n segments lit up to frac; null = nothing known. */
@Composable
internal fun SegmentBar(frac: Double?, n: Int, modifier: Modifier, color: (Int) -> Color) {
    Canvas(modifier) {
        val gap = size.width * 0.015f
        val w = (size.width - gap * (n - 1)) / n
        val lit = if (frac == null) 0 else Math.round(frac * n).toInt()
        for (i in 0 until n) {
            val c = color(i)
            drawRoundRect(if (i < lit) c else c.copy(alpha = if (frac == null) 0.06f else 0.15f),
                Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(3f))
        }
    }
}
