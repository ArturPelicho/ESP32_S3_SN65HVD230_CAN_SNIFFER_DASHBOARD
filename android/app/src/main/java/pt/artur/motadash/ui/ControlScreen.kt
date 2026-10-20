package pt.artur.motadash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import pt.artur.motadash.R
import pt.artur.motadash.AppLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.BleProtocol
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.MainActivity
import pt.artur.motadash.MotaService
import pt.artur.motadash.Orientation
import pt.artur.motadash.ParamInfo
import pt.artur.motadash.Permissions

/**
 * How the board's runtime parameters (main/rt_params.c) are shown: the
 * board stores integers, the app shows value / scale in a human unit.
 * Label, hint and a worded unit are string resources (null label = show the parameter name).
 */
private data class ParamSpec(@StringRes val label: Int?, val unit: String, val scale: Int = 1, val decimals: Int = 0,
                             val order: Int, @StringRes val hint: Int? = null, @StringRes val unitText: Int? = null)

private val PARAM_SPECS = mapOf(
    "TANK_DL" to ParamSpec(R.string.set_param_tank, "L", 10, 1, 0),
    "BASE_L100" to ParamSpec(R.string.set_param_base_l100, "L/100 km", 100, 2, 1, R.string.set_param_base_l100_hint),
    "FUEL_K" to ParamSpec(R.string.set_param_fuel_k, "", 1000, 3, 2, R.string.set_param_fuel_k_hint,
        unitText = R.string.set_unit_fuel_k),
    "RPM_SHIFT" to ParamSpec(R.string.set_param_rpm_shift, "rpm", order = 3),
    "GEARS" to ParamSpec(R.string.set_param_gears, "", order = 4),
    "SPD_HOLD_S" to ParamSpec(R.string.set_param_spd_hold, "s", order = 5),
    "SPD_SRC" to ParamSpec(R.string.set_param_spd_src, "", order = 6, hint = R.string.set_param_spd_src_hint),
)

private fun spec(p: ParamInfo) = PARAM_SPECS[p.name] ?: ParamSpec(null, p.unit, order = 99)

private fun shown(raw: Int, s: ParamSpec): String =
    if (s.scale == 1) raw.toString()
    else String.format(java.util.Locale.US, "%.${s.decimals}f", raw.toDouble() / s.scale)

private fun parsed(text: String, s: ParamSpec): Int? =
    text.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * s.scale).toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(activity: MainActivity) {
    val status by Link.status.collectAsStateWithLifecycle()
    val params by Link.params.collectAsStateWithLifecycle()
    val tankLine by Link.tankLine.collectAsStateWithLifecycle()
    val fillLine by Link.fillLine.collectAsStateWithLifecycle()
    val suggestedK by Link.suggestedK.collectAsStateWithLifecycle()
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val connected = status == LinkStatus.CONNECTED
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section(stringResource(R.string.settings_language)) {
            val current = remember { AppLocale.tag(ctx) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AppLocale.OPTIONS.forEach { (tag, name) ->
                    FilterChip(selected = current == tag,
                        onClick = { if (tag != current) { AppLocale.set(ctx, tag); activity.recreate() } },
                        label = { Text(name ?: stringResource(R.string.settings_language_phone)) })
                }
            }
        }
        Section(stringResource(R.string.settings_theme)) {
            var theme by remember { mutableStateOf(ThemeStore.get(ctx)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AppTheme.entries.forEach { t ->
                    FilterChip(selected = theme == t,
                        onClick = { theme = t; ThemeStore.set(ctx, t) },
                        label = { Text(stringResource(t.res)) })
                }
            }
        }
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
                KeyValue(stringResource(R.string.set_in_tank), "${fmt(t?.tankL, 2)} L")
                KeyValue(stringResource(R.string.set_range), stringResource(R.string.set_range_value, fmt(t?.rangeKm, 0), fmt(t?.rangeL100, 2)))
                KeyValue(stringResource(R.string.set_since_refuel), "${fmt(t?.fillKm, 1)} km · ${fmt(t?.fillAvg, 2)} L/100")
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

        Section(stringResource(R.string.set_board_params)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM") }) { Text(stringResource(R.string.set_read_board)) }
            }
            if (params.isEmpty()) {
                Text(stringResource(if (connected) R.string.set_reading else R.string.set_connect_for_params), color = Palette.label)
            }
            params.values.filter { it.name != "SPD_SRC" }.sortedBy { spec(it).order }
                .forEach { p -> ParamRow(p, connected, if (p.name == "BASE_L100") t?.rangeL100 else null) }
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

        Section(stringResource(R.string.set_screen)) {
            var orient by remember { mutableStateOf(Link.orientation(ctx)) }
            Text(stringResource(R.string.set_orientation), color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Orientation.entries.forEach { o ->
                    FilterChip(selected = orient == o, onClick = {
                        orient = o; Link.setOrientation(ctx, o); activity.applyOrientation()
                    }, label = { Text(o.label, fontSize = 12.sp) })
                }
            }
            Text(stringResource(R.string.set_orientation_help),
                color = Palette.label, fontSize = 12.sp)
        }

        Section(stringResource(R.string.set_link_phone)) {
            KeyValue(stringResource(R.string.set_status), statusText(status), if (connected) Palette.green else Palette.orange)
            KeyValue(stringResource(R.string.set_board), Link.savedAddress(ctx) ?: stringResource(R.string.set_board_none))
            KeyValue(stringResource(R.string.set_app_version), runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { Link.saveAddress(ctx, null); Link.service?.reconnect() ?: MotaService.start(ctx) }) {
                    Text(stringResource(R.string.set_find_other))
                }
                OutlinedButton(onClick = { MotaService.start(ctx) }) { Text(stringResource(R.string.set_start)) }
                OutlinedButton(onClick = { MotaService.stop(ctx) }) { Text(stringResource(R.string.set_stop)) }
            }
            val bg = Permissions.background(ctx)
            KeyValue(stringResource(R.string.set_location_always),
                stringResource(if (bg) R.string.set_yes else R.string.set_location_needed),
                if (bg) Palette.green else Palette.orange)
            if (!bg) Button(onClick = { activity.requestBackgroundLocation() }) { Text(stringResource(R.string.set_allow_always)) }
            val opt = activity.batteryOptimised()
            KeyValue(stringResource(R.string.set_battery_opt),
                stringResource(if (opt) R.string.set_battery_opt_on else R.string.set_battery_opt_off),
                if (opt) Palette.orange else Palette.green)
            if (opt) Button(onClick = { activity.requestNoBatteryOptimisation() }) { Text(stringResource(R.string.set_battery_opt_disable)) }
            OutlinedButton(onClick = { activity.openAppSettings() }) { Text(stringResource(R.string.set_app_settings)) }
            val scale by UiScale.value.collectAsStateWithLifecycle()
            Text(stringResource(R.string.set_ui_size), color = Palette.text, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiScale.steps.forEach { v ->
                    FilterChip(selected = scale == v, onClick = { UiScale.set(ctx, v) },
                        label = { Text("${(v * 100).toInt()}%") })
                }
            }
            var screenOn by remember { mutableStateOf(Link.keepScreenOn(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_keep_screen_on), color = Palette.text, modifier = Modifier.weight(1f))
                Switch(checked = screenOn, onCheckedChange = {
                    screenOn = it; Link.setKeepScreenOn(ctx, it); activity.applyScreenOn()
                })
            }
        }
    }
}

@Composable
private fun ParamRow(p: ParamInfo, connected: Boolean, live: Double?) {
    val ps = spec(p)
    val label = ps.label?.let { stringResource(it) } ?: p.name
    val unit = ps.unitText?.let { stringResource(it) } ?: ps.unit
    var text by remember(p.name, p.value) { mutableStateOf(shown(p.value, ps)) }
    val v = parsed(text, ps)
    val valid = v != null && v >= p.min && v <= p.max
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.Transparent)) {
        Text(label + if (unit.isNotEmpty()) " ($unit)" else "", color = Palette.text, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (ps.scale == 1) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.width(130.dp),
            )
            Button(enabled = connected && valid && v != p.value, onClick = { Link.send("PARAM SET ${p.name} $v") },
                modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.set_save)) }
            if (p.def != p.value) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM SET ${p.name} ${p.def}") },
                    modifier = Modifier.padding(start = 6.dp)) { Text(shown(p.def, ps)) }
            }
        }
        if (live != null) {
            Text(stringResource(R.string.set_range_uses, fmt(live, 2), unit), color = Palette.amber, fontSize = 12.sp)
        }
        ps.hint?.let { Text(stringResource(it), color = Palette.label, fontSize = 11.sp) }
        Text(stringResource(R.string.set_param_range, shown(p.min, ps), shown(p.max, ps), unit), color = Palette.dim, fontSize = 11.sp)
    }
}
