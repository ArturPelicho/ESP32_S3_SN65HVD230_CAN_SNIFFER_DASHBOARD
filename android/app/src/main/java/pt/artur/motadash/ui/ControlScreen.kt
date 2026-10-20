package pt.artur.motadash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import pt.artur.motadash.R
import androidx.compose.runtime.LaunchedEffect
import pt.artur.motadash.Comms
import pt.artur.motadash.AppLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.Link
import pt.artur.motadash.LinkStatus
import pt.artur.motadash.MainActivity
import pt.artur.motadash.MotaService
import pt.artur.motadash.Orientation
import pt.artur.motadash.ParamInfo
import pt.artur.motadash.Permissions
import pt.artur.motadash.Release
import pt.artur.motadash.Updater

/**
 * How the board's runtime parameters (main/rt_params.c) are shown: the
 * board stores integers, the app shows value / scale in a human unit.
 * Label, hint and a worded unit are string resources (null label = show the parameter name).
 */
private data class ParamSpec(@StringRes val label: Int?, val unit: String, val scale: Int = 1, val decimals: Int = 0,
                             val order: Int, @StringRes val hint: Int? = null, @StringRes val unitText: Int? = null)

private val PARAM_SPECS = mapOf(
    "TANK_DL" to ParamSpec(R.string.set_param_tank, "L", 10, 1, 0),
    "BASE_L100" to ParamSpec(R.string.set_param_base_l100, "L/100 km", 100, 2, 1, R.string.set_param_base_l100_hint),
    "FUEL_K" to ParamSpec(R.string.set_param_fuel_k, "", 1000, 3, 2, R.string.set_param_fuel_k_hint,
        unitText = R.string.set_unit_fuel_k),
    "RPM_SHIFT" to ParamSpec(R.string.set_param_rpm_shift, "rpm", order = 3),
    "GEARS" to ParamSpec(R.string.set_param_gears, "", order = 4),
    "SPD_HOLD_S" to ParamSpec(R.string.set_param_spd_hold, "s", order = 5),
    "SPD_SRC" to ParamSpec(R.string.set_param_spd_src, "", order = 6, hint = R.string.set_param_spd_src_hint),
)

private fun spec(p: ParamInfo) = PARAM_SPECS[p.name] ?: ParamSpec(null, p.unit, order = 99)

private fun shown(raw: Int, s: ParamSpec): String =
    if (s.scale == 1) raw.toString()
    else String.format(java.util.Locale.US, "%.${s.decimals}f", raw.toDouble() / s.scale)

private fun parsed(text: String, s: ParamSpec): Int? =
    text.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * s.scale).toInt() }


/** The groups of Definições, listed on the left; only the chosen one shows on the right. */
private enum class SetCat(@StringRes val res: Int) {
    LOOK(R.string.set_cat_look), BIKE(R.string.set_cat_bike),
    PHONE(R.string.set_cat_phone), ABOUT(R.string.set_cat_about),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(activity: MainActivity) {
    val status by Link.status.collectAsStateWithLifecycle()
    val params by Link.params.collectAsStateWithLifecycle()
    val t by Link.telemetry.collectAsStateWithLifecycle()
    val connected = status == LinkStatus.CONNECTED
    val ctx = LocalContext.current
    var cat by rememberSaveable { mutableStateOf(SetCat.LOOK) }

    Row(Modifier.fillMaxSize()) {
        // Fixed menu on the left.
        Column(
            Modifier.width(if (isLandscape()) 170.dp else 120.dp).fillMaxHeight().background(Palette.panel)
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            SetCat.entries.forEach { c ->
                val on = c == cat
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { cat = c }
                        .background(if (on) Palette.bg else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(if (on) Palette.amber else Color.Transparent))
                    Text(stringResource(c.res), color = if (on) Palette.amber else Palette.text, fontSize = 16.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        // The chosen group, with room to breathe.
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            when (cat) {
                SetCat.LOOK -> {
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
                    Section(stringResource(R.string.settings_theme)) {
                        var mode by remember { mutableStateOf(ThemeStore.mode(ctx)) }
                        var autoBy by remember { mutableStateOf(ThemeStore.autoBy(ctx)) }
                        var day by remember { mutableStateOf(ThemeStore.dayScheme(ctx)) }
                        var night by remember { mutableStateOf(ThemeStore.nightScheme(ctx)) }
                        var linkMap by remember { mutableStateOf(ThemeStore.linkMap(ctx)) }
                        val hasLight = remember { LightSensor.available(ctx) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            ThemeMode.entries.forEach { m ->
                                FilterChip(selected = mode == m,
                                    onClick = { mode = m; ThemeStore.setMode(ctx, m) },
                                    label = { Text(stringResource(m.res)) })
                            }
                        }
                        if (mode == ThemeMode.AUTO) {
                            Text(stringResource(R.string.theme_auto_by), color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AutoBy.entries.forEach { a ->
                                    FilterChip(selected = autoBy == a,
                                        onClick = { autoBy = a; ThemeStore.setAutoBy(ctx, a) },
                                        label = { Text(stringResource(a.res)) })
                                }
                            }
                            if (autoBy == AutoBy.LIGHT && !hasLight) {
                                Text(stringResource(R.string.theme_no_light_sensor), color = Palette.orange, fontSize = 12.sp)
                            }
                        }
                        Text(stringResource(R.string.theme_day_look), color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayScheme.entries.forEach { d ->
                                FilterChip(selected = day == d,
                                    onClick = { day = d; ThemeStore.setDayScheme(ctx, d) },
                                    label = { Text(stringResource(d.res)) })
                            }
                        }
                        Text(stringResource(R.string.theme_night_look), color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NightScheme.entries.forEach { n ->
                                FilterChip(selected = night == n,
                                    onClick = { night = n; ThemeStore.setNightScheme(ctx, n) },
                                    label = { Text(stringResource(n.res)) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.theme_link_map), color = Palette.text, modifier = Modifier.weight(1f))
                            Switch(checked = linkMap, onCheckedChange = { linkMap = it; ThemeStore.setLinkMap(ctx, it) })
                        }
                    }
                    Section(stringResource(R.string.set_screen)) {
                        var orient by remember { mutableStateOf(Link.orientation(ctx)) }
                        Text(stringResource(R.string.set_orientation), color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Orientation.entries.forEach { o ->
                                FilterChip(selected = orient == o, onClick = {
                                    orient = o; Link.setOrientation(ctx, o); activity.applyOrientation()
                                }, label = { Text(o.label, fontSize = 12.sp) })
                            }
                        }
                        Text(stringResource(R.string.set_orientation_help),
                            color = Palette.label, fontSize = 12.sp)
                        var screenOn by remember { mutableStateOf(Link.keepScreenOn(ctx)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.set_keep_screen_on), color = Palette.text, modifier = Modifier.weight(1f))
                            Switch(checked = screenOn, onCheckedChange = {
                                screenOn = it; Link.setKeepScreenOn(ctx, it); activity.applyScreenOn()
                            })
                        }
                    }
                    Section(stringResource(R.string.set_ui_size)) {
                        val scale by UiScale.value.collectAsStateWithLifecycle()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            UiScale.steps.forEach { v ->
                                FilterChip(selected = scale == v, onClick = { UiScale.set(ctx, v) },
                                    label = { Text("${(v * 100).toInt()}%") })
                            }
                        }
                    }
                }
                SetCat.BIKE -> {
                    Section(stringResource(R.string.set_link_phone)) {
                        KeyValue(stringResource(R.string.set_status), statusText(status), if (connected) Palette.green else Palette.orange)
                        KeyValue(stringResource(R.string.set_board), Link.savedAddress(ctx) ?: stringResource(R.string.set_board_none))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(onClick = { Link.saveAddress(ctx, null); Link.service?.reconnect() ?: MotaService.start(ctx) }) {
                                Text(stringResource(R.string.set_find_other))
                            }
                            OutlinedButton(onClick = { MotaService.start(ctx) }) { Text(stringResource(R.string.set_start)) }
                            OutlinedButton(onClick = { MotaService.stop(ctx) }) { Text(stringResource(R.string.set_stop)) }
                        }
                    }
                    Section(stringResource(R.string.set_board_params)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(enabled = connected, onClick = { Link.send("PARAM") }) { Text(stringResource(R.string.set_read_board)) }
                        }
                        if (params.isEmpty()) {
                            Text(stringResource(if (connected) R.string.set_reading else R.string.set_connect_for_params), color = Palette.label)
                        }
                        params.values.filter { it.name != "SPD_SRC" }.sortedBy { spec(it).order }
                            .forEach { p -> ParamRow(p, connected, if (p.name == "BASE_L100") t?.rangeL100 else null) }
                    }
                }
                SetCat.PHONE -> {
                    CommsSettings(activity)
                    Section(stringResource(R.string.set_cat_phone)) {
                        val bg = Permissions.background(ctx)
                        KeyValue(stringResource(R.string.set_location_always),
                            stringResource(if (bg) R.string.set_yes else R.string.set_location_needed),
                            if (bg) Palette.green else Palette.orange)
                        if (!bg) Button(onClick = { activity.requestBackgroundLocation() }) { Text(stringResource(R.string.set_allow_always)) }
                        val opt = activity.batteryOptimised()
                        KeyValue(stringResource(R.string.set_battery_opt),
                            stringResource(if (opt) R.string.set_battery_opt_on else R.string.set_battery_opt_off),
                            if (opt) Palette.orange else Palette.green)
                        if (opt) Button(onClick = { activity.requestNoBatteryOptimisation() }) { Text(stringResource(R.string.set_battery_opt_disable)) }
                        OutlinedButton(onClick = { activity.openAppSettings() }) { Text(stringResource(R.string.set_app_settings)) }
                    }
                }
                SetCat.ABOUT -> {
                    Section(stringResource(R.string.set_cat_about)) {
                        KeyValue(stringResource(R.string.set_app_version), runCatching {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        }.getOrNull() ?: "?")
                        UpdateBlock()
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamRow(p: ParamInfo, connected: Boolean, live: Double?) {
    val ps = spec(p)
    val label = ps.label?.let { stringResource(it) } ?: p.name
    val unit = ps.unitText?.let { stringResource(it) } ?: ps.unit
    var text by remember(p.name, p.value) { mutableStateOf(shown(p.value, ps)) }
    val v = parsed(text, ps)
    val valid = v != null && v >= p.min && v <= p.max
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.Transparent)) {
        Text(label + if (unit.isNotEmpty()) " ($unit)" else "", color = Palette.text, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (ps.scale == 1) KeyboardType.Number else KeyboardType.Decimal),
                modifier = Modifier.width(130.dp),
            )
            Button(enabled = connected && valid && v != p.value, onClick = { Link.send("PARAM SET ${p.name} $v") },
                modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.set_save)) }
            if (p.def != p.value) {
                OutlinedButton(enabled = connected, onClick = { Link.send("PARAM SET ${p.name} ${p.def}") },
                    modifier = Modifier.padding(start = 6.dp)) { Text(shown(p.def, ps)) }
            }
        }
        if (live != null) {
            Text(stringResource(R.string.set_range_uses, fmt(live, 2), unit), color = Palette.amber, fontSize = 12.sp)
        }
        ps.hint?.let { Text(stringResource(it), color = Palette.label, fontSize = 11.sp) }
        Text(stringResource(R.string.set_param_range, shown(p.min, ps), shown(p.max, ps), unit), color = Palette.dim, fontSize = 11.sp)
    }
}

/** "Check for update", then the newest GitHub build and a button to download and install it. */
@Composable
private fun UpdateBlock() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedBuild(ctx) }
    var release by remember { mutableStateOf<Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askAllow by remember { mutableStateOf(false) }
    val noNetwork = stringResource(R.string.upd_no_network)
    val noRelease = stringResource(R.string.upd_no_release)
    fun why(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> noNetwork
        is NoSuchElementException -> noRelease
        else -> e.message ?: e.javaClass.simpleName
    }

    val r = release
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; error = null; askAllow = false
            scope.launch {
                Updater.latest().onSuccess { release = it }.onFailure { error = why(it) }
                busy = false
            }
        }) { Text(stringResource(R.string.upd_check)) }
        if (busy && percent == null) Text(stringResource(R.string.upd_checking), color = Palette.label, fontSize = 12.sp)
    }
    if (r != null) {
        val newer = installed == null || r.build > installed
        KeyValue(stringResource(R.string.upd_latest),
            if (newer) stringResource(R.string.upd_available, r.version) else stringResource(R.string.upd_up_to_date, installed?.let { "0.1.$it" } ?: r.version),
            if (newer) Palette.amber else Palette.green)
        if (newer) {
            Button(enabled = !busy, onClick = {
                busy = true; error = null; askAllow = false
                scope.launch {
                    Updater.download(ctx, r) { percent = it }
                        .onSuccess { apk ->
                            if (Updater.canInstall(ctx)) Updater.install(ctx, apk)
                            else { askAllow = true; Updater.askInstallPermission(ctx) }
                        }
                        .onFailure { error = why(it) }
                    percent = null
                    busy = false
                }
            }) { Text(stringResource(R.string.upd_install)) }
        }
    }
    percent?.let { Text(stringResource(R.string.upd_downloading, it), color = Palette.label, fontSize = 12.sp) }
    if (askAllow) Text(stringResource(R.string.upd_allow_hint), color = Palette.orange, fontSize = 12.sp)
    error?.let { Text(stringResource(R.string.upd_error, it), color = Palette.red, fontSize = 12.sp) }
}

/** Calls and messages on screen: what shows, read aloud, which apps, and the permissions they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommsSettings(activity: MainActivity) {
    val ctx = LocalContext.current
    // Permissions change in other screens; look again every couple of seconds while this is open.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    tick
    Section(stringResource(R.string.comms_title)) {
        @Composable
        fun Toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            var on by remember { mutableStateOf(get()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Palette.text, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it; set(it) })
            }
        }
        Toggle(stringResource(R.string.comms_show_calls), { Comms.showCalls(ctx) }) { Comms.setShowCalls(ctx, it) }
        val phone = Comms.hasPhone(ctx)
        KeyValue(stringResource(R.string.comms_phone_perm), stringResource(if (phone) R.string.set_yes else R.string.comms_needed),
            if (phone) Palette.green else Palette.orange)
        if (!phone) Button(onClick = { activity.requestPhonePermissions() }) { Text(stringResource(R.string.comms_allow_phone)) }

        Toggle(stringResource(R.string.comms_show_msgs), { Comms.showMessages(ctx) }) { Comms.setShowMessages(ctx, it) }
        Toggle(stringResource(R.string.comms_show_text), { Comms.showText(ctx) }) { Comms.setShowText(ctx, it) }
        Toggle(stringResource(R.string.comms_speak), { Comms.speak(ctx) }) { Comms.setSpeak(ctx, it) }
        val listener = Comms.hasListener(ctx)
        KeyValue(stringResource(R.string.comms_notif_perm), stringResource(if (listener) R.string.set_yes else R.string.comms_needed),
            if (listener) Palette.green else Palette.orange)
        if (!listener) Button(onClick = { activity.openNotificationAccess() }) { Text(stringResource(R.string.comms_allow_notif)) }

        Text(stringResource(R.string.comms_apps), color = Palette.label, fontSize = 13.sp)
        val pm = ctx.packageManager
        val sms = Comms.smsApp(ctx)
        val offered = (listOfNotNull(sms) + Comms.KNOWN.keys).distinct().filter { p ->
            runCatching { pm.getApplicationInfo(p, 0) }.isSuccess
        }
        var chosen by remember { mutableStateOf(Comms.apps(ctx)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offered.forEach { p ->
                val name = if (p == sms) stringResource(R.string.comms_sms) else Comms.KNOWN[p] ?: p
                FilterChip(selected = p in chosen, onClick = {
                    Comms.setApp(ctx, p, p !in chosen); chosen = Comms.apps(ctx)
                }, label = { Text(name) })
            }
        }
    }
}
