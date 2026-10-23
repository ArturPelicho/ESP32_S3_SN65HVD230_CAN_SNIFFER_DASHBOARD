package app.navexpress

import androidx.annotation.StringRes

/** Within this many metres of the line the bike counts as on it (GPS error, the other side of a wide road). */
const val SNAP_M = 40.0

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
 * Nav mode's reroute logic, kept out of the screen: when the bike has been off the track for a few
 * seconds, ask for a road route from where it is now to the next planned waypoint (or the end); on an
 * imported track, to a point a kilometre further along it. Follow that route; when the bike leaves it
 * too, ask again from the new position, dropping the old one. Back on the track for a while, the route
 * is dropped.
 */
class Rejoin(private val track: Track, private val reversed: Boolean) {
    private val follower = TrackFollower(track, reversed)
    /** Along-track distance of each planned waypoint, in the riding direction. */
    private val planAt: List<Double> = run {
        val legs = if (reversed) track.lines.reversed() else track.lines
        if (track.plan.size < 2 || legs.size != track.plan.size - 1) emptyList()
        else legs.runningFold(0.0) { acc, l -> acc + (1 until l.size).sumOf { metres(l[it - 1], l[it]) } }
    }

    /** Where a route leads. */
    enum class Goal { TRACK, WAYPOINT, END }

    var route: List<LatLon>? = null; private set
    /** Where the route meets the track, metres along it. */
    var targetM = 0.0; private set
    var goal = Goal.TRACK; private set
    /** Number of the waypoint the route leads to (1 = the start), for [Goal.WAYPOINT]. */
    var goalWp = 0; private set
    var failed = false; private set
    private var routeFollower: TrackFollower? = null
    private var offSince = 0L
    private var onSince = 0L
    private var offRouteSince = 0L
    private var lastAsk = -1_000_000L
    private var doneMax = 0.0
    private var askedAt = 0L
    private var askedVersion = RouteOptions.version
    private var lastMode = TrackMode.NAV
    /** A route is being worked out. */
    val busy: Boolean get() = askedAt != 0L

    /** Metres left on the route (as of the last fix on it). */
    var routeLeftM: Double? = null; private set

    /** Where the bike is taken to be on the route, when close enough to it. */
    var routeSnap: TrackProgress? = null; private set

    private fun drop() { route = null; routeFollower = null; routeLeftM = null; guide = null; routeSnap = null }

    /**
     * Called on every fix with the track progress. Returns the point to route to when a new route
     * should be asked for (the caller fetches it from [here] and hands it to [routed]), or null.
     */
    fun step(p: TrackProgress, here: LatLon, nowMs: Long, mode: TrackMode = TrackMode.NAV): LatLon? {
        // Switching mode: the old way back was for the other one.
        if (mode != lastMode) { lastMode = mode; drop(); lastAsk = -1_000_000L; failed = false }
        // An answer that never came (killed request): ask again.
        if (busy && nowMs - askedAt > 60_000) askedAt = 0L
        if (p.offM <= SNAP_M) {
            offSince = 0; offRouteSince = 0; doneMax = maxOf(doneMax, p.doneM); failed = false; routeSnap = null
            if (onSince == 0L) onSince = nowMs
            // Only once really back on the track: a road crossing it, or running beside it for a moment, keeps the route.
            if (route == null || nowMs - onSince > 8_000 || p.doneM >= targetM - 50) drop()
            return null
        }
        onSince = 0
        if (offSince == 0L) offSince = nowMs
        // Route choices changed (track box menu): the route goes, a new one comes with the new choices.
        if (askedVersion != RouteOptions.version) { askedVersion = RouteOptions.version; if (route != null || failed) { drop(); lastAsk = -1_000_000L } }
        val rp = routeFollower?.locate(here.lat, here.lon)
        val onRoute = rp != null && rp.offM <= 50
        routeSnap = rp?.takeIf { it.offM <= SNAP_M }
        if (onRoute) { routeLeftM = rp?.leftM; offRouteSince = 0; return null }
        if (route != null) {
            // Left the route as well: from here it is useless, a new one comes from the new position.
            if (offRouteSince == 0L) offRouteSince = nowMs
            if (nowMs - offRouteSince < 5_000) return null
            drop()
        }
        if (busy || nowMs - offSince < 6_000 || nowMs - lastAsk < (if (failed) 15_000 else 8_000)) return null
        val base = maxOf(doneMax, p.doneM)
        // Nav, planned: the next waypoint ahead (the end being the last). Nav, imported: a kilometre further
        // along (or the end). Track: the nearest point of the track, a little ahead, the soonest way back.
        val wp = if (mode == TrackMode.NAV) planAt.indexOfFirst { it > base + 50 } else -1
        if (wp >= 0) {
            targetM = planAt[wp]
            goal = if (wp == planAt.size - 1) Goal.END else Goal.WAYPOINT
            goalWp = wp + 1
        } else {
            targetM = minOf(follower.totalM, if (mode == TrackMode.NAV) base + 1000 else p.doneM + 150)
            goal = if (targetM >= follower.totalM - 1) Goal.END else Goal.TRACK
        }
        lastAsk = nowMs; askedAt = nowMs
        return follower.pointAt(targetM)
    }

    /** Turns on the route, and how far along it the bike is. */
    var guide: Guidance? = null; private set
    val routeDoneM: Double? get() = routeFollower?.let { f -> routeLeftM?.let { f.totalM - it } }
    /** The router's expected seconds left on the route, when it gave them. */
    val routeSecsLeft: Double? get() = routeFollower?.let { f -> routeDoneM?.let { f.secsLeft(it) } }

    /** The answer to the last request: a road route, or null without network or route. */
    fun routed(r: Routed?) {
        askedAt = 0L
        failed = r == null
        offRouteSince = 0
        if (r == null) { drop(); return }
        route = r.pts
        routeFollower = TrackFollower(track.copy(lines = listOf(r.pts), plan = emptyList(), turns = emptyList(), secs = listOf(r.secs)), false)
        routeLeftM = routeFollower?.totalM
        guide = Guidance(listOf(r.pts), r.turns, arrive = r.pts.isNotEmpty() && goal == Goal.END)
    }
}
