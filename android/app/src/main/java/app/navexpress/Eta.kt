package app.navexpress

/**
 * Arrival time for the line being followed. With the router's expected times, the time it still
 * expects (which knows each road's type and speed) is scaled by the rider's own pace against it:
 * the last few minutes weighted with the whole ride. Without them (an imported track), the metres left
 * at the rider's speed, mixed the same way.
 */
class Eta {
    private companion object {
        const val RECENT_MS = 5 * 60_000L
        const val W_RECENT = 0.6
    }

    /** One step: real seconds taken for [units] of the plan (router seconds, or metres). */
    private class Step(val atMs: Long, val realS: Double, val units: Double)

    private val recent = ArrayDeque<Step>()
    private var allReal = 0.0
    private var allUnits = 0.0
    private var lastMs = 0L
    private var lastLeft = 0.0
    private var lastKey: Any? = null
    private var lastTimed = false

    /**
     * Called on each fix. [key] names what is followed: a new one starts afresh, [part] changing (a new
     * route back to the track) only skips that step. [left] is router seconds when [timed], else metres.
     * Returns the arrival as epoch ms, or null while there is too little to go on.
     */
    fun update(key: Any, part: Any?, timed: Boolean, left: Double, nowMs: Long, wallMs: Long): Long? {
        if (key != lastKey || timed != lastTimed) {
            recent.clear(); allReal = 0.0; allUnits = 0.0; lastMs = 0L
            lastKey = key; lastTimed = timed
        }
        if (lastMs != 0L && part == lastPart) {
            val dt = (nowMs - lastMs) / 1000.0
            // Riding away from it counts as time with no progress (a stop, a wrong turn).
            val du = (lastLeft - left).coerceAtLeast(0.0)
            if (dt in 0.0..30.0) {
                recent.addLast(Step(nowMs, dt, du)); allReal += dt; allUnits += du
            }
        }
        lastPart = part; lastMs = nowMs; lastLeft = left
        while (recent.isNotEmpty() && nowMs - recent.first().atMs > RECENT_MS) recent.removeFirst()

        // Real seconds per unit: recent and overall, each only once there is enough of it.
        val enough = if (timed) 30.0 else 300.0
        val rUnits = recent.sumOf { it.units }
        val rPace = if (rUnits >= enough) recent.sumOf { it.realS } / rUnits else null
        val aPace = if (allUnits >= enough) allReal / allUnits else null
        val pace = when {
            rPace != null && aPace != null -> W_RECENT * rPace + (1 - W_RECENT) * aPace
            else -> rPace ?: aPace ?: if (timed) 1.0 else return null
        }
        // A stop now and then is real, an hour at a café should not make the estimate absurd.
        val p = if (timed) pace.coerceIn(0.5, 3.0) else pace.coerceIn(1 / 40.0, 1 / 3.0)
        return wallMs + (left * p * 1000).toLong()
    }

    private var lastPart: Any? = null
}
