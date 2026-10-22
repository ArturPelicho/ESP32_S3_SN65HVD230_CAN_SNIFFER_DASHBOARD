package pt.artur.motadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pt.artur.motadash.Call
import pt.artur.motadash.Comms
import pt.artur.motadash.Msg
import pt.artur.motadash.R

/** Calls and messages over whatever tab is open: big buttons, readable with gloves and a helmet on. */
@Composable
fun CommsOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val call by Comms.call.collectAsStateWithLifecycle()
    val msg by Comms.shown.collectAsStateWithLifecycle()
    Column(modifier.padding(8.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        call?.takeIf { Comms.showCalls(ctx) }?.let { CallCard(it) }
        msg?.let { m ->
            key(m) { MessageCard(m) }
        }
    }
}

/**
 * A call in one row, so the road and the next turn stay in view: who, then big Answer / Reject
 * buttons while it rings; once answered, a slim bar with the time and Hang up.
 */
@Composable
private fun CallCard(c: Call) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.ringing) { while (!c.ringing) { now = System.currentTimeMillis(); delay(1000) } }
    val who = c.name ?: c.number ?: stringResource(R.string.comms_unknown)
    Row(
        Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(16.dp))
            .border(2.dp, if (c.ringing) Palette.green else Palette.frame, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = if (c.ringing) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📞", fontSize = if (c.ringing) 24.sp else 18.sp)
        Column(Modifier.weight(1f)) {
            Text(who, color = Palette.text, fontSize = if (c.ringing) 20.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (c.ringing) stringResource(R.string.comms_calling)
                else stringResource(R.string.comms_in_call, ((now - c.since) / 1000).let { "%d:%02d".format(it / 60, it % 60) })
            Text(sub, color = Palette.label, fontSize = 13.sp, maxLines = 1)
        }
        if (c.ringing) BigButton(stringResource(R.string.comms_answer), Color(0xFF1E8E3E), Modifier.width(120.dp)) { Comms.answer(ctx) }
        BigButton(stringResource(if (c.ringing) R.string.comms_reject else R.string.comms_hang_up), Color(0xFFC62828),
            Modifier.width(if (c.ringing) 120.dp else 110.dp), slim = !c.ringing) { Comms.hangUp(ctx) }
    }
}

@Composable
private fun MessageCard(m: Msg) {
    val ctx = LocalContext.current
    LaunchedEffect(m) { delay(if (m.missedCall) 20_000 else 12_000); Comms.dismiss(m) }
    Row(
        Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(16.dp))
            .border(2.dp, if (m.missedCall) Palette.orange else Palette.cyan, RoundedCornerShape(16.dp))
            .clickable { Comms.dismiss(m) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (m.missedCall) "📵" else "💬", fontSize = 26.sp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("${m.app} · ${m.from}", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.text.isNotEmpty() && Comms.showText(ctx)) {
                Text(m.text, color = Palette.text, fontSize = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("✕", color = Palette.label, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun BigButton(label: String, color: Color, modifier: Modifier, slim: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(if (slim) 48.dp else 60.dp).background(color, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = if (slim) 17.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}
