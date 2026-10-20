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
import androidx.compose.ui.res.stringResource
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
import pt.artur.motadash.R

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
    val searching = stringResource(R.string.maps_searching)
    var status by remember { mutableStateOf(searching) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    fun check() {
        status = searching
        scope.launch {
            MapStore.fetchIndex(ctx)
                .onSuccess { status = ctx.getString(R.string.maps_status_version, pretty(it.version)) }
                .onFailure {
                    status = index?.let { i -> ctx.getString(R.string.maps_status_offline_list, "${it.message}", pretty(i.version)) }
                        ?: ctx.getString(R.string.maps_status_offline, "${it.message}")
                }
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
            Text(stringResource(R.string.maps_title), color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.maps_close)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.maps_view), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.maps_auto_zoom), color = Palette.text, fontSize = 15.sp)
                    Text(stringResource(R.string.maps_auto_zoom_hint), color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = autoZoom, onCheckedChange = onAutoZoom)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.maps_camera_beep), color = Palette.text, fontSize = 15.sp)
                    Text(stringResource(R.string.maps_camera_beep_hint),
                        color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = cameraBeep, onCheckedChange = onCameraBeep)
            }
            Text(stringResource(R.string.maps_theme_title), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapTheme.entries.forEach { t ->
                    FilterChip(selected = theme == t, onClick = { onTheme(t) }, label = { Text(t.label) })
                }
            }
            Text(stringResource(R.string.maps_theme_hint), color = Palette.label, fontSize = 12.sp)
            Text(stringResource(R.string.maps_names_title), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapLanguage.entries.forEach { l ->
                    FilterChip(selected = lang == l, onClick = { onLang(l) }, label = { Text(l.label) })
                }
            }
            Text(stringResource(R.string.maps_regions), color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.maps_status_free, status, mb(free)), color = Palette.label, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { check() }) { Text(stringResource(R.string.maps_check_updates)) }
                if (updates.isNotEmpty()) {
                    Button(onClick = { updates.forEach { MapStore.download(ctx, it) } }) { Text(stringResource(R.string.maps_update_all, updates.size)) }
                }
            }

            if (regions.isEmpty() && installed.isEmpty()) {
                Text(stringResource(R.string.maps_list_needs_network), color = Palette.label, fontSize = 14.sp)
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text(stringResource(R.string.maps_search_hint)) }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("✕") } },
            )
            // Installed first, then by name; the search narrows it with every letter typed.
            val q = plain(search)
            val ids = (regions.map { it.id } + installed.map { it.id }).distinct()
                .sortedWith(compareBy({ id -> installed.none { it.id == id } }, { id -> plain(regions.firstOrNull { it.id == id }?.name ?: id) }))
                .filter { id -> q.isEmpty() || plain(regions.firstOrNull { it.id == id }?.name ?: id).contains(q) || plain(id).contains(q) }
            if (ids.isEmpty() && q.isNotEmpty()) Text(stringResource(R.string.maps_no_match, search), color = Palette.label, fontSize = 14.sp)
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
                                mb(it.bytes) + (if (it.contoursBytes > 0) " " + stringResource(R.string.maps_size_contours, mb(it.contoursBytes)) else "") +
                                    (if (it.shadeBytes > 0) " " + stringResource(R.string.maps_size_shade, mb(it.shadeBytes)) else "") +
                                    if (it.limitsBytes > 0) " " + stringResource(R.string.maps_size_limits, mb(it.limitsBytes)) else ""
                            }
                            val state = when {
                                dl != null && dl.failed -> stringResource(R.string.maps_state_failed)
                                dl != null -> stringResource(R.string.maps_state_downloading, if (dl.total > 0) "${dl.done * 100 / dl.total}%" else "…")
                                have != null && index != null && have.version < index!!.version ->
                                    stringResource(R.string.maps_state_installed_update, pretty(have.version))
                                have != null -> stringResource(R.string.maps_state_installed, pretty(have.version))
                                else -> stringResource(R.string.maps_state_not_installed)
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
                                OutlinedButton(onClick = { MapStore.cancel(ctx, id) }) { Text(stringResource(R.string.maps_cancel)) }
                            r?.url != null && (have == null || dl?.failed == true) ->
                                Button(onClick = { MapStore.download(ctx, r) }) { Text(stringResource(R.string.maps_download)) }
                            r?.url != null && index != null && have != null && have.version < index!!.version ->
                                Button(onClick = { MapStore.download(ctx, r) }) { Text(stringResource(R.string.maps_update)) }
                        }
                        // Map already here from before speed limits or relief existed: fetch only those, without the whole map.
                        val needLimits = r?.limitsUrl != null && have != null && have.limits == null
                        val needShade = r?.shadeUrl != null && have != null && have.shade == null
                        if (r != null && dl == null && (needLimits || needShade)) {
                            val what = when {
                                needLimits && needShade -> R.string.maps_add_limits_shade
                                needLimits -> R.string.maps_add_limits
                                else -> R.string.maps_add_shade
                            }
                            val bytes = (if (needLimits) r.limitsBytes else 0) + (if (needShade) r.shadeBytes else 0)
                            Button(onClick = { MapStore.download(ctx, r, extrasOnly = true) }) {
                                Text(stringResource(what, mb(bytes)))
                            }
                        }
                        if (have != null && dl == null) {
                            if (confirmDelete == id) {
                                Button(onClick = { MapStore.delete(ctx, id); confirmDelete = null }) { Text(stringResource(R.string.maps_confirm_delete)) }
                                OutlinedButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.maps_no)) }
                            } else {
                                OutlinedButton(onClick = { confirmDelete = id }) { Text(stringResource(R.string.maps_delete)) }
                            }
                        }
                    }
                }
            }

            }
            Text(stringResource(R.string.maps_attribution), color = Palette.label, fontSize = 12.sp)
        }
    }
}

/** Lower case without accents, for the search ("Açores" matches "acor"). */
private fun plain(s: String): String =
    java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** 20261019 -> 19/10/2026 */
private fun pretty(v: String): String =
    if (v.length == 8 && v.all { it.isDigit() }) "${v.substring(6)}/${v.substring(4, 6)}/${v.substring(0, 4)}" else v
