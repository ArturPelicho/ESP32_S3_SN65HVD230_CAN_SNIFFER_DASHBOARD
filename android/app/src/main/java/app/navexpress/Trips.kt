package app.navexpress

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One trip counter: distance, fuel, time on the move and top speed since it was reset. */
data class Trip(
    val distM: Double = 0.0,
    val fuelL: Double = 0.0,
    val movingS: Double = 0.0,
    val maxKmh: Double = 0.0,
    val sinceMs: Long = System.currentTimeMillis(),
) {
    /** Average while moving (stops don't count), once there is a little to go on. */
    val avgKmh: Double? get() = if (movingS >= 30 && distM > 100) distM / movingS * 3.6 else null
    /** L/100 km, from the board's fuel; null without a board or below 1 km. */
    val l100: Double? get() = if (distM >= 1000 && fuelL > 0) fuelL / (distM / 1000) * 100 else null

    fun add(m: Double, l: Double, s: Double, kmh: Double?) =
        copy(distM = distM + m, fuelL = fuelL + l, movingS = movingS + s, maxKmh = maxOf(maxKmh, kmh ?: 0.0))

    fun json(): JSONObject = JSONObject().put("d", distM).put("f", fuelL).put("t", movingS).put("v", maxKmh).put("s", sinceMs)

    companion object {
        fun of(o: JSONObject?) = if (o == null) Trip() else
            Trip(o.optDouble("d", 0.0), o.optDouble("f", 0.0), o.optDouble("t", 0.0), o.optDouble("v", 0.0), o.optLong("s", System.currentTimeMillis()))
    }
}

/** Trips A and B and the odometer (set once to the bike's own reading, then counted on). */
data class TripSet(val a: Trip = Trip(), val b: Trip = Trip(), val odoM: Double = 0.0)

/**
 * The phone's trip counters, kept like a bike's: A and B reset on their own, the odometer only when
 * set. With the board connected they count its distance and fuel; without it, the phone's GPS.
 */
object Trips {
    private const val PREFS = "trips"
    private const val MOVING_KMH = 3.0
    private val _set = MutableStateFlow(TripSet())
    val set: StateFlow<TripSet> = _set.asStateFlow()
    private var app: Context? = null
    private var lastSave = 0L

    // Board
    private var lastDistKm: Double? = null
    private var lastUsedL: Double? = null
    private var lastBoardMs = 0L
    // Phone GPS
    private var lastFix: Location? = null

    fun load(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _set.value = runCatching {
            val o = JSONObject(p.getString("set", null) ?: "{}")
            TripSet(Trip.of(o.optJSONObject("a")), Trip.of(o.optJSONObject("b")), o.optDouble("odo", 0.0))
        }.getOrDefault(TripSet())
    }

    private fun save(force: Boolean = false) {
        val ctx = app ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSave < 15_000) return
        lastSave = now
        val s = _set.value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("set", JSONObject().put("a", s.a.json()).put("b", s.b.json()).put("odo", s.odoM).toString()).apply()
    }

    private fun add(m: Double, l: Double, s: Double, kmh: Double?) {
        if (m <= 0 && l <= 0 && s <= 0) return
        val c = _set.value
        _set.value = TripSet(c.a.add(m, l, s, kmh), c.b.add(m, l, s, kmh), c.odoM + m)
        save()
    }

    /** Each telemetry packet: what the board counted since the last one. */
    fun onTelemetry(t: Telemetry) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastBoardMs == 0L) 99.0 else (now - lastBoardMs) / 1000.0
        // After a gap (just connected) this packet is only the starting point.
        val dKm = if (dt > 5) null else lastDistKm?.let { t.distKm - it }
        val dL = if (dt > 5) null else lastUsedL?.let { t.usedL - it }
        lastDistKm = t.distKm; lastUsedL = t.usedL; lastBoardMs = now
        // A board counter reset (or a reboot) reads as going backwards: skip that step.
        val m = dKm?.takeIf { it in 0.0..0.5 }?.times(1000) ?: 0.0
        val l = dL?.takeIf { it in 0.0..0.2 } ?: 0.0
        val kmh = t.displaySpeed
        val s = if (dt in 0.0..3.0 && (kmh ?: 0.0) >= MOVING_KMH) dt else 0.0
        add(m, l, s, kmh)
    }

    /** A phone GPS fix (from any listener; repeats are ignored). Counts only while the board is silent. */
    fun onFix(loc: Location) {
        val prev = lastFix
        if (prev != null && loc.elapsedRealtimeNanos <= prev.elapsedRealtimeNanos) return
        if (loc.provider != android.location.LocationManager.GPS_PROVIDER) return
        lastFix = loc
        RideProfile.add(loc)    // every phone fix passes here once, so the ride's height strip is fed too
        if (SystemClock.elapsedRealtime() - lastBoardMs < 5_000) return
        if (prev == null || (loc.hasAccuracy() && loc.accuracy > 30)) return
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (dt <= 0 || dt > 10) return
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else null
        val d = prev.distanceTo(loc).toDouble()
        if ((kmh ?: (d / dt * 3.6)) < MOVING_KMH || d / dt > 90) return   // standing still (GPS wander), or a jump
        add(d, 0.0, dt, kmh)
    }

    fun reset(ctx: Context, b: Boolean) {
        load(ctx)
        _set.value = if (b) _set.value.copy(b = Trip()) else _set.value.copy(a = Trip())
        save(force = true)
    }

    /** The odometer, set to the bike's own reading (km). */
    fun setOdo(ctx: Context, km: Double) {
        load(ctx)
        _set.value = _set.value.copy(odoM = km * 1000)
        save(force = true)
    }
}
