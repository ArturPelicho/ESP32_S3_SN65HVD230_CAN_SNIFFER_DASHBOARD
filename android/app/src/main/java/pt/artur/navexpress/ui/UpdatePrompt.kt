package pt.artur.navexpress.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pt.artur.navexpress.R
import pt.artur.navexpress.Release
import pt.artur.navexpress.Updater

/** Looked for once each time the app starts; a newer build is offered with Update / Dismiss. */
private var checkedThisRun = false

@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<Release?>(null) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askAllow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (checkedThisRun) return@LaunchedEffect
        checkedThisRun = true
        val installed = Updater.installedBuild(ctx)
        Updater.latest().onSuccess { r -> if (installed == null || r.build > installed) offer = r }
    }
    val r = offer ?: return
    AlertDialog(
        onDismissRequest = { if (percent == null) offer = null },
        title = { Text(stringResource(R.string.upd_prompt_title, r.version)) },
        text = {
            Column {
                Text(stringResource(R.string.upd_prompt_text, Updater.installedBuild(ctx)?.let { "0.1.$it" } ?: "?", r.version))
                percent?.let { Text(stringResource(R.string.upd_downloading, it), fontSize = 13.sp) }
                if (askAllow) Text(stringResource(R.string.upd_allow_hint), color = Palette.orange, fontSize = 13.sp)
                error?.let { Text(stringResource(R.string.upd_error, it), color = Palette.red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(enabled = percent == null, onClick = {
                error = null; askAllow = false; percent = 0
                scope.launch {
                    Updater.download(ctx, r) { percent = it }
                        .onSuccess { apk ->
                            if (Updater.canInstall(ctx)) { Updater.install(ctx, apk); offer = null }
                            else { askAllow = true; Updater.askInstallPermission(ctx) }
                        }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                    percent = null
                }
            }) { Text(stringResource(R.string.upd_prompt_update)) }
        },
        dismissButton = { TextButton(enabled = percent == null, onClick = { offer = null }) { Text(stringResource(R.string.upd_prompt_dismiss)) } },
    )
}
