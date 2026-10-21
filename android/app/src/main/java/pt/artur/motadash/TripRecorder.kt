package pt.artur.motadash

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The trip being recorded: when it started, how far so far. */
data class Recording(val startedMs: Long, val distanceM: Double, val points: Int)

/**
 * Records the ride as a trajeto, with times (GPX export carries them). Points go to a file as they
 * come, so a recording survives the app being closed: it carries on when the app or its background
 * service starts again. Runs while the app's foreground service keeps the process alive.
 * Optionally starts when the bike's board connects and stops a while after it goes away.
 */
@SuppressLint("MissingPermission") // only started once location is allowed
object TripRecorder {
    private const val MIN_STEP_M = 5.0
    private const val MAX_ACCURACY_M = 30f
    /** The board gone this long ends an automatic recording (stops at fuel, a coffee: same trip). */
    private const val AUTO_STOP_MS = 15 * 60_000L

    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state.asStateFlow()

    private var lm: LocationManager? = null
    private var last: Location? = null
    private var app: Context? = null
    private var auto = false
    private var boardGoneAt = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, "trip/current.csv").apply { parentFile?.mkdirs() }

    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("auto", false)
    fun setAutoStart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    private val listener = LocationListener { loc -> onFix(loc) }

    /** Picks up a recording left running (app closed, phone restarted). */
    fun resume(ctx: Context) {
        val c = ctx.applicationContext
        if (_state.value != null || !prefs(c).getBoolean("on", false)) return
        auto = prefs(c).getBoolean("isAuto", false)
        val pts = read(c)
        var d = 0.0
        for (i in 1 until pts.size) d += metres(pts[i - 1].first, pts[i].first)
        _state.value = Recording(prefs(c).getLong("started", System.currentTimeMillis()), d, pts.size)
        listen(c)
    }

    fun start(ctx: Context, automatic: Boolean = false) {
        val c = ctx.applicationContext
        if (_state.value != null || !Permissions.ready(c)) return
        file(c).delete()
        val now = System.currentTimeMillis()
        prefs(c).edit().putBoolean("on", true).putBoolean("isAuto", automatic).putLong("started", now).apply()
        auto = automatic
        last = null
        _state.value = Recording(now, 0.0, 0)
        MotaService.start(c)
        listen(c)
    }

    /** Ends the recording and keeps it as a trajeto (unless it is too short to be a ride). */
    fun stop(ctx: Context): Track? {
        val c = ctx.applicationContext
        val rec = _state.value ?: return null
        lm?.removeUpdates(listener)
        lm = null
        _state.value = null
        prefs(c).edit().putBoolean("on", false).apply()
        val pts = read(c)
        file(c).delete()
        if (pts.size < 2 || rec.distanceM < 100) return null
        val name = L.s(R.string.trip_name, SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(rec.startedMs)))
        val t = Track(
            id = rec.startedMs.toString(), name = name,
            lines = listOf(pts.map { it.first }), waypoints = emptyList(),
            color = TrackStore.COLORS[TrackStore.tracks.value.size % TrackStore.COLORS.size],
            width = 2, visible = true, times = listOf(pts.map { it.second }),
        )
        TrackStore.add(c, t)
        return t
    }

    /** The board's link changed: automatic recordings start with it and end a while after it goes. */
    fun boardConnected(ctx: Context, connected: Boolean) {
        val c = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (connected) {
            boardGoneAt = 0
            if (_state.value == null && autoStart(c)) start(c, automatic = true)
        } else if (boardGoneAt == 0L) {
            boardGoneAt = now
            handler.postDelayed({ if (auto && boardGoneAt != 0L && System.currentTimeMillis() - boardGoneAt >= AUTO_STOP_MS) stop(c) }, AUTO_STOP_MS)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun listen(c: Context) {
        app = c
        val m = c.getSystemService(LocationManager::class.java) ?: return
        lm = m
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
    }

    private fun onFix(loc: Location) {
        val c = app ?: return
        val rec = _state.value ?: return
        if (auto && boardGoneAt != 0L && System.currentTimeMillis() - boardGoneAt > AUTO_STOP_MS) { stop(c); return }
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val prev = last
        val step = prev?.distanceTo(loc)?.toDouble() ?: 0.0
        if (prev != null && step < MIN_STEP_M) return
        last = loc
        val ele = if (loc.hasAltitude()) String.format(Locale.US, "%.1f", loc.altitude) else ""
        runCatching { file(c).appendText(String.format(Locale.US, "%.7f,%.7f,%s,%d\n", loc.latitude, loc.longitude, ele, loc.time)) }
        _state.value = rec.copy(distanceM = rec.distanceM + step, points = rec.points + 1)
    }

    private fun read(c: Context): List<Pair<LatLon, Long>> = runCatching {
        file(c).readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 4) null
            else LatLon(p[0].toDouble(), p[1].toDouble(), p[2].toDoubleOrNull()) to p[3].toLong()
        }
    }.getOrDefault(emptyList())
}
