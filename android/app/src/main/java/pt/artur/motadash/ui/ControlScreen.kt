package pt.artur.motadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import pt.artur.motadash.ParamInfo
import pt.artur.motadash.Permissions

/** Human labels for the board's runtime parameters (main/rt_params.c). */
private val PARAM_LABELS = mapOf(
    "FUEL_K" to "Constante de combustível (L/h ×1000 por INJ)",
    "TANK_DL" to "Capacidade do depósito (dL)",
    "SPD_SRC" to "Fonte de velocidade dos consumos",
    "SPD_HOLD_S" to "Manter média sem velocidade (s)",
    "GEARS" to "Número de mudanças",
    "BASE_L100" to "Consumo base p/ autonomia (L/100 ×100)",
    "RPM_SHIFT" to "Aviso de troca de mudança (rpm)",
)

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
                KeyValue("Autonomia", "${fmt(t?.rangeKm, 0)} km")
                KeyValue("Desde o abastecimento", "${fmt(t?.fillKm, 1)} km · ${fmt(t?.fillAvg, 2)} L/100")
            }
            tankLine?.let { Text(it, color = Palette.label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
            fillLine?.let { Text(it, color = Palette.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            suggestedK?.let { k ->
                val cur = params["FUEL_K"]?.value
                Text("Calibração: a bomba sugere FUEL_K = $k (atual ${cur ?: "?"})",
                    color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected, onClick = {
                        Link.send("PARAM SET FUEL_K $k"); Link.clearSuggestion()
                    }) { Text("Aplicar $k") }
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
            params.values.sortedBy { it.name }.forEach { p -> ParamRow(p, connected) }
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

        Section("Ligação e telemóvel") {
            KeyValue("Estado", statusText(status), if (connected) Palette.green else Palette.orange)
            KeyValue("Placa", Link.savedAddress(ctx) ?: "nenhuma (a procurar \"OBDII\")")
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
private fun ParamRow(p: ParamInfo, connected: Boolean) {
    var text by remember(p.name, p.value) { mutableStateOf(p.value.toString()) }
    val v = text.toIntOrNull()
    val valid = v != null && v >= p.min && v <= p.max
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color.Transparent)) {
        Text(PARAM_LABELS[p.name] ?: p.name, color = Palette.text, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(130.dp),
            )
            Button(enabled = connected && valid && v != p.value, onClick = { Link.send("PARAM SET ${p.name} $v") },
                modifier = Modifier.padding(start = 8.dp)) { Text("Guardar") }
            if (p.def != p.value) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM SET ${p.name} ${p.def}") },
                    modifier = Modifier.padding(start = 6.dp)) { Text("${p.def}") }
            }
        }
        Text("${p.name} · ${p.min}..${p.max} ${p.unit}", color = Palette.label, fontSize = 11.sp)
    }
}
