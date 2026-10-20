package pt.artur.motadash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.BleProtocol
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus

/** Diagnóstico: live readings, and the engine fault codes as a section of their own. */
@Composable
fun DiagScreen() {
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    var faults by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !faults, onClick = { faults = false }, label = { Text("Leituras") })
            FilterChip(selected = faults, onClick = { faults = true },
                label = { Text(if (dtc.total > 0) "Erros (${dtc.total})" else "Erros",
                    color = if (dtc.total > 0 && !faults) Palette.orange else Color.Unspecified) })
        }
        Box(Modifier.weight(1f)) { if (faults) DtcScreen() else DiagReadings() }
    }
}

@Composable
private fun DiagReadings() {
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val gps by Link.gps.collectAsStateWithLifecycle()
    val gearLine by Link.gearLine.collectAsStateWithLifecycle()
    val console by Link.console.collectAsStateWithLifecycle()
    val status by Link.status.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Velocidades (todas as fontes)") {
            BleProtocol.SPEED_SOURCE_LABELS.forEachIndexed { i, label ->
                val v = t?.speeds?.getOrNull(i)
                val tag = if (t?.fuelSpeedSrc == i) "  ← consumos" else ""
                KeyValue(label + tag, if (v == null) "--" else "${fmt(v, 1)} km/h",
                    if (v == null) Palette.dim else Palette.text)
            }
            KeyValue("Usada nos consumos", "${fmt(t?.fuelSpeed, 1)} km/h (" +
                BleProtocol.SPEED_ORIGINS.getOrElse(t?.fuelSpeedOrigin ?: 0) { "?" } + ")")
        }
        Section("GPS do telemóvel") {
            KeyValue("Frequência", "${fmt(gps.hz, 1)} Hz", if (gps.hz >= 1.0) Palette.green else Palette.orange)
            KeyValue("Velocidade", "${fmt(gps.speedKmh, 1)} km/h ± ${fmt(gps.speedAccKmh, 1)}")
            KeyValue("Precisão da posição", "${fmt(gps.hAccM, 0)} m")
            KeyValue("Fixes recebidos / enviados", "${gps.fixes} / ${gps.sent}")
        }
        Section("Mudanças") {
            KeyValue("Engrenada", t?.gear?.takeIf { it > 0 }?.toString() ?: "-")
            KeyValue("Encontradas", "${t?.gearsFound ?: 0}" + if (t?.gearsComplete == true) " (todas)" else "")
            KeyValue("Dados de aprendizagem", "${t?.learnS ?: 0} s")
            gearLine?.let { Text(it, color = Palette.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        Section("Motor") {
            KeyValue("RPM", "${t?.rpm ?: "--"}")
            KeyValue("Temp. cabeça", "${fmt(t?.chtC, 0)} °C", chtColor(t?.chtC))
            KeyValue("MAP", "${fmt(t?.mapKpa, 0)} kPa")
            KeyValue("Ar admissão", "${fmt(t?.iatC, 0)} °C")
            KeyValue("O2", "${fmt(t?.o2V, 2)} V")
            KeyValue("Trims curto / longo", "${fmt(t?.stftPct, 1)} / ${fmt(t?.ltftPct, 1)} %")
            KeyValue("Avanço", "${fmt(t?.sparkDeg, 1)} °")
            KeyValue("INJ", fmt(t?.inj, 2))
            KeyValue("Caudal", "${fmt(t?.flowLph, 2)} L/h")
            KeyValue("Bateria", "${fmt(t?.batteryV, 2)} V", batteryColor(t?.batteryV))
        }
        Section("Consola") {
            var cmd by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, singleLine = true,
                    label = { Text("Comando (ex.: SPEED, GEAR, CAL)") }, modifier = Modifier.weight(1f))
                Button(enabled = status == LinkStatus.CONNECTED && cmd.isNotBlank(),
                    onClick = { Link.send(cmd.trim()); cmd = "" },
                    modifier = Modifier.padding(start = 8.dp)) { Text("Enviar") }
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                console.takeLast(60).forEach {
                    Text(it, color = Palette.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
