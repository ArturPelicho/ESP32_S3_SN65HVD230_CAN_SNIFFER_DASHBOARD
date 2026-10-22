package app.navexpress

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trajetos drawn on the map from numbered waypoints (long press on the map).
 * A planned track keeps its waypoints in [Track.plan] and one line per leg in [Track.lines]:
 * lines[i] goes from plan[i] to plan[i + 1]. A leg starts straight and is swapped for the road
 * route when there is network, so planning always works offline.
 */
object Planner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    /** New trajeto with its first waypoint; it becomes the one being followed and added to. */
    fun start(ctx: Context, p: LatLon): Track {
        val t = Track(
            id = System.currentTimeMillis().toString(),
            name = L.s(R.string.tracks_default_name, SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())),
            lines = emptyList(), waypoints = emptyList(),
            color = TrackStore.COLORS[TrackStore.tracks.value.size % TrackStore.COLORS.size],
            width = 2, visible = true, plan = listOf(p.copy(ele = null)),
        )
        TrackStore.add(ctx, t)
        TrackStore.setActive(ctx, t.id)
        TrackStore.setReversed(ctx, false)
        return t
    }

    fun add(ctx: Context, id: String, p: LatLon) {
        val q = p.copy(ele = null)
        TrackStore.change(ctx, id) { t ->
            if (t.plan.isEmpty()) t.copy(plan = listOf(q))
            else t.relined(legs(t) + listOf(listOf(t.plan.last(), q))).copy(plan = t.plan + q)
        }
        reroute(ctx, id)
    }

    fun move(ctx: Context, id: String, k: Int, p: LatLon) {
        val q = p.copy(ele = null)
        TrackStore.change(ctx, id) { t ->
            if (k !in t.plan.indices) return@change t
            val plan = t.plan.toMutableList().also { it[k] = q }
            val lines = legs(t).toMutableList()
            if (k > 0) lines[k - 1] = listOf(plan[k - 1], q)
            if (k < plan.size - 1) lines[k] = listOf(q, plan[k + 1])
            t.relined(lines).copy(plan = plan)
        }
        reroute(ctx, id)
    }

    /** Puts a new waypoint on leg i, between waypoints i and i + 1. */
    fun insert(ctx: Context, id: String, i: Int, p: LatLon) {
        val q = p.copy(ele = null)
        TrackStore.change(ctx, id) { t ->
            if (i !in 0 until t.plan.size - 1) return@change t
            val plan = t.plan.toMutableList().also { it.add(i + 1, q) }
            val lines = legs(t).toMutableList()
            lines[i] = listOf(plan[i], q)
            lines.add(i + 1, listOf(q, plan[i + 2]))
            t.relined(lines).copy(plan = plan)
        }
        reroute(ctx, id)
    }

    /** Deletes waypoint k; the legs either side become one. The last waypoint takes the trajeto with it. */
    fun remove(ctx: Context, id: String, k: Int) {
        val t0 = TrackStore.tracks.value.firstOrNull { it.id == id } ?: return
        if (t0.plan.size <= 1) { TrackStore.delete(ctx, id); return }
        TrackStore.change(ctx, id) { t ->
            if (k !in t.plan.indices) return@change t
            val plan = t.plan.toMutableList().also { it.removeAt(k) }
            val lines = legs(t).toMutableList()
            when (k) {
                0 -> lines.removeAt(0)
                t.plan.size - 1 -> lines.removeAt(lines.size - 1)
                else -> { lines.removeAt(k); lines[k - 1] = listOf(plan[k - 1], plan[k]) }
            }
            t.relined(lines).copy(plan = plan)
        }
        reroute(ctx, id)
    }

    /** Rides it the other way: start and finish swap, waypoints renumber from the new start. */
    fun reverse(ctx: Context, id: String) {
        TrackStore.change(ctx, id) { t ->
            t.copy(lines = t.lines.reversed().map { it.reversed() }, plan = t.plan.reversed(), waypoints = t.waypoints.reversed(), times = emptyList(),
                secs = t.lines.indices.reversed().map { i -> t.secsOf(i)?.let { s -> s.reversed().map { s.last() - it } } ?: emptyList() })
        }
    }

    /** "Go there": a new trajeto from where the bike is to [to], followed at once; edit and save it as usual. */
    fun goThere(ctx: Context, here: LatLon, to: LatLon, name: String? = null) {
        val t = start(ctx, here)
        if (name != null) TrackStore.change(ctx, t.id) { it.copy(name = "→ $name") }
        add(ctx, t.id, to)
    }

    /** The legs of a planned track; straight ones if they don't match the waypoints (should not happen). */
    private fun legs(t: Track): List<List<LatLon>> =
        if (t.lines.size == t.plan.size - 1) t.lines
        else (0 until t.plan.size - 1).map { listOf(t.plan[it], t.plan[it + 1]) }

    private fun straight(t: Track, i: Int) =
        t.lines[i].size == 2 && t.lines[i][0] == t.plan[i] && t.lines[i][1] == t.plan[i + 1]

    /** Works out every leg again with the current route options; each old leg stays until its new one comes. */
    fun replan(ctx: Context, id: String) = reroute(ctx, id, all = true)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Trajetos whose road legs are being worked out right now. */
    val busy: StateFlow<Set<String>> = _busy

    private fun onLine(l: List<LatLon>, p: LatLon) = (0 until l.size - 1).any { toSegment(p, l[it], l[it + 1]).first < 20 }

    /**
     * Swaps straight legs (or, with [all], every leg) for road routes, one by one, while the trajeto is
     * unchanged around them.
     */
    fun reroute(ctx: Context, id: String, all: Boolean = false) {
        if (!MapStore.routeRoads(ctx)) return
        val app = ctx.applicationContext
        synchronized(jobs) {
            jobs[id]?.cancel()
            jobs[id] = scope.launch {
                val me = coroutineContext[Job]
                _busy.update { it + id }
                try {
                    val done = HashSet<Pair<LatLon, LatLon>>()
                    while (true) {
                        val t = TrackStore.tracks.value.firstOrNull { it.id == id } ?: break
                        if (t.lines.size != t.plan.size - 1) break
                        val i = t.lines.indices.firstOrNull { (all || straight(t, it)) && (t.plan[it] to t.plan[it + 1]) !in done } ?: break
                        val a = t.plan[i]
                        val b = t.plan[i + 1]
                        done += a to b
                        val road = Router.route(app, a, b) ?: continue
                        TrackStore.change(app, id) { cur ->
                            if (cur.plan.getOrNull(i) == a && cur.plan.getOrNull(i + 1) == b && cur.lines.size == cur.plan.size - 1)
                                cur.relined(cur.lines.toMutableList().also { it[i] = road.pts }).let { n ->
                                    // Turns of the old leg no longer lie on the line.
                                    val keep = cur.turns.filter { tp -> n.lines.any { l -> onLine(l, LatLon(tp.lat, tp.lon)) } }
                                    n.copy(turns = keep + road.turns, secs = n.secs.toMutableList().also { it[i] = road.secs })
                                }
                            else cur
                        }
                    }
                    // Turns of legs since moved or deleted no longer lie on the line.
                    TrackStore.change(app, id) { cur ->
                        val keep = cur.turns.filter { tp -> cur.lines.any { l -> onLine(l, LatLon(tp.lat, tp.lon)) } }
                        if (keep.size == cur.turns.size) cur else cur.copy(turns = keep)
                    }
                } finally {
                    // A newer job for the same trajeto keeps it busy.
                    synchronized(jobs) { if (jobs[id] === me) _busy.update { it - id } }
                }
            }
        }
    }
}

/**
 * Road routes (OpenStreetMap data): on the phone when its routing data covers both ends, else from the
 * public BRouter server (needs network).
 */
object Router {
    private const val SERVER = "https://brouter.de/brouter"
    /** The app's profile, uploaded to the server once per run (it keeps custom profiles for a while). */
    @Volatile private var customId: String? = null

    /** The road route from a to b, or null without routing data, network or route. */
    fun road(ctx: Context, a: LatLon, b: LatLon): List<LatLon>? = route(ctx, a, b)?.pts

    /** The road route with its turns, for the current route options. */
    fun route(ctx: Context, a: LatLon, b: LatLon): Routed? =
        (if (RouteOptions.style(ctx) == RouteStyle.FUN && !RouteOptions.avoidUnpaved(ctx)) dirt(ctx, a, b)
        else routeWith(ctx, RouteOptions.params(ctx), a, b))
            ?.let { r -> RoadNames.label(MapStore.installed.value.filter { it.enabled }.map { it.map }, r) }

    private fun routeWith(ctx: Context, params: Map<String, String>, a: LatLon, b: LatLon): Routed? =
        (if (OfflineRouter.has(ctx, a, b)) OfflineRouter.route(ctx, a, b, params) else null)
            ?: runCatching { online(ctx, params, a, b) }.getOrNull()

    /**
     * Fun: as much dirt as the allowed detour lets. The fastest route sets the yardstick; then routes that
     * make paved roads ever cheaper, until one is no longer than the yardstick plus the detour.
     */
    private fun dirt(ctx: Context, a: LatLon, b: LatLon): Routed? {
        val ref = routeWith(ctx, RouteOptions.params(ctx, RouteStyle.FAST, o = RouteStyle.FUN), a, b) ?: return routeWith(ctx, RouteOptions.params(ctx), a, b)
        val max = length(ref.pts) * (1 + RouteOptions.funDetour(ctx) / 100.0)
        for (paved in RouteOptions.PAVED_LEVELS) {
            val r = routeWith(ctx, RouteOptions.params(ctx, RouteStyle.FUN, paved), a, b) ?: continue
            if (length(r.pts) <= max) return r
        }
        return ref
    }

    private fun length(l: List<LatLon>) = (1 until l.size).sumOf { metres(l[it - 1], l[it]) }

    private fun online(ctx: Context, params: Map<String, String>, a: LatLon, b: LatLon): Routed? {
        val id = customId ?: upload(ctx)
        if (id != null) {
            fetch(id, params, a, b)?.let { return it }
            // Gone from the server: upload it again, once.
            upload(ctx)?.let { nid -> fetch(nid, params, a, b)?.let { return it } }
        }
        // Without the app's profile, the stock one (no main-road rule).
        return fetch("car-vario", params - "minor_penalty" - "dirt_kmh" - "paved_penalty", a, b)
    }

    private fun upload(ctx: Context): String? = runCatching {
        val text = ctx.assets.open("${OfflineRouter.PROFILE}.brf").bufferedReader().use { it.readText() }
        val c = URL("$SERVER/profile").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 15_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.setRequestProperty("User-Agent", "NavExpress")
        try {
            c.outputStream.use { it.write(text.toByteArray()) }
            if (c.responseCode != 200) return@runCatching null
            JSONObject(c.inputStream.bufferedReader().readText()).optString("profileid").takeIf { it.startsWith("custom_") }
        } finally {
            c.disconnect()
        }
    }.getOrNull().also { customId = it }

    private fun fetch(profile: String, params: Map<String, String>, a: LatLon, b: LatLon): Routed? {
        val q = String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f", a.lon, a.lat, b.lon, b.lat)
        val extra = params.entries.joinToString("") { "&profile:${it.key}=${it.value}" }
        val c = URL("$SERVER?alternativeidx=0&format=geojson&profile=$profile" + extra + "&timode=2&lonlats=" + q.replace("|", "%7C")).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "NavExpress")
        try {
            if (c.responseCode != 200) return null
            val f = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("features").getJSONObject(0)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = List(coords.length()) { i ->
                val p = coords.getJSONArray(i)
                LatLon(p.getDouble(1), p.getDouble(0), if (p.length() > 2) p.optDouble(2).takeIf { !it.isNaN() } else null)
            }
            if (pts.size < 2) return null
            // [index in the coordinates, command, roundabout exit, metres to the next, angle]
            val vh = f.optJSONObject("properties")?.optJSONArray("voicehints")
            val turns = if (vh == null) emptyList() else (0 until vh.length()).mapNotNull { i ->
                val h = vh.getJSONArray(i)
                val p = pts.getOrNull(h.getInt(0)) ?: return@mapNotNull null
                TurnKind.ofBrouter(h.getInt(1))?.let { TurnPt(p.lat, p.lon, it, h.optInt(2)) }
            }
            val tm = f.optJSONObject("properties")?.optJSONArray("times")
            val secs = if (tm == null || tm.length() != pts.size) emptyList() else List(tm.length()) { tm.getDouble(it) }
            return Routed(pts, turns, secs)
        } finally {
            c.disconnect()
        }
    }
}

/** GPX 1.1 of a track, for sharing or saving: one trkseg per line (all legs in one for a planned trajeto). */
object GpxWriter {
    fun write(t: Track): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"NavExpress\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(esc(t.name)).append("</name></metadata>\n")
        for (w in t.waypoints) wpt(w.lat, w.lon, w.name, null)
        t.plan.forEachIndexed { i, p -> wpt(p.lat, p.lon, "${i + 1}", L.s(R.string.tracks_gpx_waypoint_type)) }
        append("  <trk>\n    <name>").append(esc(t.name)).append("</name>\n")
        val segs = if (t.plan.isNotEmpty()) listOf(t.lines.flatten()) else t.lines
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        for ((si, s) in segs.withIndex()) {
            if (s.isEmpty()) continue
            val ts = t.times.getOrNull(si)?.takeIf { it.size == s.size && t.plan.isEmpty() }
            append("    <trkseg>\n")
            var last: LatLon? = null
            for ((pi, p) in s.withIndex()) {
                if (last != null && last.lat == p.lat && last.lon == p.lon) continue
                last = p
                append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
                p.ele?.let { append(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
                ts?.let { append("<time>").append(iso.format(Date(it[pi]))).append("</time>") }
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun StringBuilder.wpt(lat: Double, lon: Double, name: String, type: String?) {
        append(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\"><name>", lat, lon)).append(esc(name)).append("</name>")
        type?.let { append("<type>").append(esc(it)).append("</type>") }
        append("</wpt>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A file name from the track name. */
    fun fileName(t: Track) = t.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().ifEmpty { "trajeto" } + ".gpx"
}
