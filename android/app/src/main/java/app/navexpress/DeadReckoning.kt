package app.navexpress

import android.location.Location
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps the bike moving on the map when the GPS drops out (tunnels, deep valleys): from the last
 * real fix, at the last known speed, along the line being followed (Nav route or track) when the
 * bike was on it, else along the map's road under it, else straight ahead. Gives up after a few
 * minutes or below walking pace. Also filters out impossible GPS jumps ([real]).
 */
class DeadReckoning {
    companion object {
        const val PROVIDER = "dr"
        private const val START_AFTER_MS = 2_500L
        private const val GIVE_UP_MS = 180_000L
        private const val MIN_KMH = 15.0
        /**
         * A jump needing more acceleration than this from the last good speed is a GPS glitch
         * (about 1.5 g: beyond any road vehicle, a 300 km/h car included).
         */
        private const val MAX_ACCEL_MS2 = 15.0
        /** And never faster than this (m/s, ~500 km/h), however long since the last fix. */
        private const val MAX_SPEED_MS = 140.0
        /** Glitches refused for this long in a row: the GPS is right after all. */
        private const val REFUSE_FOR_MS = 10_000L
    }

    private var lastReal: Location? = null
    private var follower: TrackFollower? = null
    /** Along [follower]: where the estimate started on it, and the distance travelled when it did. */
    private var onStart = 0.0
    private var onFrom = 0.0
    private var travelled = 0.0
    private var lastTick = 0L
    private var lastBearing = 0.0
    private var refusingSince = 0L

    /**
     * A real GPS fix: true to use it. A fix that would mean an impossible acceleration from the last
     * good one's speed (a jump hundreds of metres away) is refused, for up to [REFUSE_FOR_MS].
     */
    fun real(loc: Location): Boolean {
        val prev = lastReal
        if (prev != null) {
            val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            val d = prev.distanceTo(loc).toDouble()
            val now = SystemClock.elapsedRealtime()
            val v0 = if (prev.hasSpeed()) prev.speed.toDouble() else 0.0
            val allowed = minOf(v0 + MAX_ACCEL_MS2 * dt, MAX_SPEED_MS)
            if (dt > 0 && d > 100 + (if (loc.hasAccuracy()) loc.accuracy else 0f) && d / dt > allowed) {
                if (refusingSince == 0L) refusingSince = now
                if (now - refusingSince < REFUSE_FOR_MS) return false
            }
        }
        refusingSince = 0
        lastReal = loc; travelled = 0.0; follower = null; lastTick = 0
        return true
    }

    /**
     * Called about once a second. [path] is the line being followed (in the riding direction), or null;
     * [kmh] the speed now if something else knows it (the board), else the last fix's is used;
     * [road] gives the road under a point going a given way, from there on (the map's roads).
     * Returns the estimated position, or null when there is nothing to estimate.
     */
    fun tick(path: List<LatLon>?, kmh: Double?, road: (LatLon, Double) -> List<LatLon>?): Location? {
        val r = lastReal ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - r.elapsedRealtimeNanos) / 1_000_000
        if (age < START_AFTER_MS || age > GIVE_UP_MS) return null
        val v = (kmh ?: if (r.hasSpeed()) r.speed * 3.6 else 0.0)
        if (v < MIN_KMH) return null
        val now = SystemClock.elapsedRealtime()
        if (lastTick == 0L) {
            // First estimate: everything since the last fix, on the followed line or else on the road.
            travelled = v / 3.6 * age / 1000.0
            lastBearing = if (r.hasBearing()) r.bearing.toDouble() else 0.0
            val here = LatLon(r.latitude, r.longitude)
            if (path != null && path.size >= 2) {
                val f = followerOf(path)
                val p = f.locate(r.latitude, r.longitude)
                if (p != null && p.offM <= SNAP_M) { follower = f; onStart = p.doneM; onFrom = 0.0 }
            }
            if (follower == null) road(here, lastBearing)?.takeIf { it.size >= 2 }?.let { follower = followerOf(it); onStart = 0.0; onFrom = 0.0 }
        } else travelled += v / 3.6 * (now - lastTick) / 1000.0
        lastTick = now

        // End of the road piece the map gave: carry on along the next one from its end.
        follower?.let { f ->
            if (onStart + travelled - onFrom >= f.totalM) {
                val end = f.pointAt(f.totalM)
                val next = road(end, lastBearing)?.takeIf { it.size >= 2 }
                follower = next?.let { followerOf(it) }
                onFrom += f.totalM - onStart; onStart = 0.0
                if (next == null) { follower = null; lastFree = end }
            }
        }

        val out = Location(PROVIDER)
        val f = follower
        if (f != null) {
            val at = onStart + travelled - onFrom
            val a = f.pointAt(at)
            val b = f.pointAt(at + 10)
            out.latitude = a.lat; out.longitude = a.lon
            if (metres(a, b) > 1) lastBearing = bearing(a, b)
            out.bearing = lastBearing.toFloat()
            lastFree = a; freeFrom = travelled
        } else {
            // Straight ahead from the last point known on a line (or the last fix).
            val from = lastFree ?: LatLon(r.latitude, r.longitude)
            val d = travelled - freeFrom
            out.latitude = from.lat + d * cos(Math.toRadians(lastBearing)) / 110_540.0
            out.longitude = from.lon + d * sin(Math.toRadians(lastBearing)) / (111_320.0 * cos(Math.toRadians(from.lat)))
            out.bearing = lastBearing.toFloat()
        }
        out.speed = (v / 3.6).toFloat()
        if (r.hasAltitude()) out.altitude = r.altitude
        out.accuracy = 50f
        out.time = System.currentTimeMillis()
        out.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        return out
    }

    private var lastFree: LatLon? = null
    private var freeFrom = 0.0

    private fun followerOf(line: List<LatLon>) = TrackFollower(Track("dr", "", listOf(line), emptyList(), "", 1, false), false)
}
