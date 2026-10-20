package pt.artur.motadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** One set of colours for the whole app; [Palette] reads the current one. */
data class Scheme(
    val dark: Boolean,
    val bg: Color,          // screen background
    val panel: Color,       // cards and tiles
    val frame: Color,       // borders, idle outlines
    val label: Color,       // secondary text
    val text: Color,        // main text
    val dim: Color,         // no data
    val amber: Color,       // accent: titles, selected tab
    val cyan: Color,        // second accent: active buttons
    val green: Color, val blue: Color, val orange: Color, val red: Color, val yellow: Color,
)

/**
 * Day: warm tan sand with lighter sand panels, near-black ink and deep accents,
 * for full sun (on the panel: text 13:1, label 9:1, accents and status colours >= 4.5:1).
 */
val SAND = Scheme(
    dark = false, bg = Color(0xFFD8BF8E), panel = Color(0xFFEAD7AE), frame = Color(0xFFA88A55),
    label = Color(0xFF3F2E1A), text = Color(0xFF1A1108), dim = Color(0xFFC2A877),
    amber = Color(0xFF8C3208), cyan = Color(0xFF0B5560),
    green = Color(0xFF1E5E22), blue = Color(0xFF17448A), orange = Color(0xFF934700), red = Color(0xFFA01A1A), yellow = Color(0xFF6E5400),
)

/** Night: the same desert after dark, coffee-black with warm amber; nothing glaring. */
val DESERT_NIGHT = Scheme(
    dark = true, bg = Color(0xFF0D0A07), panel = Color(0xFF1A1510), frame = Color(0xFF3A2E22),
    label = Color(0xFFA8967D), text = Color(0xFFF0E5D2), dim = Color(0xFF4A3D2F),
    amber = Color(0xFFE8A13A), cyan = Color(0xFF5BBCC6),
    green = Color(0xFF5CBF6A), blue = Color(0xFF5B9BE0), orange = Color(0xFFF08A2E), red = Color(0xFFE8553F), yellow = Color(0xFFE6C75A),
)

/** The original look: cool graphite with bright accents. */
val GRAPHITE = Scheme(
    dark = true, bg = Color(0xFF000000), panel = Color(0xFF12161C), frame = Color(0xFF2B3440),
    label = Color(0xFF9AA4B0), text = Color(0xFFF2F4F7), dim = Color(0xFF39424E),
    amber = Color(0xFFFFB020), cyan = Color(0xFF3FD0FF),
    green = Color(0xFF34D058), blue = Color(0xFF2F9BFF), orange = Color(0xFFFF8A00), red = Color(0xFFFF3B30), yellow = Color(0xFFFFE14D),
)

/** Colours of the current scheme; reading them in a composable redraws it when the scheme changes. */
object Palette {
    var scheme by mutableStateOf(DESERT_NIGHT)
    val dark get() = scheme.dark
    val bg get() = scheme.bg
    val panel get() = scheme.panel
    val frame get() = scheme.frame
    val label get() = scheme.label
    val text get() = scheme.text
    val dim get() = scheme.dim
    val amber get() = scheme.amber
    val cyan get() = scheme.cyan
    val green get() = scheme.green
    val blue get() = scheme.blue
    val orange get() = scheme.orange
    val red get() = scheme.red
    val yellow get() = scheme.yellow
    /** Boxes over the map (speed, buttons, banners), see-through. */
    val overlay get() = scheme.panel.copy(alpha = 0.90f)
    val overlaySoft get() = scheme.panel.copy(alpha = 0.80f)
    /** Full-screen panels over the map (Mapas, Trajetos). */
    val sheet get() = scheme.bg.copy(alpha = 0.96f)
}

/** Same ladder as the board's "Dashboard thresholds" defaults. */
fun chtColor(c: Double?): Color = when {
    c == null -> Palette.dim
    c < 80 -> Palette.blue
    c < 135 -> Palette.green
    c < 150 -> Palette.orange
    else -> Palette.red
}

fun batteryColor(v: Double?): Color = when {
    v == null -> Palette.dim
    v < 11.8 || v > 15.2 -> Palette.red
    v < 12.3 || v > 14.8 -> Palette.orange
    else -> Palette.green
}

fun fmt(v: Double?, decimals: Int = 1, none: String = "--"): String =
    if (v == null) none else String.format(Locale.US, "%.${decimals}f", v)

@Composable
fun Tile(
    label: String,
    value: String,
    unit: String = "",
    color: Color = Palette.text,
    modifier: Modifier = Modifier,
    sub: String? = null,
    background: Color = Palette.panel,
) {
    Column(
        modifier
            .background(background, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Palette.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(value, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(" $unit", color = Palette.label, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        if (sub != null) Text(sub, color = Palette.label, fontSize = 11.sp)
    }
}

@Composable
fun KeyValue(key: String, value: String, color: Color = Palette.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = Palette.label, fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Palette.panel, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(title, color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        content()
    }
}
