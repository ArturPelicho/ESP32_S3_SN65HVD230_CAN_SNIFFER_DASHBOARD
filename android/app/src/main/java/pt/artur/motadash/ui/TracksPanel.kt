package pt.artur.motadash.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.Track
import pt.artur.motadash.TrackStore

/** Trajetos: import GPX/KML/KMZ/GeoJSON/TCX/FIT, show, colour, follow, rename or delete them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksPanel(onShow: (Track) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val tracks by TrackStore.tracks.collectAsStateWithLifecycle()
    val active by TrackStore.active.collectAsStateWithLifecycle()
    val reversed by TrackStore.reversed.collectAsStateWithLifecycle()
    val message by TrackStore.message.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) TrackStore.import(ctx, uris)
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xF20B0E12)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trajetos", color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Fechar") }
        }
        Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Abrir ficheiro (GPX, KML, KMZ, GeoJSON, TCX, FIT)") }
        Text("Também podes abrir um ficheiro com a Mota Dash a partir do gestor de ficheiros ou do WhatsApp.",
            color = Palette.label, fontSize = 12.sp)
        message?.let { Text(it, color = Palette.cyan, fontSize = 13.sp) }
        if (tracks.isEmpty()) Text("Ainda não há trajetos.", color = Palette.label, fontSize = 14.sp)

        for (t in tracks.reversed()) {
            val following = active == t.id
            Column(
                Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp))
                    .border(2.dp, if (following) Palette.cyan else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(parseColor(t.color), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        if (renaming == t.id) {
                            var text by remember { mutableStateOf(t.name) }
                            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { TrackStore.update(ctx, t.copy(name = text.trim().ifEmpty { t.name })); renaming = null }) {
                                    Text("Guardar")
                                }
                                OutlinedButton(onClick = { renaming = null }) { Text("Cancelar") }
                            }
                        } else {
                            Text(t.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { renaming = t.id })
                        }
                        val parts = listOfNotNull(
                            "%.1f km".format(t.lengthM / 1000),
                            if (t.lines.size > 1) "${t.lines.size} partes" else null,
                            if (t.waypoints.isNotEmpty()) "${t.waypoints.size} pontos" else null,
                            if (following) "a seguir" + (if (reversed) " (sentido inverso)" else "") else null,
                        )
                        Text(parts.joinToString(" · "), color = Palette.label, fontSize = 13.sp)
                    }
                    Switch(checked = t.visible, onCheckedChange = { TrackStore.update(ctx, t.copy(visible = it)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.lines.isNotEmpty()) {
                        if (following) {
                            OutlinedButton(onClick = { TrackStore.setActive(ctx, null) }) { Text("Parar de seguir") }
                            FilterChip(selected = reversed, onClick = { TrackStore.setReversed(ctx, !reversed) },
                                label = { Text("Sentido inverso") })
                        } else {
                            Button(onClick = { TrackStore.setActive(ctx, t.id); TrackStore.setReversed(ctx, false); onClose() }) {
                                Text("Seguir")
                            }
                        }
                    }
                    OutlinedButton(onClick = { onShow(t) }) { Text("Ver no mapa") }
                    if (confirmDelete == t.id) {
                        Button(onClick = { TrackStore.delete(ctx, t.id); confirmDelete = null }) { Text("Confirmar apagar") }
                        OutlinedButton(onClick = { confirmDelete = null }) { Text("Não") }
                    } else {
                        OutlinedButton(onClick = { confirmDelete = t.id }) { Text("Apagar") }
                    }
                }
                // Appearance: colour and width.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackStore.COLORS.forEach { c ->
                        Box(
                            Modifier.size(28.dp).background(parseColor(c), CircleShape)
                                .border(3.dp, if (t.color == c) Color.White else Color.Transparent, CircleShape)
                                .clickable { TrackStore.update(ctx, t.copy(color = c)) }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackStore.WIDTHS.forEach { (w, label) ->
                        FilterChip(selected = t.width == w, onClick = { TrackStore.update(ctx, t.copy(width = w)) },
                            label = { Text(label) })
                    }
                }
            }
        }
        Text("Toca no nome para mudar. A cor e a espessura ficam guardadas em cada trajeto.", color = Palette.label, fontSize = 12.sp)
    }
}

internal fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Palette.cyan)
