package pt.artur.motadash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.Units
import pt.artur.motadash.BleProtocol
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.R

/**
 * The Painel's own settings: fuel average and trip, refuelling and calibration,
 * speed source and gears. Sits inside the caller's scrolling Column (no scroll of its own).
 */
@Composable
fun DashSettings() {
    val status by Link.status.collectAsStateWithLifecycle()
    val params by Link.params.collectAsStateWithLifecycle()
    val tankLine by Link.tankLine.collectAsStateWithLifecycle()
    val fillLine by Link.fillLine.collectAsStateWithLifecycle()
    val suggestedK by Link.suggestedK.collectAsStateWithLifecycle()
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val connected = status == LinkStatus.CONNECTED

    Section(stringResource(R.string.set_fuel)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = connected, onClick = { Link.send("AVG RESET") }) { Text(stringResource(R.string.set_avg_reset)) }
            OutlinedButton(enabled = connected, onClick = { Link.send("TRIP") }) { Text(stringResource(R.string.set_show_trip)) }
        }
    }

    Section(stringResource(R.string.set_refuel)) {
        var litres by remember { mutableStateOf("") }
        var full by remember { mutableStateOf(true) }
        val value = litres.replace(',', '.').toDoubleOrNull()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = litres, onValueChange = { litres = it },
                label = { Text(stringResource(R.string.set_litres_added)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(170.dp),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.set_filled_up), color = Palette.label, fontSize = 12.sp)
                Switch(checked = full, onCheckedChange = { full = it })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(enabled = connected && value != null && value > 0, onClick = {
                val l = String.format(java.util.Locale.US, "%.2f", value)
                Link.send(if (full) "FILL FULL $l" else "FILL ADD $l")
                litres = ""
            }) { Text(stringResource(R.string.set_record)) }
            OutlinedButton(enabled = connected && value != null, onClick = {
                Link.send(String.format(java.util.Locale.US, "TANK SET %.2f", value))
                litres = ""
            }) { Text(stringResource(R.string.set_set_level)) }
            OutlinedButton(enabled = connected, onClick = { Link.send("TANK") }) { Text(stringResource(R.string.set_tank_state)) }
        }
        Text(
            stringResource(R.string.set_refuel_help),
            color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        if (t != null) {
            KeyValue(stringResource(R.string.set_in_tank), "${fmt(Units.vol(t?.tankL), 2)} ${Units.volUnit}")
            KeyValue(stringResource(R.string.set_range), stringResource(R.string.set_range_value, fmt(Units.dist(t?.rangeKm), 0), Units.distUnit, fmt(Units.cons(t?.rangeL100), 2), Units.consUnit))
            KeyValue(stringResource(R.string.set_since_refuel), "${fmt(Units.dist(t?.fillKm), 1)} ${Units.distUnit} · ${fmt(Units.cons(t?.fillAvg), 2)} ${Units.consUnit}")
        }
        tankLine?.let { Text(it, color = Palette.label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        fillLine?.let { Text(it, color = Palette.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        suggestedK?.let { k ->
            val cur = params["FUEL_K"]?.value
            fun lph(v: Int?) = if (v == null) "?" else String.format(java.util.Locale.US, "%.3f", v / 1000.0)
            Text(stringResource(R.string.set_calibration, lph(k), lph(cur)),
                color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = connected, onClick = {
                    Link.send("PARAM SET FUEL_K $k"); Link.clearSuggestion()
                }) { Text(stringResource(R.string.set_apply, lph(k))) }
                OutlinedButton(onClick = { Link.clearSuggestion() }) { Text(stringResource(R.string.set_ignore)) }
            }
        }
    }

    Section(stringResource(R.string.set_speed_source)) {
        val cur = params["SPD_SRC"]?.value
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            BleProtocol.SPEED_SOURCE_LABELS.take(4).forEachIndexed { i, label ->
                FilterChip(selected = cur == i, enabled = connected,
                    onClick = { Link.send("PARAM SET SPD_SRC $i") },
                    label = { Text(label.removePrefix("Vel_"), fontSize = 12.sp) })
            }
        }
        Text(stringResource(R.string.set_speed_source_help),
            color = Palette.label, fontSize = 12.sp)
    }

    Section(stringResource(R.string.set_gears)) {
        Text(stringResource(R.string.set_gears_help),
            color = Palette.label, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(enabled = connected, onClick = { Link.send("GEAR") }) { Text(stringResource(R.string.set_show_ratios)) }
            OutlinedButton(enabled = connected, onClick = { Link.send("GEAR RESET") },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.red)) { Text(stringResource(R.string.set_relearn)) }
        }
    }
}
