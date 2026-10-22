package app.navexpress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.navexpress.BleProtocol
import app.navexpress.Link
import app.navexpress.LinkStatus
import app.navexpress.R

/** Diagnóstico: live readings, and the engine fault codes as a section of their own. */
@Composable
fun DiagScreen() {
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    var faults by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(
            listOf(stringResource(R.string.diag_tab_readings),
                if (dtc.total > 0) stringResource(R.string.diag_tab_faults_count, dtc.total) else stringResource(R.string.diag_tab_faults)),
            if (faults) 1 else 0, { faults = it == 1 },
            listOf(null, if (dtc.total > 0) Palette.orange else null),
        )
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
        Section(stringResource(R.string.diag_speeds)) {
            BleProtocol.SPEED_SOURCE_LABELS.forEachIndexed { i, label ->
                val v = t?.speeds?.getOrNull(i)
                val tag = if (t?.fuelSpeedSrc == i) "  ← " + stringResource(R.string.diag_fuel_tag) else ""
                KeyValue(label + tag, if (v == null) "--" else "${fmt(v, 1)} km/h",
                    if (v == null) Palette.dim else Palette.text)
            }
            KeyValue(stringResource(R.string.diag_fuel_speed), "${fmt(t?.fuelSpeed, 1)} km/h (" +
                BleProtocol.SPEED_ORIGINS.getOrElse(t?.fuelSpeedOrigin ?: 0) { "?" } + ")")
        }
        Section(stringResource(R.string.diag_phone_gps)) {
            KeyValue(stringResource(R.string.diag_gps_rate), "${fmt(gps.hz, 1)} Hz", if (gps.hz >= 1.0) Palette.green else Palette.orange)
            KeyValue(stringResource(R.string.diag_gps_speed), "${fmt(gps.speedKmh, 1)} km/h ± ${fmt(gps.speedAccKmh, 1)}")
            KeyValue(stringResource(R.string.diag_gps_accuracy), "${fmt(gps.hAccM, 0)} m")
            KeyValue(stringResource(R.string.diag_gps_fixes), "${gps.fixes} / ${gps.sent}")
        }
        Section(stringResource(R.string.diag_gears)) {
            KeyValue(stringResource(R.string.diag_gear_engaged), t?.gear?.takeIf { it > 0 }?.toString() ?: "-")
            KeyValue(stringResource(R.string.diag_gears_found), "${t?.gearsFound ?: 0}" +
                if (t?.gearsComplete == true) " (" + stringResource(R.string.diag_gears_all) + ")" else "")
            KeyValue(stringResource(R.string.diag_learn_data), "${t?.learnS ?: 0} s")
            gearLine?.let { Text(it, color = Palette.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        Section(stringResource(R.string.diag_engine)) {
            KeyValue("RPM", "${t?.rpm ?: "--"}")
            KeyValue(stringResource(R.string.diag_head_temp), "${fmt(t?.chtC, 0)} °C", chtColor(t?.chtC))
            KeyValue("MAP", "${fmt(t?.mapKpa, 0)} kPa")
            KeyValue(stringResource(R.string.diag_intake_air), "${fmt(t?.iatC, 0)} °C")
            KeyValue("O2", "${fmt(t?.o2V, 2)} V")
            KeyValue(stringResource(R.string.diag_trims), "${fmt(t?.stftPct, 1)} / ${fmt(t?.ltftPct, 1)} %")
            KeyValue(stringResource(R.string.diag_advance), "${fmt(t?.sparkDeg, 1)} °")
            KeyValue("INJ", fmt(t?.inj, 2))
            KeyValue(stringResource(R.string.diag_flow), "${fmt(t?.flowLph, 2)} L/h")
            KeyValue(stringResource(R.string.diag_battery), "${fmt(t?.batteryV, 2)} V", batteryColor(t?.batteryV))
        }
        Section(stringResource(R.string.diag_console)) {
            var cmd by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, singleLine = true,
                    label = { Text(stringResource(R.string.diag_command)) }, modifier = Modifier.weight(1f))
                Button(enabled = status == LinkStatus.CONNECTED && cmd.isNotBlank(),
                    onClick = { Link.send(cmd.trim()); cmd = "" },
                    modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.diag_send)) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                console.takeLast(60).forEach {
                    Text(it, color = Palette.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
