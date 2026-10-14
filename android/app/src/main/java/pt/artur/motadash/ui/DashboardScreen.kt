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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.BleProtocol
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.Telemetry

private const val RPM_MAX = 10500f

@Composable
fun DashboardScreen() {
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val status by Link.status.collectAsStateWithLifecycle()
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink",
    )
    val on = blink > 0.5f
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f)) { Cluster(t, status, on) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tiles(t, on)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) { Cluster(t, status, on) }
                Tiles(t, on)
            }
        }
    }
}

/** RPM arc with the speed and the gear inside. */
@Composable
private fun Cluster(t: Telemetry?, status: LinkStatus, blinkOn: Boolean) {
    val rpm = t?.rpm ?: 0
    val shiftRpm = (t?.shiftRpm ?: 10000).toFloat()
    val shifting = t?.shift == true
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = size.minDimension * 0.07f
            val inset = stroke / 2
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset((size.width - arcSize.width) / 2, inset)
            val start = 135f
            val sweep = 270f
            drawArc(Palette.dim, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            val redFrom = sweep * (shiftRpm / RPM_MAX)
            drawArc(Palette.red.copy(alpha = 0.35f), start + redFrom, sweep - redFrom, false, topLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt))
            val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
            val color = when {
                shifting -> if (blinkOn) Palette.red else Palette.text
                rpm >= shiftRpm * 0.85f -> Palette.orange
                else -> Palette.amber
            }
            drawArc(color, start, sweep * frac, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            // Ticks every 1000 rpm.
            val cx = size.width / 2
            val cy = inset + arcSize.height / 2
            val r = arcSize.width / 2
            for (k in 0..10) {
                val a = Math.toRadians((start + sweep * (k * 1000f / RPM_MAX)).toDouble())
                val r1 = r - stroke * 0.9f
                val r2 = r - stroke * 1.6f
                drawLine(Palette.label, Offset(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat()),
                    Offset(cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat()), strokeWidth = 3f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val speed = t?.displaySpeed
            Text(
                if (speed == null) "--" else speed.toInt().toString(),
                color = if (speed == null) Palette.dim else Palette.text,
                fontSize = 96.sp, fontWeight = FontWeight.Bold,
            )
            Text("km/h  ${speedSourceTag(t)}", color = Palette.label, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Box(
                    Modifier.size(64.dp).border(3.dp, Palette.cyan, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val gear = t?.gear ?: 0
                    Text(if (gear == 0) "-" else gear.toString(), color = Palette.cyan, fontSize = 44.sp,
                        fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("$rpm", color = if (shifting && blinkOn) Palette.red else Palette.text, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold)
                    Text("RPM", color = Palette.label, fontSize = 12.sp)
                }
            }
            if (status != LinkStatus.CONNECTED) {
                Text(statusText(status), color = Palette.orange, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun speedSourceTag(t: Telemetry?): String {
    if (t == null) return ""
    val s = t.speeds
    return when {
        s.getOrNull(1) != null -> "RODA"
        s.getOrNull(3) != null -> "MUDANÇA"
        s.getOrNull(2) != null -> "GPS"
        s.getOrNull(0) != null -> "GPS TEL."
        t.fuelSpeed != null -> "MÉDIA"
        else -> ""
    }
}

fun statusText(s: LinkStatus) = when (s) {
    LinkStatus.STOPPED -> "Serviço parado"
    LinkStatus.NO_DEVICE -> "Bluetooth desligado"
    LinkStatus.SCANNING -> "À procura da mota…"
    LinkStatus.WAITING -> "À espera da mota (ignição?)"
    LinkStatus.CONNECTING -> "A ligar…"
    LinkStatus.CONNECTED -> "Ligado"
}

@Composable
private fun Tiles(t: Telemetry?, blinkOn: Boolean) {
    val m = Modifier
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val critical = t?.chtCritical == true
            val cht = t?.chtC
            Tile(
                label = if (critical) "PARAR MOTOR" else "TEMP. CABEÇA",
                value = fmt(cht, 0),
                unit = "°C",
                color = if (critical) (if (blinkOn) Color.Black else Palette.text) else chtColor(cht),
                background = if (critical) Palette.red else Palette.panel,
                modifier = m.weight(1f),
            )
            Tile("BATERIA", fmt(t?.batteryV, 1), "V", batteryColor(t?.batteryV), m.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val instUnit = if (t?.instPerKm == true) "L/100" else "L/h"
            val inst = when {
                t == null || !t.fuelValid -> "--"
                t.fuelCut -> "0.0"
                else -> fmt(t.instant, 1)
            }
            Tile(if (t?.fuelCut == true) "CORTE" else "AGORA", inst, instUnit,
                if (t?.fuelCut == true) Palette.cyan else Palette.yellow, m.weight(1f))
            Tile("MÉDIA", fmt(t?.average, 1), if (t?.avgPerKm == true) "L/100" else "L/h", Palette.text, m.weight(1f),
                sub = "${fmt(t?.usedL, 2)} L · ${fmt(t?.distKm, 1)} km")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("DEPÓSITO", fmt(t?.tankL, 1), "L", Palette.text, m.weight(1f),
                sub = if (t?.fillAvg != null) "${fmt(t.fillAvg, 2)} L/100 desde o abast." else null)
            val range = t?.rangeKm
            Tile("AUTONOMIA", fmt(range, 0), "km",
                when {
                    range == null -> Palette.dim
                    range < 30 -> Palette.red
                    range < 60 -> Palette.orange
                    else -> Palette.green
                }, m.weight(1f))
        }
        val thr = t?.throttlePct
        Column(Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ACELERADOR", color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${fmt(thr, 0)} %", color = Palette.text, fontSize = 14.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp)) {
                drawRect(Palette.dim, size = size)
                drawRect(Palette.green, size = Size(size.width * ((thr ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), size.height))
            }
        }
        if (t != null && !t.gearsComplete) {
            Text("A aprender mudanças: ${t.gearsFound} encontradas, ${t.learnS} s de dados",
                color = Palette.label, fontSize = 12.sp)
        }
        if (t != null && t.fuelSpeedOrigin != 1) {
            Text("Velocidade dos consumos: ${BleProtocol.SPEED_ORIGINS.getOrElse(t.fuelSpeedOrigin) { "?" }}",
                color = Palette.label, fontSize = 12.sp)
        }
    }
}
