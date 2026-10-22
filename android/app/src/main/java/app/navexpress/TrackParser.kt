package app.navexpress

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** One point of a track, in degrees (and metres for the altitude). */
data class LatLon(val lat: Double, val lon: Double, val ele: Double? = null)

data class Waypoint(val lat: Double, val lon: Double, val name: String)

/** What a track file holds: lines (segments, routes) and named points. */
data class ParsedTrack(val name: String?, val lines: List<List<LatLon>>, val waypoints: List<Waypoint>)

/**
 * Reads GPX, KML, KMZ, GeoJSON, TCX and FIT files into lines and waypoints.
 * No Android classes, so it runs in plain JVM tests too.
 */
object TrackParser {

    fun parse(bytes: ByteArray, fileName: String = ""): ParsedTrack {
        val lower = fileName.lowercase()
        val t = when {
            bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> parseKmz(bytes)
            isFit(bytes) || lower.endsWith(".fit") -> parseFit(bytes)
            firstChar(bytes) == '{' || lower.endsWith(".geojson") || lower.endsWith(".json") -> parseGeoJson(String(bytes))
            else -> parseXml(bytes)
        }
        val lines = t.lines.filter { it.size >= 2 }
        if (lines.isEmpty() && t.waypoints.isEmpty()) error(L.s(R.string.tracks_err_no_tracks))
        return t.copy(lines = lines)
    }

    private fun firstChar(b: ByteArray): Char? {
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < b.size && b[i].toInt().toChar().isWhitespace()) i++
        return if (i < b.size) b[i].toInt().toChar() else null
    }

    // ---- XML: GPX, KML, TCX ------------------------------------------------

    private fun parseXml(bytes: ByteArray): ParsedTrack {
        val h = XmlHandler()
        val f = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), h)
        h.closeLine()
        return ParsedTrack(h.title, h.lines, h.waypoints)
    }

    private class XmlHandler : DefaultHandler() {
        val lines = mutableListOf<List<LatLon>>()
        val waypoints = mutableListOf<Waypoint>()
        var title: String? = null
        private var line = mutableListOf<LatLon>()
        private val stack = ArrayDeque<String>()
        private val text = StringBuilder()
        // GPX point being read (trkpt / rtept / wpt).
        private var ptLat = Double.NaN
        private var ptLon = Double.NaN
        private var ptEle: Double? = null
        private var ptName: String? = null
        // KML placemark and TCX trackpoint.
        private var placemarkName: String? = null
        private var tcxLat = Double.NaN
        private var tcxLon = Double.NaN
        private var tcxEle: Double? = null

        fun closeLine() {
            if (line.isNotEmpty()) lines += line
            line = mutableListOf()
        }

        override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
            val n = localName.ifEmpty { qName?.substringAfter(':') ?: "" }
            stack.addLast(n)
            text.setLength(0)
            when (n) {
                "trkseg", "rte", "LineString", "Track", "gx:Track" -> closeLine()
                "trkpt", "rtept", "wpt" -> {
                    ptLat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                    ptLon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                    ptEle = null; ptName = null
                }
                "Placemark" -> placemarkName = null
                "Trackpoint" -> { tcxLat = Double.NaN; tcxLon = Double.NaN; tcxEle = null }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            val n = localName.ifEmpty { qName?.substringAfter(':') ?: "" }
            stack.removeLastOrNull()
            val parent = stack.lastOrNull()
            val s = text.toString().trim()
            when (n) {
                "ele" -> if (parent in setOf("trkpt", "rtept", "wpt")) ptEle = s.toDoubleOrNull()
                "name" -> when {
                    parent == "wpt" || parent == "rtept" -> ptName = s
                    parent == "Placemark" -> placemarkName = s
                    title == null && parent in setOf("metadata", "trk", "rte", "Document", "Folder") && s.isNotEmpty() -> title = s
                }
                "trkpt", "rtept" -> if (!ptLat.isNaN() && !ptLon.isNaN()) line += LatLon(ptLat, ptLon, ptEle)
                "wpt" -> if (!ptLat.isNaN() && !ptLon.isNaN()) waypoints += Waypoint(ptLat, ptLon, ptName ?: "")
                "trkseg", "rte", "Track", "Lap" -> closeLine()
                // KML
                "coordinates" -> {
                    val pts = s.split(Regex("\\s+")).mapNotNull { c ->
                        val p = c.split(",")
                        val lon = p.getOrNull(0)?.toDoubleOrNull()
                        val lat = p.getOrNull(1)?.toDoubleOrNull()
                        if (lon != null && lat != null) LatLon(lat, lon, p.getOrNull(2)?.toDoubleOrNull()) else null
                    }
                    when (parent) {
                        "Point" -> pts.firstOrNull()?.let { waypoints += Waypoint(it.lat, it.lon, placemarkName ?: "") }
                        else -> { closeLine(); line += pts; closeLine() }
                    }
                }
                "coord" -> {      // gx:coord "lon lat alt"
                    val p = s.split(Regex("\\s+"))
                    val lon = p.getOrNull(0)?.toDoubleOrNull()
                    val lat = p.getOrNull(1)?.toDoubleOrNull()
                    if (lon != null && lat != null) line += LatLon(lat, lon, p.getOrNull(2)?.toDoubleOrNull())
                }
                // TCX
                "LatitudeDegrees" -> tcxLat = s.toDoubleOrNull() ?: Double.NaN
                "LongitudeDegrees" -> tcxLon = s.toDoubleOrNull() ?: Double.NaN
                "AltitudeMeters" -> if (parent == "Trackpoint") tcxEle = s.toDoubleOrNull()
                "Trackpoint" -> if (!tcxLat.isNaN() && !tcxLon.isNaN()) line += LatLon(tcxLat, tcxLon, tcxEle)
                "Id", "Name" -> if (title == null && parent in setOf("Activity", "Course") && s.isNotEmpty()) title = s
            }
            text.setLength(0)
        }
    }

    // ---- KMZ: a zip with a .kml inside -------------------------------------

    private fun parseKmz(bytes: ByteArray): ParsedTrack {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase().endsWith(".kml")) return parseXml(z.readBytes())
            }
        }
        error(L.s(R.string.tracks_err_kmz_no_kml))
    }

    // ---- GeoJSON --------------------------------------------------------------

    private fun parseGeoJson(text: String): ParsedTrack {
        val root = JSONObject(text)
        val lines = mutableListOf<List<LatLon>>()
        val wpts = mutableListOf<Waypoint>()
        var title: String? = root.optJSONObject("properties")?.optString("name")?.ifEmpty { null }

        fun pos(a: JSONArray) = LatLon(a.getDouble(1), a.getDouble(0), if (a.length() > 2) a.optDouble(2).takeIf { !it.isNaN() } else null)
        fun line(a: JSONArray) = List(a.length()) { pos(a.getJSONArray(it)) }
        fun geometry(g: JSONObject?, name: String) {
            g ?: return
            val c = g.optJSONArray("coordinates")
            when (g.optString("type")) {
                "LineString" -> lines += line(c!!)
                "MultiLineString", "Polygon" -> for (i in 0 until c!!.length()) lines += line(c.getJSONArray(i))
                "MultiPolygon" -> for (i in 0 until c!!.length()) {
                    val poly = c.getJSONArray(i)
                    for (j in 0 until poly.length()) lines += line(poly.getJSONArray(j))
                }
                "Point" -> pos(c!!).let { wpts += Waypoint(it.lat, it.lon, name) }
                "MultiPoint" -> for (i in 0 until c!!.length()) pos(c.getJSONArray(i)).let { wpts += Waypoint(it.lat, it.lon, name) }
                "GeometryCollection" -> g.optJSONArray("geometries")?.let { gs ->
                    for (i in 0 until gs.length()) geometry(gs.getJSONObject(i), name)
                }
            }
        }
        fun feature(f: JSONObject) {
            val name = f.optJSONObject("properties")?.optString("name") ?: ""
            if (title == null && f.optJSONObject("geometry")?.optString("type")?.contains("Line") == true && name.isNotEmpty()) title = name
            geometry(f.optJSONObject("geometry"), name)
        }
        when (root.optString("type")) {
            "FeatureCollection" -> root.optJSONArray("features")?.let { fs -> for (i in 0 until fs.length()) feature(fs.getJSONObject(i)) }
            "Feature" -> feature(root)
            else -> geometry(root, "")
        }
        return ParsedTrack(title, lines, wpts)
    }

    // ---- FIT (Garmin and others) ----------------------------------------------

    private fun isFit(b: ByteArray): Boolean =
        b.size > 12 && b[8] == '.'.code.toByte() && b[9] == 'F'.code.toByte() && b[10] == 'I'.code.toByte() && b[11] == 'T'.code.toByte()

    private class FitDef(val global: Int, val bigEndian: Boolean, val fields: List<Triple<Int, Int, Int>>, val devBytes: Int)

    /** Reads "record" messages (position_lat / position_long / altitude) into one line. */
    private fun parseFit(b: ByteArray): ParsedTrack {
        if (!isFit(b)) error(L.s(R.string.tracks_err_not_fit))
        val headerSize = b[0].toInt() and 0xFF
        val dataSize = le32(b, 4)
        val end = minOf(b.size, headerSize + dataSize)
        val defs = HashMap<Int, FitDef>()
        val line = mutableListOf<LatLon>()
        val semi = 180.0 / 2147483648.0
        var i = headerSize
        while (i < end) {
            val h = b[i].toInt() and 0xFF; i++
            if (h and 0x80 != 0) {                     // compressed timestamp header: data message
                val d = defs[(h shr 5) and 0x3] ?: error(L.s(R.string.tracks_err_fit_invalid))
                i = fitData(b, i, d, line, semi)
            } else if (h and 0x40 != 0) {              // definition message
                val local = h and 0x0F
                val big = b[i + 1].toInt() == 1
                val global = if (big) ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    else (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                val n = b[i + 4].toInt() and 0xFF
                i += 5
                val fields = List(n) { k -> Triple(b[i + 3 * k].toInt() and 0xFF, b[i + 3 * k + 1].toInt() and 0xFF, b[i + 3 * k + 2].toInt() and 0xFF) }
                i += 3 * n
                var dev = 0
                if (h and 0x20 != 0) {
                    val nd = b[i].toInt() and 0xFF; i++
                    for (k in 0 until nd) dev += b[i + 3 * k + 1].toInt() and 0xFF
                    i += 3 * nd
                }
                defs[local] = FitDef(global, big, fields, dev)
            } else {
                val d = defs[h and 0x0F] ?: error(L.s(R.string.tracks_err_fit_invalid))
                i = fitData(b, i, d, line, semi)
            }
        }
        return ParsedTrack(null, listOf(line), emptyList())
    }

    private fun fitData(b: ByteArray, start: Int, d: FitDef, line: MutableList<LatLon>, semi: Double): Int {
        var i = start
        var lat: Long? = null
        var lon: Long? = null
        var alt: Long? = null
        for ((num, size, _) in d.fields) {
            if (d.global == 20 && (num == 0 || num == 1) && size == 4) {
                val v = int(b, i, 4, d.bigEndian)
                val s = if (v >= 0x80000000L) v - 0x100000000L else v
                if (v != 0x7FFFFFFFL) { if (num == 0) lat = s else lon = s }
            } else if (d.global == 20 && num == 2 && size == 2) {
                val v = int(b, i, 2, d.bigEndian)
                if (v != 0xFFFFL) alt = v
            }
            i += size
        }
        i += d.devBytes
        if (lat != null && lon != null) line += LatLon(lat * semi, lon * semi, alt?.let { it / 5.0 - 500 })
        return i
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun int(b: ByteArray, i: Int, size: Int, big: Boolean): Long {
        var v = 0L
        for (k in 0 until size) {
            val byte = (b[i + if (big) k else size - 1 - k].toLong() and 0xFF)
            v = (v shl 8) or byte
        }
        return v
    }
}
