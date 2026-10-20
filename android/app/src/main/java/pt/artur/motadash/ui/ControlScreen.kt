package pt.artur.motadash.ui

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
 */
private data class ParamSpec(val label: String, val unit: String, val scale: Int = 1, val decimals: Int = 0,
                             val order: Int, val hint: String? = null)

private val PARAM_SPECS = mapOf(
    "TANK_DL" to ParamSpec("Capacidade do depósito", "L", 10, 1, 0),
    "BASE_L100" to ParamSpec("Consumo para a autonomia", "L/100 km", 100, 2, 1,
        "Aprende sozinho: a autonomia mistura este valor com a média desde o abastecimento, " +
            "e a cada abastecimento (com 30 km ou mais) passa a ser a média dessa viagem."),
    "FUEL_K" to ParamSpec("Constante de combustível", "L/h por INJ", 1000, 3, 2,
        "Corrigida pela bomba: ao atestar duas vezes seguidas aparece a sugestão em baixo."),
    "RPM_SHIFT" to ParamSpec("Aviso de troca de mudança", "rpm", order = 3),
    "GEARS" to ParamSpec("Número de mudanças", "", order = 4),
    "SPD_HOLD_S" to ParamSpec("Manter média sem velocidade", "s", order = 5),
    "SPD_SRC" to ParamSpec("Fonte de velocidade dos consumos", "", order = 6, hint = "Escolhe em cima."),
)

private fun spec(p: ParamInfo) = PARAM_SPECS[p.name] ?: ParamSpec(p.name, p.unit, order = 99)

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
        Section("Consumos") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = connected, onClick = { Link.send("AVG RESET") }) { Text("Reset da média") }
                OutlinedButton(enabled = connected, onClick = { Link.send("TRIP") }) { Text("Ver TRIP") }
            }
        }

        Section("Abastecimento") {
            var litres by remember { mutableStateOf("") }
            var full by remember { mutableStateOf(true) }
            val value = litres.replace(',', '.').toDoubleOrNull()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = litres, onValueChange = { litres = it },
                    label = { Text("Litros metidos") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(170.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Atestei (cheio)", color = Palette.label, fontSize = 12.sp)
                    Switch(checked = full, onCheckedChange = { full = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = connected && value != null && value > 0, onClick = {
                    val l = String.format(java.util.Locale.US, "%.2f", value)
                    Link.send(if (full) "FILL FULL $l" else "FILL ADD $l")
                    litres = ""
                }) { Text("Registar") }
                OutlinedButton(enabled = connected && value != null, onClick = {
                    Link.send(String.format(java.util.Locale.US, "TANK SET %.2f", value))
                    litres = ""
                }) { Text("Definir nível") }
                OutlinedButton(enabled = connected, onClick = { Link.send("TANK") }) { Text("Estado") }
            }
            Text(
                "\"Atestei\": o depósito fica cheio e, desde o último atestar, os litros da bomba " +
                    "corrigem a constante. Sem atestar: soma ao nível. \"Definir nível\": litros que lá estão agora.",
                color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (t != null) {
                KeyValue("No depósito", "${fmt(t?.tankL, 2)} L")
                KeyValue("Autonomia", "${fmt(t?.rangeKm, 0)} km a ${fmt(t?.rangeL100, 2)} L/100")
                KeyValue("Desde o abastecimento", "${fmt(t?.fillKm, 1)} km · ${fmt(t?.fillAvg, 2)} L/100")
            }
            tankLine?.let { Text(it, color = Palette.label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            fillLine?.let { Text(it, color = Palette.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            suggestedK?.let { k ->
                val cur = params["FUEL_K"]?.value
                fun lph(v: Int?) = if (v == null) "?" else String.format(java.util.Locale.US, "%.3f", v / 1000.0)
                Text("Calibração: a bomba sugere ${lph(k)} L/h por INJ (atual ${lph(cur)})",
                    color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected, onClick = {
                        Link.send("PARAM SET FUEL_K $k"); Link.clearSuggestion()
                    }) { Text("Aplicar ${lph(k)}") }
                    OutlinedButton(onClick = { Link.clearSuggestion() }) { Text("Ignorar") }
                }
            }
        }

        Section("Fonte de velocidade para os consumos") {
            val cur = params["SPD_SRC"]?.value
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                BleProtocol.SPEED_SOURCE_LABELS.take(4).forEachIndexed { i, label ->
                    FilterChip(selected = cur == i, enabled = connected,
                        onClick = { Link.send("PARAM SET SPD_SRC $i") },
                        label = { Text(label.removePrefix("Vel_"), fontSize = 12.sp) })
                }
            }
            Text("As outras fontes continuam visíveis no Diagnóstico para comparar.",
                color = Palette.label, fontSize = 12.sp)
        }

        Section("Parâmetros da placa") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM") }) { Text("Ler da placa") }
            }
            if (params.isEmpty()) {
                Text(if (connected) "A ler…" else "Liga à mota para ver os parâmetros.", color = Palette.label)
            }
            params.values.filter { it.name != "SPD_SRC" }.sortedBy { spec(it).order }
                .forEach { p -> ParamRow(p, connected, if (p.name == "BASE_L100") t?.rangeL100 else null) }
        }

        Section("Mudanças") {
            Text("A placa aprende a relação km/h por 1000 rpm de cada mudança enquanto andas com GPS.",
                color = Palette.label, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(enabled = connected, onClick = { Link.send("GEAR") }) { Text("Ver relações") }
                OutlinedButton(enabled = connected, onClick = { Link.send("GEAR RESET") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.red)) { Text("Reaprender") }
            }
        }

        Section("Ecrã") {
            var orient by remember { mutableStateOf(Link.orientation(ctx)) }
            Text("Orientação", color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Orientation.entries.forEach { o ->
                    FilterChip(selected = orient == o, onClick = {
                        orient = o; Link.setOrientation(ctx, o); activity.applyOrientation()
                    }, label = { Text(o.label, fontSize = 12.sp) })
                }
            }
            Text("\"Horizontal invertida\" é a do DMD2 com o telemóvel rodado para a direita.",
                color = Palette.label, fontSize = 12.sp)
        }

        Section("Ligação e telemóvel") {
            KeyValue("Estado", statusText(status), if (connected) Palette.green else Palette.orange)
            KeyValue("Placa", Link.savedAddress(ctx) ?: "nenhuma (a procurar \"OBDII\")")
            KeyValue("Versão da app", runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { Link.saveAddress(ctx, null); Link.service?.reconnect() ?: MotaService.start(ctx) }) {
                    Text("Procurar outra")
                }
                OutlinedButton(onClick = { MotaService.start(ctx) }) { Text("Iniciar") }
                OutlinedButton(onClick = { MotaService.stop(ctx) }) { Text("Parar") }
            }
            val bg = Permissions.background(ctx)
            KeyValue("Localização \"sempre\"", if (bg) "sim" else "não (necessária p/ arrancar sozinha)",
                if (bg) Palette.green else Palette.orange)
            if (!bg) Button(onClick = { activity.requestBackgroundLocation() }) { Text("Permitir sempre") }
            val opt = activity.batteryOptimised()
            KeyValue("Otimização de bateria", if (opt) "ativa (pode matar a app)" else "desligada",
                if (opt) Palette.orange else Palette.green)
            if (opt) Button(onClick = { activity.requestNoBatteryOptimisation() }) { Text("Desligar otimização") }
            OutlinedButton(onClick = { activity.openAppSettings() }) { Text("Definições da app (Xiaomi: Arranque automático)") }
            val scale by UiScale.value.collectAsStateWithLifecycle()
            Text("Tamanho da interface", color = Palette.text, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiScale.steps.forEach { v ->
                    FilterChip(selected = scale == v, onClick = { UiScale.set(ctx, v) },
                        label = { Text("${(v * 100).toInt()}%") })
                }
            }
            var screenOn by remember { mutableStateOf(Link.keepScreenOn(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ecrã sempre ligado com a app aberta", color = Palette.text, modifier = Modifier.weight(1f))
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
    var text by remember(p.name, p.value) { mutableStateOf(shown(p.value, ps)) }
    val v = parsed(text, ps)
    val valid = v != null && v >= p.min && v <= p.max
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.Transparent)) {
        Text(ps.label + if (ps.unit.isNotEmpty()) " (${ps.unit})" else "", color = Palette.text, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (ps.scale == 1) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.width(130.dp),
            )
            Button(enabled = connected && valid && v != p.value, onClick = { Link.send("PARAM SET ${p.name} $v") },
                modifier = Modifier.padding(start = 8.dp)) { Text("Guardar") }
            if (p.def != p.value) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM SET ${p.name} ${p.def}") },
                    modifier = Modifier.padding(start = 6.dp)) { Text(shown(p.def, ps)) }
            }
        }
        if (live != null) {
            Text("Agora a autonomia usa ${fmt(live, 2)} ${ps.unit}", color = Palette.amber, fontSize = 12.sp)
        }
        ps.hint?.let { Text(it, color = Palette.label, fontSize = 11.sp) }
        Text("${shown(p.min, ps)} a ${shown(p.max, ps)} ${ps.unit}", color = Palette.dim, fontSize = 11.sp)
    }
}
