package pt.artur.motadash

import android.content.Context
import androidx.annotation.StringRes

/** What a road route aims for; each sets the BRouter car profile's speed and preferences. */
enum class RouteStyle(@StringRes private val res: Int, @StringRes private val hintRes: Int) {
    /** Quickest by time: main roads, up to the bike's cruising speed. */
    FAST(R.string.route_fast, R.string.route_fast_hint),
    /** Least fuel: steady, slower roads (the profile's energy model with a lower top speed). */
    ECO(R.string.route_eco, R.string.route_eco_hint),
    /** Back roads: no motorways, round the towns, through forest, tracks and dirt allowed. */
    FUN(R.string.route_fun, R.string.route_fun_hint);
    val label: String get() = L.s(res)
    val hint: String get() = L.s(hintRes)
}

/** The route choices from the Trajetos panel, used for both offline and online routes. */
object RouteOptions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("route", Context.MODE_PRIVATE)

    fun style(ctx: Context): RouteStyle =
        RouteStyle.entries.firstOrNull { it.name == prefs(ctx).getString("style", null) } ?: RouteStyle.FAST
    fun setStyle(ctx: Context, s: RouteStyle) = run { prefs(ctx).edit().putString("style", s.name).apply(); changed() }

    fun avoidMotorways(ctx: Context) = prefs(ctx).getBoolean("motorways", false)
    fun setAvoidMotorways(ctx: Context, on: Boolean) = run { prefs(ctx).edit().putBoolean("motorways", on).apply(); changed() }
    fun avoidToll(ctx: Context) = prefs(ctx).getBoolean("toll", false)
    fun setAvoidToll(ctx: Context, on: Boolean) = run { prefs(ctx).edit().putBoolean("toll", on).apply(); changed() }
    fun avoidUnpaved(ctx: Context) = prefs(ctx).getBoolean("unpaved", false)
    fun setAvoidUnpaved(ctx: Context, on: Boolean) = run { prefs(ctx).edit().putBoolean("unpaved", on).apply(); changed() }
    fun bypassTowns(ctx: Context) = prefs(ctx).getBoolean("towns", false)
    fun setBypassTowns(ctx: Context, on: Boolean) = run { prefs(ctx).edit().putBoolean("towns", on).apply(); changed() }
    /** Stay on the main roads unless a lane, service road or track saves clearly more (see [minorPenalty]). */
    fun keepMain(ctx: Context) = prefs(ctx).getBoolean("keepMain", true)
    fun setKeepMain(ctx: Context, on: Boolean) = run { prefs(ctx).edit().putBoolean("keepMain", on).apply(); changed() }

    /** Bumps on every change, so a Nav route worked out with the old choices is asked again. */
    var version = 0; private set
    fun changed() { version++ }

    /**
     * Price, in the profile's cost units, of leaving the main roads for a lower level and coming back:
     * the route takes the lane only when it saves more than that. Fastest: 2 minutes plus 1 km; economical:
     * 2 minutes. The profile's cost is energy plus time weighted so a metre at vmax costs about 1 (BRouter's
     * kinematic model), so one second costs pw / cost0.
     */
    fun minorPenalty(s: RouteStyle, vmaxKmh: Double): Double {
        val v = vmaxKmh / 3.6
        val fAir = 0.4; val fRoll = 232.0; val pStandby = 250.0
        val pw = 2 * fAir * v * v * v - pStandby
        val cost0 = (pw + pStandby) / v + fRoll + fAir * v * v
        val perSecond = pw / cost0
        return when (s) {
            RouteStyle.FAST -> 120 * perSecond + 1000
            RouteStyle.ECO -> 120 * perSecond
            RouteStyle.FUN -> 0.0
        }
    }

    /** Route profile parameters (its "# %name%" variables) for the current choices. */
    fun params(ctx: Context): Map<String, String> {
        val s = style(ctx)
        val fun_ = s == RouteStyle.FUN
        fun b(v: Boolean) = if (v) "1" else "0"
        val vmax = when (s) { RouteStyle.FAST -> 100.0; RouteStyle.ECO -> 70.0; RouteStyle.FUN -> 80.0 }
        val penalty = if (keepMain(ctx)) minorPenalty(s, vmax) else 0.0
        return mapOf(
            "vmax" to vmax.toInt().toString(),
            "minor_penalty" to penalty.toInt().toString(),
            "avoid_motorways" to b(fun_ || avoidMotorways(ctx)),
            "avoid_toll" to b(avoidToll(ctx)),
            "avoid_unpaved" to b(!fun_ && avoidUnpaved(ctx)),
            "use_offroad" to b(fun_ && !avoidUnpaved(ctx)),
            "consider_town" to b(fun_ || bypassTowns(ctx)),
            "consider_forest" to b(fun_),
        )
    }
}
