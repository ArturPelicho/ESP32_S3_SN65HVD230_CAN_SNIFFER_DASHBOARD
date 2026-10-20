package pt.artur.motadash

import androidx.annotation.StringRes

/** How a followed track behaves when the bike leaves it. */
enum class TrackMode(@StringRes private val res: Int, @StringRes private val badgeRes: Int) {
    /** Navigation: off the track, a road route back to it (to the next waypoint, or further along) is worked out. */
    NAV(R.string.track_mode_nav, R.string.track_badge_nav),
    /** Track: the line is the line; off it, only the way straight back to the nearest point. */
    TRACK(R.string.track_mode_track, R.string.track_badge_track);
    val label: String get() = L.s(res)
    val badge: String get() = L.s(badgeRes)
}

/**
 * Nav mode's rejoin logic, kept out of the screen: when the bike has been off the track for a few
 * seconds, ask for a road route to a point ahead on the track; follow that route; ask again if the bike
 * leaves the route too. Back on the track, the route is dropped.
 */
class Rejoin(private val track: Track, private val reversed: Boolean) {
    private val follower = TrackFollower(track, reversed)
    /** Along-track distance of each planned waypoint, in the riding direction. */
    private val planAt: List<Double> = run {
        val legs = if (reversed) track.lines.reversed() else track.lines
        if (track.plan.size < 2 || legs.size != track.plan.size - 1) emptyList()
        else legs.runningFold(0.0) { acc, l -> acc + (1 until l.size).sumOf { metres(l[it - 1], l[it]) } }
    }

    var route: List<LatLon>? = null; private set
    /** Where the route meets the track, metres along it. */
    var targetM = 0.0; private set
    var failed = false; private set
    private var routeFollower: TrackFollower? = null
    private var offSince = 0L
    private var lastAsk = -1_000_000L
    private var doneMax = 0.0
    private var asking = false

    /** Metres left on the route back to the track, when there is one. */
    var routeLeftM: Double? = null; private set

    /**
     * Called on every fix with the track progress. Returns the point to route to when a new route
     * should be asked for (the caller fetches it and hands it to [routed]), or null.
     */
    fun step(p: TrackProgress, here: LatLon, nowMs: Long): LatLon? {
        if (p.offM <= 40) {
            offSince = 0; doneMax = maxOf(doneMax, p.doneM)
            route = null; routeFollower = null; routeLeftM = null; failed = false
            return null
        }
        if (offSince == 0L) offSince = nowMs
        val rp = routeFollower?.locate(here.lat, here.lon)
        routeLeftM = rp?.leftM
        val offRoute = rp == null || rp.offM > 60
        if (!offRoute || asking || nowMs - offSince < 6_000 || nowMs - lastAsk < 15_000) return null
        val base = maxOf(doneMax, p.doneM)
        // Planned: the next waypoint ahead. Imported: a kilometre further along (or the end).
        targetM = planAt.firstOrNull { it > base + 50 } ?: minOf(follower.totalM, base + 1000)
        lastAsk = nowMs; asking = true
        return follower.pointAt(targetM)
    }

    /** The answer to the last request: a road route, or null without network or route. */
    fun routed(r: List<LatLon>?) {
        asking = false
        failed = r == null
        if (r != null) {
            route = r
            routeFollower = TrackFollower(track.copy(lines = listOf(r), plan = emptyList()), false)
            routeLeftM = routeFollower?.totalM
        }
    }
}
