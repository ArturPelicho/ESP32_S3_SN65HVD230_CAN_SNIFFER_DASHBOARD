package pt.artur.navexpress

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/** A PMTiles v3 file on the phone, read tile by tile (relief for heights, the map for road names). */
class PmTiles private constructor(private val f: RandomAccessFile) {
    private var rootOff = 0L; private var rootLen = 0L
    private var leafOff = 0L; private var dataOff = 0L
    private var gzipDirs = false; private var gzipTiles = false
    var maxZoom = 0
        private set

    companion object {
        fun open(file: File): PmTiles? = runCatching {
            PmTiles(RandomAccessFile(file, "r")).takeIf { it.header() }
        }.getOrNull()
    }

    fun close() = runCatching { f.close() }

    /** The tile's bytes, uncompressed; null where the file has none. */
    @Synchronized
    fun tile(z: Int, x: Long, y: Long): ByteArray? {
        val raw = find(tileId(z, x, y)) ?: return null
        return if (gzipTiles) GZIPInputStream(ByteArrayInputStream(raw)).readBytes() else raw
    }

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

    fun tileId(z: Int, x: Long, y: Long): Long {
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

}
