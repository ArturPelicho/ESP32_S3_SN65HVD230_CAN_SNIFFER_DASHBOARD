package pt.artur.motadash

import android.content.Context
import androidx.annotation.StringRes

/** What a road route aims for; each sets the BRouter car profile's speed and preferences. */
enum class RouteStyle(@StringRes private val res: Int, @StringRes private val hintRes: Int) {
    /** Quickest by time: main roads, up to the bike's cruising speed. */
    FAST(R.string.route_fast, R.string.route_fast_hint),
    /** Least fuel: steady, slower roads (the profile's energy model with a lower top speed). */
    ECO(R.string.route_eco, R.string.route_eco_hint),
    /** Dirt: as many tracks and unpaved roads as the allowed detour lets (see [RouteOptions.funDetour]). */
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

    // Each route type keeps its own toggles: fun avoids motorways and towns unless told otherwise.
    private fun key(k: String, s: RouteStyle) = "${k}_${s.name}"
    private fun get(ctx: Context, k: String, s: RouteStyle, def: Boolean) =
        prefs(ctx).getBoolean(key(k, s), prefs(ctx).getBoolean(k, def))
    private fun put(ctx: Context, k: String, s: RouteStyle, on: Boolean) =
        run { prefs(ctx).edit().putBoolean(key(k, s), on).apply(); changed() }

    fun avoidMotorways(ctx: Context, s: RouteStyle = style(ctx)) = get(ctx, "motorways", s, s == RouteStyle.FUN)
    fun setAvoidMotorways(ctx: Context, on: Boolean, s: RouteStyle = style(ctx)) = put(ctx, "motorways", s, on)
    fun avoidToll(ctx: Context, s: RouteStyle = style(ctx)) = get(ctx, "toll", s, false)
    fun setAvoidToll(ctx: Context, on: Boolean, s: RouteStyle = style(ctx)) = put(ctx, "toll", s, on)
    fun avoidUnpaved(ctx: Context, s: RouteStyle = style(ctx)) = get(ctx, "unpaved", s, false)
    fun setAvoidUnpaved(ctx: Context, on: Boolean, s: RouteStyle = style(ctx)) = put(ctx, "unpaved", s, on)
    fun bypassTowns(ctx: Context, s: RouteStyle = style(ctx)) = get(ctx, "towns", s, s == RouteStyle.FUN)
    fun setBypassTowns(ctx: Context, on: Boolean, s: RouteStyle = style(ctx)) = put(ctx, "towns", s, on)
    /** Stay on the main roads unless a lane, service road or track saves clearly more (see [minorPenalty]). */
    fun keepMain(ctx: Context, s: RouteStyle = style(ctx)) = s != RouteStyle.FUN && get(ctx, "keepMain", s, true)
    fun setKeepMain(ctx: Context, on: Boolean, s: RouteStyle = style(ctx)) = put(ctx, "keepMain", s, on)

    /** Fun mode: how much longer than the fastest route the dirt route may be, in percent. */
    fun funDetour(ctx: Context) = prefs(ctx).getInt("funDetour", 30)
    fun setFunDetour(ctx: Context, pct: Int) = run { prefs(ctx).edit().putInt("funDetour", pct).apply(); changed() }
    val DETOURS = listOf(10, 20, 30, 50, 100)
    /** Extra cost per metre of paved road tried in turn, most dirt first (1 about doubles a paved metre; past ~15 the search goes astray). */
    val PAVED_LEVELS = listOf(10.0, 6.0, 3.0, 1.5, 0.5, 0.0)
    /** Speed the fun route assumes on dirt (rough tracks half that). */
    private const val DIRT_KMH = 35

    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumps on every change: a Nav route back is asked again, a planned trajeto is worked out again. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes
    val version: Int get() = _changes.value
    fun changed() { _changes.value++ }

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

    /**
     * Route profile parameters (its "# %name%" variables) for style [s] with the toggles of style [o]
     * (fun's yardstick is the fastest route with fun's toggles).
     */
    fun params(ctx: Context, s: RouteStyle = style(ctx), paved: Double = 0.0, o: RouteStyle = s): Map<String, String> {
        val fun_ = s == RouteStyle.FUN
        fun b(v: Boolean) = if (v) "1" else "0"
        val vmax = when (s) { RouteStyle.FAST -> 100.0; RouteStyle.ECO -> 70.0; RouteStyle.FUN -> 80.0 }
        val penalty = if (keepMain(ctx, o)) minorPenalty(s, vmax) else 0.0
        val dirt = !avoidUnpaved(ctx, o)
        return mapOf(
            "vmax" to vmax.toInt().toString(),
            "minor_penalty" to penalty.toInt().toString(),
            "avoid_motorways" to b(avoidMotorways(ctx, o)),
            "avoid_toll" to b(avoidToll(ctx, o)),
            "avoid_unpaved" to b(!dirt),
            "use_offroad" to b(fun_ && dirt),
            "consider_town" to b(bypassTowns(ctx, o)),
            "consider_forest" to b(fun_),
            "dirt_kmh" to (if (fun_ && dirt) DIRT_KMH else 0).toString(),
            "paved_penalty" to String.format(java.util.Locale.US, "%.2f", if (fun_) paved else 0.0),
        )
    }
}
