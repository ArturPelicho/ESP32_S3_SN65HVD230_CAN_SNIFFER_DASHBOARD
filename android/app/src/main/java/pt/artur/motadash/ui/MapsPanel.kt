package pt.artur.motadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pt.artur.motadash.FollowMode
import pt.artur.motadash.MapLanguage
import pt.artur.motadash.MapRegion
import pt.artur.motadash.MapStore
import pt.artur.motadash.MapTheme

/** Mapas: view options (follow mode, auto zoom, theme, names), then download, update, switch off or delete regions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapsPanel(
    theme: MapTheme,
    lang: MapLanguage,
    mode: FollowMode,
    autoZoom: Boolean,
    onTheme: (MapTheme) -> Unit,
    onLang: (MapLanguage) -> Unit,
    onMode: (FollowMode) -> Unit,
    onAutoZoom: (Boolean) -> Unit,
    cameraBeep: Boolean,
    onCameraBeep: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val index by MapStore.index.collectAsStateWithLifecycle()
    val installed by MapStore.installed.collectAsStateWithLifecycle()
    val downloads by MapStore.downloads.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("A procurar mapas novos…") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    fun check() {
        status = "A procurar mapas novos…"
        scope.launch {
            MapStore.fetchIndex(ctx)
                .onSuccess { status = "Mapas de ${pretty(it.version)}" }
                .onFailure { status = "Sem ligação: ${it.message}" + (index?.let { i -> " · lista de ${pretty(i.version)}" } ?: "") }
        }
    }
    LaunchedEffect(Unit) { check() }
    LaunchedEffect(Unit) { while (true) { MapStore.pollDownloads(ctx); delay(1000) } }

    val regions: List<MapRegion> = index?.regions ?: emptyList()
    val updates = regions.filter { r ->
        val have = installed.firstOrNull { it.id == r.id }
        have != null && index != null && have.version < index!!.version && downloads[r.id] == null
    }
    val free = MapStore.dir(ctx).usableSpace

    Column(Modifier.fillMaxSize().background(Color(0xF20B0E12))) {
        // Title and Fechar stay put; only the content below scrolls.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mapas offline", color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Fechar") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Vista", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Zoom automático", color = Palette.text, fontSize = 15.sp)
                    Text("Aproxima na cidade e afasta na estrada, conforme a velocidade.", color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = autoZoom, onCheckedChange = onAutoZoom)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bip ao aproximar de um radar", color = Palette.text, fontSize = 15.sp)
                    Text("Limites e radares vêm do OpenStreetMap: confirma sempre os sinais na estrada.",
                        color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = cameraBeep, onCheckedChange = onCameraBeep)
            }
            Text("Tema do mapa", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapTheme.entries.forEach { t ->
                    FilterChip(selected = theme == t, onClick = { onTheme(t) }, label = { Text(t.label) })
                }
            }
            Text("Automático muda para noite com o pôr do sol onde estás.", color = Palette.label, fontSize = 12.sp)
            Text("Nomes no mapa", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapLanguage.entries.forEach { l ->
                    FilterChip(selected = lang == l, onClick = { onLang(l) }, label = { Text(l.label) })
                }
            }
            Text("Regiões", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("$status · livre no telemóvel: ${mb(free)}", color = Palette.label, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { check() }) { Text("Procurar atualizações") }
                if (updates.isNotEmpty()) {
                    Button(onClick = { updates.forEach { MapStore.download(ctx, it) } }) { Text("Atualizar tudo (${updates.size})") }
                }
            }

            if (regions.isEmpty() && installed.isEmpty()) {
                Text("A lista de mapas aparece aqui quando houver rede.", color = Palette.label, fontSize = 14.sp)
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text("Procurar país ou região") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("✕") } },
            )
            // Installed first, then by name; the search narrows it with every letter typed.
            val q = plain(search)
            val ids = (regions.map { it.id } + installed.map { it.id }).distinct()
                .sortedWith(compareBy({ id -> installed.none { it.id == id } }, { id -> plain(regions.firstOrNull { it.id == id }?.name ?: id) }))
                .filter { id -> q.isEmpty() || plain(regions.firstOrNull { it.id == id }?.name ?: id).contains(q) || plain(id).contains(q) }
            if (ids.isEmpty() && q.isNotEmpty()) Text("Nenhuma região com \"$search\".", color = Palette.label, fontSize = 14.sp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            for (id in ids) {
                val r = regions.firstOrNull { it.id == id }
                val have = installed.firstOrNull { it.id == id }
                val dl = downloads[id]
                Column(
                    Modifier.fillMaxWidth().background(Palette.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r?.name ?: id, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            val size = r?.let {
                                mb(it.bytes) + (if (it.contoursBytes > 0) " + relevo ${mb(it.contoursBytes)}" else "") +
                                    if (it.limitsBytes > 0) " + limites ${mb(it.limitsBytes)}" else ""
                            }
                            val state = when {
                                dl != null && dl.failed -> "O download falhou"
                                dl != null -> "A descarregar " + (if (dl.total > 0) "${dl.done * 100 / dl.total}%" else "…")
                                have != null && index != null && have.version < index!!.version ->
                                    "Instalado (${pretty(have.version)}), há versão nova"
                                have != null -> "Instalado (${pretty(have.version)})"
                                else -> "Não instalado"
                            }
                            Text(listOfNotNull(size, state).joinToString(" · "), color = Palette.label, fontSize = 13.sp)
                        }
                        if (have != null) {
                            Switch(checked = have.enabled, onCheckedChange = { MapStore.setEnabled(ctx, id, it) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            dl != null && !dl.failed ->
                                OutlinedButton(onClick = { MapStore.cancel(ctx, id) }) { Text("Cancelar") }
                            r?.url != null && (have == null || dl?.failed == true) ->
                                Button(onClick = { MapStore.download(ctx, r) }) { Text("Descarregar") }
                            r?.url != null && index != null && have != null && have.version < index!!.version ->
                                Button(onClick = { MapStore.download(ctx, r) }) { Text("Atualizar") }
                        }
                        // Map already here from before speed limits existed: fetch only those, without the whole map.
                        if (r != null && r.limitsUrl != null && have != null && have.limits == null && dl == null) {
                            Button(onClick = { MapStore.download(ctx, r, limitsOnly = true) }) {
                                Text("Só limites e radares (${mb(r.limitsBytes)})")
                            }
                        }
                        if (have != null && dl == null) {
                            if (confirmDelete == id) {
                                Button(onClick = { MapStore.delete(ctx, id); confirmDelete = null }) { Text("Confirmar apagar") }
                                OutlinedButton(onClick = { confirmDelete = null }) { Text("Não") }
                            } else {
                                OutlinedButton(onClick = { confirmDelete = id }) { Text("Apagar") }
                            }
                        }
                    }
                }
            }

            }
            Text("Dados © OpenStreetMap, mapa base Protomaps, relevo Copernicus DEM.", color = Palette.label, fontSize = 12.sp)
        }
    }
}

/** Lower case without accents, for the search ("Açores" matches "acor"). */
private fun plain(s: String): String =
    java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** 20261019 -> 19/10/2026 */
private fun pretty(v: String): String =
    if (v.length == 8 && v.all { it.isDigit() }) "${v.substring(6)}/${v.substring(4, 6)}/${v.substring(0, 4)}" else v
