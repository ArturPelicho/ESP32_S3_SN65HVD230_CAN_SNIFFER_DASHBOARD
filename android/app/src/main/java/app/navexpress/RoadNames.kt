package app.navexpress

import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Names the road taken at each turn of a route ("N2", "Rua de Évora") from the offline map on the
 * phone, since the router knows none: just past the turn, the map's road that runs the route's way.
 */
object RoadNames {
    private const val PROBE_M = 35.0
    private const val NEAR_M = 15.0

    /** The route's turns with the road taken at each, where the maps on the phone tell. */
    fun label(maps: List<File>, r: Routed): Routed {
        if (r.turns.isEmpty() || maps.isEmpty()) return r
        val files = maps.mapNotNull { PmTiles.open(it) }
        try {
            val cum = DoubleArray(r.pts.size).also { c -> for (i in 1 until r.pts.size) c[i] = c[i - 1] + metres(r.pts[i - 1], r.pts[i]) }
            val cache = HashMap<Triple<Int, Long, Long>, List<Road>>()
            val turns = r.turns.map { t ->
                if (t.road != null || t.kind == TurnKind.ARRIVE) return@map t
                val at = along(r.pts, cum, LatLon(t.lat, t.lon)) ?: return@map t
                val p = pointAt(r.pts, cum, at + PROBE_M)
                val q = pointAt(r.pts, cum, at + PROBE_M + 15)
                val name = files.firstNotNullOfOrNull { road(it, p, bearing(p, q), cache) }
                if (name != null) t.copy(road = name) else t
            }
            return Routed(r.pts, turns, r.secs)
        } catch (e: Exception) {
            return r
        } finally {
            files.forEach { it.close() }
        }
    }

    private class Road(val pts: List<LatLon>, val ref: String?, val name: String?)

    /** The map's road at [p] running about [dir] (either way), as "ref" or "name"; null when none. */
    private fun road(pm: PmTiles, p: LatLon, dir: Double, cache: HashMap<Triple<Int, Long, Long>, List<Road>>): String? {
        val z = pm.maxZoom.coerceAtMost(15)
        val n = (1L shl z).toDouble()
        val fx = (p.lon + 180) / 360 * n
        val r = Math.toRadians(p.lat)
        val fy = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
        val x = floor(fx).toLong(); val y = floor(fy).toLong()
        val roads = cache.getOrPut(Triple(z, x, y)) { pm.tile(z, x, y)?.let { roadsOf(it, z, x, y) } ?: emptyList() }
        var best: Road? = null; var bd = NEAR_M
        for (rd in roads) for (i in 0 until rd.pts.size - 1) {
            val (d, _) = toSegment(p, rd.pts[i], rd.pts[i + 1])
            if (d >= bd) continue
            val b = bearing(rd.pts[i], rd.pts[i + 1])
            val diff = ((b - dir + 540) % 360) - 180
            if (kotlin.math.abs(diff) > 40 && kotlin.math.abs(diff) < 140) continue
            bd = d; best = rd
        }
        val b = best ?: return null
        return listOfNotNull(b.ref?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() }, b.name?.takeIf { it.isNotBlank() && b.ref.isNullOrBlank() }).firstOrNull()
    }

    // ---- Mapbox vector tile: just the "roads" layer's lines with ref and name ----

    private fun roadsOf(tile: ByteArray, z: Int, tx: Long, ty: Long): List<Road> {
        val out = ArrayList<Road>()
        val t = Pb(tile, 0, tile.size)
        while (t.more()) {
            val (field, wire) = t.key()
            if (field == 3 && wire == 2) {
                val l = t.sub()
                layer(l, z, tx, ty, out)
            } else t.skip(wire)
        }
        return out
    }

    private fun layer(l: Pb, z: Int, tx: Long, ty: Long, out: MutableList<Road>) {
        var name = ""
        val keys = ArrayList<String>(); val values = ArrayList<String?>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (l.more()) {
            val (f, w) = l.key()
            when {
                f == 1 && w == 2 -> name = l.string()
                f == 2 && w == 2 -> features += l.sub()
                f == 3 && w == 2 -> keys += l.string()
                f == 4 && w == 2 -> values += value(l.sub())
                f == 5 && w == 0 -> extent = l.varint().toInt()
                else -> l.skip(w)
            }
        }
        if (name != "roads") return
        val kRef = keys.indexOf("ref"); val kName = keys.indexOf("name")
        for (fp in features) {
            var type = 0; var tags: IntArray? = null; var geom: IntArray? = null
            while (fp.more()) {
                val (f, w) = fp.key()
                when {
                    f == 2 && w == 2 -> tags = fp.packed()
                    f == 3 && w == 0 -> type = fp.varint().toInt()
                    f == 4 && w == 2 -> geom = fp.packed()
                    else -> fp.skip(w)
                }
            }
            if (type != 2 || geom == null) continue
            var ref: String? = null; var nm: String? = null
            tags?.let { tg -> for (i in 0 until tg.size - 1 step 2) {
                if (tg[i] == kRef) ref = values.getOrNull(tg[i + 1])
                if (tg[i] == kName) nm = values.getOrNull(tg[i + 1])
            } }
            if (ref == null && nm == null) continue
            for (line in lines(geom, z, tx, ty, extent)) if (line.size >= 2) out += Road(line, ref, nm)
        }
    }

    private fun value(v: Pb): String? {
        var s: String? = null
        while (v.more()) {
            val (f, w) = v.key()
            if (f == 1 && w == 2) s = v.string() else v.skip(w)
        }
        return s
    }

    private fun lines(g: IntArray, z: Int, tx: Long, ty: Long, extent: Int): List<List<LatLon>> {
        val n = (1L shl z).toDouble()
        fun ll(px: Int, py: Int): LatLon {
            val lon = (tx + px.toDouble() / extent) / n * 360 - 180
            val my = PI * (1 - 2 * (ty + py.toDouble() / extent) / n)
            return LatLon(Math.toDegrees(atan(0.5 * (exp(my) - exp(-my)))), lon)
        }
        val out = ArrayList<List<LatLon>>()
        var cur = ArrayList<LatLon>()
        var x = 0; var y = 0; var i = 0
        fun zz(v: Int) = (v ushr 1) xor -(v and 1)
        while (i < g.size) {
            val cmd = g[i] and 7; val count = g[i] ushr 3; i++
            when (cmd) {
                1 -> repeat(count) {
                    if (cur.size >= 2) out += cur
                    cur = ArrayList()
                    x += zz(g[i]); y += zz(g[i + 1]); i += 2
                    cur += ll(x, y)
                }
                2 -> repeat(count) { x += zz(g[i]); y += zz(g[i + 1]); i += 2; cur += ll(x, y) }
                else -> {}
            }
        }
        if (cur.size >= 2) out += cur
        return out
    }

    /** A protobuf reader over part of a byte array. */
    private class Pb(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var v = 0L; var s = 0
            while (true) { val c = b[p++].toInt() and 0xff; v = v or ((c and 0x7f).toLong() shl s); if (c < 0x80) return v; s += 7 }
        }
        fun key(): Pair<Int, Int> { val k = varint().toInt(); return (k ushr 3) to (k and 7) }
        fun sub(): Pb { val n = varint().toInt(); val r = Pb(b, p, p + n); p += n; return r }
        fun string(): String { val n = varint().toInt(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun packed(): IntArray {
            val n = varint().toInt(); val stop = p + n
            val out = ArrayList<Int>()
            while (p < stop) out += varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> { val n = varint().toInt(); p += n }
                5 -> p += 4
                else -> p = end
            }
        }
    }

    // ---- along the route ----

    private fun along(pts: List<LatLon>, cum: DoubleArray, q: LatLon): Double? {
        var bd = Double.MAX_VALUE; var at = 0.0
        for (i in 0 until pts.size - 1) {
            val (d, t) = toSegment(q, pts[i], pts[i + 1])
            if (d < bd) { bd = d; at = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        return if (bd < 25) at else null
    }

    private fun pointAt(pts: List<LatLon>, cum: DoubleArray, d: Double): LatLon {
        val i = cum.indexOfFirst { it >= d }.let { if (it <= 0) (if (d <= 0) 1 else pts.size - 1) else it }
        val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
        val t = ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        return LatLon(pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * t, pts[i - 1].lon + (pts[i].lon - pts[i - 1].lon) * t)
    }
}
