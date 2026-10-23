package app.navexpress

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** The weather at one place and time: rain (mm/h), temperature (°C), wind and gusts (km/h, from where it blows), UV. */
data class WxNow(val rainMmH: Float, val tempC: Float?, val windKmh: Float?, val windFrom: Float?, val gustKmh: Float?, val uv: Float?)

/**
 * Forecasts from Open-Meteo (free, no key): every 15 minutes for two days, for a list of places.
 * The last answer is kept on the phone, so with no network the forecast carries on from it.
 */
object Weather {
    private const val API = "https://api.open-meteo.com/v1/forecast"
    private const val TIMEOUT_MS = 15_000
    private const val CHUNK = 50
    /** A kept forecast is still used for this long without network. */
    private const val KEEP_MS = 36 * 3600_000L

    /** One place's forecast, times in epoch seconds. */
    class Series(val lat: Double, val lon: Double, private val t: LongArray, private val rain: FloatArray, private val temp: FloatArray,
                 private val wind: FloatArray, private val dir: FloatArray, private val gust: FloatArray,
                 private val uvT: LongArray, private val uv: FloatArray) {

        /** The forecast at [ms] (epoch ms), or null outside it. */
        fun at(ms: Long): WxNow? {
            val s = ms / 1000
            if (t.size < 2 || s < t.first() - 900 || s > t.last()) return null
            var i = t.indexOfFirst { it >= s }
            if (i <= 0) i = 1
            val f = ((s - t[i - 1]).toFloat() / (t[i] - t[i - 1]).coerceAtLeast(1)).coerceIn(0f, 1f)
            fun lerp(a: FloatArray): Float? { val x = a[i - 1]; val y = a[i]; return if (x.isNaN() || y.isNaN()) null else x + (y - x) * f }
            // Rain is the amount in the 15 minutes before each time: the slot running now, as mm per hour.
            val r = rain[i].takeIf { !it.isNaN() } ?: 0f
            // Wind direction the short way round.
            val d = if (dir[i - 1].isNaN() || dir[i].isNaN()) null else {
                var dd = dir[i] - dir[i - 1]; if (dd > 180) dd -= 360; if (dd < -180) dd += 360
                ((dir[i - 1] + dd * f) + 360) % 360
            }
            val u = if (uvT.isEmpty()) null else uv[uvT.indices.minBy { abs(uvT[it] - s) }].takeIf { !it.isNaN() }
            return WxNow(r * 4, lerp(temp), lerp(wind), d, lerp(gust), u)
        }
    }

    /** Forecasts for [pts], fresh when the network allows, otherwise the ones kept under [key]; null when neither. */
    suspend fun fetch(ctx: Context, key: String, pts: List<LatLon>): List<Series>? = withContext(Dispatchers.IO) {
        if (pts.isEmpty()) return@withContext null
        val file = File(File(ctx.filesDir, "weather").apply { mkdirs() }, "$key.json")
        val fresh = runCatching {
            val all = download(pts)
            file.writeText(JSONObject().put("at", System.currentTimeMillis()).put("data", all).toString())
            all
        }.getOrNull()
        val data = fresh ?: runCatching {
            val o = JSONObject(file.readText())
            if (System.currentTimeMillis() - o.getLong("at") > KEEP_MS) null else o.getJSONArray("data")
        }.getOrNull() ?: return@withContext null
        // A kept forecast for other places (the map moved) is no use.
        val out = (0 until data.length()).mapNotNull { runCatching { parse(data.getJSONObject(it)) }.getOrNull() }
        if (out.size != pts.size || pts.indices.any { abs(out[it].lat - pts[it].lat) > 0.1 || abs(out[it].lon - pts[it].lon) > 0.1 }) null else out
    }

    /** Open-Meteo's answer for [pts], one object per place in the same order; throws without network. */
    internal fun download(pts: List<LatLon>): JSONArray {
        val all = JSONArray()
        for (part in pts.chunked(CHUNK)) {
            val v = get(url(part)).trimStart()
            if (v.startsWith("[")) { val a = JSONArray(v); for (i in 0 until a.length()) all.put(a.getJSONObject(i)) }
            else all.put(JSONObject(v))
        }
        if (all.length() != pts.size) error("got ${all.length()} of ${pts.size}")
        return all
    }

    internal fun series(o: JSONObject): Series = parse(o)

    /** When the forecast kept under [key] was fetched, or null. */
    fun keptAt(ctx: Context, key: String): Long? =
        runCatching { JSONObject(File(File(ctx.filesDir, "weather"), "$key.json").readText()).getLong("at") }.getOrNull()

    private fun url(pts: List<LatLon>): String {
        fun f(v: Double) = String.format(Locale.US, "%.3f", v)
        return API + "?latitude=" + pts.joinToString(",") { f(it.lat) } + "&longitude=" + pts.joinToString(",") { f(it.lon) } +
            "&minutely_15=precipitation,temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=uv_index&forecast_days=2&timeformat=unixtime&timezone=GMT"
    }

    private fun parse(o: JSONObject): Series {
        val m = o.getJSONObject("minutely_15")
        val t = m.getJSONArray("time")
        fun floats(name: String, from: JSONObject = m): FloatArray {
            val a = from.optJSONArray(name) ?: return FloatArray(t.length()) { Float.NaN }
            return FloatArray(a.length()) { if (a.isNull(it)) Float.NaN else a.getDouble(it).toFloat() }
        }
        val h = o.optJSONObject("hourly")
        val ht = h?.optJSONArray("time")
        return Series(o.getDouble("latitude"), o.getDouble("longitude"), LongArray(t.length()) { t.getLong(it) },
            floats("precipitation"), floats("temperature_2m"), floats("wind_speed_10m"), floats("wind_direction_10m"), floats("wind_gusts_10m"),
            if (ht == null) LongArray(0) else LongArray(ht.length()) { ht.getLong(it) },
            if (h == null) FloatArray(0) else floats("uv_index", h))
    }

    internal fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "NavExpress")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** The newest rain radar picture from RainViewer, as a tile URL template; null without network. */
    suspend fun radarTiles(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("https://api.rainviewer.com/public/weather-maps.json"))
            val past = o.getJSONObject("radar").getJSONArray("past")
            o.getString("host") + past.getJSONObject(past.length() - 1).getString("path") + "/256/{z}/{x}/{y}/2/1_1.png"
        }.getOrNull()
    }
}

/**
 * The weather layer's forecasts on a fixed grid of the world, a few cells across the screen at any
 * zoom. Cells are fetched as the map shows new ground and again when older than 15 minutes; they are
 * kept on the phone for 36 h, so ground already seen still has its weather with no network.
 */
object WxGrid {
    /** Cell sizes in degrees, finest first; the map picks the one that puts about six rows on screen. */
    private val SIZES = doubleArrayOf(0.025, 0.05, 0.1, 0.2, 0.4, 0.8, 1.6, 3.2)
    private const val ROWS = 6
    private const val FRESH_MS = 15 * 60_000L
    private const val KEEP_MS = 36 * 3600_000L
    private const val MAX_CELLS = 500
    /** At most this many places per fetch, so a big jump does not ask for hundreds at once. */
    private const val MAX_FETCH = 120

    private class Cell(val raw: JSONObject, val s: Weather.Series, val at: Long)

    private val cells = java.util.concurrent.ConcurrentHashMap<String, Cell>()
    private var loaded = false
    private var failedAt = 0L

    /** The cells covering a part of the map: [level] picks the size, rows [i0]..[i1] (south to north), columns [j0]..[j1]. */
    data class View(val level: Int, val i0: Int, val i1: Int, val j0: Int, val j1: Int) {
        val size: Double get() = SIZES[level]
        val rows: Int get() = i1 - i0 + 1
        val cols: Int get() = j1 - j0 + 1
    }

    /** One cell's centre and its forecast. */
    class Spot(val i: Int, val j: Int, val lat: Double, val lon: Double, val s: Weather.Series)

    /** The cells for the map between [south], [west], [north] and [east], with one cell more all round. */
    fun view(south: Double, west: Double, north: Double, east: Double): View {
        val span = (north - south).coerceAtLeast(0.005)
        val level = SIZES.indexOfFirst { it >= span / ROWS }.let { if (it < 0) SIZES.lastIndex else it }
        val d = SIZES[level]
        return View(level, floor(south / d).toInt() - 1, floor(north / d).toInt() + 1,
            floor(west / d).toInt() - 1, floor(east / d).toInt() + 1)
    }

    private fun key(l: Int, i: Int, j: Int) = "$l:$i:$j"
    private fun centre(v: View, i: Int, j: Int) = LatLon((i + 0.5) * v.size, (j + 0.5) * v.size)

    /** Fetches the cells of [v] that are missing or old; true when anything new came. */
    suspend fun update(ctx: Context, v: View): Boolean = withContext(Dispatchers.IO) {
        load(ctx)
        val now = System.currentTimeMillis()
        val want = ArrayList<Pair<String, LatLon>>()
        for (i in v.i0..v.i1) for (j in v.j0..v.j1) {
            val c = cells[key(v.level, i, j)]
            if (c == null || now - c.at > FRESH_MS) want += key(v.level, i, j) to centre(v, i, j)
        }
        // Without network, try again a minute later rather than on every look.
        if (want.isEmpty() || now - failedAt < 60_000L) return@withContext false
        val ask = want.take(MAX_FETCH)
        val got = runCatching { Weather.download(ask.map { it.second }) }.getOrNull()
            ?: run { failedAt = now; return@withContext false }
        for (n in ask.indices) runCatching {
            val o = got.getJSONObject(n)
            cells[ask[n].first] = Cell(o, Weather.series(o), now)
        }
        save(ctx)
        true
    }

    /** The cells of [v] that have a forecast. */
    fun spots(v: View): List<Spot> {
        val out = ArrayList<Spot>()
        val now = System.currentTimeMillis()
        for (i in v.i0..v.i1) for (j in v.j0..v.j1) {
            val c = cells[key(v.level, i, j)]?.takeIf { now - it.at < KEEP_MS } ?: continue
            val p = centre(v, i, j)
            out += Spot(i, j, p.lat, p.lon, c.s)
        }
        return out
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "weather").apply { mkdirs() }, "grid.json")

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            val now = System.currentTimeMillis()
            for (k in o.keys()) runCatching {
                val e = o.getJSONObject(k)
                val at = e.getLong("at")
                if (now - at < KEEP_MS) cells[k] = Cell(e.getJSONObject("d"), Weather.series(e.getJSONObject("d")), at)
            }
        }
    }

    private fun save(ctx: Context) {
        if (cells.size > MAX_CELLS) cells.entries.sortedBy { it.value.at }.take(cells.size - MAX_CELLS).forEach { cells.remove(it.key) }
        runCatching {
            val o = JSONObject()
            for ((k, c) in cells) o.put(k, JSONObject().put("at", c.at).put("d", c.raw))
            file(ctx).writeText(o.toString())
        }
    }
}

/** A stretch of the followed line: rain forecast for when the bike gets there, and rain there now (mm/h). */
class WxCell(val fromM: Double, val toM: Double, val etaMs: Long, val rainThen: Float, val rainNow: Float)

/**
 * The weather along a followed line: places every few kilometres, each read at the time the bike should
 * get there, so the progress bar shows rain that will have stopped, or will have started, by then.
 */
class RouteWeather(private val key: String, private val totalM: Double, pointAt: (Double) -> LatLon) {
    private val step = max(5000.0, totalM / 60)
    private val at = generateSequence(0.0) { it + step }.takeWhile { it < totalM }.toList() + totalM
    private val pts = at.map(pointAt)
    @Volatile private var series: List<Weather.Series>? = null
    @Volatile var fetchedMs: Long = 0L
        private set
    /** Forecast kept from an earlier fetch, not fresh. */
    @Volatile var stale = false
        private set

    val ready: Boolean get() = series != null

    /** Fetch again; true when there is a forecast to show (fresh or kept). */
    suspend fun refresh(ctx: Context): Boolean {
        val s = Weather.fetch(ctx, "route-" + key.replace(Regex("[^A-Za-z0-9_-]"), "_"), pts) ?: return series != null
        series = s
        val kept = Weather.keptAt(ctx, "route-" + key.replace(Regex("[^A-Za-z0-9_-]"), "_")) ?: System.currentTimeMillis()
        fetchedMs = kept
        stale = System.currentTimeMillis() - kept > 20 * 60_000L
        return true
    }

    /**
     * From [doneM] to the end: each stretch with the rain when the bike gets there. With [arrivalMs] the
     * time to each place is that share of the time left; without it, the current speed (at least 50 km/h).
     */
    fun ahead(doneM: Double, nowMs: Long, arrivalMs: Long?, speedMs: Double): List<WxCell> {
        val s = series ?: return emptyList()
        val left = (totalM - doneM).coerceAtLeast(1.0)
        val v = speedMs.coerceAtLeast(14.0)
        val out = ArrayList<WxCell>()
        for (i in at.indices) {
            val d = at[i]
            val from = if (i == 0) 0.0 else (at[i - 1] + d) / 2
            val to = if (i == at.size - 1) totalM else (d + at[i + 1]) / 2
            if (to < doneM) continue
            val eta = if (arrivalMs != null && arrivalMs > nowMs) nowMs + ((arrivalMs - nowMs) * ((d - doneM).coerceAtLeast(0.0) / left)).toLong()
                else nowMs + ((d - doneM).coerceAtLeast(0.0) / v * 1000).toLong()
            val then = s[i].at(eta)?.rainMmH ?: continue
            val now = s[i].at(nowMs)?.rainMmH ?: 0f
            out += WxCell(from.coerceAtLeast(doneM), to, eta, then, now)
        }
        return out
    }

    companion object {
        /** Rain worth showing (mm/h). */
        const val WET = 0.2f
    }
}
