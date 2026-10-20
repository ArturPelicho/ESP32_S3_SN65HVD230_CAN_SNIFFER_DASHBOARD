package pt.artur.motadash

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's language: the phone's own (empty tag) or one picked in Definições.
 * English is the base (res/values); every other language lives in res/values-<lang>.
 * Activity and service wrap their context with [wrap], so text and number formats follow it.
 */
object AppLocale {
    /** Languages offered, as BCP 47 tags; "" follows the phone. Each is named in its own language. */
    val OPTIONS = listOf(
        "" to null, "en-GB" to "English (UK)", "pt-PT" to "Português", "es-ES" to "Español",
        "fr-FR" to "Français", "it-IT" to "Italiano", "de-DE" to "Deutsch",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun tag(ctx: Context): String = prefs(ctx).getString("lang", "") ?: ""

    fun set(ctx: Context, tag: String) = prefs(ctx).edit().putString("lang", tag).apply()

    fun wrap(base: Context): Context {
        val t = tag(base)
        if (t.isEmpty()) return base
        val loc = Locale.forLanguageTag(t)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }
}

/**
 * Text for code outside the screens (stores, service, parsers), in the app's language.
 * Screens use stringResource(); everything else uses L.s(R.string.x, args...).
 */
object L {
    @Volatile private var ctx: Context? = null

    /** Called with a context already wrapped by [AppLocale.wrap] (the activity, or the service). */
    fun init(c: Context) { ctx = c }

    fun s(@StringRes id: Int, vararg args: Any): String =
        ctx?.getString(id, *args) ?: ""
}
