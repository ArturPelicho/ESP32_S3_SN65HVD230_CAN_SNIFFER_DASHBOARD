package pt.artur.motadash

import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Height and distance of the ride so far, for the profile strip when no track is followed: it grows
 * from the left and gets denser as the ride goes on (the whole ride always fits). Heights come from
 * the downloaded relief where there is one, else the GPS's own, smoothed. A long stop starts afresh.
 */
object RideProfile {
    private const val MAX_POINTS = 600
    private const val NEW_RIDE_MS = 30 * 60_000L

    private val dist = ArrayList<Float>()
    private val ele = ArrayList<Float>()
    private var step = 20.0
    private var totalM = 0.0
    private var last: Location? = null
    private var lastKept = 0.0
    private var gpsEle: Double? = null

    private val _profile = MutableStateFlow<ElevProfile?>(null)
    val profile: StateFlow<ElevProfile?> = _profile.asStateFlow()

    private var dems: List<Dem> = emptyList()
    private var demFiles: List<File> = emptyList()

    /** The relief files of the maps in use (opened once, kept until they change). */
    @Synchronized
    fun useRelief(files: List<File>) {
        if (files == demFiles) return
        dems.forEach { it.close() }
        demFiles = files
        dems = files.mapNotNull { Dem.open(it) }
    }

    @Synchronized
    fun add(loc: Location) {
        if (loc.provider != LocationManager.GPS_PROVIDER || (loc.hasAccuracy() && loc.accuracy > 30)) return
        val prev = last
        if (prev != null && (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000 > NEW_RIDE_MS) clear()
        if (loc.hasAltitude()) gpsEle = gpsEle?.let { it + (loc.altitude - it) * 0.2 } ?: loc.altitude
        val p = last
        if (p == null) { last = loc; keep(loc); return }
        val dt = (loc.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
        val d = p.distanceTo(loc).toDouble()
        // GPS wander standing still, or an impossible jump.
        if (dt <= 0 || d < 5 || d / dt > 90) return
        last = loc
        totalM += d
        if (totalM - lastKept >= step) keep(loc)
    }

    private fun keep(loc: Location) {
        val h = dems.firstNotNullOfOrNull { it.height(loc.latitude, loc.longitude) } ?: gpsEle ?: return
        dist += totalM.toFloat(); ele += h.toFloat()
        lastKept = totalM
        // Too many points: keep every other one and space new ones twice as far.
        if (dist.size > MAX_POINTS) {
            val keepIdx = dist.indices.filter { it % 2 == 0 || it == dist.size - 1 }
            val d2 = keepIdx.map { dist[it] }; val e2 = keepIdx.map { ele[it] }
            dist.clear(); dist.addAll(d2); ele.clear(); ele.addAll(e2)
            step *= 2
        }
        _profile.value = if (dist.size >= 2 && totalM >= 50) ElevProfile(dist.toFloatArray(), ele.toFloatArray(), ride = true) else null
    }

    @Synchronized
    fun clear() {
        dist.clear(); ele.clear(); step = 20.0; totalM = 0.0; last = null; lastKept = 0.0; gpsEle = null
        _profile.value = null
    }
}
