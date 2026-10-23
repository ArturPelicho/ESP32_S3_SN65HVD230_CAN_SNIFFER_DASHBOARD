package app.navexpress

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkStatus { STOPPED, NO_DEVICE, SCANNING, WAITING, CONNECTING, CONNECTED }

data class ParamInfo(val name: String, val value: Int, val min: Int, val max: Int, val def: Int, val unit: String)

/** Fault codes from the board's "DTC" answer (main.c dtc_report). */
data class DtcState(
    val reading: Boolean = false,
    val mil: Boolean? = null,
    val milCount: Int? = null,
    val groups: Map<String, List<String>> = emptyMap(),   // STORED / PENDING / PERMANENT -> codes
    val notes: Map<String, String> = emptyMap(),          // group -> NONE / NO DATA / UNSUPPORTED
    val readAt: Long? = null,
    val clearResult: String? = null,
) {
    val total: Int get() = groups.values.sumOf { it.size }
}

/** Screen orientation choices, as ActivityInfo values. */
enum class Orientation(@StringRes val labelRes: Int, val value: Int) {
    AUTO(R.string.link_orient_auto, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    PORTRAIT(R.string.link_orient_portrait, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE(R.string.link_orient_landscape, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    LANDSCAPE_INV(R.string.link_orient_landscape_inv, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    LANDSCAPE_ANY(R.string.link_orient_landscape_any, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

    /** In the app's language (read at call time). */
    val label: String get() = L.s(labelRes)
}

data class GpsInfo(
    val hz: Double = 0.0,
    val speedKmh: Double? = null,
    val speedAccKmh: Double? = null,
    val hAccM: Double? = null,
    val fixes: Long = 0,
    val sent: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val altM: Double? = null,
)

/** State shared by the service (writer) and the UI (reader). */
object Link {
    private val _status = MutableStateFlow(LinkStatus.STOPPED)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _gps = MutableStateFlow(GpsInfo())
    val gps: StateFlow<GpsInfo> = _gps.asStateFlow()

    private val _params = MutableStateFlow<Map<String, ParamInfo>>(emptyMap())
    val params: StateFlow<Map<String, ParamInfo>> = _params.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    /** Last "TANK ..." and "GEAR ..." answers, and a pending fuel constant suggestion. */
    private val _tankLine = MutableStateFlow<String?>(null)
    val tankLine: StateFlow<String?> = _tankLine.asStateFlow()
    private val _gearLine = MutableStateFlow<String?>(null)
    val gearLine: StateFlow<String?> = _gearLine.asStateFlow()
    private val _suggestedK = MutableStateFlow<Int?>(null)
    val suggestedK: StateFlow<Int?> = _suggestedK.asStateFlow()
    private val _fillLine = MutableStateFlow<String?>(null)
    val fillLine: StateFlow<String?> = _fillLine.asStateFlow()

    private val _dtc = MutableStateFlow(DtcState())
    val dtc: StateFlow<DtcState> = _dtc.asStateFlow()

    @Volatile var service: MotaService? = null

    fun setStatus(s: LinkStatus) {
        val was = _status.value == LinkStatus.CONNECTED
        _status.value = s
        val now = s == LinkStatus.CONNECTED
        if (was != now) service?.let { TripRecorder.boardConnected(it, now) }
    }
    fun setTelemetry(t: Telemetry?) { _telemetry.value = t; t?.let { Trips.onTelemetry(it) } }
    fun updateGps(f: (GpsInfo) -> GpsInfo) = _gps.update(f)
    fun clearSuggestion() { _suggestedK.value = null }

    private val paramRe = Regex("""^PARAM (\w+)=(-?\d+)(?: min=(-?\d+) max=(-?\d+) def=(-?\d+) unit=(.*))?$""")
    private val fillRe = Regex("""suggested_k=(\d+)""")

    /** One text line from the board's command UART. */
    fun onLine(raw: String) {
        val line = raw.trim().trimStart('>').trim()
        if (line.isEmpty()) return
        _console.update { (it + line).takeLast(200) }
        paramRe.find(line)?.let { m ->
            val name = m.groupValues[1]
            val value = m.groupValues[2].toInt()
            _params.update { map ->
                val old = map[name]
                val info = if (m.groupValues[3].isNotEmpty()) {
                    ParamInfo(name, value, m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                        m.groupValues[5].toInt(), m.groupValues[6])
                } else {
                    old?.copy(value = value) ?: ParamInfo(name, value, Int.MIN_VALUE, Int.MAX_VALUE, value, "")
                }
                map + (name to info)
            }
        }
        if (line.startsWith("DTC ")) onDtcLine(line.removePrefix("DTC ").trim())
        if (line == "?") _dtc.update { it.copy(reading = false) } // firmware without DTC support
        if (line.startsWith("TANK ")) _tankLine.value = line
        if (line.startsWith("GEAR ")) _gearLine.value = line
        if (line.startsWith("FILL ")) {
            _fillLine.value = line
            _suggestedK.value = fillRe.find(line)?.groupValues?.get(1)?.toInt()
        }
    }

    private val milRe = Regex("""^MIL=(\S+) COUNT=(\S+)$""")

    private fun onDtcLine(rest: String) {
        milRe.find(rest)?.let { m ->
            _dtc.update {
                it.copy(reading = true, mil = m.groupValues[1].toIntOrNull()?.let { v -> v != 0 },
                    milCount = m.groupValues[2].toIntOrNull(), groups = emptyMap(), notes = emptyMap())
            }
            return
        }
        when {
            rest == "END" -> _dtc.update { it.copy(reading = false, readAt = System.currentTimeMillis()) }
            rest.startsWith("CLEAR") -> _dtc.update {
                it.copy(clearResult = when {
                    rest.startsWith("CLEARED") -> L.s(R.string.link_dtc_cleared)
                    rest.startsWith("CLEAR REFUSED") ->
                        L.s(R.string.link_dtc_refused, rest.substringAfterLast(' '))
                    else -> L.s(R.string.link_dtc_no_reply)
                })
            }
            else -> {
                val group = rest.substringBefore(' ')
                val value = rest.substringAfter(' ', "")
                if (value.matches(Regex("[PCBU][0-9A-F]{4}"))) {
                    _dtc.update { it.copy(groups = it.groups + (group to (it.groups[group].orEmpty() + value))) }
                } else if (value.isNotEmpty()) {
                    _dtc.update { it.copy(notes = it.notes + (group to value)) }
                }
            }
        }
    }

    fun readDtc(keepClearResult: Boolean = false) {
        _dtc.update { it.copy(reading = true, clearResult = if (keepClearResult) it.clearResult else null) }
        send("DTC")
    }

    fun clearDtc() {
        _dtc.update { it.copy(clearResult = L.s(R.string.link_dtc_clearing)) }
        send("DTC CLEAR")
    }

    fun send(command: String) {
        _console.update { (it + "> $command").takeLast(200) }
        service?.sendCommand(command)
    }

    // Saved board address.
    private const val PREFS = "mota"
    fun savedAddress(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("address", null)
    fun saveAddress(ctx: Context, address: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (address == null) remove("address") else putString("address", address)
        }.apply()
    }
    fun orientation(ctx: Context): Orientation {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("orientation", null)
        return Orientation.entries.firstOrNull { it.name == name } ?: Orientation.AUTO
    }
    fun setOrientation(ctx: Context, o: Orientation) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("orientation", o.name).apply()
    fun keepScreenOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("screen_on", true)
    fun setKeepScreenOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("screen_on", on).apply()
}
