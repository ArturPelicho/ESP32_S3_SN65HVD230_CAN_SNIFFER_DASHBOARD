package pt.artur.motadash.ui

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import pt.artur.motadash.MapStyle
import pt.artur.motadash.R

/** What the rider picks in Definições; AUTO is sand by day and desert night after sunset. */
enum class AppTheme(@StringRes val res: Int) {
    AUTO(R.string.theme_auto), SAND(R.string.theme_sand), NIGHT(R.string.theme_night), GRAPHITE(R.string.theme_graphite)
}

object ThemeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Where the bike was last seen, for sunset in AUTO (mainland Portugal until the first fix). */
    @Volatile var lat = 39.5
    @Volatile var lon = -8.0

    fun get(ctx: Context): AppTheme =
        runCatching { AppTheme.valueOf(prefs(ctx).getString("theme", "AUTO")!!) }.getOrDefault(AppTheme.AUTO)

    fun set(ctx: Context, t: AppTheme) {
        prefs(ctx).edit().putString("theme", t.name).apply()
        apply(ctx)
    }

    /** Picks the scheme for now; call at start, on change, and every minute for AUTO. */
    fun apply(ctx: Context) {
        Palette.scheme = when (get(ctx)) {
            AppTheme.SAND -> SAND
            AppTheme.NIGHT -> DESERT_NIGHT
            AppTheme.GRAPHITE -> GRAPHITE
            AppTheme.AUTO -> if (MapStyle.isNight(lat, lon)) DESERT_NIGHT else SAND
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
