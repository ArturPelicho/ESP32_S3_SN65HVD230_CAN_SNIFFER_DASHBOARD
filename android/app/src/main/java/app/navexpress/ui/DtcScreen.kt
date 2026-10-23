package app.navexpress.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import app.navexpress.DtcNames
import app.navexpress.Link
import app.navexpress.LinkStatus
import app.navexpress.R

private val GROUPS = listOf(
    "STORED" to R.string.dtc_group_stored,
    "PENDING" to R.string.dtc_group_pending,
    "PERMANENT" to R.string.dtc_group_permanent,
)

@Composable
private fun noteText(note: String) = when (note) {
    "NONE" -> stringResource(R.string.dtc_note_none)
    "NO DATA" -> stringResource(R.string.dtc_note_no_data)
    "UNSUPPORTED" -> stringResource(R.string.dtc_note_unsupported)
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
        Section(stringResource(R.string.dtc_mil_section)) {
            val mil = dtc.mil
            KeyValue(stringResource(R.string.dtc_mil), when (mil) {
                null -> "--"; true -> stringResource(R.string.dtc_mil_on); false -> stringResource(R.string.dtc_mil_off) },
                when (mil) { null -> Palette.dim; true -> Palette.orange; false -> Palette.green })
            KeyValue(stringResource(R.string.dtc_mil_count), dtc.milCount?.toString() ?: "--")
            dtc.readAt?.let {
                KeyValue(stringResource(R.string.dtc_read_at), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = connected && !dtc.reading, onClick = { Link.readDtc() }) {
                    Text(if (dtc.reading) stringResource(R.string.dtc_reading) else stringResource(R.string.dtc_read))
                }
                OutlinedButton(enabled = connected, onClick = { confirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.red)) { Text(stringResource(R.string.dtc_clear)) }
            }
            dtc.clearResult?.let { Text(it, color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (!connected) Text(stringResource(R.string.dtc_connect_hint), color = Palette.label, fontSize = 12.sp)
        }

        GROUPS.forEach { (group, title) ->
            Section(stringResource(title)) {
                val codes = dtc.groups[group].orEmpty()
                if (codes.isEmpty()) {
                    Text(dtc.notes[group]?.let { noteText(it) } ?: if (dtc.reading) "…" else "--",
                        color = Palette.label, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                codes.forEach { code ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp)
                            .background(Palette.frame, RoundedCornerShape(8.dp)).padding(8.dp),
                    ) {
                        Text(code, color = if (group == "PENDING") Palette.yellow else Palette.orange,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(DtcNames.name(code) ?: stringResource(R.string.dtc_no_description), color = Palette.text, fontSize = 14.sp,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }

        Section(stringResource(R.string.dtc_notes)) {
            Text(
                stringResource(R.string.dtc_notes_text),
                color = Palette.label, fontSize = 12.sp,
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.dtc_confirm_title)) },
            text = { Text(stringResource(R.string.dtc_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Link.clearDtc()
                    // Read back what is left once the ECU has done it.
                    Link.readDtc(keepClearResult = true)
                }) { Text(stringResource(R.string.dtc_confirm_clear), color = Palette.red) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.dtc_cancel)) } },
        )
    }
}
