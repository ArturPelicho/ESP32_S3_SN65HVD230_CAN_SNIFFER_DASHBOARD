package pt.artur.motadash

import android.content.Context
import btools.router.HintAccess
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * Road routes worked out on the phone, without network: the BRouter engine (built into the app) on
 * its routing data (OpenStreetMap, 5x5 degree tiles from brouter.de), downloaded per map region.
 */
object OfflineRouter {
    private const val SEGMENTS = "https://brouter.de/brouter/segments4/"
    /** BRouter's car-vario with the app's main-road rule (assets/navexpress.brf), options from [RouteOptions]. */
    const val PROFILE = "navexpress"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** Routing data being downloaded, by map region id. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val _changed = MutableStateFlow(0)
    /** Bumps whenever routing data is added or removed, so the screens re-read what is there. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "brouter/segments4").apply { mkdirs() }

    /** Tile name holding a point, as BRouter names them: W10_N35 covers 10-5 W, 35-40 N. */
    fun tile(lat: Double, lon: Double): String {
        val x = floor(lon / 5).toInt() * 5
        val y = floor(lat / 5).toInt() * 5
        return (if (x < 0) "W${-x}" else "E$x") + "_" + (if (y < 0) "S${-y}" else "N$y")
    }

    /** Tiles covering a region's bbox (west, south, east, north). */
    fun tilesFor(bbox: List<Double>): List<String> {
        if (bbox.size < 4) return emptyList()
        val out = LinkedHashSet<String>()
        var lon = floor(bbox[0] / 5) * 5
        while (lon < bbox[2]) {
            var lat = floor(bbox[1] / 5) * 5
            while (lat < bbox[3]) { out += tile(lat + 0.1, lon + 0.1); lat += 5 }
            lon += 5
        }
        return out.toList()
    }

    private fun file(ctx: Context, t: String) = File(dir(ctx), "$t.rd5")

    /** All of a region's routing data is on the phone. */
    fun installed(ctx: Context, r: MapRegion): Boolean = tilesFor(r.bbox).let { ts -> ts.isNotEmpty() && ts.all { file(ctx, it).exists() } }
    fun bytes(ctx: Context, r: MapRegion): Long = tilesFor(r.bbox).sumOf { file(ctx, it).takeIf { f -> f.exists() }?.length() ?: 0L }

    /** Both ends have routing data here. */
    fun has(ctx: Context, a: LatLon, b: LatLon) = file(ctx, tile(a.lat, a.lon)).exists() && file(ctx, tile(b.lat, b.lon)).exists()

    fun download(ctx: Context, r: MapRegion) {
        val app = ctx.applicationContext
        synchronized(jobs) {
            if (jobs[r.id]?.isActive == true) return
            jobs[r.id] = scope.launch {
                val todo = tilesFor(r.bbox).filter { !file(app, it).exists() }
                var done = 0L
                var total = 0L
                _downloads.value = _downloads.value + (r.id to DownloadState(0, 0))
                val ok = runCatching {
                    for (t in todo) {
                        val c = URL("$SEGMENTS$t.rd5").openConnection() as HttpURLConnection
                        c.connectTimeout = 15_000; c.readTimeout = 30_000
                        c.setRequestProperty("User-Agent", "NavExpress")
                        try {
                            // Sea tiles don't exist on the server: nothing to route there.
                            if (c.responseCode == 404) continue
                            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                            total += c.contentLengthLong.coerceAtLeast(0)
                            val tmp = File(dir(app), "$t.rd5.tmp")
                            c.inputStream.use { inp ->
                                tmp.outputStream().use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    var last = 0L
                                    while (isActive) {
                                        val n = inp.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n); done += n
                                        if (done - last > 512 * 1024) { last = done; _downloads.value = _downloads.value + (r.id to DownloadState(done, total)) }
                                    }
                                }
                            }
                            if (!isActive) { tmp.delete(); return@launch }
                            if (!tmp.renameTo(file(app, t))) error("rename")
                            _changed.value++
                        } finally {
                            c.disconnect()
                        }
                    }
                }.isSuccess
                _downloads.value = if (ok) _downloads.value - r.id else _downloads.value + (r.id to DownloadState(done, total, failed = true))
                _changed.value++
            }
        }
    }

    fun cancel(r: MapRegion) {
        synchronized(jobs) { jobs.remove(r.id)?.cancel() }
        _downloads.value = _downloads.value - r.id
    }

    /** Removes a region's routing data, keeping tiles another region with routing data still uses. */
    fun delete(ctx: Context, r: MapRegion, others: List<MapRegion>) {
        val keep = others.filter { it.id != r.id && installed(ctx, it) }.flatMap { tilesFor(it.bbox) }.toSet()
        tilesFor(r.bbox).filter { it !in keep }.forEach { file(ctx, it).delete() }
        _changed.value++
    }

    /** The profile files, copied out of the app once (BRouter reads them from disk). */
    private fun profile(ctx: Context): File {
        val d = File(ctx.filesDir, "brouter/profiles2").apply { mkdirs() }
        // Copied again after an update (the profile can change between versions).
        val stamp = File(d, "version")
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() }.getOrDefault("")
        val fresh = stamp.exists() && stamp.readText() == ver
        for ((n, asset) in listOf("$PROFILE.brf" to "$PROFILE.brf", "lookups.dat" to "brouter/lookups.dat")) {
            val f = File(d, n)
            if (!fresh || !f.exists() || f.length() == 0L) ctx.assets.open(asset).use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        stamp.writeText(ver)
        return File(d, "$PROFILE.brf")
    }

    private val lock = Any()

    /** The road route from a to b on the phone's routing data, or null (no data there, or no route). */
    fun route(ctx: Context, a: LatLon, b: LatLon): Routed? = synchronized(lock) {
        runCatching {
            val rc = RoutingContext()
            rc.localFunction = profile(ctx).absolutePath
            rc.keyValues = HashMap(RouteOptions.params(ctx))
            rc.turnInstructionMode = 2
            val wp: List<OsmNodeNamed> = RoutingParamCollector().readPositions(doubleArrayOf(a.lon, b.lon), doubleArrayOf(a.lat, b.lat))
            val re = RoutingEngine(null, null, dir(ctx), wp, rc)
            re.quite = true
            re.doRun(30_000)
            if (re.errorMessage != null) return@runCatching null
            val track = re.foundTrack ?: return@runCatching null
            val pts = track.nodes.map { n ->
                LatLon(n.iLat / 1e6 - 90, n.iLon / 1e6 - 180, n.elev.toDouble().takeIf { !it.isNaN() && it > -1000 })
            }
            if (pts.size < 2) return@runCatching null
            val turns = HintAccess.hints(track).mapNotNull { h ->
                val p = pts.getOrNull(h[0]) ?: return@mapNotNull null
                TurnKind.ofBrouter(h[1])?.let { TurnPt(p.lat, p.lon, it, h[2]) }
            }
            Routed(pts, turns)
        }.getOrNull()
    }
}
