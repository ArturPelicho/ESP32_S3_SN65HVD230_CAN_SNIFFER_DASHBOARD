package pt.artur.motadash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkStatus { STOPPED, NO_DEVICE, SCANNING, WAITING, CONNECTING, CONNECTED }

data class ParamInfo(val name: String, val value: Int, val min: Int, val max: Int, val def: Int, val unit: String)

data class GpsInfo(
    val hz: Double = 0.0,
    val speedKmh: Double? = null,
    val speedAccKmh: Double? = null,
    val hAccM: Double? = null,
    val fixes: Long = 0,
    val sent: Long = 0,
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

    @Volatile var service: MotaService? = null

    fun setStatus(s: LinkStatus) { _status.value = s }
    fun setTelemetry(t: Telemetry?) { _telemetry.value = t }
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
        if (line.startsWith("TANK ")) _tankLine.value = line
        if (line.startsWith("GEAR ")) _gearLine.value = line
        if (line.startsWith("FILL ")) {
            _fillLine.value = line
            _suggestedK.value = fillRe.find(line)?.groupValues?.get(1)?.toInt()
        }
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
    fun keepScreenOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("screen_on", true)
    fun setKeepScreenOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("screen_on", on).apply()
}
