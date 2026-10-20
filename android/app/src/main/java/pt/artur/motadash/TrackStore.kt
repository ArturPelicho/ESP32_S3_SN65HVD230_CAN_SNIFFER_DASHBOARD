package pt.artur.motadash

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
) {
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
    val WIDTHS = listOf(1 to "Fino", 2 to "Normal", 3 to "Grosso")
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
                val name = displayName(app, uri) ?: uri.lastPathSegment ?: "trajeto"
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("sem acesso")
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
                if (done.isNotEmpty()) add("Importado: " + done.joinToString(", "))
                if (failed.isNotEmpty()) add("Não deu para ler: " + failed.joinToString(", "))
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
    }

    private fun fromJson(o: JSONObject): Track {
        val ls = o.getJSONArray("lines")
        val ws = o.optJSONArray("wpts") ?: JSONArray()
        val pl = o.optJSONArray("plan") ?: JSONArray()
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
private fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
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
