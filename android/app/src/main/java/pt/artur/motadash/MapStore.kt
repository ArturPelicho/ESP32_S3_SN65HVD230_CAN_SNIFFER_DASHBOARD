package pt.artur.motadash

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
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
    val limitsFile: String? = null,
    val limitsBytes: Long = 0,
    val limitsUrl: String? = null,
    /** Terrain heights (raster-dem, Mapbox RGB encoding) for the shaded relief. */
    val shadeFile: String? = null,
    val shadeBytes: Long = 0,
    val shadeUrl: String? = null,
)

data class MapIndex(val version: String, val regions: List<MapRegion>)

/** A region file on the phone. */
data class InstalledMap(
    val id: String, val version: String, val enabled: Boolean, val map: File, val contours: File?,
    /** Speed limits and speed cameras (layers "limits" and "cameras"), if downloaded. */
    val limits: File? = null,
    /** Terrain heights for the shaded relief, if downloaded. */
    val shade: File? = null,
)

enum class MapTheme(@StringRes private val res: Int) {
    AUTO(R.string.maps_theme_auto), DAY(R.string.maps_theme_day), NIGHT(R.string.maps_theme_night);
    val label: String get() = L.s(res)
}
/** Names on the map: in the app's language where the map has them, or as written locally. */
enum class MapLanguage(@StringRes private val res: Int) {
    APP(R.string.maps_lang_app), LOCAL(R.string.maps_lang_local);
    val label: String get() = L.s(res)
}

/** How the map follows the bike: heading up, heading up tilted (3D), or north up. */
enum class FollowMode(@StringRes private val res: Int, val button: String) {
    HEADING(R.string.maps_follow_heading, "▲"), THREE_D(R.string.maps_follow_3d, "3D"), NORTH(R.string.maps_follow_north, "N");
    val label: String get() = L.s(res)
    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Map layers the rider can switch on and off from the map's layers button. */
/** Screen corner for the navigation boxes (turn banner, track box), clear of the road ahead. */
enum class NavCorner(@StringRes private val res: Int) {
    TOP_LEFT(R.string.nav_corner_top_left), TOP_RIGHT(R.string.nav_corner_top_right),
    BOTTOM_LEFT(R.string.nav_corner_bottom_left), BOTTOM_RIGHT(R.string.nav_corner_bottom_right),
    TOP(R.string.nav_corner_top);
    val label: String get() = L.s(res)
}

enum class MapLayer(@StringRes val res: Int) {
    /** The tracks drawn on the map (navigation carries on when hidden). */
    TRACKS(R.string.layer_tracks),
    SHADE(R.string.layer_shade), CONTOURS(R.string.layer_contours), ROADS(R.string.layer_roads), BUILDINGS_3D(R.string.layer_3d), CAMERAS(R.string.layer_cameras), DIRT(R.string.layer_dirt),
    /** Wind, temperature, rain radar and UV from the internet; off until switched on. */
    WEATHER(R.string.layer_weather);
    val label: String get() = L.s(res)
    /** Has its own options (long press on it in the layers list). */
    val hasOptions: Boolean get() = this == SHADE || this == CONTOURS || this == ROADS || this == DIRT || this == WEATHER
}

/** Road classes that can be hidden one by one (MapStyle's class codes). */
enum class RoadClass(val code: String, @StringRes private val res: Int) {
    MOTORWAY("mw", R.string.road_motorway), TRUNK("tr", R.string.road_trunk), PRIMARY("pr", R.string.road_primary),
    SECONDARY("se", R.string.road_secondary), TERTIARY("te", R.string.road_tertiary), MINOR("mi", R.string.road_minor),
    SERVICE("sv", R.string.road_service);
    val label: String get() = L.s(res)
}

/**
 * Map detail that can start at a zoom of the rider's choosing: road classes, rivers and streams, water names,
 * street names and road numbers. [def] is the usual start; below [floor] the map files hold nothing of it.
 */
enum class Detail(val code: String, @StringRes private val res: Int, val def: Int, val floor: Int) {
    // Floors below the base map's own (trunk 6, primary 7, secondary/tertiary 9) need the region's extras pack.
    MOTORWAY("mw", R.string.road_motorway, 5, 3), TRUNK("tr", R.string.road_trunk, 6, 5), PRIMARY("pr", R.string.road_primary, 7, 5),
    SECONDARY("se", R.string.road_secondary, 7, 5), TERTIARY("te", R.string.road_tertiary, 8, 5), MINOR("mi", R.string.road_minor, 12, 12),
    SERVICE("sv", R.string.road_service, 13, 13), WATERWAY("water", R.string.detail_waterways, 10, 9), WATER_NAMES("wlabel", R.string.detail_water_names, 9, 5),
    STREET_NAMES("names", R.string.detail_street_names, 13, 11), ROAD_REFS("refs", R.string.detail_road_refs, 8, 6);
    val label: String get() = L.s(res)
}

/** What the weather layer shows, each on or off. */
enum class WxKind(@StringRes private val res: Int) {
    WIND(R.string.wx_wind), TEMP(R.string.wx_temp), RAIN(R.string.wx_rain), UV(R.string.wx_uv);
    val label: String get() = L.s(res)
}

/** Dirt road kinds that can be hidden one by one: grades 1-3, ungraded, and closed or private ones. */
enum class DirtKind(val g: Int, @StringRes private val res: Int, val color: Long) {
    EASY(1, R.string.dirt_easy, 0xFF3FAE49), MEDIUM(2, R.string.dirt_medium, 0xFFE0B000), HARD(3, R.string.dirt_hard, 0xFFE2463C),
    UNKNOWN(0, R.string.dirt_unknown, 0xFF9B7448), PRIVATE(-1, R.string.dirt_private, 0xFF111111);
    val label: String get() = L.s(res)
}

/**
 * The layers' own options: shaded relief strength (10-100), contour spacing chosen per zoom level
 * (metres; -1 none; missing = the general setting), and the road classes and dirt kinds hidden.
 */
data class LayerOpts(
    val shade: Int = 70,
    val contourAt: Map<Int, Int> = emptyMap(),
    val roadsOff: Set<RoadClass> = emptySet(),
    val dirtOff: Set<DirtKind> = emptySet(),
    val wxOff: Set<WxKind> = setOf(WxKind.UV),
    /** Zoom from which each kind of detail shows, where the rider changed it. */
    val detailAt: Map<Detail, Int> = emptyMap(),
) {
    fun from(d: Detail): Int = detailAt[d] ?: d.def
}

/**
 * Auto zoom by speed: [nearZoom] at or below [slowKmh], [farZoom] at or above [fastKmh],
 * in a straight line between them.
 */
data class AutoZoom(val slowKmh: Int = 20, val fastKmh: Int = 110, val nearZoom: Double = 17.0, val farZoom: Double = 14.0)

fun AutoZoom.zoomFor(kmh: Double): Double = when {
    fastKmh <= slowKmh -> if (kmh <= slowKmh) nearZoom else farZoom
    kmh <= slowKmh -> nearZoom
    kmh >= fastKmh -> farZoom
    else -> nearZoom + (farZoom - nearZoom) * (kmh - slowKmh) / (fastKmh - slowKmh)
}

/** State of a region's download, for the Mapas list. */
data class DownloadState(val done: Long, val total: Long, val failed: Boolean = false)

/**
 * Offline maps: the index of regions published by the "Map data" workflow
 * (GitHub release maps-<date>), the files on the phone, and their downloads.
 */
object MapStore {
    /** Asks the map to open one of its panels ("maps" or "gpx"), from the app's settings. */
    val openPanel = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Asks the map to show one track (by id), from the app's settings. */
    val showTrack = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private const val RELEASES =
        "https://api.github.com/repos/ArturPelicho/NavExpress/releases?per_page=100&page="
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
        MapLanguage.entries.firstOrNull { it.name == prefs(ctx).getString("lang", null) } ?: MapLanguage.APP
    fun setLanguage(ctx: Context, l: MapLanguage) = prefs(ctx).edit().putString("lang", l.name).apply()
    fun followMode(ctx: Context): FollowMode =
        FollowMode.entries.firstOrNull { it.name == prefs(ctx).getString("follow", null) } ?: FollowMode.THREE_D
    fun setFollowMode(ctx: Context, m: FollowMode) = prefs(ctx).edit().putString("follow", m.name).apply()
    fun autoZoom(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_zoom", true)
    fun setAutoZoom(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_zoom", on).apply()
    private val _autoZoomFlow = MutableStateFlow(AutoZoom())
    /** The auto zoom settings, for the map to follow while the Mapas panel changes them. */
    val autoZoomFlow: StateFlow<AutoZoom> = _autoZoomFlow.asStateFlow()
    fun autoZoomCfg(ctx: Context): AutoZoom {
        val p = prefs(ctx); val d = AutoZoom()
        return AutoZoom(p.getInt("az_slow", d.slowKmh), p.getInt("az_fast", d.fastKmh),
            p.getFloat("az_near", d.nearZoom.toFloat()).toDouble(), p.getFloat("az_far", d.farZoom.toFloat()).toDouble())
    }
    fun setAutoZoomCfg(ctx: Context, a: AutoZoom) {
        prefs(ctx).edit().putInt("az_slow", a.slowKmh).putInt("az_fast", a.fastKmh)
            .putFloat("az_near", a.nearZoom.toFloat()).putFloat("az_far", a.farZoom.toFloat()).apply()
        _autoZoomFlow.value = a
    }

    private val _contourStepFlow = MutableStateFlow(0)
    /** Contour spacing in metres (10, 20, 50 or 100), or 0 for by zoom; see [MapStyle.build]. */
    val contourStepFlow: StateFlow<Int> = _contourStepFlow.asStateFlow()
    fun contourStep(ctx: Context): Int = prefs(ctx).getInt("contour_step", 0)
    fun setContourStep(ctx: Context, step: Int) {
        prefs(ctx).edit().putInt("contour_step", step).apply()
        _contourStepFlow.value = step
    }

    private val _layerOpts = MutableStateFlow(LayerOpts())
    val layerOptsFlow: StateFlow<LayerOpts> = _layerOpts.asStateFlow()
    fun layerOpts(ctx: Context): LayerOpts {
        val p = prefs(ctx)
        return LayerOpts(
            shade = p.getInt("shade_strength", 70),
            contourAt = (p.getString("contour_at", "") ?: "").split(",").mapNotNull { e ->
                e.split(":").takeIf { it.size == 2 }?.let { (z, st) -> z.toIntOrNull()?.let { zz -> st.toIntOrNull()?.let { zz to it } } }
            }.toMap(),
            roadsOff = (p.getStringSet("roads_off", emptySet()) ?: emptySet()).mapNotNull { n -> RoadClass.entries.firstOrNull { it.name == n } }.toSet(),
            dirtOff = (p.getStringSet("dirt_off", emptySet()) ?: emptySet()).mapNotNull { n -> DirtKind.entries.firstOrNull { it.name == n } }.toSet(),
            wxOff = (p.getStringSet("wx_off", setOf(WxKind.UV.name)) ?: emptySet()).mapNotNull { n -> WxKind.entries.firstOrNull { it.name == n } }.toSet(),
            detailAt = (p.getString("detail_at", "") ?: "").split(",").mapNotNull { e ->
                e.split(":").takeIf { it.size == 2 }?.let { (k, z) -> Detail.entries.firstOrNull { it.name == k }?.let { d -> z.toIntOrNull()?.let { d to it } } }
            }.toMap(),
        )
    }
    fun setLayerOpts(ctx: Context, o: LayerOpts) {
        prefs(ctx).edit().putInt("shade_strength", o.shade)
            .putString("contour_at", o.contourAt.entries.joinToString(",") { "${it.key}:${it.value}" })
            .putStringSet("roads_off", o.roadsOff.map { it.name }.toSet())
            .putStringSet("dirt_off", o.dirtOff.map { it.name }.toSet())
            .putStringSet("wx_off", o.wxOff.map { it.name }.toSet())
            .putString("detail_at", o.detailAt.entries.joinToString(",") { "${it.key.name}:${it.value}" }).apply()
        _layerOpts.value = o
    }

    fun cameraBeep(ctx: Context): Boolean = prefs(ctx).getBoolean("camera_beep", true)
    fun setCameraBeep(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("camera_beep", on).apply()

    fun layers(ctx: Context): Set<MapLayer> {
        val off = prefs(ctx).getStringSet("layers_off", emptySet()) ?: emptySet()
        // The weather layer needs the network: off until chosen.
        val wx = prefs(ctx).getBoolean("layer_weather", false)
        return MapLayer.entries.filter { if (it == MapLayer.WEATHER) wx else it.name !in off }.toSet()
    }
    fun setLayer(ctx: Context, l: MapLayer, on: Boolean) {
        if (l == MapLayer.WEATHER) { prefs(ctx).edit().putBoolean("layer_weather", on).apply(); return }
        val off = (prefs(ctx).getStringSet("layers_off", emptySet()) ?: emptySet()).toMutableSet()
        if (on) off -= l.name else off += l.name
        prefs(ctx).edit().putStringSet("layers_off", off).apply()
    }

    /** Trajetos planned on the map follow the roads (BRouter, needs network) instead of straight lines. */
    fun routeRoads(ctx: Context): Boolean = prefs(ctx).getBoolean("route_roads", true)
    fun setRouteRoads(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("route_roads", on).apply()

    /** Next turn shown on the map, and said aloud, while following a track. */
    fun turnBanner(ctx: Context): Boolean = prefs(ctx).getBoolean("turn_banner", true)
    fun setTurnBanner(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("turn_banner", on).apply()
    /** Arrival time at the next waypoint of a planned trajeto, in the track box. */
    fun etaWaypoint(ctx: Context): Boolean = prefs(ctx).getBoolean("eta_wp", true)
    fun setEtaWaypoint(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("eta_wp", on).apply()
    /** Arrival time at the end of the progress (height) strip instead of in the track box. */
    fun etaOnBar(ctx: Context): Boolean = prefs(ctx).getBoolean("eta_bar", true)
    fun setEtaOnBar(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("eta_bar", on).apply()
    /** Track mode calls rally pacenotes from the track's shape instead of turn by turn. */
    fun pacenotes(ctx: Context): Boolean = prefs(ctx).getBoolean("pacenotes", true)
    fun setPacenotes(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("pacenotes", on).apply()

    /** Pacenotes as rally numbers ("left 4") rather than words ("medium left"). */
    fun pacenoteNumbers(ctx: Context): Boolean = prefs(ctx).getBoolean("pacenote_numbers", false)
    fun setPacenoteNumbers(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("pacenote_numbers", on).apply()

    /** Seconds ahead a pacenote is called at the latest; a co-driver also calls it as the corner before ends. */
    fun pacenoteLead(ctx: Context): Int = prefs(ctx).getInt("pacenote_lead", 8)
    fun setPacenoteLead(ctx: Context, s: Int) = prefs(ctx).edit().putInt("pacenote_lead", s).apply()
    val PACENOTE_LEADS = listOf(5, 8, 11, 14)

    /** Rain along the followed line, for when the bike gets there, on the progress bar (needs the network now and then). */
    fun wxOnBar(ctx: Context): Boolean = prefs(ctx).getBoolean("wx_on_bar", true)
    fun setWxOnBar(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wx_on_bar", on).apply()

    /** Times each section of a track ridden before, against the best: "Fast" or "Slow". */
    fun sectionTimes(ctx: Context): Boolean = prefs(ctx).getBoolean("section_times", true)
    fun setSectionTimes(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("section_times", on).apply()

    fun turnVoice(ctx: Context): Boolean = prefs(ctx).getBoolean("turn_voice", true)
    fun setTurnVoice(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("turn_voice", on).apply()

    /** Spoken directions and read-aloud messages, 0 (silent) to 100. */
    fun voiceVolume(ctx: Context): Int = prefs(ctx).getInt("voice_volume", 100)
    fun setVoiceVolume(ctx: Context, v: Int) = prefs(ctx).edit().putInt("voice_volume", v).apply()
    /** Warning beeps (speed cameras), 0 (silent) to 100. */
    fun alertVolume(ctx: Context): Int = prefs(ctx).getInt("alert_volume", 90)
    fun setAlertVolume(ctx: Context, v: Int) = prefs(ctx).edit().putInt("alert_volume", v).apply()

    /** Where the turn banner and the track box sit on the map. */
    fun navCorner(ctx: Context): NavCorner =
        NavCorner.entries.firstOrNull { it.name == prefs(ctx).getString("nav_corner", null) } ?: NavCorner.TOP_RIGHT
    fun setNavCorner(ctx: Context, c: NavCorner) = prefs(ctx).edit().putString("nav_corner", c.name).apply()

    /** Nav (reroutes back to the track) or Track (the line stays the line). */
    fun trackMode(ctx: Context): TrackMode =
        TrackMode.entries.firstOrNull { it.name == prefs(ctx).getString("track_mode", null) } ?: TrackMode.NAV
    fun setTrackMode(ctx: Context, m: TrackMode) = prefs(ctx).edit().putString("track_mode", m.name).apply()

    /** Loads what is on the phone and the last index seen (works offline). */
    fun load(ctx: Context) {
        prefs(ctx).getString("index", null)?.let { runCatching { _index.value = parseIndex(JSONObject(it), null) } }
        _autoZoomFlow.value = autoZoomCfg(ctx)
        _contourStepFlow.value = contourStep(ctx)
        _layerOpts.value = layerOpts(ctx)
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
            val limits = p.getString("limits_$id", null)?.let { File(d, it) }?.takeIf { it.exists() }
            val shade = p.getString("shade_$id", null)?.let { File(d, it) }?.takeIf { it.exists() }
            InstalledMap(id, ver, p.getBoolean("on_$id", true), map, contours, limits, shade)
        }
    }

    /** Asks GitHub for the newest maps-* release and its index. */
    suspend fun fetchIndex(ctx: Context): Result<MapIndex> = withContext(Dispatchers.IO) {
        runCatching {
            // App releases (one per build) share the list with the monthly map ones: page until a map one shows up.
            var best: JSONObject? = null
            for (page in 1..20) {
                val releases = JSONArray(get(RELEASES + page))
                for (i in 0 until releases.length()) {
                    val r = releases.getJSONObject(i)
                    val tag = r.optString("tag_name")
                    if (!tag.startsWith("maps-") || r.optBoolean("draft")) continue
                    if (best == null || tag > best.optString("tag_name")) best = r
                }
                if (best != null || releases.length() < 100) break
            }
            val rel = best ?: error(L.s(R.string.maps_err_none_published))
            val assets = rel.getJSONArray("assets")
            val urls = HashMap<String, String>()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                urls[a.getString("name")] = a.getString("browser_download_url")
            }
            val indexName = urls.keys.firstOrNull { it.startsWith("maps-index-") } ?: error(L.s(R.string.maps_err_no_index))
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
        c.setRequestProperty("User-Agent", "NavExpress")
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
            val lf = r.optString("limitsFile").ifEmpty { null }
            val sf = r.optString("shadeFile").ifEmpty { null }
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
                limitsFile = lf,
                limitsBytes = r.optLong("limitsBytes"),
                limitsUrl = lf?.let { urls?.get(it) } ?: r.optString("limitsUrl").ifEmpty { null },
                shadeFile = sf,
                shadeBytes = r.optLong("shadeBytes"),
                shadeUrl = sf?.let { urls?.get(it) } ?: r.optString("shadeUrl").ifEmpty { null },
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
                put("limitsFile", r.limitsFile ?: ""); put("limitsBytes", r.limitsBytes)
                put("limitsUrl", r.limitsUrl ?: "")
                put("shadeFile", r.shadeFile ?: ""); put("shadeBytes", r.shadeBytes); put("shadeUrl", r.shadeUrl ?: "")
            }
        }))
    }

    /** Version string of the newest data for a region, if the index has it. */
    fun available(id: String): String? = _index.value?.takeIf { idx -> idx.regions.any { it.id == id } }?.version

    // ---- Downloads ----------------------------------------------------------

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    /**
     * Starts (or restarts) the download of a region's map, contour, speed limit and relief files.
     * With [extrasOnly], fetches just the speed limits and relief a map already on the phone lacks.
     */
    fun download(ctx: Context, r: MapRegion, extrasOnly: Boolean = false) {
        val url = r.url ?: return
        val p = prefs(ctx)
        if (extrasOnly && p.getString("file_${r.id}", null) == null) return
        val wantLimits = r.limitsFile != null && r.limitsUrl != null && (!extrasOnly || p.getString("limits_${r.id}", null) == null)
        val wantShade = r.shadeFile != null && r.shadeUrl != null && (!extrasOnly || p.getString("shade_${r.id}", null) == null)
        if (extrasOnly && !wantLimits && !wantShade) return
        cancel(ctx, r.id)
        val files = buildList {
            if (!extrasOnly) {
                add(r.file to url)
                if (r.contoursFile != null && r.contoursUrl != null) add(r.contoursFile to r.contoursUrl)
            }
            if (wantLimits) add(r.limitsFile!! to r.limitsUrl!!)
            if (wantShade) add(r.shadeFile!! to r.shadeUrl!!)
        }
        val ids = files.map { (name, u) ->
            File(dir(ctx), "$name.part").delete()
            val req = DownloadManager.Request(Uri.parse(u))
                .setTitle(L.s(R.string.maps_download_title, r.name))
                .setDescription(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(File(dir(ctx), "$name.part")))
            dm(ctx).enqueue(req)
        }
        p.edit()
            .putString("dl_${r.id}", ids.joinToString(","))
            .putString("dlver_${r.id}", if (extrasOnly) p.getString("ver_${r.id}", "") else available(r.id) ?: "")
            .putString("dlfile_${r.id}", if (extrasOnly) p.getString("file_${r.id}", "") else r.file)
            .putString("dlcontours_${r.id}", if (extrasOnly) p.getString("contours_${r.id}", "") else r.contoursFile ?: "")
            .putString("dllimits_${r.id}", if (wantLimits) r.limitsFile else p.getString("limits_${r.id}", "") ?: "")
            .putString("dlshade_${r.id}", if (wantShade) r.shadeFile else p.getString("shade_${r.id}", "") ?: "")
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
        val limits = p.getString("dllimits_$id", "")!!.ifEmpty { null }
        val shade = p.getString("dlshade_$id", "")!!.ifEmpty { null }
        // An extras-only download leaves the files already here as they are (no .part for them).
        for (name in listOfNotNull(file, contours, limits, shade)) {
            val part = File(d, "$name.part")
            if (part.exists()) part.renameTo(File(d, name))
        }
        // Old version's files go once the new ones are in place.
        listOfNotNull(p.getString("file_$id", null), p.getString("contours_$id", null), p.getString("limits_$id", null),
            p.getString("shade_$id", null))
            .filter { it != file && it != contours && it != limits && it != shade }
            .forEach { File(d, it).delete() }
        p.edit()
            .putStringSet("ids", (p.getStringSet("ids", emptySet()) ?: emptySet()) + id)
            .putString("ver_$id", p.getString("dlver_$id", "") ?: "")
            .putString("file_$id", file)
            .apply {
                if (contours != null) putString("contours_$id", contours) else remove("contours_$id")
                if (limits != null) putString("limits_$id", limits) else remove("limits_$id")
                if (shade != null) putString("shade_$id", shade) else remove("shade_$id")
            }
            .putBoolean("on_$id", true)
            .remove("dl_$id").remove("dlver_$id").remove("dlfile_$id").remove("dlcontours_$id")
            .remove("dllimits_$id").remove("dlshade_$id")
            .apply()
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        prefs(ctx).edit().putBoolean("on_$id", on).apply()
        refreshInstalled(ctx)
    }

    fun delete(ctx: Context, id: String) {
        cancel(ctx, id)
        val p = prefs(ctx)
        listOfNotNull(p.getString("file_$id", null), p.getString("contours_$id", null), p.getString("limits_$id", null),
            p.getString("shade_$id", null))
            .forEach { File(dir(ctx), it).delete() }
        p.edit()
            .putStringSet("ids", (p.getStringSet("ids", emptySet()) ?: emptySet()) - id)
            .remove("ver_$id").remove("file_$id").remove("contours_$id").remove("limits_$id").remove("shade_$id").remove("on_$id")
            .apply()
        refreshInstalled(ctx)
    }
}
