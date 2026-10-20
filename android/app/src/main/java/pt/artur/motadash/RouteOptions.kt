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
    fun setStyle(ctx: Context, s: RouteStyle) = prefs(ctx).edit().putString("style", s.name).apply()

    fun avoidMotorways(ctx: Context) = prefs(ctx).getBoolean("motorways", false)
    fun setAvoidMotorways(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("motorways", on).apply()
    fun avoidToll(ctx: Context) = prefs(ctx).getBoolean("toll", false)
    fun setAvoidToll(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("toll", on).apply()
    fun avoidUnpaved(ctx: Context) = prefs(ctx).getBoolean("unpaved", false)
    fun setAvoidUnpaved(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("unpaved", on).apply()
    fun bypassTowns(ctx: Context) = prefs(ctx).getBoolean("towns", false)
    fun setBypassTowns(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("towns", on).apply()

    /** car-vario profile parameters (its "# %name%" variables) for the current choices. */
    fun params(ctx: Context): Map<String, String> {
        val s = style(ctx)
        val fun_ = s == RouteStyle.FUN
        fun b(v: Boolean) = if (v) "1" else "0"
        return mapOf(
            "vmax" to when (s) { RouteStyle.FAST -> "100"; RouteStyle.ECO -> "70"; RouteStyle.FUN -> "80" },
            "avoid_motorways" to b(fun_ || avoidMotorways(ctx)),
            "avoid_toll" to b(avoidToll(ctx)),
            "avoid_unpaved" to b(!fun_ && avoidUnpaved(ctx)),
            "use_offroad" to b(fun_ && !avoidUnpaved(ctx)),
            "consider_town" to b(fun_ || bypassTowns(ctx)),
            "consider_forest" to b(fun_),
        )
    }
}
