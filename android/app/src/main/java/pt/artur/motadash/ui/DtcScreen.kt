package pt.artur.motadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import pt.artur.motadash.DtcNames
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus

private val GROUPS = listOf(
    "STORED" to "Memorizados (acendem a luz de avaria)",
    "PENDING" to "Pendentes (vistos neste ciclo, ainda por confirmar)",
    "PERMANENT" to "Permanentes (só saem quando a centralina vê a reparação)",
)

private fun noteText(note: String) = when (note) {
    "NONE" -> "Nenhum"
    "NO DATA" -> "Sem resposta"
    "UNSUPPORTED" -> "A centralina não suporta"
    else -> note
}

/** Engine fault codes: OBD modes 03 / 07 / 0A, cleared with mode 04. */
@Composable
fun DtcScreen() {
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    val status by Link.status.collectAsStateWithLifecycle()
    val connected = status == LinkStatus.CONNECTED
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Luz de avaria do motor") {
            val mil = dtc.mil
            KeyValue("Luz (MIL)", when (mil) { null -> "--"; true -> "ACESA"; false -> "apagada" },
                when (mil) { null -> Palette.dim; true -> Palette.orange; false -> Palette.green })
            KeyValue("Erros memorizados segundo a centralina", dtc.milCount?.toString() ?: "--")
            dtc.readAt?.let {
                KeyValue("Lido às", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = connected && !dtc.reading, onClick = { Link.readDtc() }) {
                    Text(if (dtc.reading) "A ler…" else "Ler erros")
                }
                OutlinedButton(enabled = connected, onClick = { confirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.red)) { Text("Apagar erros") }
            }
            dtc.clearResult?.let { Text(it, color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (!connected) Text("Liga à mota (ignição ligada) para ler.", color = Palette.label, fontSize = 12.sp)
        }

        GROUPS.forEach { (group, title) ->
            Section(title) {
                val codes = dtc.groups[group].orEmpty()
                if (codes.isEmpty()) {
                    Text(dtc.notes[group]?.let(::noteText) ?: if (dtc.reading) "…" else "--",
                        color = Palette.label, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                codes.forEach { code ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp)
                            .background(Palette.frame, RoundedCornerShape(8.dp)).padding(8.dp),
                    ) {
                        Text(code, color = if (group == "PENDING") Palette.yellow else Palette.orange,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(DtcNames.name(code) ?: "Sem descrição", color = Palette.text, fontSize = 14.sp,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }

        Section("Notas") {
            Text(
                "Apagar limpa os erros memorizados e pendentes e os dados de congelamento, e reinicia os " +
                    "testes de prontidão da centralina. Faz-se com a ignição ligada e o motor parado. " +
                    "Se a avaria continuar, o erro volta a aparecer.",
                color = Palette.label, fontSize = 12.sp,
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Apagar os erros do motor?") },
            text = { Text("Ignição ligada e motor parado. Os erros apagados não se recuperam.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Link.clearDtc()
                    // Read back what is left once the ECU has done it.
                    Link.readDtc(keepClearResult = true)
                }) { Text("Apagar", color = Palette.red) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}
