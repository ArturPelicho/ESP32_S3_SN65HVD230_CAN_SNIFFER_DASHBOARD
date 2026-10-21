package pt.artur.motadash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class DistUnit(@StringRes val res: Int) { KM(R.string.unit_km), MI(R.string.unit_mi) }
enum class TempUnit(@StringRes val res: Int) { C(R.string.unit_c), F(R.string.unit_f) }
/** Fuel use; the volume shown (litres or gallons) follows it. */
enum class FuelUnit(@StringRes val res: Int) { L100(R.string.unit_l100), KML(R.string.unit_kml), MPG_UK(R.string.unit_mpg_uk), MPG_US(R.string.unit_mpg_us) }

data class UnitSet(val dist: DistUnit, val temp: TempUnit, val fuel: FuelUnit)

/**
 * Units on screen and in the voice. Everything inside the app (and on the board) stays metric:
 * km/h, metres, °C, litres and L/100 km; these only turn a value into what the rider reads.
 */
object Units {
    private const val PREFS = "units"
    private const val KM_PER_MI = 1.609344
    private const val L_PER_GAL_UK = 4.54609
    private const val L_PER_GAL_US = 3.785411784

    private val _set = MutableStateFlow(defaults())
    val set: StateFlow<UnitSet> = _set.asStateFlow()
    /** Also as Compose state, so every screen showing a value redraws when the units change. */
    private val snap = mutableStateOf(_set.value)
    private val u get() = snap.value

    /** Before the rider picks: what the phone's country uses. */
    private fun defaults(): UnitSet = when (Locale.getDefault().country) {
        "US" -> UnitSet(DistUnit.MI, TempUnit.F, FuelUnit.MPG_US)
        "GB" -> UnitSet(DistUnit.MI, TempUnit.C, FuelUnit.MPG_UK)
        "LR", "MM" -> UnitSet(DistUnit.MI, TempUnit.F, FuelUnit.MPG_US)
        else -> UnitSet(DistUnit.KM, TempUnit.C, FuelUnit.L100)
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = defaults()
        _set.value = UnitSet(
            DistUnit.entries.firstOrNull { it.name == p.getString("dist", null) } ?: d.dist,
            TempUnit.entries.firstOrNull { it.name == p.getString("temp", null) } ?: d.temp,
            FuelUnit.entries.firstOrNull { it.name == p.getString("fuel", null) } ?: d.fuel,
        )
        snap.value = _set.value
    }

    fun change(ctx: Context, s: UnitSet) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dist", s.dist.name).putString("temp", s.temp.name).putString("fuel", s.fuel.name).apply()
        _set.value = s
        snap.value = s
    }

    val imperial get() = u.dist == DistUnit.MI

    // Speed
    fun speed(kmh: Double): Double = if (imperial) kmh / KM_PER_MI else kmh
    fun speed(kmh: Double?): Double? = kmh?.let { speed(it) }
    val speedUnit get() = if (imperial) "mph" else "km/h"
    /** A speed limit as its sign would read: mph limits come in steps of 5. */
    fun limit(kmh: Int): Int = if (imperial) ((kmh / KM_PER_MI / 5).let { Math.round(it) } * 5).toInt() else kmh

    // Distance
    fun dist(km: Double): Double = if (imperial) km / KM_PER_MI else km
    fun dist(km: Double?): Double? = km?.let { dist(it) }
    val distUnit get() = if (imperial) "mi" else "km"
    /**
     * A distance on screen, short ones in m (rounded to [step]) or ft, else in km or mi with one decimal
     * (none past 100).
     */
    fun short(m: Double, step: Int = 10): String {
        if (imperial) {
            val mi = m / 1000 / KM_PER_MI
            if (mi < 0.1) return "${((m * 3.28084 / 50).roundToLong() * 50).coerceAtLeast(0)} ft"
            return (if (mi >= 100) "%.0f mi" else "%.1f mi").format(mi)
        }
        if (m < 1000) return "${((m + step / 2) / step).toInt() * step} m"
        return (if (m >= 100_000) "%.0f km" else "%.1f km").format(m / 1000)
    }
    /** Heights: metres or feet. */
    fun alt(m: Double): Double = if (imperial) m * 3.28084 else m
    fun alt(m: Double?): Double? = m?.let { alt(it) }
    val altUnit get() = if (imperial) "ft" else "m"

    // Temperature
    fun temp(c: Double): Double = if (u.temp == TempUnit.F) c * 9 / 5 + 32 else c
    fun temp(c: Double?): Double? = c?.let { temp(it) }
    val tempUnit get() = if (u.temp == TempUnit.F) "°F" else "°C"

    // Fuel
    private val litresPerUnit get() = when (u.fuel) {
        FuelUnit.MPG_UK -> L_PER_GAL_UK
        FuelUnit.MPG_US -> L_PER_GAL_US
        else -> 1.0
    }
    fun vol(l: Double): Double = l / litresPerUnit
    fun vol(l: Double?): Double? = l?.let { vol(it) }
    val volUnit get() = if (litresPerUnit == 1.0) "L" else "gal"
    /** Fuel use from L/100 km; null for no distance covered (km/L and mpg have no value at 0). */
    fun cons(l100: Double?): Double? = l100?.let {
        when (u.fuel) {
            FuelUnit.L100 -> it
            FuelUnit.KML -> if (it > 0.01) 100 / it else null
            FuelUnit.MPG_UK -> if (it > 0.01) 282.4809 / it else null
            FuelUnit.MPG_US -> if (it > 0.01) 235.2146 / it else null
        }
    }
    val consUnit get() = when (u.fuel) {
        FuelUnit.L100 -> "L/100"
        FuelUnit.KML -> "km/L"
        FuelUnit.MPG_UK, FuelUnit.MPG_US -> "mpg"
    }
    /** Fuel flow standing still (L/h, or gal/h). */
    fun flow(lph: Double?): Double? = vol(lph)
    val flowUnit get() = "$volUnit/h"
}

private fun Double.roundToLong() = Math.round(this)
