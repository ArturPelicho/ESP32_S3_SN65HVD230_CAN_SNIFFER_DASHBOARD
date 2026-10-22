package app.navexpress.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.navexpress.Units
import app.navexpress.MapStore
import app.navexpress.TrackMode
import androidx.compose.runtime.LaunchedEffect
import app.navexpress.LatLon
import app.navexpress.Places
import android.widget.Toast
import app.navexpress.TripRecorder
import app.navexpress.Planner
import app.navexpress.RouteOptions
import app.navexpress.RouteStyle
import app.navexpress.R
import app.navexpress.Track
import app.navexpress.TrackStore

/** Trajetos: import GPX/KML/KMZ/GeoJSON/TCX/FIT, show, colour, follow, rename or delete them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksPanel(onShow: (Track) -> Unit, onClose: () -> Unit, embedded: Boolean = false) {
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

    Column(Modifier.fillMaxSize().background(Palette.sheet)) {
        // Title and Fechar stay put; only the content below scrolls.
        if (!embedded) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tracks_title), color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.tracks_close)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TripSection()
            PlacesSection(onGo = onClose)
            Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.tracks_open_file)) }
            Text(stringResource(R.string.tracks_open_hint),
                color = Palette.label, fontSize = 12.sp)
            Text(stringResource(R.string.tracks_create_hint),
                color = Palette.label, fontSize = 12.sp)
            var roads by remember { mutableStateOf(MapStore.routeRoads(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tracks_roads), color = Palette.text, fontSize = 15.sp)
                    Text(stringResource(R.string.tracks_roads_hint),
                        color = Palette.label, fontSize = 12.sp)
                }
                Switch(checked = roads, onCheckedChange = { roads = it; MapStore.setRouteRoads(ctx, it) })
            }
            RouteOptionsSection()
            var mode by remember { mutableStateOf(MapStore.trackMode(ctx)) }
            Text(stringResource(R.string.track_mode_title), color = Palette.text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackMode.entries.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { mode = m; MapStore.setTrackMode(ctx, m) }, label = { Text(m.label) })
                }
            }
            Text(stringResource(R.string.track_mode_hint), color = Palette.label, fontSize = 12.sp)
            var banner by remember { mutableStateOf(MapStore.turnBanner(ctx)) }
            var voice by remember { mutableStateOf(MapStore.turnVoice(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.turn_show), color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = banner, onCheckedChange = { banner = it; MapStore.setTurnBanner(ctx, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.turn_voice), color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = voice, onCheckedChange = { voice = it; MapStore.setTurnVoice(ctx, it) })
            }
            Text(stringResource(R.string.turn_hint), color = Palette.label, fontSize = 12.sp)
            message?.let { Text(it, color = Palette.cyan, fontSize = 13.sp) }
            if (tracks.isEmpty()) Text(stringResource(R.string.tracks_empty), color = Palette.label, fontSize = 14.sp)

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
                                        Text(stringResource(R.string.tracks_save))
                                    }
                                    OutlinedButton(onClick = { renaming = null }) { Text(stringResource(R.string.tracks_cancel)) }
                                }
                            } else {
                                Text(t.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { renaming = t.id })
                            }
                            val parts = listOfNotNull(
                                Units.short(t.lengthM),
                                if (t.lines.size > 1 && t.plan.isEmpty()) stringResource(R.string.tracks_parts, t.lines.size) else null,
                                if (t.plan.isNotEmpty()) stringResource(R.string.tracks_waypoints, t.plan.size) else null,
                                if (t.waypoints.isNotEmpty()) stringResource(R.string.tracks_points, t.waypoints.size) else null,
                                if (following) stringResource(if (reversed) R.string.tracks_following_reversed else R.string.tracks_following) else null,
                            )
                            Text(parts.joinToString(" · "), color = Palette.label, fontSize = 13.sp)
                        }
                        Switch(checked = t.visible, onCheckedChange = { TrackStore.update(ctx, t.copy(visible = it)) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.lines.isNotEmpty()) {
                            if (following) {
                                OutlinedButton(onClick = { TrackStore.setActive(ctx, null) }) { Text(stringResource(R.string.tracks_stop_following)) }
                                FilterChip(selected = reversed, onClick = { TrackStore.setReversed(ctx, !reversed) },
                                    label = { Text(stringResource(R.string.tracks_reversed)) })
                            } else {
                                Button(onClick = { TrackStore.setActive(ctx, t.id); TrackStore.setReversed(ctx, false); onClose() }) {
                                    Text(stringResource(R.string.tracks_follow))
                                }
                            }
                        }
                        OutlinedButton(onClick = { onShow(t) }) { Text(stringResource(R.string.tracks_show_on_map)) }
                        if (t.plan.size >= 2) OutlinedButton(onClick = { Planner.replan(ctx, t.id) }) { Text(stringResource(R.string.route_replan)) }
                        if (confirmDelete == t.id) {
                            Button(onClick = { TrackStore.delete(ctx, t.id); confirmDelete = null }) { Text(stringResource(R.string.tracks_confirm_delete)) }
                            OutlinedButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.tracks_no)) }
                        } else {
                            OutlinedButton(onClick = { TrackStore.duplicate(ctx, t.id) }) { Text(stringResource(R.string.tracks_duplicate)) }
                            OutlinedButton(onClick = { confirmDelete = t.id }) { Text(stringResource(R.string.tracks_delete)) }
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
            Text(stringResource(R.string.tracks_footer), color = Palette.label, fontSize = 12.sp)
        }
    }
}

internal fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Palette.cyan)

/** Kind of road route (quick, economical, back roads) and what to avoid; used offline and online. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteOptionsSection() {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(RouteOptions.style(ctx)) }
    Text(stringResource(R.string.route_title), color = Palette.text, fontSize = 15.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteStyle.entries.forEach { s ->
            FilterChip(selected = style == s, onClick = { style = s; RouteOptions.setStyle(ctx, s) }, label = { Text(s.label) })
        }
    }
    Text(style.hint, color = Palette.label, fontSize = 12.sp)
    if (style == RouteStyle.FUN) {
        var detour by remember { mutableStateOf(RouteOptions.funDetour(ctx)) }
        Text(stringResource(R.string.route_fun_detour), color = Palette.text, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteOptions.DETOURS.forEach { d ->
                FilterChip(selected = detour == d, onClick = { detour = d; RouteOptions.setFunDetour(ctx, d) }, label = { Text("+$d%") })
            }
        }
        Text(stringResource(R.string.route_fun_detour_hint, detour), color = Palette.label, fontSize = 12.sp)
    }
    @Composable
    fun option(@androidx.annotation.StringRes label: Int, get: () -> Boolean, set: (Boolean) -> Unit, enabled: Boolean = true) {
        // Each route type has its own toggles: read again when the type changes.
        var on by remember(style) { mutableStateOf(get()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), color = if (enabled) Palette.text else Palette.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = on, enabled = enabled, onCheckedChange = { on = it; set(it) })
        }
    }
    val fun_ = style == RouteStyle.FUN
    option(R.string.route_avoid_motorways, { RouteOptions.avoidMotorways(ctx) }, { RouteOptions.setAvoidMotorways(ctx, it) })
    option(R.string.route_avoid_toll, { RouteOptions.avoidToll(ctx) }, { RouteOptions.setAvoidToll(ctx, it) })
    option(R.string.route_avoid_unpaved, { RouteOptions.avoidUnpaved(ctx) }, { RouteOptions.setAvoidUnpaved(ctx, it) })
    option(R.string.route_bypass_towns, { RouteOptions.bypassTowns(ctx) }, { RouteOptions.setBypassTowns(ctx, it) })
    option(R.string.route_keep_main, { RouteOptions.keepMain(ctx) }, { RouteOptions.setKeepMain(ctx, it) }, enabled = !fun_)
    Text(stringResource(R.string.route_keep_main_hint), color = Palette.label, fontSize = 12.sp)
    Text(stringResource(R.string.route_options_hint), color = Palette.label, fontSize = 12.sp)
}

/** Recording the ride: start/stop, how far and how long so far, and starting with the bike. */
@Composable
private fun TripSection() {
    val ctx = LocalContext.current
    val trip by TripRecorder.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.trip_title), color = Palette.text, fontSize = 15.sp)
    val r = trip
    if (r == null) {
        Button(onClick = { TripRecorder.start(ctx) }) { Text(stringResource(R.string.trip_start)) }
    } else {
        Text(stringResource(R.string.trip_so_far, Units.short(r.distanceM), tripTime(r.startedMs)), color = Palette.cyan, fontSize = 14.sp)
        Button(onClick = {
            val t = TripRecorder.stop(ctx)
            Toast.makeText(ctx, if (t != null) ctx.getString(R.string.trip_saved, t.name) else ctx.getString(R.string.trip_too_short), Toast.LENGTH_LONG).show()
        }) { Text(stringResource(R.string.trip_stop)) }
    }
    var auto by remember { mutableStateOf(TripRecorder.autoStart(ctx)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.trip_auto), color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = auto, onCheckedChange = { auto = it; TripRecorder.setAutoStart(ctx, it) })
    }
    Text(stringResource(R.string.trip_hint), color = Palette.label, fontSize = 12.sp)
}

@OptIn(ExperimentalLayoutApi::class)
/** Favourite places: go there (a new trajeto from the bike), rename, delete. Added with a long press on the map. */
@Composable
private fun PlacesSection(onGo: () -> Unit) {
    val ctx = LocalContext.current
    val places by Places.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Places.load(ctx) }
    Text(stringResource(R.string.place_title), color = Palette.text, fontSize = 15.sp)
    if (places.isEmpty()) Text(stringResource(R.string.place_hint), color = Palette.label, fontSize = 12.sp)
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    for (p in places) {
        Column(Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (editing == p.id) {
                var text by remember(p.id) { mutableStateOf(p.name) }
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Places.rename(ctx, p.id, text.trim().ifEmpty { p.name }); editing = null }) { Text(stringResource(R.string.tracks_save)) }
                    OutlinedButton(onClick = { editing = null }) { Text(stringResource(R.string.tracks_cancel)) }
                }
            } else {
                Text("★ " + p.name, color = Palette.text, fontSize = 15.sp, modifier = Modifier.clickable { editing = p.id })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val here = Places.here
                    Button(enabled = here != null, onClick = {
                        here?.let { Planner.goThere(ctx, it, LatLon(p.lat, p.lon), p.name); onGo() }
                    }) { Text(stringResource(R.string.place_go)) }
                    if (deleting == p.id) OutlinedButton(onClick = { Places.delete(ctx, p.id); deleting = null }) {
                        Text(stringResource(R.string.place_confirm_delete, p.name), color = Palette.red)
                    } else OutlinedButton(onClick = { deleting = p.id }) { Text(stringResource(R.string.place_delete)) }
                }
            }
        }
    }
}
