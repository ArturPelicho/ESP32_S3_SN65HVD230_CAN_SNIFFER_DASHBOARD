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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

object Palette {
    val panel = Color(0xFF12161C)
    val frame = Color(0xFF2B3440)
    val label = Color(0xFF9AA4B0)
    val text = Color(0xFFF2F4F7)
    val dim = Color(0xFF39424E)
    val amber = Color(0xFFFFB020)
    val cyan = Color(0xFF3FD0FF)
    val green = Color(0xFF34D058)
    val blue = Color(0xFF2F9BFF)
    val orange = Color(0xFFFF8A00)
    val red = Color(0xFFFF3B30)
    val yellow = Color(0xFFFFE14D)
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
