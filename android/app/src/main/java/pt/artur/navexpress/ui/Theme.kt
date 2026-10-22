package pt.artur.navexpress.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import pt.artur.navexpress.MapStyle
import pt.artur.navexpress.R

/** Day, night (each in its chosen look), or switching by itself. */
enum class ThemeMode(@StringRes val res: Int) {
    AUTO(R.string.theme_mode_auto), DAY(R.string.theme_mode_day), NIGHT(R.string.theme_mode_night)
}

/** What AUTO follows: sunset where the bike is, the phone's light sensor, or the phone's dark mode. */
enum class AutoBy(@StringRes val res: Int) {
    SUN(R.string.theme_by_sun), LIGHT(R.string.theme_by_light), PHONE(R.string.theme_by_phone)
}

/** Which light scheme day uses. */
enum class DayScheme(@StringRes val res: Int) {
    SAND(R.string.theme_day_sand), GRAPHITE(R.string.theme_day_graphite)
}

/** Which dark scheme night uses. */
enum class NightScheme(@StringRes val res: Int) {
    DESERT(R.string.theme_night_desert), GRAPHITE(R.string.theme_night_graphite), OLED(R.string.theme_night_oled)
}

object ThemeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Where the bike was last seen, for sunset in AUTO (mainland Portugal until the first fix). */
    @Volatile var lat = 39.5
    @Volatile var lon = -8.0

    /** The old single choice ("theme": AUTO, SAND, NIGHT, GRAPHITE), read once if nothing newer is saved. */
    private fun old(ctx: Context): String? = prefs(ctx).getString("theme", null)

    fun mode(ctx: Context): ThemeMode {
        val p = prefs(ctx)
        val saved = p.getString("theme_mode", null) ?: when (old(ctx)) {
            "SAND" -> "DAY"
            "NIGHT", "GRAPHITE" -> "NIGHT"
            else -> "AUTO"
        }
        return runCatching { ThemeMode.valueOf(saved) }.getOrDefault(ThemeMode.AUTO)
    }

    fun setMode(ctx: Context, m: ThemeMode) {
        prefs(ctx).edit().putString("theme_mode", m.name).apply()
        changed(ctx)
    }

    fun autoBy(ctx: Context): AutoBy =
        runCatching { AutoBy.valueOf(prefs(ctx).getString("theme_auto_by", "SUN")!!) }.getOrDefault(AutoBy.SUN)

    fun setAutoBy(ctx: Context, a: AutoBy) {
        prefs(ctx).edit().putString("theme_auto_by", a.name).apply()
        changed(ctx)
    }

    fun dayScheme(ctx: Context): DayScheme =
        runCatching { DayScheme.valueOf(prefs(ctx).getString("theme_day", "SAND")!!) }.getOrDefault(DayScheme.SAND)

    fun setDayScheme(ctx: Context, d: DayScheme) {
        prefs(ctx).edit().putString("theme_day", d.name).apply()
        changed(ctx)
    }

    fun nightScheme(ctx: Context): NightScheme {
        val saved = prefs(ctx).getString("theme_night", null) ?: if (old(ctx) == "GRAPHITE") "GRAPHITE" else "DESERT"
        return runCatching { NightScheme.valueOf(saved) }.getOrDefault(NightScheme.DESERT)
    }

    fun setNightScheme(ctx: Context, n: NightScheme) {
        prefs(ctx).edit().putString("theme_night", n.name).apply()
        changed(ctx)
    }

    /** True when the map's day/night follows the app theme (its button then calls [cycleMode]). */
    fun linkMap(ctx: Context): Boolean = prefs(ctx).getBoolean("theme_link_map", true)

    fun setLinkMap(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("theme_link_map", on).apply()
    }

    /** AUTO -> DAY -> NIGHT -> AUTO, applied at once. */
    fun cycleMode(ctx: Context) {
        val m = mode(ctx)
        setMode(ctx, ThemeMode.entries[(m.ordinal + 1) % ThemeMode.entries.size])
    }

    private fun changed(ctx: Context) {
        LightSensor.sync(ctx)
        apply(ctx)
    }

    /** True when the current settings say night now. */
    fun isNight(ctx: Context): Boolean = when (mode(ctx)) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> when (autoBy(ctx)) {
            AutoBy.SUN -> MapStyle.isNight(lat, lon)
            AutoBy.LIGHT -> LightSensor.night ?: MapStyle.isNight(lat, lon)
            AutoBy.PHONE -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** Picks the scheme for now; call at start, on change, every minute, and when the light sensor decides. */
    fun apply(ctx: Context) {
        Palette.scheme = if (!isNight(ctx)) when (dayScheme(ctx)) {
            DayScheme.SAND -> SAND
            DayScheme.GRAPHITE -> GRAPHITE_DAY
        } else when (nightScheme(ctx)) {
            NightScheme.DESERT -> DESERT_NIGHT
            NightScheme.GRAPHITE -> GRAPHITE
            NightScheme.OLED -> OLED
        }
    }

    /** Material colours (buttons, chips, switches, fields) from the current scheme. */
    fun material(s: Scheme): ColorScheme =
        if (s.dark) darkColorScheme(
            primary = s.amber, onPrimary = s.bg, secondary = s.cyan, onSecondary = s.bg,
            background = s.bg, onBackground = s.text, surface = s.panel, onSurface = s.text,
            surfaceVariant = s.panel, onSurfaceVariant = s.label, outline = s.frame, outlineVariant = s.frame,
            secondaryContainer = s.frame, onSecondaryContainer = s.text, error = s.red,
        ) else lightColorScheme(
            primary = s.amber, onPrimary = s.panel, secondary = s.cyan, onSecondary = s.panel,
            background = s.bg, onBackground = s.text, surface = s.panel, onSurface = s.text,
            surfaceVariant = s.bg, onSurfaceVariant = s.label, outline = s.frame, outlineVariant = s.frame,
            secondaryContainer = s.frame, onSecondaryContainer = s.text, error = s.red,
        )

    /** Status and navigation bars in the scheme's background, with icons that stay readable on it. */
    @Suppress("DEPRECATION")
    fun bars(activity: Activity, s: Scheme) {
        val w = activity.window
        w.statusBarColor = s.bg.toArgb()
        w.navigationBarColor = s.bg.toArgb()
        WindowCompat.getInsetsController(w, w.decorView).apply {
            isAppearanceLightStatusBars = !s.dark
            isAppearanceLightNavigationBars = !s.dark
        }
    }
}

/**
 * The phone's ambient light sensor, for AUTO by light. Listens only while the activity is
 * resumed and the setting asks for it. Night below 15 lux, day above 60 lux, each held 5 s.
 */
object LightSensor : SensorEventListener {
    private const val DARK_LUX = 15f
    private const val BRIGHT_LUX = 60f
    private const val HOLD_MS = 5_000L

    /** The last decision; null until the first reading (then AUTO uses sunset). */
    @Volatile var night: Boolean? = null
        private set

    private var app: Context? = null
    private var manager: SensorManager? = null
    private var resumed = false
    private var lux: Float? = null
    private var candidate: Boolean? = null
    private var since = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { decide(); handler.postDelayed(this, 1_000) }
    }

    /** True when the phone has a light sensor. */
    fun available(ctx: Context): Boolean =
        ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    /** From the activity's onResume. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        resumed = true
        sync(ctx)
    }

    /** From the activity's onPause. */
    fun stop() {
        resumed = false
        unregister()
    }

    /** Listens or not, after a theme setting changed. */
    fun sync(ctx: Context) {
        if (app == null) app = ctx.applicationContext
        val wanted = resumed && ThemeStore.mode(ctx) == ThemeMode.AUTO && ThemeStore.autoBy(ctx) == AutoBy.LIGHT
        if (!wanted) { unregister(); return }
        if (manager != null) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return   // none: AUTO stays on sunset
        manager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        handler.postDelayed(tick, 1_000)
    }

    private fun unregister() {
        manager?.unregisterListener(this)
        manager = null
        handler.removeCallbacks(tick)
        lux = null
        candidate = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        lux = event.values.firstOrNull() ?: return
        decide()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Hysteresis: a new verdict must hold for [HOLD_MS]; the very first one counts at once. */
    private fun decide() {
        val l = lux ?: return
        val want = when {
            l < DARK_LUX -> true
            l > BRIGHT_LUX -> false
            else -> null
        }
        if (want == null || want == night) { candidate = null; return }
        val now = SystemClock.elapsedRealtime()
        if (night == null) { decided(want); return }
        if (candidate != want) { candidate = want; since = now; return }
        if (now - since >= HOLD_MS) decided(want)
    }

    private fun decided(v: Boolean) {
        night = v
        candidate = null
        app?.let { ThemeStore.apply(it) }
    }
}
