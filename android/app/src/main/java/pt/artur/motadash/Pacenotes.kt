package pt.artur.motadash

import android.content.Context
import kotlin.math.abs
import kotlin.math.sign

/** What a pacenote calls. */
enum class NoteKind { CORNER, HAIRPIN, CREST, DIP }

/**
 * One pacenote, [atM]..[endM] metres along the followed line. [dir]: -1 left, +1 right. [grade]: the
 * rally scale, 1 tightest to 6 nearly flat, from the corner's radius.
 */
data class Note(
    val atM: Double, val endM: Double, val kind: NoteKind,
    val dir: Int = 0, val grade: Int = 0,
    val long: Boolean = false, val tightens: Boolean = false, val opens: Boolean = false,
)

/**
 * Rally-style pacenotes read from a track's shape (and heights, when known), for Track mode: each
 * corner graded 1 to 6 by its radius, long / tightens / opens, crests and dips, notes that follow
 * each other closely linked ("into", "com contracurva"), and the straight to the next note.
 */
class Pacenotes(lines: List<List<LatLon>>, heights: ElevProfile?) {
    val notes: List<Note>

    init {
        val corners = ArrayList<Note>()
        var base = 0.0
        for (l in lines) {
            corners += corners(l, base)
            base += (1 until l.size).sumOf { metres(l[it - 1], l[it]) }
        }
        val bumps = heights?.let { bumps(it) } ?: emptyList()
        // A crest in the middle of a corner is still worth its own call; one right on another note's start is not.
        notes = (corners + bumps.filter { b -> corners.none { abs(it.atM - b.atM) < 15 } }).sortedBy { it.atM }
    }

    /** The next note not yet reached, [doneM] metres along, and how far it is. */
    fun next(doneM: Double): Pair<Note, Double>? = notes.firstOrNull { it.atM > doneM - 2 }?.let { it to (it.atM - doneM).coerceAtLeast(0.0) }

    /** Notes that follow so closely they are one call, from index [i]. */
    fun group(i: Int): List<Note> {
        val g = arrayListOf(notes[i])
        while (i + g.size < notes.size && notes[i + g.size].atM - g.last().endM < LINK_M) g += notes[i + g.size]
        return g
    }

    companion object {
        private const val STEP = 5.0
        /** Bending less than this (degrees per metre, a radius of about 350 m) is straight. */
        private const val K_MIN = 57.3 / 350
        /** Notes closer than this are linked into one call. */
        const val LINK_M = 30.0

        /** Corner grade from the radius in metres, for a road bike: 1 tight ... 6 almost flat. */
        fun grade(r: Double): Int = when {
            r < 25 -> 1
            r < 45 -> 2
            r < 70 -> 3
            r < 110 -> 4
            r < 170 -> 5
            else -> 6
        }

        internal fun corners(l: List<LatLon>, base: Double): List<Note> {
            if (l.size < 2) return emptyList()
            val d = DoubleArray(l.size)
            for (i in 1 until l.size) d[i] = d[i - 1] + metres(l[i - 1], l[i])
            val total = d.last()
            val n = (total / STEP).toInt()
            if (n < 8) return emptyList()
            var j = 0
            val p = List(n + 1) { k ->
                val s = k * STEP
                while (j < l.size - 2 && d[j + 1] < s) j++
                val t = ((s - d[j]) / (d[j + 1] - d[j]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                LatLon(l[j].lat + (l[j + 1].lat - l[j].lat) * t, l[j].lon + (l[j + 1].lon - l[j].lon) * t)
            }
            // Points smoothed over 30 m (GPS jitter, sparse vertices), then the heading at each.
            val ps = List(n + 1) { k ->
                val a = (k - 3).coerceAtLeast(0); val b = (k + 3).coerceAtMost(n)
                LatLon((a..b).sumOf { p[it].lat } / (b - a + 1), (a..b).sumOf { p[it].lon } / (b - a + 1))
            }
            val h = DoubleArray(n + 1)
            for (k in 0..n) h[k] = bearing(ps[(k - 1).coerceAtLeast(0)], ps[(k + 1).coerceAtMost(n)])
            for (k in 1..n) { var x = h[k] - h[k - 1]; while (x > 180) x -= 360; while (x < -180) x += 360; h[k] = h[k - 1] + x }
            // Curvature in degrees per metre, positive to the right, lightly smoothed.
            val raw = DoubleArray(n + 1) { k -> if (k == 0 || k == n) 0.0 else (h[k + 1] - h[k - 1]) / (2 * STEP) }
            val kk = DoubleArray(n + 1) { k -> (raw[(k - 1).coerceAtLeast(0)] + 2 * raw[k] + raw[(k + 1).coerceAtMost(n)]) / 4 }

            // Runs bending one way, joined across short easings that do not bend the other way.
            val runs = ArrayList<IntArray>()
            var k = 0
            while (k <= n) {
                if (abs(kk[k]) < K_MIN) { k++; continue }
                val s = sign(kk[k])
                var e = k
                while (e + 1 <= n && sign(kk[e + 1]) == s && abs(kk[e + 1]) >= K_MIN) e++
                val last = runs.lastOrNull()
                if (last != null && sign(kk[last[0]]) == s && k - last[1] <= 4 && (last[1]..k).none { sign(kk[it]) == -s && abs(kk[it]) >= K_MIN }) last[1] = e
                else runs += intArrayOf(k, e)
                k = e + 1
            }
            val out = ArrayList<Note>()
            for ((a, b) in runs.map { it[0] to it[1] }) {
                val angle = (a..b).sumOf { kk[it] } * STEP
                val len = (b - a + 1) * STEP
                // GPS jitter makes short wiggles: a short bend has to turn a fair bit to count.
                if (abs(angle) < 18 || (len < 30 && abs(angle) < 35)) continue
                val kmax = (a..b).maxOf { abs(kk[it]) }
                val core = (a..b).filter { abs(kk[it]) >= 0.6 * kmax }
                val coreAngle = core.sumOf { abs(kk[it]) } * STEP
                val r = core.size * STEP / Math.toRadians(coreAngle)
                val hairpin = abs(angle) >= 140 && r < 35
                var tightens = false; var opens = false
                // Tightens or opens: the way out bends much more (or less) than the way in, ends trimmed.
                val ia = a + 2; val ib = b - 2
                if (ib - ia >= 8) {
                    val third = (ib - ia + 1) / 3
                    val k1 = (ia until ia + third).sumOf { abs(kk[it]) } / third
                    val k3 = (ib - third + 1..ib).sumOf { abs(kk[it]) } / third
                    tightens = k3 > k1 * 2; opens = k1 > k3 * 2
                }
                val g = grade(r)
                out += Note(
                    base + a * STEP, base + b * STEP, if (hairpin) NoteKind.HAIRPIN else NoteKind.CORNER,
                    if (angle > 0) 1 else -1, if (hairpin) 1 else g,
                    long = !hairpin && abs(angle) >= 60 && (len >= 120 || (abs(angle) >= 110 && g >= 3)),
                    tightens = !hairpin && tightens, opens = !hairpin && opens,
                )
            }
            return out
        }

        /** Crests and dips: where the slope swings by 9 % or more within about 60 m. */
        internal fun bumps(pr: ElevProfile): List<Note> {
            val n = pr.distM.size
            if (n < 12) return emptyList()
            val step = (pr.totalM / (n - 1)).toDouble()
            if (step > 25) return emptyList()
            val w = (10 / step).toInt().coerceAtLeast(1)
            val e = DoubleArray(n) { i -> val a = (i - 2 * w).coerceAtLeast(0); val b = (i + 2 * w).coerceAtMost(n - 1); (a..b).sumOf { pr.eleM[it].toDouble() } / (b - a + 1) }
            val span = (30 / step).toInt().coerceAtLeast(2)
            fun slope(i: Int): Double { val a = (i - span / 2).coerceAtLeast(0); val b = (i + span / 2).coerceAtMost(n - 1); return (e[b] - e[a]) / ((b - a) * step) }
            val out = ArrayList<Note>()
            var i = span
            while (i < n - span) {
                val before = slope(i - span / 2 - 1); val after = slope(i + span / 2 + 1)
                val crest = before >= 0.04 && after <= -0.04 && before - after >= 0.09
                val dip = before <= -0.04 && after >= 0.04 && after - before >= 0.09
                if ((crest && e[i] >= e[i - 1] && e[i] >= e[i + 1]) || (dip && e[i] <= e[i - 1] && e[i] <= e[i + 1])) {
                    out += Note(i * step - 10, i * step + 10, if (crest) NoteKind.CREST else NoteKind.DIP)
                    i += (60 / step).toInt().coerceAtLeast(1)
                } else i++
            }
            return out
        }

        /** Rally distances: the straight after a call, rounded to what co-drivers say. */
        private val STEPS = intArrayOf(30, 40, 50, 70, 100, 150, 200, 250, 300, 400, 500)

        fun rounded(m: Double): Int? {
            val v = if (Units.imperial) m * 1.0936 else m
            if (v < 30 || v > 560) return null
            return STEPS.minBy { abs(it - v) }
        }
    }
}

/** How a note reads and is said. */
object NoteText {
    fun of(ctx: Context, n: Note, numbers: Boolean = MapStore.pacenoteNumbers(ctx)): String {
        val dir = if (n.dir < 0) L.s(R.string.pn_left) else L.s(R.string.pn_right)
        return when (n.kind) {
            NoteKind.CREST -> L.s(R.string.pn_crest)
            NoteKind.DIP -> L.s(R.string.pn_dip)
            NoteKind.HAIRPIN -> L.s(R.string.pn_hairpin) + " " + dir
            NoteKind.CORNER -> {
                val parts = ArrayList<String>()
                if (numbers) parts += listOfNotNull(L.s(R.string.pn_long).takeIf { n.long }, dir, n.grade.toString()).joinToString(" ")
                else {
                    if (n.long) parts += L.s(R.string.pn_long)
                    parts += "${word(n.grade)} $dir"
                }
                if (n.tightens) parts += L.s(R.string.pn_tightens)
                if (n.opens) parts += L.s(R.string.pn_opens)
                parts.joinToString(if (numbers) " " else ", ")
            }
        }
    }

    private fun word(g: Int) = L.s(when (g) {
        1 -> R.string.pn_g1; 2 -> R.string.pn_g2; 3 -> R.string.pn_g3
        4 -> R.string.pn_g4; 5 -> R.string.pn_g5; else -> R.string.pn_g6
    })

    /** A whole call: the linked notes, then the straight to the next one ("…, 100"). */
    fun call(ctx: Context, group: List<Note>, gapM: Double?): String {
        val numbers = MapStore.pacenoteNumbers(ctx)
        val sb = StringBuilder(of(ctx, group[0], numbers).cap())
        for (i in 1 until group.size) {
            val a = group[i - 1]; val b = group[i]
            val counter = a.dir != 0 && b.dir != 0 && a.dir != b.dir
            sb.append(", ").append(L.s(if (numbers) R.string.pn_into else if (counter) R.string.pn_counter else R.string.pn_and)).append(' ').append(of(ctx, b, numbers))
        }
        gapM?.let { Pacenotes.rounded(it) }?.let { sb.append(", ").append(if (numbers) "$it" else L.s(R.string.turn_metres, it)) }
        return sb.toString()
    }

    /** Arrow for the banner. */
    fun arrow(n: Note): String = when (n.kind) {
        NoteKind.CREST -> "⌒"
        NoteKind.DIP -> "◡"
        NoteKind.HAIRPIN -> if (n.dir < 0) "↶" else "↷"
        NoteKind.CORNER -> when {
            n.grade >= 5 -> if (n.dir < 0) "↖" else "↗"
            n.grade >= 3 -> if (n.dir < 0) "←" else "→"
            else -> if (n.dir < 0) "↙" else "↘"
        }
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}

/** Says the pacenotes ahead in time to act on them: each call once, linked notes together. */
class PacenoteVoice {
    private var notes: Pacenotes? = null
    private var nextIdx = 0
    private var lastDone = -1.0

    fun step(ctx: Context, pn: Pacenotes, doneM: Double, speedMs: Float) {
        if (pn !== notes) { notes = pn; nextIdx = -1 }
        // A fresh start or a jump along the line: carry on from the first note still ahead.
        if (nextIdx < 0 || abs(doneM - lastDone) > 200) nextIdx = pn.notes.indexOfFirst { it.atM > doneM - 2 }.let { if (it < 0) pn.notes.size else it }
        lastDone = doneM
        if (nextIdx >= pn.notes.size || !MapStore.turnVoice(ctx)) return
        val lead = (speedMs * 5.0).coerceIn(60.0, 300.0)
        if (pn.notes[nextIdx].atM - doneM > lead) return
        val g = pn.group(nextIdx)
        nextIdx += g.size
        val gap = pn.notes.getOrNull(nextIdx)?.let { it.atM - g.last().endM }
        Speech.say(ctx, NoteText.call(ctx, g, gap))
    }
}
