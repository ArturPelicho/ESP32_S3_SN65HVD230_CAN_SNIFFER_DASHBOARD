package pt.artur.motadash

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Height along a track, evenly spaced in distance, for the profile strip on the map. */
class ElevProfile(val distM: FloatArray, val eleM: FloatArray) {
    val totalM: Float get() = distM.last()
    val minE: Float = eleM.min()
    val maxE: Float = eleM.max()

    /** Height at [d] metres along the track. */
    fun at(d: Double): Float {
        val i = distM.indexOfFirst { it >= d }.let { if (it < 0) distM.size - 1 else it }
        if (i == 0) return eleM[0]
        val t = ((d - distM[i - 1]) / (distM[i] - distM[i - 1]).coerceAtLeast(1e-3f)).toFloat().coerceIn(0f, 1f)
        return eleM[i - 1] + (eleM[i] - eleM[i - 1]) * t
    }

    companion object {
        /**
         * The track's own heights (GPX, road routes), the gaps filled from the downloaded relief.
         * Null when nothing gives a height (no altitude in the file and no relief for the area).
         */
        fun of(track: Track, reversed: Boolean, maps: List<InstalledMap>, samples: Int = 300): ElevProfile? {
            val lines = if (reversed) track.lines.reversed().map { it.reversed() } else track.lines
            val pts = lines.flatten()
            if (pts.size < 2) return null
            val dist = DoubleArray(pts.size)
            for (i in 1 until pts.size) dist[i] = dist[i - 1] + metres(pts[i - 1], pts[i])
            val total = dist.last()
            if (total < 1) return null
            val dems = maps.mapNotNull { it.shade }.mapNotNull { Dem.open(it) }
            fun dem(lat: Double, lon: Double): Double? = dems.firstNotNullOfOrNull { it.height(lat, lon) }
            try {
                // Long straight legs (planned offline) get points every ~sample spacing so the relief shows.
                val step = total / samples
                val dOut = FloatArray(samples + 1)
                val eOut = arrayOfNulls<Double>(samples + 1)
                var j = 0
                for (k in 0..samples) {
                    val d = (k * step).coerceAtMost(total)
                    while (j < pts.size - 2 && dist[j + 1] < d) j++
                    val a = pts[j]; val b = pts[j + 1]
                    val seg = (dist[j + 1] - dist[j]).coerceAtLeast(1e-6)
                    val t = ((d - dist[j]) / seg).coerceIn(0.0, 1.0)
                    val own = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * t else null
                    dOut[k] = d.toFloat()
                    eOut[k] = own ?: dem(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                }
                val known = eOut.indices.filter { eOut[it] != null }
                if (known.isEmpty()) return null
                // Any samples still without a height: straight between the nearest known ones.
                val e = FloatArray(samples + 1) { k ->
                    eOut[k]?.toFloat() ?: run {
                        val lo = known.lastOrNull { it < k }
                        val hi = known.firstOrNull { it > k }
                        when {
                            lo == null -> eOut[hi!!]!!.toFloat()
                            hi == null -> eOut[lo]!!.toFloat()
                            else -> (eOut[lo]!! + (eOut[hi]!! - eOut[lo]!!) * (k - lo) / (hi - lo)).toFloat()
                        }
                    }
                }
                return ElevProfile(dOut, e)
            } finally {
                dems.forEach { it.close() }
            }
        }
    }
}

/** Reads terrain heights from a relief file (PMTiles v3, Mapbox RGB raster-dem tiles) on the phone. */
class Dem private constructor(private val f: RandomAccessFile) {
    private var rootOff = 0L; private var rootLen = 0L
    private var leafOff = 0L; private var dataOff = 0L
    private var gzipDirs = false; private var gzipTiles = false
    private var maxZoom = 0
    private val tiles = object : LinkedHashMap<Long, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap?>) = size > 12
    }

    companion object {
        fun open(file: File): Dem? = runCatching {
            Dem(RandomAccessFile(file, "r")).takeIf { it.header() }
        }.getOrNull()
    }

    fun close() = runCatching { f.close() }

    private fun read(off: Long, len: Int): ByteArray = ByteArray(len).also { f.seek(off); f.readFully(it) }

    private fun header(): Boolean {
        val h = read(0, 127)
        if (String(h, 0, 7) != "PMTiles" || h[7].toInt() != 3) return false
        fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (h[o + i].toLong() and 0xff); return v }
        rootOff = u64(8); rootLen = u64(16); leafOff = u64(40); dataOff = u64(56)
        gzipDirs = h[97].toInt() == 2
        gzipTiles = h[98].toInt() == 2
        maxZoom = h[101].toInt()
        return true
    }

    private class Entry(val id: Long, val off: Long, val len: Int, val run: Int)

    private fun directory(off: Long, len: Long): List<Entry> {
        var b = read(off, len.toInt())
        if (gzipDirs) b = GZIPInputStream(ByteArrayInputStream(b)).readBytes()
        var p = 0
        fun varint(): Long {
            var v = 0L; var s = 0
            while (true) { val c = b[p++].toInt() and 0xff; v = v or ((c and 0x7f).toLong() shl s); if (c < 0x80) return v; s += 7 }
        }
        val n = varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        val runs = IntArray(n) { varint().toInt() }
        val lens = IntArray(n) { varint().toInt() }
        val offs = LongArray(n)
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    private fun find(id: Long): ByteArray? {
        var off = rootOff; var len = rootLen
        repeat(4) {
            val dir = directory(off, len)
            val e = dir.lastOrNull { it.id <= id } ?: return null
            if (e.run == 0) { off = leafOff + e.off; len = e.len.toLong() }
            else return if (id < e.id + e.run) read(dataOff + e.off, e.len) else null
        }
        return null
    }

    private fun tileId(z: Int, x: Long, y: Long): Long {
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        var xx = x; var yy = y; var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((xx and s) > 0) 1L else 0L
            val ry = if ((yy and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                val t = xx; xx = yy; yy = t
            }
            s /= 2
        }
        return acc + d
    }

    /** Metres above sea level, or null outside this file. */
    fun height(lat: Double, lon: Double): Double? = runCatching {
        val z = maxZoom
        val n = (1L shl z).toDouble()
        val fx = (lon + 180) / 360 * n
        val r = Math.toRadians(lat)
        val fy = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
        val x = floor(fx).toLong(); val y = floor(fy).toLong()
        val id = tileId(z, x, y)
        val bmp = if (tiles.containsKey(id)) tiles[id] else find(id)?.let { raw ->
            val b = if (gzipTiles) GZIPInputStream(ByteArrayInputStream(raw)).readBytes() else raw
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.also { tiles[id] = it }
        bmp ?: return@runCatching null
        val px = ((fx - x) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val py = ((fy - y) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(px, py)
        val v = ((c shr 16) and 0xff) * 65536 + ((c shr 8) and 0xff) * 256 + (c and 0xff)
        -10000.0 + v * 0.1
    }.getOrNull()
}
