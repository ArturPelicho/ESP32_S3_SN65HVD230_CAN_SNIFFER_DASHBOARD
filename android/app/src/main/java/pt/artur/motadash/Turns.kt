package pt.artur.motadash

import android.content.Context
import kotlin.math.abs

/** What to do at a turn. */
enum class TurnKind {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT, UTURN, ROUNDABOUT, EXIT_LEFT, EXIT_RIGHT, ARRIVE;

    companion object {
        /** BRouter's voice hint commands (VoiceHint.java). Straight on gives nothing to say. */
        fun ofBrouter(cmd: Int): TurnKind? = when (cmd) {
            2 -> LEFT; 3 -> SLIGHT_LEFT; 4 -> SHARP_LEFT
            5 -> RIGHT; 6 -> SLIGHT_RIGHT; 7 -> SHARP_RIGHT
            8 -> KEEP_LEFT; 9 -> KEEP_RIGHT
            10, 11, 15 -> UTURN
            13, 14 -> ROUNDABOUT
            17 -> EXIT_LEFT; 18 -> EXIT_RIGHT
            else -> null
        }

        /** From the change of direction, in degrees (positive to the right). */
        fun ofAngle(d: Double): TurnKind = when {
            d <= -150 || d >= 150 -> UTURN
            d < -110 -> SHARP_LEFT
            d < -50 -> LEFT
            d < -25 -> SLIGHT_LEFT
            d > 110 -> SHARP_RIGHT
            d > 50 -> RIGHT
            d > 25 -> SLIGHT_RIGHT
            else -> STRAIGHT
        }
    }
}

/** A turn where a road route makes it, kept with the trajeto. [exit]: roundabout exit (0 when unknown). */
data class TurnPt(val lat: Double, val lon: Double, val kind: TurnKind, val exit: Int = 0)

/** A road route with its turns. */
/** A road route: its points, turns, and the router's expected time at each point (seconds from the start). */
class Routed(val pts: List<LatLon>, val turns: List<TurnPt>, val secs: List<Double> = emptyList())

/** A turn on the line being followed, [atM] metres along it. [then]: way out of a roundabout when its exit is unknown. */
data class Maneuver(val atM: Double, val kind: TurnKind, val exit: Int = 0, val then: TurnKind? = null)

/**
 * The turns along a followed line: the router's own where the line came from it (planned trajetos,
 * the Nav way back), otherwise read from the shape of the line itself (any GPX).
 */
class Guidance(lines: List<List<LatLon>>, stored: List<TurnPt>, arrive: Boolean = true) {
    private val pts: List<LatLon> = lines.flatten()
    private val gap = BooleanArray(pts.size).also { g ->
        var k = 0
        for (l in lines) { if (k > 0 && k < g.size) g[k] = true; k += l.size }
    }
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + if (gap[i]) 0.0 else metres(pts[i - 1], pts[i])
    }
    val totalM = cum.lastOrNull() ?: 0.0

    val maneuvers: List<Maneuver> = run {
        val fromRouter = stored.mapNotNull { s -> along(LatLon(s.lat, s.lon))?.let { Maneuver(it, s.kind, s.exit) } }
        val list = if (fromRouter.isNotEmpty()) fromRouter.sortedBy { it.atM } else lines.flatMapIndexed { i, l -> fromShape(l, start(i, lines)) }
        if (arrive && totalM > 50) list + Maneuver(totalM, TurnKind.ARRIVE) else list
    }

    /** The next turn ahead of [doneM] metres along, and how far it is. */
    fun next(doneM: Double): Pair<Maneuver, Double>? =
        maneuvers.firstOrNull { it.atM > doneM + 3 }?.let { it to it.atM - doneM }

    private fun start(i: Int, lines: List<List<LatLon>>): Double {
        val k = lines.take(i).sumOf { it.size }
        return if (k < cum.size) cum[k] else totalM
    }

    /** Metres along the line to the point, or null when it is not on the line. */
    private fun along(p: LatLon): Double? {
        var bd = Double.MAX_VALUE; var at = 0.0
        for (i in 0 until pts.size - 1) {
            if (gap[i + 1]) continue
            val (d, t) = toSegment(p, pts[i], pts[i + 1])
            if (d < bd) { bd = d; at = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        return if (bd < 20) at else null
    }

    companion object {
        private const val STEP = 10.0

        /** Turns read from a line's shape: a clear change of direction, a roundabout where it turns a lot and comes out. */
        fun fromShape(l: List<LatLon>, base: Double): List<Maneuver> {
            if (l.size < 2) return emptyList()
            val d = DoubleArray(l.size)
            for (i in 1 until l.size) d[i] = d[i - 1] + metres(l[i - 1], l[i])
            val total = d.last()
            if (total < 60) return emptyList()
            fun at(s: Double): LatLon {
                var i = d.indexOfFirst { it >= s }
                if (i <= 0) i = 1
                val seg = (d[i] - d[i - 1]).coerceAtLeast(1e-6)
                val t = ((s - d[i - 1]) / seg).coerceIn(0.0, 1.0)
                val a = l[i - 1]; val b = l[i]
                return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            val n = (total / STEP).toInt()
            val p = List(n + 1) { at(it * STEP) }
            // Heading of each 10 m step, and how much it turns from one step to the next.
            val h = DoubleArray(n) { bearing(p[it], p[it + 1]) }
            fun diff(a: Double, b: Double) = ((b - a + 540) % 360) - 180
            val turn = DoubleArray(n) { if (it == 0) 0.0 else diff(h[it - 1], h[it]) }
            fun net(k: Int, w: Int): Double {
                val a = (k - w).coerceAtLeast(0); val b = (k + w).coerceAtMost(n - 1)
                return diff(bearing(p[a], p[(a + 1).coerceAtMost(n)]), bearing(p[b], p[(b + 1).coerceAtMost(n)]))
            }
            val out = ArrayList<Maneuver>()
            var k = 3
            while (k < n - 3) {
                val c = net(k, 3)
                if (abs(c) < 25) { k++; continue }
                // Peak of the turn within the next 50 m.
                var best = k; var bv = abs(c)
                for (j in k + 1..minOf(n - 4, k + 5)) { val v = abs(net(j, 3)); if (v > bv) { bv = v; best = j } }
                // A sharp corner gives a flat top as wide as the window: take its middle.
                var last = best
                while (last + 1 < n - 3 && abs(net(last + 1, 3)) >= bv * 0.9) last++
                best = (best + last) / 2
                // A roundabout: much more turning on the way than the way in and out differ.
                val a = (best - 6).coerceAtLeast(1); val b = (best + 6).coerceAtMost(n - 1)
                val swept = (a until b).sumOf { abs(turn[it]) }
                val through = net(best, 6)
                out += if (swept > abs(through) + 150) Maneuver(base + best * STEP, TurnKind.ROUNDABOUT, 0, TurnKind.ofAngle(through))
                    else Maneuver(base + best * STEP, TurnKind.ofAngle(net(best, 3)))
                // Past this turn: on until the line runs straight again.
                k = best + if (out.last().kind == TurnKind.ROUNDABOUT) 8 else 6
                while (k < n - 3 && k < best + 15 && abs(net(k, 3)) >= 25) k++
            }
            return out.filter { it.kind != TurnKind.STRAIGHT }
        }
    }
}

/** Says the next turn: once ahead (further at speed) and once on it. */
class TurnVoice {
    private var farFor: Maneuver? = null
    private var nearFor: Maneuver? = null

    fun step(ctx: Context, next: Pair<Maneuver, Double>?, speedMs: Float) {
        if (next == null || !MapStore.turnVoice(ctx)) return
        val (m, dist) = next
        val far = maxOf(400.0, speedMs * 15.0)
        val near = maxOf(50.0, speedMs * 4.0)
        when {
            dist <= near && nearFor != m -> { nearFor = m; farFor = m; say(ctx, Turns.phrase(m).cap()) }
            m.kind != TurnKind.ARRIVE && dist in (near + 100)..far && farFor != m -> { farFor = m; say(ctx, L.s(R.string.turn_in, Turns.spoken(dist), Turns.phrase(m))) }
        }
    }

    private var lastText = ""
    private var lastAt = 0L

    /** A route worked out again brings "the same" turn back as a new one: it is not said twice in a row. */
    private fun say(ctx: Context, text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < 45_000) return
        lastText = text; lastAt = now
        Speech.say(ctx, text)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}

object Turns {
    fun phrase(m: Maneuver): String = when (m.kind) {
        TurnKind.ROUNDABOUT -> when {
            m.exit > 0 -> L.s(R.string.turn_roundabout_exit, m.exit)
            m.then != null && m.then != TurnKind.STRAIGHT -> L.s(R.string.turn_roundabout_then, plain(m.then))
            else -> L.s(R.string.turn_roundabout)
        }
        else -> plain(m.kind)
    }

    private fun plain(k: TurnKind): String = L.s(when (k) {
        TurnKind.STRAIGHT -> R.string.turn_straight
        TurnKind.SLIGHT_LEFT -> R.string.turn_slight_left
        TurnKind.LEFT -> R.string.turn_left
        TurnKind.SHARP_LEFT -> R.string.turn_sharp_left
        TurnKind.SLIGHT_RIGHT -> R.string.turn_slight_right
        TurnKind.RIGHT -> R.string.turn_right
        TurnKind.SHARP_RIGHT -> R.string.turn_sharp_right
        TurnKind.KEEP_LEFT -> R.string.turn_keep_left
        TurnKind.KEEP_RIGHT -> R.string.turn_keep_right
        TurnKind.UTURN -> R.string.turn_uturn
        TurnKind.ROUNDABOUT -> R.string.turn_roundabout
        TurnKind.EXIT_LEFT -> R.string.turn_exit_left
        TurnKind.EXIT_RIGHT -> R.string.turn_exit_right
        TurnKind.ARRIVE -> R.string.turn_arrive
    })

    /** Arrow for the banner. */
    fun arrow(m: Maneuver): String = when (m.kind) {
        TurnKind.STRAIGHT -> "↑"
        TurnKind.SLIGHT_LEFT, TurnKind.KEEP_LEFT, TurnKind.EXIT_LEFT -> "↖"
        TurnKind.LEFT -> "←"
        TurnKind.SHARP_LEFT -> "↙"
        TurnKind.SLIGHT_RIGHT, TurnKind.KEEP_RIGHT, TurnKind.EXIT_RIGHT -> "↗"
        TurnKind.RIGHT -> "→"
        TurnKind.SHARP_RIGHT -> "↘"
        TurnKind.UTURN -> "↶"
        TurnKind.ROUNDABOUT -> "↻"
        TurnKind.ARRIVE -> "🏁"
    }

    /** Distance on the banner. */
    fun shown(m: Double): String = if (m < 1000) "${((m + 5) / 10).toInt() * 10} m" else "%.1f km".format(m / 1000)

    /** Distance as spoken: rounded to 50 m, or tenths of a kilometre. */
    fun spoken(m: Double): String =
        if (m < 950) L.s(R.string.turn_metres, (((m + 25) / 50).toInt() * 50).coerceAtLeast(50))
        else L.s(R.string.turn_km, "%.1f".format(m / 1000))
}
