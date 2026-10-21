package pt.artur.motadash

import android.location.Location
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps the bike moving on the map when the GPS drops out (tunnels, deep valleys): from the last
 * real fix, at the last known speed, along the line being followed (Nav route or track) when the
 * bike was on it, else straight ahead. Gives up after a few minutes or below walking pace.
 */
class DeadReckoning {
    companion object {
        const val PROVIDER = "dr"
        private const val START_AFTER_MS = 2_500L
        private const val GIVE_UP_MS = 180_000L
        private const val MIN_KMH = 15.0
    }

    private var lastReal: Location? = null
    private var line: List<LatLon>? = null
    private var follower: TrackFollower? = null
    private var startM = 0.0
    private var travelled = 0.0
    private var lastTick = 0L

    /** A real fix: dead reckoning stops and starts again from here next time. */
    fun real(loc: Location) { lastReal = loc; travelled = 0.0; follower = null; lastTick = 0 }

    /**
     * Called about once a second. [path] is the line being followed (in the riding direction), or null;
     * [kmh] the speed now if something else knows it (the board), else the last fix's is used.
     * Returns the estimated position, or null when there is nothing to estimate.
     */
    fun tick(path: List<LatLon>?, kmh: Double?): Location? {
        val r = lastReal ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - r.elapsedRealtimeNanos) / 1_000_000
        if (age < START_AFTER_MS || age > GIVE_UP_MS) return null
        val v = (kmh ?: if (r.hasSpeed()) r.speed * 3.6 else 0.0)
        if (v < MIN_KMH) return null
        val now = SystemClock.elapsedRealtime()
        if (lastTick == 0L) {
            // First estimate: everything since the last fix.
            travelled = v / 3.6 * age / 1000.0
            if (path != null && path.size >= 2) {
                val f = TrackFollower(Track("dr", "", listOf(path), emptyList(), "", 1, false), false)
                val p = f.locate(r.latitude, r.longitude)
                if (p != null && p.offM <= SNAP_M) { follower = f; line = path; startM = p.doneM }
            }
        } else travelled += v / 3.6 * (now - lastTick) / 1000.0
        lastTick = now

        val out = Location(PROVIDER)
        val f = follower
        if (f != null && travelled + startM < f.totalM) {
            val a = f.pointAt(startM + travelled)
            val b = f.pointAt(startM + travelled + 10)
            out.latitude = a.lat; out.longitude = a.lon
            out.bearing = bearing(a, b).toFloat()
        } else {
            val brg = if (r.hasBearing()) r.bearing.toDouble() else 0.0
            val d = travelled
            out.latitude = r.latitude + d * cos(Math.toRadians(brg)) / 110_540.0
            out.longitude = r.longitude + d * sin(Math.toRadians(brg)) / (111_320.0 * cos(Math.toRadians(r.latitude)))
            out.bearing = brg.toFloat()
        }
        out.speed = (v / 3.6).toFloat()
        if (r.hasAltitude()) out.altitude = r.altitude
        out.accuracy = 50f
        out.time = System.currentTimeMillis()
        out.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        return out
    }
}
