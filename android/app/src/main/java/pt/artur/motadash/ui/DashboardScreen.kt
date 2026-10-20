package pt.artur.motadash.ui

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
import pt.artur.motadash.DtcState
import pt.artur.motadash.GpsInfo
import pt.artur.motadash.L
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.R
import pt.artur.motadash.Telemetry

private const val RPM_MAX = 10500f
private const val SHIFT_LIGHTS = 12
private const val SHIFT_LIGHTS_FROM = 6000f
private const val CHT_MIN = 40.0
private const val CHT_MAX = 170.0

private class DashState(
    val t: Telemetry?, val status: LinkStatus, val gps: GpsInfo, val dtc: DtcState,
    val blinkOn: Boolean, val now: Long, val tankCapacityL: Double,
)

@Composable
fun DashboardScreen() {
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
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val s = DashState(t, status, gps, dtc, blink > 0.5f, now, params["TANK_DL"]?.value?.div(10.0) ?: 16.0)
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg).padding(6.dp)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShiftLights(s, Modifier.fillMaxWidth().height(if (landscape) 14.dp else 18.dp))
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxHeight().aspectRatio(1f)) { Cluster(s) }
                    Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) { MainGauges(s) }
                    Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) { SideReadouts(s) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) { Cluster(s) }
                    MainGauges(s)
                    SideReadouts(s)
                }
            }
        }
    }
}

/** Row of LEDs from SHIFT_LIGHTS_FROM up to the shift rpm, all flashing red at it. */
@Composable
private fun ShiftLights(s: DashState, modifier: Modifier) {
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
private fun Cluster(s: DashState) {
    val t = s.t
    val rpm = t?.rpm ?: 0
    val shiftRpm = (t?.shiftRpm ?: 10000).toFloat()
    val shifting = t?.shift == true
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val unit = maxWidth.value / 360f // text scales with the cluster
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
            val r = maxWidth.value * 0.31f
            Text("$k", color = if (k * 1000 >= shiftRpm) Palette.red else Palette.label,
                fontSize = (15 * unit).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
                    .offset(x = (r * Math.cos(a)).toFloat().dp, y = (r * Math.sin(a)).toFloat().dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val speed = t?.displaySpeed
            Text(if (speed == null) "--" else speed.toInt().toString(),
                color = if (speed == null) Palette.dim else Palette.text,
                fontSize = (92 * unit).sp, fontWeight = FontWeight.Bold, lineHeight = (92 * unit).sp)
            Text("km/h  ${speedSourceTag(t)?.let { stringResource(it) } ?: ""}", color = Palette.label, fontSize = (13 * unit).sp)
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

/** Fuel and head temperature as segment bars, and the consumption. */
@Composable
private fun ColumnScope.MainGauges(s: DashState) {
    val t = s.t
    // Fuel: segments like the stock gauge.
    val capacity = s.tankCapacityL
    val tank = t?.tankL
    val tankFrac = if (tank == null) null else (tank / capacity).coerceIn(0.0, 1.0)
    val range = t?.rangeKm
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("E", color = Palette.red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            SegmentBar(tankFrac, 10, Modifier.weight(1f).height(22.dp).padding(horizontal = 6.dp)) { i ->
                if (i < 2) Palette.red else if (i < 3) Palette.orange else Palette.green
            }
            Text("F", color = Palette.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            BigValue(fmt(tank, 1), "L", Palette.text, 26.sp)
            BigValue(fmt(range, 0), "km", when {
                range == null -> Palette.dim
                range < 30 -> if (s.blinkOn) Palette.red else Palette.text
                range < 60 -> Palette.orange
                else -> Palette.green
            }, 26.sp)
        }
        Text(stringResource(R.string.dash_range_at, fmt(t?.rangeL100, 2)), color = Palette.label, fontSize = 11.sp)
    }
    // Head temperature.
    val cht = t?.chtC
    val critical = t?.chtCritical == true
    Panel(if (critical && s.blinkOn) Palette.red else Palette.panel) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (critical) R.string.dash_stop_engine else R.string.dash_head_temp), color = if (critical) Palette.text else Palette.label,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            BigValue(fmt(cht, 0), "°C", if (critical) Palette.text else chtColor(cht), 24.sp)
        }
        val chtFrac = if (cht == null) null else ((cht - CHT_MIN) / (CHT_MAX - CHT_MIN)).coerceIn(0.0, 1.0)
        SegmentBar(chtFrac, 13, Modifier.fillMaxWidth().height(14.dp)) { i ->
            chtColor(CHT_MIN + (i + 0.5) * (CHT_MAX - CHT_MIN) / 13)
        }
    }
    // Consumption.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val instUnit = if (t?.instPerKm == true) "L/100" else "L/h"
        val inst = when {
            t == null || !t.fuelValid -> "--"
            t.fuelCut -> "0.0"
            else -> fmt(t.instant, 1)
        }
        Tile(stringResource(if (t?.fuelCut == true) R.string.dash_fuel_cut else R.string.dash_now), inst, instUnit,
            if (t?.fuelCut == true) Palette.cyan else Palette.yellow, Modifier.weight(1f))
        Tile(stringResource(R.string.dash_average), fmt(t?.average, 1), if (t?.avgPerKm == true) "L/100" else "L/h", Palette.text,
            Modifier.weight(1f))
    }
}

/** Clock, link, battery, throttle, trip, fault codes. */
@Composable
private fun ColumnScope.SideReadouts(s: DashState) {
    val t = s.t
    val dtcCount = s.dtc.total
    if (dtcCount > 0 || s.dtc.mil == true) {
        Panel(if (s.blinkOn) Palette.orange else Palette.panel) {
            Text(stringResource(R.string.dash_engine_fault), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(if (dtcCount > 0) stringResource(R.string.dash_fault_codes, dtcCount,
                    s.dtc.groups.values.flatten().take(3).joinToString(" "))
                else stringResource(R.string.dash_mil_on), color = Palette.text, fontSize = 12.sp)
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.now)), color = Palette.text,
                fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.dash_bike) + if (s.status == LinkStatus.CONNECTED) " ●" else " ○",
                    color = if (s.status == LinkStatus.CONNECTED) Palette.green else Palette.orange, fontSize = 11.sp)
                Text("GPS ${fmt(s.gps.hz, 0)} Hz", color = if (s.gps.hz >= 1.0) Palette.green else Palette.dim,
                    fontSize = 11.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tile(stringResource(R.string.dash_battery), fmt(t?.batteryV, 1), "V", batteryColor(t?.batteryV), Modifier.weight(1f))
        Tile(stringResource(R.string.dash_air), fmt(t?.iatC, 0), "°C", Palette.text, Modifier.weight(1f))
    }
    val thr = t?.throttlePct
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.dash_throttle), color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("${fmt(thr, 0)} %", color = Palette.text, fontSize = 14.sp)
        }
        SegmentBar(thr?.div(100.0), 10, Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) { Palette.green }
    }
    Panel {
        Text(stringResource(R.string.dash_trip), color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            BigValue(fmt(t?.distKm, 1), "km", Palette.text, 20.sp)
            BigValue(fmt(t?.usedL, 2), "L", Palette.text, 20.sp)
        }
        Text(stringResource(R.string.dash_since_refuel, fmt(t?.fillKm, 0), fmt(t?.fillAvg, 2)),
            color = Palette.label, fontSize = 11.sp)
    }
    if (t != null && !t.gearsComplete) {
        Text(stringResource(R.string.dash_learning_gears, t.gearsFound, t.learnS), color = Palette.label, fontSize = 11.sp)
    }
}

@Composable
private fun Panel(background: Color = Palette.panel, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp), content = content)
}

@Composable
private fun BigValue(value: String, unit: String, color: Color, size: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(3.dp))
        Text(unit, color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** Horizontal bar of n segments lit up to frac; null = nothing known. */
@Composable
private fun SegmentBar(frac: Double?, n: Int, modifier: Modifier, color: (Int) -> Color) {
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
