package app.navexpress.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Size of the whole interface (text and buttons), so it reads well with a helmet on. */
object UiScale {
    val steps = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)
    private const val PREFS = "ui"
    private val _value = MutableStateFlow(1f)
    val value: StateFlow<Float> = _value.asStateFlow()

    fun load(ctx: Context) {
        _value.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("scale", 1f)
    }

    fun set(ctx: Context, v: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("scale", v).apply()
        _value.value = v
    }
}
