package pt.artur.motadash

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Height along a track, evenly spaced in distance, for the profile strip on the map. */
/** [ride]: the ride so far (no track), not a track ahead. */
class ElevProfile(val distM: FloatArray, val eleM: FloatArray, val ride: Boolean = false) {
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
class Dem private constructor(private val pm: PmTiles) {
    private val tiles = object : LinkedHashMap<Long, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap?>) = size > 12
    }

    companion object {
        fun open(file: File): Dem? = PmTiles.open(file)?.let { Dem(it) }
    }

    fun close() = pm.close()

    /** Metres above sea level, or null outside this file. */
    fun height(lat: Double, lon: Double): Double? = runCatching {
        val z = pm.maxZoom
        val n = (1L shl z).toDouble()
        val fx = (lon + 180) / 360 * n
        val r = Math.toRadians(lat)
        val fy = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
        val x = floor(fx).toLong(); val y = floor(fy).toLong()
        val id = pm.tileId(z, x, y)
        val bmp = if (tiles.containsKey(id)) tiles[id] else pm.tile(z, x, y)?.let { b ->
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
