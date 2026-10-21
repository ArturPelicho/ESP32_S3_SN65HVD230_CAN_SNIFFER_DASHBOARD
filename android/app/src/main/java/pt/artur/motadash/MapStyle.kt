package pt.artur.motadash

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * The NavExpress map style, built for the Protomaps basemap schema (v4) that
 * the "Map data" workflow extracts from OpenStreetMap. Every road class has
 * its own colour so secondary roads stand out from minor ones, and labels
 * are large enough to read with a helmet on.
 */
object MapStyle {

    private data class Colors(
        val bg: String, val earth: String, val urban: String, val park: String, val forest: String,
        val farm: String, val water: String, val waterLine: String, val building: String,
        val motorway: String, val trunk: String, val primary: String, val secondary: String,
        val tertiary: String, val minor: String, val service: String, val track: String, val path: String,
        val casing: String, val casingMajor: String, val rail: String, val border: String,
        val contour: String, val label: String, val labelMinor: String, val halo: String, val waterLabel: String,
    )

    private val DAY = Colors(
        bg = "#e9e5dc", earth = "#f1eee7", urban = "#e6e0d6", park = "#cfe5bf", forest = "#c4dcb4",
        farm = "#eeecd9", water = "#9ec7ea", waterLine = "#7fb2de", building = "#d8d0c4",
        motorway = "#e2463c", trunk = "#f08035", primary = "#f7b52c", secondary = "#f9df5a",
        tertiary = "#ffffff", minor = "#ffffff", service = "#ffffff", track = "#9b7448", path = "#8a8478",
        casing = "#a49a8b", casingMajor = "#7b5a3a", rail = "#9a9a9a", border = "#8a5ca8",
        contour = "#b08a5e", label = "#1d232b", labelMinor = "#4a515a", halo = "#ffffff", waterLabel = "#2f6ea8",
    )

    private val NIGHT = Colors(
        bg = "#0d1116", earth = "#161b21", urban = "#1d232a", park = "#16271c", forest = "#132317",
        farm = "#191e1d", water = "#0d2436", waterLine = "#17405f", building = "#252c34",
        motorway = "#d9473e", trunk = "#d97a2e", primary = "#d6a226", secondary = "#b9a43a",
        tertiary = "#8d96a3", minor = "#5b6470", service = "#46505b", track = "#9a7448", path = "#6b6660",
        casing = "#0a0d11", casingMajor = "#0a0d11", rail = "#4f5660", border = "#9b7ab8",
        contour = "#6e5a41", label = "#e8ebef", labelMinor = "#a9b1bc", halo = "#0b0e12", waterLabel = "#6fa9d6",
    )

    private const val REGULAR = "Noto Sans Regular"
    private const val MEDIUM = "Noto Sans Medium"
    private const val ITALIC = "Noto Sans Italic"

    /** Road class from the Protomaps kind / kind_detail tags. */
    private val ROAD_CLASS: List<Any> = listOf(
        "match", listOf("get", "kind_detail"),
        listOf("motorway", "motorway_link"), "mw",
        listOf("trunk", "trunk_link"), "tr",
        listOf("primary", "primary_link"), "pr",
        listOf("secondary", "secondary_link"), "se",
        listOf("tertiary", "tertiary_link"), "te",
        "track", "tk",
        listOf("path", "footway", "cycleway", "bridleway", "steps", "pedestrian"), "pa",
        "service", "sv",
        listOf("match", listOf("get", "kind"), "highway", "mw", "major_road", "te", "path", "pa", "mi"),
    )

    private fun byClass(mw: Any, tr: Any, pr: Any, se: Any, te: Any, mi: Any, sv: Any, tk: Any, pa: Any): List<Any> =
        listOf("match", ROAD_CLASS, "mw", mw, "tr", tr, "pr", pr, "se", se, "te", te, "sv", sv, "tk", tk, "pa", pa, mi)

    /** Width in pixels: base by class, scaled by zoom. */
    private fun roadWidth(extra: Double = 0.0): List<Any> {
        val w = byClass(1.6, 1.4, 1.25, 1.1, 0.9, 0.7, 0.45, 0.5, 0.4)
        fun at(f: Double) = listOf("+", listOf("*", f, w), extra)
        return listOf("interpolate", listOf("exponential", 1.5), listOf("zoom"),
            6, at(0.6), 10, at(1.5), 13, at(3.5), 16, at(9.0), 19, at(22.0))
    }

    private val RANK = byClass(9, 8, 7, 6, 5, 3, 2, 1, 0)

    private fun name(lang: MapLanguage): List<Any> =
        if (lang == MapLanguage.PT) listOf("coalesce", listOf("get", "name:pt"), listOf("get", "name"))
        else listOf("get", "name")

    /** Contour levels: spacing, line width, line opacity. Each line belongs to the coarsest level it is a multiple of. */
    private val CONTOUR_LEVELS = listOf(Triple(100, 1.1, 0.65), Triple(50, 0.8, 0.5), Triple(20, 0.6, 0.42), Triple(10, 0.45, 0.35))

    /** Zoom each level shows from when the spacing is automatic. */
    private fun contourMinZoom(level: Int) = when (level) { 100 -> 11; 50 -> 12; 20 -> 13; else -> 14 }

    /**
     * Filter for the lines of one contour level, from the height alone so it works on both
     * the older 20 m files and the 10 m ones (100: multiples of 100; 50 and 20: their multiples
     * that are not 100s; 10: the rest).
     */
    private fun contourFilter(level: Int): List<Any> {
        val ele = listOf("get", "ele")
        fun mul(n: Int) = listOf("==", listOf("%", ele, n), 0)
        fun notMul(n: Int) = listOf("!=", listOf("%", ele, n), 0)
        return when (level) {
            100 -> mul(100)
            50 -> listOf("all", mul(50), notMul(100))
            20 -> listOf("all", mul(20), notMul(100))
            else -> listOf("all", mul(10), notMul(20), notMul(50))
        }
    }

    /**
     * Full style JSON. [maps] are the enabled regions on the phone; each
     * gets its own vector source, drawn in the same layer order.
     * [contourStep]: 0 = contour spacing by zoom (100 m from z11, 50 m from z12, 20 m from z13,
     * 10 m from z14); 10, 20, 50 or 100 = lines at that spacing from z11.
     */
    fun build(maps: List<InstalledMap>, night: Boolean, lang: MapLanguage, layers: Set<MapLayer> = MapLayer.entries.toSet(),
              contourStep: Int = 0): String {
        val c = if (night) NIGHT else DAY
        val sources = LinkedHashMap<String, Any>()
        val fills = ArrayList<Any>()
        val lines = ArrayList<Any>()
        val labels = ArrayList<Any>()
        val extrusions = ArrayList<Any>()

        fills += mapOf("id" to "bg", "type" to "background", "paint" to mapOf("background-color" to c.bg))

        for (m in maps) {
            val s = "pm-${m.id}"
            sources[s] = mapOf(
                "type" to "vector",
                "url" to "pmtiles://file://${m.map.absolutePath}",
                "attribution" to "© OpenStreetMap · Protomaps",
            )
            fun layer(id: String, type: String, src: String, layout: Map<String, Any> = emptyMap(),
                      paint: Map<String, Any>, filter: Any? = null, minzoom: Int? = null, source: String = s): Map<String, Any> =
                buildMap {
                    put("id", "$id-${m.id}"); put("type", type); put("source", source); put("source-layer", src)
                    if (layout.isNotEmpty()) put("layout", layout)
                    put("paint", paint)
                    if (filter != null) put("filter", filter)
                    if (minzoom != null) put("minzoom", minzoom)
                }

            fills += layer("earth", "fill", "earth", paint = mapOf("fill-color" to c.earth))
            fills += layer("landcover", "fill", "landcover", paint = mapOf(
                "fill-color" to listOf("match", listOf("get", "kind"),
                    "forest", c.forest, listOf("grassland", "scrub"), c.park, "farmland", c.farm, "urban_area", c.urban, c.earth),
                "fill-opacity" to listOf("interpolate", listOf("linear"), listOf("zoom"), 5, 1, 9, 0.4),
            ))
            fills += layer("landuse", "fill", "landuse", paint = mapOf(
                "fill-color" to listOf("match", listOf("get", "kind"),
                    listOf("park", "national_park", "nature_reserve", "protected_area", "garden", "golf_course",
                        "grass", "meadow", "playground", "recreation_ground"), c.park,
                    listOf("forest", "wood"), c.forest,
                    listOf("farmland", "orchard", "vineyard", "allotments"), c.farm,
                    listOf("residential", "industrial", "commercial", "retail", "railway", "military"), c.urban,
                    c.earth),
                "fill-opacity" to 0.9,
            ))
            // Shaded relief from the terrain heights, under the water and the roads.
            m.shade?.takeIf { MapLayer.SHADE in layers }?.let { f ->
                val hs = "hs-${m.id}"
                sources[hs] = mapOf("type" to "raster-dem", "url" to "pmtiles://file://${f.absolutePath}",
                    "encoding" to "mapbox", "tileSize" to 256, "attribution" to "Copernicus DEM")
                fills += mapOf("id" to "hillshade-${m.id}", "type" to "hillshade", "source" to hs, "paint" to mapOf(
                    "hillshade-exaggeration" to listOf("interpolate", listOf("linear"), listOf("zoom"), 6, 0.45, 14, 0.25),
                    "hillshade-shadow-color" to if (c === NIGHT) "#000000" else "#5a4a3a",
                    "hillshade-highlight-color" to if (c === NIGHT) "#2a3038" else "#ffffff",
                    "hillshade-accent-color" to if (c === NIGHT) "#000000" else "#6b5a48",
                    "hillshade-illumination-anchor" to "map",
                ))
            }
            fills += layer("water", "fill", "water", paint = mapOf("fill-color" to c.water),
                filter = listOf("match", listOf("geometry-type"), listOf("Polygon", "MultiPolygon"), true, false))
            fills += layer("waterway", "line", "water", paint = mapOf(
                "line-color" to c.waterLine,
                "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 10, 0.6, 16, 3),
            ), filter = listOf("match", listOf("geometry-type"), listOf("LineString", "MultiLineString"), true, false), minzoom = 10)

            m.contours?.takeIf { MapLayer.CONTOURS in layers }?.let { f ->
                val cs = "ct-${m.id}"
                sources[cs] = mapOf("type" to "vector", "url" to "pmtiles://file://${f.absolutePath}",
                    "attribution" to "Copernicus DEM")
                // Finest first so the bolder lines draw on top.
                val fixed = contourStep in setOf(10, 20, 50, 100)
                val shown = CONTOUR_LEVELS.filter { (lv, _, _) -> !fixed || lv % contourStep == 0 }
                for ((lv, width, opacity) in shown.reversed()) {
                    fills += layer("contour$lv", "line", "contours", source = cs, paint = mapOf(
                        "line-color" to c.contour, "line-opacity" to opacity, "line-width" to width,
                    ), filter = contourFilter(lv), minzoom = if (fixed) 11 else contourMinZoom(lv))
                }
                // Heights on the 100 m lines, and on the 50 m ones when zoomed right in.
                for ((lv, minz) in listOf(100 to 13, 50 to 14)) {
                    if (shown.none { it.first == lv }) continue
                    labels += layer("contour-label$lv", "symbol", "contours", source = cs, layout = mapOf(
                        "symbol-placement" to "line",
                        "text-field" to listOf("concat", listOf("to-string", listOf("get", "ele")), " m"),
                        "text-font" to listOf(REGULAR),
                        "text-size" to if (lv == 100) 11 else 10,
                        "symbol-spacing" to 350,
                    ), paint = mapOf("text-color" to c.contour, "text-halo-color" to c.halo, "text-halo-width" to 1.2),
                        filter = contourFilter(lv), minzoom = minz)
                }
            }

            fills += layer("building", "fill", "buildings", paint = mapOf(
                "fill-color" to c.building,
                "fill-opacity" to listOf("interpolate", listOf("linear"), listOf("zoom"), 14, 0, 15.5, 0.85),
            ), minzoom = 14)
            // Buildings in volume, for the 3D view only (the screen switches them on); height from OSM, else ~2 floors.
            extrusions += layer("building-3d", "fill-extrusion", "buildings", layout = mapOf("visibility" to "none"), paint = mapOf(
                "fill-extrusion-color" to c.building,
                "fill-extrusion-height" to listOf("coalesce", listOf("get", "height"), 7),
                "fill-extrusion-base" to listOf("coalesce", listOf("get", "min_height"), 0),
                "fill-extrusion-opacity" to 0.85,
            ), minzoom = 15)

            // Railways fall into the "minor" class; keep them out of the road layers so they stay dashed.
            val notPath = listOf("all", listOf("match", ROAD_CLASS, listOf("tk", "pa"), false, true),
                listOf("!=", listOf("get", "kind"), "rail"))
            val tunnel = listOf("==", listOf("get", "is_tunnel"), true)
            lines += layer("rail", "line", "roads", paint = mapOf(
                "line-color" to c.rail, "line-width" to 1.2, "line-dasharray" to listOf(3, 3),
            ), filter = listOf("==", listOf("get", "kind"), "rail"), minzoom = 11)
            // Dirt tracks stand out (brown dashes); footpaths stay faint so towns stay readable.
            lines += layer("path", "line", "roads", layout = mapOf("line-cap" to "butt"), paint = mapOf(
                "line-color" to c.path,
                "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 14, 0.6, 18, 2),
                "line-dasharray" to listOf(2, 2),
                "line-opacity" to 0.55,
            ), filter = listOf("match", ROAD_CLASS, "pa", true, false), minzoom = 14)
            lines += layer("track", "line", "roads", layout = mapOf("line-cap" to "butt"), paint = mapOf(
                "line-color" to c.track,
                "line-width" to roadWidth(0.6),
                "line-dasharray" to listOf(2.2, 1.4),
            ), filter = listOf("match", ROAD_CLASS, "tk", true, false), minzoom = 12)
            lines += layer("casing", "line", "roads", layout = mapOf(
                "line-cap" to "round", "line-join" to "round", "line-sort-key" to RANK,
            ), paint = mapOf(
                "line-color" to byClass(c.casingMajor, c.casingMajor, c.casingMajor, c.casingMajor,
                    c.casing, c.casing, c.casing, c.casing, c.casing),
                "line-width" to roadWidth(1.6),
                "line-opacity" to listOf("case", tunnel, 0.4, 1),
            ), filter = notPath, minzoom = 7)
            lines += layer("road", "line", "roads", layout = mapOf(
                "line-cap" to "round", "line-join" to "round", "line-sort-key" to RANK,
            ), paint = mapOf(
                "line-color" to byClass(c.motorway, c.trunk, c.primary, c.secondary, c.tertiary,
                    c.minor, c.service, c.track, c.path),
                "line-width" to roadWidth(),
                "line-opacity" to listOf("case", tunnel, 0.55, 1),
            ), filter = notPath)
            lines += layer("border", "line", "boundaries", paint = mapOf(
                "line-color" to c.border,
                "line-width" to listOf("match", listOf("get", "kind"), "country", 1.6, 0.9),
                "line-dasharray" to listOf(4, 2),
                "line-opacity" to 0.7,
            ))

            m.limits?.let { f ->
                val ls = "lm-${m.id}"
                sources[ls] = mapOf("type" to "vector", "url" to "pmtiles://file://${f.absolutePath}",
                    "attribution" to "© OpenStreetMap")
                // Unpaved roads and tracks by how hard they are (DMD2-style): green easy, yellow medium, red hard.
                if (MapLayer.DIRT in layers) lines += layer("dirt", "line", "dirt", source = ls, layout = mapOf("line-cap" to "butt"), paint = mapOf(
                    "line-color" to listOf("match", listOf("get", "g"), 1, "#3fae49", 2, "#e0b000", "#e2463c"),
                    "line-width" to roadWidth(0.6),
                    "line-dasharray" to listOf(2.2, 1.4),
                ), minzoom = 12)
                // Almost invisible: only there so the screen can ask which limit is under the bike.
                lines += layer("limit-hit", "line", "limits", source = ls, paint = mapOf(
                    "line-color" to c.label, "line-opacity" to 0.01, "line-width" to 14,
                ), minzoom = 12)
                if (MapLayer.CAMERAS in layers) labels += layer("camera", "symbol", "cameras", source = ls, layout = mapOf(
                    "icon-image" to "radar",
                    "icon-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 12, 0.45, 16, 0.8),
                    "icon-allow-overlap" to true,
                    "text-field" to listOf("to-string", listOf("coalesce", listOf("get", "kmh"), "")),
                    "text-font" to listOf(MEDIUM),
                    "text-size" to 12,
                    "text-offset" to listOf(0, 1.6),
                    "text-optional" to true,
                ), paint = mapOf("text-color" to "#e2463c", "text-halo-color" to c.halo, "text-halo-width" to 1.6),
                    minzoom = 12)
            }

            labels += layer("water-label", "symbol", "water", layout = mapOf(
                "text-field" to name(lang), "text-font" to listOf(ITALIC), "text-size" to 13,
            ), paint = mapOf("text-color" to c.waterLabel, "text-halo-color" to c.halo, "text-halo-width" to 1.2),
                filter = listOf("all", listOf("has", "name"), listOf("==", listOf("geometry-type"), "Point")), minzoom = 9)
            labels += layer("road-label", "symbol", "roads", layout = mapOf(
                "symbol-placement" to "line",
                "text-field" to name(lang),
                "text-font" to listOf(REGULAR),
                "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 13, 12, 17, 16),
                "symbol-spacing" to 300,
                "text-max-angle" to 30,
            ), paint = mapOf("text-color" to c.label, "text-halo-color" to c.halo, "text-halo-width" to 1.6),
                filter = listOf("has", "name"), minzoom = 13)
            // Road numbers (A1, IC8, N222, EN2...) on the bigger roads.
            labels += layer("road-ref", "symbol", "roads", layout = mapOf(
                "symbol-placement" to "line",
                "text-field" to listOf("get", "ref"),
                "text-font" to listOf(MEDIUM),
                "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 8, 11, 14, 14),
                "symbol-spacing" to 500,
                "text-rotation-alignment" to "viewport",
                "text-keep-upright" to true,
            ), paint = mapOf(
                "text-color" to byClass(c.motorway, c.trunk, c.primary, c.label, c.label, c.label, c.label, c.label, c.label),
                "text-halo-color" to c.halo, "text-halo-width" to 2.2,
            ), filter = listOf("all", listOf("has", "ref"),
                listOf("match", ROAD_CLASS, listOf("mw", "tr", "pr", "se", "te"), true, false)), minzoom = 8)
            labels += layer("place", "symbol", "places", layout = mapOf(
                "text-field" to name(lang),
                "text-font" to listOf("match", listOf("get", "kind_detail"), listOf("city", "town"),
                    listOf("literal", listOf(MEDIUM)), listOf("literal", listOf(REGULAR))),
                "text-size" to listOf("match", listOf("get", "kind"),
                    "country", 17, "region", 14,
                    listOf("match", listOf("get", "kind_detail"), "city", 18, "town", 15, "village", 13, 12)),
                "text-max-width" to 8,
                "symbol-sort-key" to listOf("-", 20, listOf("coalesce", listOf("get", "population_rank"), 0)),
                "text-transform" to listOf("match", listOf("get", "kind"), listOf("country", "region"), "uppercase", "none"),
            ), paint = mapOf(
                "text-color" to listOf("match", listOf("get", "kind"), listOf("country", "region"), c.labelMinor, c.label),
                "text-halo-color" to c.halo, "text-halo-width" to 1.8,
            ), filter = listOf("has", "name"))
        }

        // Imported tracks (GPX...) over the roads, under the labels; filled by the screen.
        val kind = listOf("get", "kind")
        val tracks = listOf(
            mapOf("id" to "trk-casing", "type" to "line", "source" to "tracks",
                "filter" to listOf("==", kind, "line"),
                "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
                "paint" to mapOf("line-color" to c.halo, "line-opacity" to 0.85, "line-width" to trackWidth(3.0))),
            mapOf("id" to "trk-line", "type" to "line", "source" to "tracks",
                "filter" to listOf("==", kind, "line"),
                "layout" to mapOf("line-cap" to "round", "line-join" to "round"),
                "paint" to mapOf("line-color" to listOf("get", "color"), "line-width" to trackWidth(0.0))),
            // Arrows along the followed track, in the riding direction.
            mapOf("id" to "trk-arrow", "type" to "symbol", "source" to "tracks",
                "filter" to listOf("all", listOf("==", kind, "line"), listOf("==", listOf("get", "active"), true)),
                "layout" to mapOf("symbol-placement" to "line", "symbol-spacing" to 90, "icon-image" to "trk-arrow",
                    "icon-size" to 0.55, "icon-allow-overlap" to true, "icon-ignore-placement" to true),
                "minzoom" to 11),
            // Dashed line from the bike back to the track when off it.
            mapOf("id" to "trk-join", "type" to "line", "source" to "join",
                "paint" to mapOf("line-color" to c.label, "line-width" to 2.5, "line-dasharray" to listOf(1.5, 1.5))),
            mapOf("id" to "trk-wpt", "type" to "circle", "source" to "tracks",
                "filter" to listOf("==", kind, "wpt"),
                "paint" to mapOf("circle-radius" to 6, "circle-color" to listOf("get", "color"),
                    "circle-stroke-color" to c.halo, "circle-stroke-width" to 2)),
            mapOf("id" to "trk-wpt-label", "type" to "symbol", "source" to "tracks",
                "filter" to listOf("==", kind, "wpt"),
                "layout" to mapOf("text-field" to listOf("get", "name"), "text-font" to listOf(MEDIUM), "text-size" to 13,
                    "text-offset" to listOf(0, 1.2), "text-anchor" to "top", "text-optional" to true),
                "paint" to mapOf("text-color" to c.label, "text-halo-color" to c.halo, "text-halo-width" to 1.8),
                "minzoom" to 11),
            // Numbered waypoints of a trajeto planned on the map.
            mapOf("id" to "rte-pt", "type" to "circle", "source" to "tracks",
                "filter" to listOf("==", kind, "rtept"),
                "paint" to mapOf("circle-radius" to 12, "circle-color" to listOf("get", "color"),
                    "circle-stroke-color" to "#ffffff", "circle-stroke-width" to 2.5)),
            mapOf("id" to "rte-num", "type" to "symbol", "source" to "tracks",
                "filter" to listOf("==", kind, "rtept"),
                "layout" to mapOf("text-field" to listOf("to-string", listOf("get", "n")), "text-font" to listOf(MEDIUM),
                    "text-size" to 13, "text-allow-overlap" to true, "text-ignore-placement" to true),
                "paint" to mapOf("text-color" to "#ffffff", "text-halo-color" to "#000000", "text-halo-width" to 1.2)),
        )
        sources["tracks"] = mapOf("type" to "geojson", "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()))
        sources["join"] = mapOf("type" to "geojson", "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()))

        // Faint arrow on the followed line where the bike is taken to be, when the GPS puts it a little off.
        labels += mapOf(
            "id" to "ghost", "type" to "symbol", "source" to "ghost",
            "layout" to mapOf(
                "icon-image" to "me-arrow",
                "icon-rotate" to listOf("get", "bearing"),
                "icon-rotation-alignment" to "map",
                "icon-pitch-alignment" to "map",
                "icon-allow-overlap" to true,
                "icon-ignore-placement" to true,
            ),
            "paint" to mapOf("icon-opacity" to 0.4),
        )
        sources["ghost"] = mapOf("type" to "geojson", "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()))
        // The bike's position on top of everything (filled by the screen).
        labels += mapOf(
            "id" to "me", "type" to "symbol", "source" to "me",
            "layout" to mapOf(
                "icon-image" to "me-arrow",
                "icon-rotate" to listOf("get", "bearing"),
                "icon-rotation-alignment" to "map",
                "icon-pitch-alignment" to "map",
                "icon-allow-overlap" to true,
                "icon-ignore-placement" to true,
            ),
        )
        sources["me"] = mapOf("type" to "geojson", "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()))

        val style = mapOf(
            "version" to 8,
            "name" to "NavExpress",
            "glyphs" to "asset://glyphs/{fontstack}/{range}.pbf",
            "sources" to sources,
            "layers" to fills + lines + extrusions + tracks + labels,
        )
        return JSONObject(style).toString()
    }

    /** Track width from its "w" property (1-3), narrower when zoomed out, plus [extra] for the casing. */
    private fun trackWidth(extra: Double): List<Any> = listOf(
        "interpolate", listOf("linear"), listOf("zoom"),
        8, listOf("+", listOf("*", listOf("get", "w"), 1.2), extra * 0.6),
        14, listOf("+", listOf("*", listOf("get", "w"), 2.5), extra),
        18, listOf("+", listOf("*", listOf("get", "w"), 4), extra * 1.4),
    )

    /** True when the sun is below the horizon at this place and time (good enough for day/night maps). */
    fun isNight(lat: Double, lon: Double, millis: Long = System.currentTimeMillis()): Boolean {
        val d = millis / 86_400_000.0 + 2440587.5 - 2451545.0       // days since J2000
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val dec = asin(sin(e) * sin(l))
        val ra = Math.atan2(cos(e) * sin(l), cos(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        val alt = asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha))
        return alt < Math.toRadians(-3.0)      // a bit after sunset, when the screen needs it
    }
}
