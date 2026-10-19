package pt.artur.motadash

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable map region, as listed in maps-index-<version>.json. */
data class MapRegion(
    val id: String,
    val name: String,
    val bbox: List<Double>,        // west, south, east, north
    val file: String,
    val bytes: Long,
    val url: String?,
    val contoursFile: String?,
    val contoursBytes: Long,
    val contoursUrl: String?,
)

data class MapIndex(val version: String, val regions: List<MapRegion>)

/** A region file on the phone. */
data class InstalledMap(val id: String, val version: String, val enabled: Boolean, val map: File, val contours: File?)

enum class MapTheme(val label: String) { AUTO("Automático"), DAY("Dia"), NIGHT("Noite") }
enum class MapLanguage(val label: String) { PT("Português"), LOCAL("Língua local") }

/** State of a region's download, for the Mapas list. */
data class DownloadState(val done: Long, val total: Long, val failed: Boolean = false)

/**
 * Offline maps: the index of regions published by the "Map data" workflow
 * (GitHub release maps-<date>), the files on the phone, and their downloads.
 */
object MapStore {
    private const val RELEASES =
        "https://api.github.com/repos/ArturPelicho/ESP32_S3_SN65HVD230_CAN_SNIFFER_DASHBOARD/releases?per_page=50"
    private const val PREFS = "maps"

    private val _index = MutableStateFlow<MapIndex?>(null)
    val index: StateFlow<MapIndex?> = _index.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledMap>>(emptyList())
    val installed: StateFlow<List<InstalledMap>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(ctx: Context): File = (ctx.getExternalFilesDir("maps") ?: File(ctx.filesDir, "maps")).apply { mkdirs() }

    fun theme(ctx: Context): MapTheme =
        MapTheme.entries.firstOrNull { it.name == prefs(ctx).getString("theme", null) } ?: MapTheme.AUTO
    fun setTheme(ctx: Context, t: MapTheme) = prefs(ctx).edit().putString("theme", t.name).apply()
    fun language(ctx: Context): MapLanguage =
        MapLanguage.entries.firstOrNull { it.name == prefs(ctx).getString("lang", null) } ?: MapLanguage.PT
    fun setLanguage(ctx: Context, l: MapLanguage) = prefs(ctx).edit().putString("lang", l.name).apply()

    /** Loads what is on the phone and the last index seen (works offline). */
    fun load(ctx: Context) {
        prefs(ctx).getString("index", null)?.let { runCatching { _index.value = parseIndex(JSONObject(it), null) } }
        refreshInstalled(ctx)
        pollDownloads(ctx)
    }

    private fun refreshInstalled(ctx: Context) {
        val p = prefs(ctx)
        val d = dir(ctx)
        _installed.value = (p.getStringSet("ids", emptySet()) ?: emptySet()).sorted().mapNotNull { id ->
            val ver = p.getString("ver_$id", null) ?: return@mapNotNull null
            val map = File(d, p.getString("file_$id", null) ?: return@mapNotNull null)
            if (!map.exists()) return@mapNotNull null
            val contours = p.getString("contours_$id", null)?.let { File(d, it) }?.takeIf { it.exists() }
            InstalledMap(id, ver, p.getBoolean("on_$id", true), map, contours)
        }
    }

    /** Asks GitHub for the newest maps-* release and its index. */
    suspend fun fetchIndex(ctx: Context): Result<MapIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = JSONArray(get(RELEASES))
            var best: JSONObject? = null
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (!tag.startsWith("maps-") || r.optBoolean("draft")) continue
                if (best == null || tag > best.optString("tag_name")) best = r
            }
            val rel = best ?: error("Ainda não há mapas publicados")
            val assets = rel.getJSONArray("assets")
            val urls = HashMap<String, String>()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                urls[a.getString("name")] = a.getString("browser_download_url")
            }
            val indexName = urls.keys.firstOrNull { it.startsWith("maps-index-") } ?: error("Release sem índice")
            val json = JSONObject(get(urls.getValue(indexName)))
            val idx = parseIndex(json, urls)
            // Keep the urls inside the saved copy so downloads work from it later.
            prefs(ctx).edit().putString("index", toJson(idx).toString()).apply()
            _index.value = idx
            idx
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MotaDash")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseIndex(o: JSONObject, urls: Map<String, String>?): MapIndex {
        val regions = o.getJSONArray("regions")
        return MapIndex(o.getString("version"), List(regions.length()) { i ->
            val r = regions.getJSONObject(i)
            val bbox = r.getJSONArray("bbox")
            val file = r.getString("file")
            val cf = r.optString("contoursFile").ifEmpty { null }
            MapRegion(
                id = r.getString("id"),
                name = r.getString("name"),
                bbox = List(bbox.length()) { bbox.getDouble(it) },
                file = file,
                bytes = r.optLong("bytes"),
                url = urls?.get(file) ?: r.optString("url").ifEmpty { null },
                contoursFile = cf,
                contoursBytes = r.optLong("contoursBytes"),
                contoursUrl = cf?.let { urls?.get(it) } ?: r.optString("contoursUrl").ifEmpty { null },
            )
        })
    }

    private fun toJson(idx: MapIndex) = JSONObject().apply {
        put("version", idx.version)
        put("regions", JSONArray(idx.regions.map { r ->
            JSONObject().apply {
                put("id", r.id); put("name", r.name); put("bbox", JSONArray(r.bbox))
                put("file", r.file); put("bytes", r.bytes); put("url", r.url ?: "")
                put("contoursFile", r.contoursFile ?: ""); put("contoursBytes", r.contoursBytes)
                put("contoursUrl", r.contoursUrl ?: "")
            }
        }))
    }

    /** Version string of the newest data for a region, if the index has it. */
    fun available(id: String): String? = _index.value?.takeIf { idx -> idx.regions.any { it.id == id } }?.version

    // ---- Downloads ----------------------------------------------------------

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    /** Starts (or restarts) the download of a region's map and contour files. */
    fun download(ctx: Context, r: MapRegion) {
        val url = r.url ?: return
        cancel(ctx, r.id)
        val files = buildList {
            add(r.file to url)
            if (r.contoursFile != null && r.contoursUrl != null) add(r.contoursFile to r.contoursUrl)
        }
        val ids = files.map { (name, u) ->
            File(dir(ctx), "$name.part").delete()
            val req = DownloadManager.Request(Uri.parse(u))
                .setTitle("Mapa ${r.name}")
                .setDescription(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(File(dir(ctx), "$name.part")))
            dm(ctx).enqueue(req)
        }
        prefs(ctx).edit()
            .putString("dl_${r.id}", ids.joinToString(","))
            .putString("dlver_${r.id}", available(r.id) ?: "")
            .putString("dlfile_${r.id}", r.file)
            .putString("dlcontours_${r.id}", r.contoursFile ?: "")
            .apply()
        pollDownloads(ctx)
    }

    fun cancel(ctx: Context, id: String) {
        val ids = prefs(ctx).getString("dl_$id", null)?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return
        if (ids.isNotEmpty()) dm(ctx).remove(*ids.toLongArray())
        prefs(ctx).edit().remove("dl_$id").apply()
        _downloads.value = _downloads.value - id
    }

    /**
     * Updates download progress; when every file of a region has arrived,
     * swaps the new files in and removes the old version. Call it about
     * once a second while the Mapas list is open, and at start.
     */
    fun pollDownloads(ctx: Context) {
        val p = prefs(ctx)
        val states = HashMap<String, DownloadState>()
        val ids = p.getStringSet("ids", emptySet()) ?: emptySet()
        val pending = p.all.keys.filter { it.startsWith("dl_") }.map { it.removePrefix("dl_") }
        var changed = false
        for (id in pending) {
            val dlIds = p.getString("dl_$id", "")!!.split(",").mapNotNull { it.toLongOrNull() }
            if (dlIds.isEmpty()) continue
            var done = 0L; var total = 0L; var ok = 0; var failed = false
            dm(ctx).query(DownloadManager.Query().setFilterById(*dlIds.toLongArray())).use { c ->
                while (c.moveToNext()) {
                    done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    total += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> ok++
                        DownloadManager.STATUS_FAILED -> failed = true
                    }
                }
                if (c.count < dlIds.size) failed = true
            }
            if (!failed && ok == dlIds.size) {
                finish(ctx, id)
                changed = true
            } else {
                states[id] = DownloadState(done, total, failed)
            }
        }
        _downloads.value = states
        if (changed || _installed.value.map { it.id }.toSet() != ids) refreshInstalled(ctx)
    }

    private fun finish(ctx: Context, id: String) {
        val p = prefs(ctx)
        val d = dir(ctx)
        val file = p.getString("dlfile_$id", null) ?: return
        val contours = p.getString("dlcontours_$id", "")!!.ifEmpty { null }
        File(d, "$file.part").renameTo(File(d, file))
        contours?.let { File(d, "$it.part").renameTo(File(d, it)) }
        // Old version's files go once the new ones are in place.
        listOfNotNull(p.getString("file_$id", null), p.getString("contours_$id", null))
            .filter { it != file && it != contours }
            .forEach { File(d, it).delete() }
        p.edit()
            .putStringSet("ids", (p.getStringSet("ids", emptySet()) ?: emptySet()) + id)
            .putString("ver_$id", p.getString("dlver_$id", "") ?: "")
            .putString("file_$id", file)
            .apply {
                if (contours != null) putString("contours_$id", contours) else remove("contours_$id")
            }
            .putBoolean("on_$id", true)
            .remove("dl_$id").remove("dlver_$id").remove("dlfile_$id").remove("dlcontours_$id")
            .apply()
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        prefs(ctx).edit().putBoolean("on_$id", on).apply()
        refreshInstalled(ctx)
    }

    fun delete(ctx: Context, id: String) {
        cancel(ctx, id)
        val p = prefs(ctx)
        listOfNotNull(p.getString("file_$id", null), p.getString("contours_$id", null))
            .forEach { File(dir(ctx), it).delete() }
        p.edit()
            .putStringSet("ids", (p.getStringSet("ids", emptySet()) ?: emptySet()) - id)
            .remove("ver_$id").remove("file_$id").remove("contours_$id").remove("on_$id")
            .apply()
        refreshInstalled(ctx)
    }
}
