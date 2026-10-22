package pt.artur.navexpress

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/** A section just ridden: its number (from 1), the time, and the best before it (null the first time). */
data class Split(val section: Int, val secs: Double, val best: Double?) {
    val faster: Boolean? get() = best?.let { secs < it - 0.05 }
    val delta: Double? get() = best?.let { secs - it }
}

/**
 * Times each section of a track, about a kilometre each and cut where a corner ends, against the best
 * kept for that track and direction; a recorded trip starts with the times it was recorded at.
 */
class SectionTimes(ctx: Context, track: Track, reversed: Boolean, private val totalM: Double, notes: Pacenotes?) {
    private val file = File(File(ctx.filesDir, "sections").apply { mkdirs() }, track.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + (if (reversed) "-r" else "") + ".json")
    /** Start line, cuts, finish line, metres along. */
    val bounds: DoubleArray
    private val best: Array<Double?>

    private var entry = -1
    private var entryT = 0.0
    private var lastDone = -1.0
    private var lastT = 0.0

    init {
        val cuts = ArrayList<Double>()
        cuts += START_M
        var from = START_M
        val ends = notes?.notes?.filter { it.kind == NoteKind.CORNER || it.kind == NoteKind.HAIRPIN }?.map { it.endM + 10 } ?: emptyList()
        while (true) {
            val want = from + SECTION_M
            if (want > totalM - START_M - SECTION_M / 2) break
            // The end of the first corner past the kilometre, unless that is far off.
            val c = ends.firstOrNull { it >= want && it < want + SECTION_M / 2 } ?: want
            cuts += c; from = c
        }
        cuts += totalM - START_M
        bounds = cuts.toDoubleArray()
        val saved = runCatching { JSONObject(file.readText()) }.getOrNull()
        best = arrayOfNulls(bounds.size - 1)
        if (saved != null && abs(saved.optDouble("total") - totalM) < 1 && saved.optInt("bounds") == bounds.size) {
            val b = saved.getJSONArray("best")
            for (i in best.indices) best[i] = b.optDouble(i).takeIf { !it.isNaN() && it > 0 }
        } else if (!reversed) seed(track)
    }

    /** A recorded trip: what it took at the time is the first best of each section. */
    private fun seed(track: Track) {
        if (track.times.size != track.lines.size || track.lines.indices.any { track.times[it].size != track.lines[it].size }) return
        val d = ArrayList<Double>(); val t = ArrayList<Double>()
        var acc = 0.0
        for ((li, l) in track.lines.withIndex()) for (j in l.indices) {
            if (j > 0) acc += metres(l[j - 1], l[j])
            d += acc; t += track.times[li][j] / 1000.0
        }
        fun at(x: Double): Double? {
            val i = d.indexOfFirst { it >= x }
            if (i <= 0) return null
            val f = ((x - d[i - 1]) / (d[i] - d[i - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            return t[i - 1] + (t[i] - t[i - 1]) * f
        }
        for (i in best.indices) {
            val a = at(bounds[i]) ?: continue; val b = at(bounds[i + 1]) ?: continue
            // A stop on the way (a break, a fuel stop) is not a time to beat.
            val secs = b - a
            if (secs > 0 && (bounds[i + 1] - bounds[i]) / secs > 2.0) best[i] = secs
        }
        save()
    }

    /** Where the bike is now, on the track; a section just finished comes back as its split. */
    fun step(doneM: Double, offM: Double, nowS: Double): Split? {
        val prev = lastDone; val pt = lastT
        lastDone = doneM; lastT = nowS
        // Off the line, a jump, a long gap or going backwards: the section running no longer counts.
        if (offM > 30 || prev < 0 || doneM - prev > 250 || doneM < prev - 5 || nowS - pt > 20) { entry = -1; return null }
        if (doneM <= prev) return null
        var out: Split? = null
        for (k in bounds.indices) {
            val b = bounds[k]
            if (prev < b && doneM >= b) {
                val tc = pt + (b - prev) / (doneM - prev) * (nowS - pt)
                if (entry == k - 1 && k >= 1) {
                    val i = k - 1
                    val secs = tc - entryT
                    val before = best[i]
                    out = Split(i + 1, secs, before)
                    if (before == null || secs < before) { best[i] = secs; save() }
                }
                entry = if (k < bounds.size - 1) k else -1
                entryT = tc
            }
        }
        return out
    }

    private fun save() = runCatching {
        file.writeText(JSONObject().put("total", totalM).put("bounds", bounds.size)
            .put("best", JSONArray(best.map { it ?: JSONObject.NULL })).toString())
    }

    companion object {
        const val SECTION_M = 1000.0
        const val START_M = 15.0

        /** "1:23.4", "-1.2 s", "+0.8 s". */
        fun clock(s: Double): String { val t = (s * 10).roundToLong(); return "%d:%02d.%d".format(t / 600, t / 10 % 60, t % 10) }
        fun delta(s: Double): String = (if (s < 0) "−" else "+") + "%.1f s".format(abs(s))
    }
}
