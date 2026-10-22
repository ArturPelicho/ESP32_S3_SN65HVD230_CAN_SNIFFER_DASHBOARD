package pt.artur.navexpress

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Mirror of the firmware's main/phone_proto.h. Keep both in step: the
 * version byte comes first, fields are little-endian, and fields appended
 * at the end of a known version are ignored by older readers.
 */
object BleProtocol {
    const val VERSION = 1

    private fun mota(n: Int): UUID =
        UUID.fromString(String.format("6d6f7461-%04x-4b1e-9c3a-5a1e00000000", n))

    val SERVICE: UUID = mota(1)
    val GPS: UUID = mota(2)
    val TELEMETRY: UUID = mota(3)

    /** ELM327 UART used for the text commands (PARAM, TANK, FILL, ...). */
    val UART_SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val UART_NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val UART_WRITE: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME = "OBDII"

    // GPS flags
    const val GPS_SPEED = 1
    const val GPS_POSITION = 2
    const val GPS_BEARING = 4
    const val GPS_SPEED_ACC = 8

    const val GPS_LEN = 24
    const val TM_LEN = 73          // shortest version-1 telemetry
    const val TM_LEN_RANGE = 75    // + consumption the range uses
    const val NA16 = 0xFFFF
    const val NA16S = Short.MIN_VALUE.toInt()

    val SPEED_SOURCES = listOf("GPS_EXT", "WHEEL", "GPS_INT", "GEAR", "OBD")
    private val SPEED_SOURCE_LABEL_IDS = listOf(
        R.string.link_speed_src_gps_ext, R.string.link_speed_src_wheel, R.string.link_speed_src_gps_int,
        R.string.link_speed_src_gear, R.string.link_speed_src_obd,
    )
    private val SPEED_ORIGIN_IDS = listOf(
        R.string.link_origin_none, R.string.link_origin_source, R.string.link_origin_gear, R.string.link_origin_held,
    )
    /** Shown to the user, in the app's language (read at call time). */
    val SPEED_SOURCE_LABELS: List<String> get() = SPEED_SOURCE_LABEL_IDS.map { L.s(it) }
    val SPEED_ORIGINS: List<String> get() = SPEED_ORIGIN_IDS.map { L.s(it) }

    private fun u16(v: Double, scale: Double): Int {
        if (v.isNaN() || v < 0) return NA16
        val r = Math.round(v * scale)
        return if (r >= NA16 - 1) NA16 - 1 else r.toInt()
    }

    fun encodeGps(
        seq: Int, speedKmh: Double?, speedAccKmh: Double?, latE7: Int?, lonE7: Int?,
        hAccM: Double?, altM: Int, bearingDeg: Double?, fixAgeMs: Int,
    ): ByteArray {
        var flags = 0
        if (speedKmh != null) flags = flags or GPS_SPEED
        if (latE7 != null && lonE7 != null) flags = flags or GPS_POSITION
        if (bearingDeg != null) flags = flags or GPS_BEARING
        if (speedAccKmh != null) flags = flags or GPS_SPEED_ACC
        val b = ByteBuffer.allocate(GPS_LEN).order(ByteOrder.LITTLE_ENDIAN)
        b.put(VERSION.toByte())
        b.put(flags.toByte())
        b.putShort(seq.toShort())
        b.putShort(u16((speedKmh ?: 0.0) / 0.036, 1.0).toShort())
        b.putShort((if (speedAccKmh == null) 0 else u16(speedAccKmh / 0.036, 1.0)).toShort())
        b.putInt(latE7 ?: 0)
        b.putInt(lonE7 ?: 0)
        b.putShort(u16(hAccM ?: 0.0, 10.0).toShort())
        b.putShort(altM.coerceIn(-32768, 32767).toShort())
        b.putShort(u16(bearingDeg ?: 0.0, 100.0).toShort())
        b.putShort(fixAgeMs.coerceIn(0, 65535).toShort())
        return b.array()
    }

    fun decodeTelemetry(data: ByteArray): Telemetry? {
        if (data.size < TM_LEN || data[0].toInt() != VERSION) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.get()
        fun u8() = b.get().toInt() and 0xFF
        fun u16() = b.short.toInt() and 0xFFFF
        fun s16() = b.short.toInt()
        fun u32() = b.int.toLong() and 0xFFFFFFFFL
        val flags = u8()
        val flags2 = u8()
        val seq = u16()
        val rpm = u16()
        val cht = s16()
        val batt = u16()
        val thr = u16()
        val map = u16()
        val iat = s16()
        val o2 = u16()
        val stft = s16()
        val ltft = s16()
        val spark = s16()
        val inj = u32()
        val flow = u16()
        val inst = u16()
        val avg = u16()
        val usedMl = u32()
        val distM = u32()
        val speeds = List(5) { u16() }
        val fuelSpeed = u16()
        val fuelSrc = u8()
        val fuelOrigin = u8()
        val gear = u8()
        val gearsFound = u8()
        val tank = u16()
        val range = u16()
        val fillAvg = u16()
        val fillM = u32()
        val learnS = u16()
        val shiftRpm = u16()
        val rangeL100 = if (data.size >= TM_LEN_RANGE) u16() else NA16
        fun opt(v: Int, scale: Double) = if (v == NA16) null else v / scale
        fun optS(v: Int, scale: Double) = if (v == NA16S) null else v / scale
        return Telemetry(
            seq = seq,
            dataOn = (flags and 1) != 0,
            engineFresh = (flags and 2) != 0,
            powerFresh = (flags and 4) != 0,
            fuelValid = (flags and 8) != 0,
            fuelCut = (flags and 16) != 0,
            instPerKm = (flags and 32) != 0,
            avgPerKm = (flags and 64) != 0,
            chtCritical = (flags and 128) != 0,
            tankKnown = (flags2 and 1) != 0,
            rangeValid = (flags2 and 2) != 0,
            fillAvgValid = (flags2 and 4) != 0,
            gearsComplete = (flags2 and 8) != 0,
            shift = (flags2 and 16) != 0,
            rpm = rpm,
            chtC = optS(cht, 1.0),
            batteryV = if (batt == 0) null else batt / 1000.0,
            throttlePct = opt(thr, 10.0),
            mapKpa = opt(map, 1.0),
            iatC = optS(iat, 1.0),
            o2V = opt(o2, 1000.0),
            stftPct = optS(stft, 10.0),
            ltftPct = optS(ltft, 10.0),
            sparkDeg = optS(spark, 10.0),
            inj = inj / 10000.0,
            flowLph = opt(flow, 100.0),
            instant = opt(inst, 100.0),
            average = opt(avg, 100.0),
            usedL = usedMl / 1000.0,
            distKm = distM / 1000.0,
            speeds = speeds.map { opt(it, 100.0) },
            fuelSpeed = opt(fuelSpeed, 100.0),
            fuelSpeedSrc = fuelSrc,
            fuelSpeedOrigin = fuelOrigin,
            gear = gear,
            gearsFound = gearsFound,
            tankL = opt(tank, 100.0),
            rangeKm = opt(range, 10.0),
            fillAvg = opt(fillAvg, 100.0),
            fillKm = fillM / 1000.0,
            learnS = learnS,
            shiftRpm = shiftRpm,
            rangeL100 = opt(rangeL100, 100.0),
        )
    }
}

data class Telemetry(
    val seq: Int,
    val dataOn: Boolean,
    val engineFresh: Boolean,
    val powerFresh: Boolean,
    val fuelValid: Boolean,
    val fuelCut: Boolean,
    val instPerKm: Boolean,
    val avgPerKm: Boolean,
    val chtCritical: Boolean,
    val tankKnown: Boolean,
    val rangeValid: Boolean,
    val fillAvgValid: Boolean,
    val gearsComplete: Boolean,
    val shift: Boolean,
    val rpm: Int,
    val chtC: Double?,
    val batteryV: Double?,
    val throttlePct: Double?,
    val mapKpa: Double?,
    val iatC: Double?,
    val o2V: Double?,
    val stftPct: Double?,
    val ltftPct: Double?,
    val sparkDeg: Double?,
    val inj: Double,
    val flowLph: Double?,
    val instant: Double?,
    val average: Double?,
    val usedL: Double,
    val distKm: Double,
    val speeds: List<Double?>,
    val fuelSpeed: Double?,
    val fuelSpeedSrc: Int,
    val fuelSpeedOrigin: Int,
    val gear: Int,
    val gearsFound: Int,
    val tankL: Double?,
    val rangeKm: Double?,
    val fillAvg: Double?,
    val fillKm: Double,
    val learnS: Int,
    val shiftRpm: Int,
    /** L/100 km the range is computed with: baseline blended with the since-fill average. */
    val rangeL100: Double? = null,
) {
    /** Speed to show: wheel sensor, then the gear estimate (CAN rate), then GPS. */
    val displaySpeed: Double?
        get() = speeds.getOrNull(1) ?: speeds.getOrNull(3) ?: speeds.getOrNull(2)
            ?: speeds.getOrNull(0) ?: fuelSpeed
}
