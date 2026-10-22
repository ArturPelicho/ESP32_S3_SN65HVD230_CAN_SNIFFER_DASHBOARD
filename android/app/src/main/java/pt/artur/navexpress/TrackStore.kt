package pt.artur.navexpress

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** A track on the phone: what was imported plus how it is drawn. */
data class Track(
    val id: String,
    val name: String,
    val lines: List<List<LatLon>>,
    val waypoints: List<Waypoint>,
    val color: String,
    val width: Int,          // 1 thin, 2 normal, 3 thick
    val visible: Boolean,
    /** Numbered waypoints of a trajeto planned on the map; empty for imported files. */
    val plan: List<LatLon> = emptyList(),
    /** Turns of the road legs (from the router), wherever they still lie on the line. */
    val turns: List<TurnPt> = emptyList(),
    /** Recorded trips: the time of each point (epoch ms), line by line like [lines]; empty otherwise. */
    val times: List<List<Long>> = emptyList(),
    /**
     * Road legs: the router's expected seconds at each point from the leg's start, line by line like
     * [lines]; a line without them (straight leg, imported track) has an empty or mismatched list.
     */
    val secs: List<List<Double>> = emptyList(),
) {
    /** The router's times for line [i], when they still match it. */
    fun secsOf(i: Int): List<Double>? = secs.getOrNull(i)?.takeIf { it.size == lines[i].size && it.size >= 2 }

    /** New lines, keeping the router's times of the lines that stay (the same objects). */
    fun relined(newLines: List<List<LatLon>>): Track = copy(lines = newLines, secs = newLines.map { nl ->
        lines.indexOfFirst { it === nl }.let { k -> if (k >= 0) secs.getOrNull(k) ?: emptyList() else emptyList() }
    })

    val lengthM: Double by lazy { lines.sumOf { l -> (1 until l.size).sumOf { metres(l[it - 1], l[it]) } } }
    val points: Int get() = lines.sumOf { it.size }
}

/** Where the bike is along the followed track. */
data class TrackProgress(
    val doneM: Double,       // along the track, in the riding direction
    val leftM: Double,
    val offM: Double,        // distance from the bike to the track
    val nearest: LatLon,
)

/** Imported GPX/KML/... tracks, kept as small JSON files in the app's storage. */
object TrackStore {
    val COLORS = listOf("#ff2d95", "#2f9bff", "#34d058", "#ff8a00", "#a259ff", "#00d1c1", "#ffe14d", "#ff3b30")
    val WIDTHS: List<Pair<Int, String>>
        get() = listOf(1 to L.s(R.string.tracks_width_thin), 2 to L.s(R.string.tracks_width_normal), 3 to L.s(R.string.tracks_width_thick))
    private const val PREFS = "tracks"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _active = MutableStateFlow<String?>(null)
    /** The track being followed, if any. */
    val active: StateFlow<String?> = _active.asStateFlow()
    private val _reversed = MutableStateFlow(false)
    val reversed: StateFlow<Boolean> = _reversed.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    /** Result of the last import, for the screen to show once. */
    val message: StateFlow<String?> = _message.asStateFlow()
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "tracks").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _tracks.value = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.id }
        _active.value = prefs(ctx).getString("active", null)?.takeIf { id -> _tracks.value.any { it.id == id } }
        _reversed.value = prefs(ctx).getBoolean("reversed", false)
    }

    fun clearMessage() { _message.value = null }

    /** Reads a file the user picked or opened with the app, in the background. */
    fun import(ctx: Context, uris: List<Uri>) {
        val app = ctx.applicationContext
        load(app)
        scope.launch {
            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val name = displayName(app, uri) ?: uri.lastPathSegment ?: L.s(R.string.tracks_fallback_name)
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error(L.s(R.string.tracks_err_no_access))
                    val p = TrackParser.parse(bytes, name)
                    val t = Track(
                        id = System.currentTimeMillis().toString() + done.size,
                        name = p.name?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        lines = p.lines, waypoints = p.waypoints,
                        color = COLORS[_tracks.value.size % COLORS.size], width = 2, visible = true,
                    )
                    save(app, t)
                    _tracks.update { it + t }
                    done += t.name
                }.onFailure { failed += "$name (${it.message})" }
            }
            _message.value = buildList {
                if (done.isNotEmpty()) add(L.s(R.string.tracks_imported, done.joinToString(", ")))
                if (failed.isNotEmpty()) add(L.s(R.string.tracks_import_failed, failed.joinToString(", ")))
            }.joinToString("\n")
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun update(ctx: Context, t: Track) {
        save(ctx, t)
        _tracks.update { list -> list.map { if (it.id == t.id) t else it } }
    }

    fun add(ctx: Context, t: Track) {
        load(ctx)
        save(ctx, t)
        _tracks.update { it + t }
    }

    /** Changes one track atomically (screen and route lookups may change it at once) and saves it. */
    fun change(ctx: Context, id: String, f: (Track) -> Track): Track? {
        var out: Track? = null
        _tracks.update { list -> list.map { if (it.id == id) f(it).also { n -> out = n } else it } }
        out?.let { save(ctx, it) }
        return out
    }

    /** Keeps a copy of a track in the app (a version to come back to before changing it). */
    fun duplicate(ctx: Context, id: String): Track? {
        val t = _tracks.value.firstOrNull { it.id == id } ?: return null
        val copy = t.copy(id = System.currentTimeMillis().toString(), name = L.s(R.string.tracks_copy_name, t.name),
            color = COLORS[(COLORS.indexOf(t.color) + 1).mod(COLORS.size)])
        add(ctx, copy)
        return copy
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        _tracks.update { list -> list.filter { it.id != id } }
        if (_active.value == id) setActive(ctx, null)
    }

    fun setActive(ctx: Context, id: String?) {
        _active.value = id
        prefs(ctx).edit().putString("active", id).apply()
        _tracks.value.firstOrNull { it.id == id && !it.visible }?.let { update(ctx, it.copy(visible = true)) }
    }

    fun setReversed(ctx: Context, on: Boolean) {
        _reversed.value = on
        prefs(ctx).edit().putBoolean("reversed", on).apply()
    }

    private fun save(ctx: Context, t: Track) = File(dir(ctx), "${t.id}.json").writeText(toJson(t).toString())

    private fun toJson(t: Track) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("color", t.color); put("width", t.width); put("visible", t.visible)
        put("lines", JSONArray(t.lines.map { l ->
            JSONArray(l.map { p -> JSONArray(listOfNotNull(p.lat, p.lon, p.ele)) })
        }))
        put("wpts", JSONArray(t.waypoints.map { w -> JSONArray(listOf(w.lat, w.lon, w.name)) }))
        if (t.plan.isNotEmpty()) put("plan", JSONArray(t.plan.map { p -> JSONArray(listOf(p.lat, p.lon)) }))
        if (t.times.isNotEmpty()) put("times", JSONArray(t.times.map { JSONArray(it) }))
        if (t.secs.any { it.isNotEmpty() }) put("secs", JSONArray(t.secs.map { l -> JSONArray(l.map { Math.round(it * 10) / 10.0 }) }))
        if (t.turns.isNotEmpty()) put("turns", JSONArray(t.turns.map { p -> JSONArray(listOf(p.lat, p.lon, p.kind.name, p.exit, p.road ?: "")) }))
    }

    private fun fromJson(o: JSONObject): Track {
        val ls = o.getJSONArray("lines")
        val ws = o.optJSONArray("wpts") ?: JSONArray()
        val pl = o.optJSONArray("plan") ?: JSONArray()
        val tu = o.optJSONArray("turns") ?: JSONArray()
        val tm = o.optJSONArray("times") ?: JSONArray()
        val sc = o.optJSONArray("secs") ?: JSONArray()
        return Track(
            id = o.getString("id"), name = o.getString("name"),
            lines = List(ls.length()) { i ->
                val l = ls.getJSONArray(i)
                List(l.length()) { k ->
                    val p = l.getJSONArray(k)
                    LatLon(p.getDouble(0), p.getDouble(1), if (p.length() > 2) p.getDouble(2) else null)
                }
            },
            waypoints = List(ws.length()) { i -> ws.getJSONArray(i).let { Waypoint(it.getDouble(0), it.getDouble(1), it.getString(2)) } },
            color = o.optString("color", COLORS[0]), width = o.optInt("width", 2), visible = o.optBoolean("visible", true),
            plan = List(pl.length()) { i -> pl.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
            times = List(tm.length()) { i -> tm.getJSONArray(i).let { a -> List(a.length()) { a.getLong(it) } } },
            secs = List(sc.length()) { i -> sc.getJSONArray(i).let { a -> List(a.length()) { a.getDouble(it) } } },
            turns = (0 until tu.length()).mapNotNull { i ->
                val a = tu.getJSONArray(i)
                TurnKind.entries.firstOrNull { it.name == a.optString(2) }?.let { TurnPt(a.getDouble(0), a.getDouble(1), it, a.optInt(3), a.optString(4).ifEmpty { null }) }
            },
        )
    }
}

/** Follows the bike along one track: how far done, how far left, how far off. */
class TrackFollower(track: Track, private val reversed: Boolean) {
    private val lines = if (reversed) track.lines.reversed().map { it.reversed() } else track.lines
    private val pts: List<LatLon> = lines.flatten()
    /** True where a new line starts: no segment joins it to the point before. */
    private val gap = BooleanArray(pts.size).also { g ->
        var k = 0
        for (l in lines) { if (k > 0 && k < g.size) g[k] = true; k += l.size }
    }
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + if (gap[i]) 0.0 else metres(pts[i - 1], pts[i])
    }
    val totalM = cum.lastOrNull() ?: 0.0
    private var lastIdx = -1

    /** The router's times of each line in the riding direction (null for a line without). */
    private val lineSecs: List<List<Double>?> = track.lines.indices.map { track.secsOf(it) }.let { l ->
        if (reversed) l.reversed().map { s -> s?.let { x -> x.reversed().map { x.last() - it } } } else l
    }
    /** Some of it has the router's expected times. */
    val timed = lineSecs.any { it != null }
    /** Expected seconds at each point from the start; lines without router times at [UNTIMED_KMH]. */
    private val cumT: DoubleArray? = if (!timed) null else DoubleArray(pts.size).also { c ->
        var k = 0
        for ((li, l) in lines.withIndex()) {
            val s = lineSecs[li]
            for (j in l.indices) {
                val idx = k + j
                if (idx == 0) continue
                c[idx] = c[idx - 1] + when {
                    j == 0 -> 0.0
                    s != null -> (s[j] - s[j - 1]).coerceAtLeast(0.0)
                    else -> metres(l[j - 1], l[j]) / (UNTIMED_KMH / 3.6)
                }
            }
            k += l.size
        }
    }

    /** The router's expected seconds from [d] metres along to the end, or null without router times. */
    fun secsLeft(d: Double): Double? {
        val c = cumT ?: return null
        if (pts.size < 2) return 0.0
        val i = cum.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }
        val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        return c.last() - (c[i - 1] + (c[i] - c[i - 1]) * t)
    }

    private companion object { const val UNTIMED_KMH = 50.0 }

    /**
     * The lines in the riding direction cut where the bike is, [d] metres along: each piece with the
     * index of the track's own line it comes from and whether it is already ridden.
     */
    fun pieces(d: Double): List<Triple<Int, List<LatLon>, Boolean>> {
        val out = ArrayList<Triple<Int, List<LatLon>, Boolean>>()
        var k = 0
        for ((li, l) in lines.withIndex()) {
            val own = if (reversed) lines.size - 1 - li else li
            if (l.size >= 2) {
                val start = cum[k]; val end = cum[k + l.size - 1]
                when {
                    end <= d -> out += Triple(own, l, true)
                    start >= d -> out += Triple(own, l, false)
                    else -> {
                        val cut = pointAt(d)
                        val j = (k until k + l.size).first { cum[it] > d } - k
                        out += Triple(own, l.subList(0, j) + cut, true)
                        out += Triple(own, listOf(cut) + l.subList(j, l.size), false)
                    }
                }
            }
            k += l.size
        }
        return out
    }

    /** End of the line [d] metres along is in (where its next waypoint is): the track's own line index and metres along, or null on the last. */
    fun legEnd(d: Double): Pair<Int, Double>? {
        var k = 0
        for ((li, l) in lines.withIndex()) {
            val end = cum[(k + l.size - 1).coerceAtLeast(0)]
            if (li < lines.size - 1 && end > d) return (if (reversed) lines.size - 1 - li else li) to end
            k += l.size
        }
        return null
    }

    /** The point [d] metres along the track (clamped to its ends). */
    fun pointAt(d: Double): LatLon {
        if (pts.size < 2) return pts.firstOrNull() ?: LatLon(0.0, 0.0)
        val i = cum.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }
        val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        val a = pts[i - 1]; val b = pts[i]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    fun locate(lat: Double, lon: Double): TrackProgress? {
        if (pts.size < 2) return null
        val here = LatLon(lat, lon)
        // Near the last match first (tracks that cross themselves), the whole track when lost.
        fun best(from: Int, to: Int): Triple<Int, Double, Double> {
            var bi = from; var bd = Double.MAX_VALUE; var bt = 0.0
            for (i in from until to) {
                if (gap[i + 1]) continue
                val (d, t) = toSegment(here, pts[i], pts[i + 1])
                if (d < bd) { bd = d; bi = i; bt = t }
            }
            return Triple(bi, bd, bt)
        }
        var r = if (lastIdx >= 0) best(maxOf(0, lastIdx - 50), minOf(pts.size - 1, lastIdx + 300)) else null
        if (r == null || r.second > 80) r = best(0, pts.size - 1)
        val (i, d, t) = r
        lastIdx = i
        val segLen = cum[i + 1] - cum[i]
        val done = cum[i] + t * segLen
        val a = pts[i]; val b = pts[i + 1]
        return TrackProgress(done, totalM - done, d, LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
    }
}

/** Flat-earth metres between two points (fine for track segments). */
fun metres(a: LatLon, b: LatLon): Double {
    val dx = (b.lon - a.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
    val dy = (b.lat - a.lat) * 110_540.0
    return sqrt(dx * dx + dy * dy)
}

/** Distance from p to segment a-b in metres, and where along it (0..1) the closest point is. */
internal fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
    val k = 111_320.0 * cos(Math.toRadians(p.lat))
    val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_540.0
    val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_540.0
    val vx = bx - ax; val vy = by - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * vx; val cy = ay + t * vy
    return sqrt(cx * cx + cy * cy) to t
}

/** Bearing from a to b in degrees. */
fun bearing(a: LatLon, b: LatLon): Double {
    val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
    val dy = b.lat - a.lat
    return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
}
