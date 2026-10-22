package pt.artur.navexpress.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import pt.artur.navexpress.Feedback
import pt.artur.navexpress.R
import pt.artur.navexpress.WhatsNew

/** On opening: a crash from last time offered as a report, otherwise what is new since the last version seen. */
@Composable
fun StartPrompts() {
    val ctx = LocalContext.current
    var crash by remember { mutableStateOf(Feedback.pendingCrash(ctx)) }
    var news by remember { mutableStateOf(WhatsNew.unseen(ctx)) }

    crash?.let { trace ->
        fun done() { Feedback.clearCrash(ctx); crash = null }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text(stringResource(R.string.fb_crash_prompt_title)) },
            text = { Text(stringResource(R.string.fb_crash_prompt_text)) },
            confirmButton = { Button(onClick = { Feedback.sendCrash(ctx, trace); done() }) { Text(stringResource(R.string.fb_crash_send)) } },
            dismissButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    OutlinedButton(onClick = { Feedback.shareCrash(ctx, trace); done() }) { Text(stringResource(R.string.fb_crash_share)) }
                    TextButton(onClick = { done() }) { Text(stringResource(R.string.fb_crash_ignore)) }
                }
            },
        )
        return
    }

    if (news.isEmpty()) return
    // One tap anywhere closes it, on the card or outside.
    val close = { WhatsNew.markSeen(ctx); news = emptyList() }
    Dialog(onDismissRequest = { close() }) {
        Surface(
            Modifier.widthIn(max = 520.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp).clickable { close() },
            shape = RoundedCornerShape(16.dp), color = Palette.sheet,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                Text(stringResource(R.string.wn_title, v), color = Palette.amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                news.forEach { Text("• $it", color = Palette.text, fontSize = 15.sp) }
                Text(stringResource(R.string.wn_close), color = Palette.label, fontSize = 12.sp)
            }
        }
    }
}
