package app.navexpress.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.navexpress.R

/*
 * The app's controls, one of each kind, so every menu and panel looks and behaves the same:
 * an option is a framed line with ✓ that fills amber when on (as in Layers); a value opens a list to pick
 * from; menus keep their scroll position until the rider moves them; groups of settings are sub-tabs on top
 * inside Definições, the same menu on the left when a panel fills the screen.
 */

/** Scroll positions of open menus by name, so a menu redrawn or reopened by a change stays where the rider left it. */
object MenuScroll {
    private val pos = HashMap<String, Int>()
    /** A menu opened by the rider starts at the top. */
    fun reset(key: String) { pos.keys.filter { it == key || it.startsWith("$key/") }.forEach { pos.remove(it) } }
    internal fun get(key: String) = pos[key] ?: 0
    internal fun put(key: String, v: Int) { pos[key] = v }
}

@Composable
fun rememberMenuScroll(name: String): ScrollState {
    val st = key(name) { rememberScrollState(MenuScroll.get(name)) }
    LaunchedEffect(st, name) { snapshotFlow { st.value }.collect { MenuScroll.put(name, it) } }
    return st
}

/**
 * A menu in a window that scrolls when it is taller than the screen. [key] names it (and its page): its
 * scroll position survives redraws, page changes and a recalculation behind it. Call [MenuScroll.reset]
 * when the rider opens it.
 */
@Composable
fun ScrollMenu(key: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val h = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(14.dp), color = Palette.sheet) {
            val st = rememberMenuScroll(key)
            Column(Modifier.widthIn(min = 260.dp, max = 440.dp).heightIn(max = (h * 0.85f).dp)
                .verticalScroll(st).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/** An option that is on or off (or one of several, with [on] for the chosen one); long press for its options when [more]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OptionChip(label: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, more: Boolean = false,
               onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape)
            .background(if (on && enabled) Palette.amber.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (on && enabled) Palette.amber else Palette.label.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .then(if (enabled) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (on) "✓" else " ", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = if (enabled) Palette.text else Palette.label, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
        if (more) Text("⋯", color = Palette.label, fontSize = 15.sp)
    }
}

/**
 * The same option as [OptionChip] with FilterChip's parameters, for choices laid out in a row (units,
 * theme, sizes...): every choice in the app looks alike.
 */
@Composable
fun Chip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier,
         enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape)
            .background(if (selected && enabled) Palette.amber.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected && enabled) Palette.amber else Palette.label.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected) Text("✓", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        leadingIcon?.invoke()
        CompositionLocalProvider(LocalContentColor provides if (enabled) Palette.text else Palette.label,
            LocalTextStyle provides LocalTextStyle.current.copy(fontSize = 15.sp)) { label() }
    }
}

/** A setting with several values: shows the current one, a tap opens the list to pick from. */
@Composable
fun ValueChip(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape).border(1.dp, Palette.label.copy(alpha = if (enabled) 1f else 0.4f), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(" ", fontSize = 15.sp)
        Text(label, color = if (enabled) Palette.text else Palette.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("›", color = Palette.label, fontSize = 15.sp)
    }
}

/** Something to do (not a setting): a plain line; [color] for a warning or the main action. */
@Composable
fun ActionItem(label: String, color: Color = Palette.text, bold: Boolean = false, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 16.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp))
}

/** A title over a few options in a menu. */
@Composable
fun MenuHeader(text: String) =
    Text(text, color = Palette.label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))

/** A page's title in a menu, with ‹ to go back when [onBack] is given. */
@Composable
fun MenuTitle(text: String, onBack: (() -> Unit)? = null) =
    Text((if (onBack != null) "‹  " else "") + text, color = Palette.amber, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .then(if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier).padding(horizontal = 6.dp, vertical = 8.dp))

/** Pick one of [options]: the list replaces the menu's page, the chosen one is ticked. */
@Composable
fun PickList(title: String, options: List<String>, current: Int, onBack: () -> Unit, onPick: (Int) -> Unit) {
    MenuTitle(title, onBack)
    options.forEachIndexed { i, o -> OptionChip(o, i == current, Modifier.fillMaxWidth()) { onPick(i); onBack() } }
}

/** Groups of a panel as tabs along the top: the chosen one underlined in amber, clearly not a setting. */
@Composable
fun SubTabs(labels: List<String>, selected: Int, onPick: (Int) -> Unit, colors: List<Color?> = emptyList()) {
    Column(Modifier.fillMaxWidth().background(Palette.panel)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                Column(Modifier.clickable { onPick(i) }.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l, color = if (on) Palette.amber else colors.getOrNull(i) ?: Palette.text, fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 12.dp, bottom = 9.dp))
                    Box(Modifier.width(48.dp).height(3.dp).background(if (on) Palette.amber else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.frame))
    }
}

/**
 * A panel of settings in groups: inside Definições ([embedded]) the groups are sub-tabs on top; filling the
 * screen (opened from the map) it has a title with Fechar and the groups on the left, as Definições.
 * [content] draws the chosen group; it scrolls by itself, keeping its place per group.
 */
@Composable
fun GroupedPanel(key: String, title: String, groups: List<String>, selected: Int, onPick: (Int) -> Unit, embedded: Boolean,
                 onClose: () -> Unit, colors: List<Color?> = emptyList(), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.sheet)
        // Keep touches off whatever is underneath (the map).
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        if (!embedded) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.maps_close)) }
        }
        if (embedded) SubTabs(groups, selected, onPick, colors)
        Row(Modifier.weight(1f)) {
            if (!embedded) SideMenu(groups, selected, onPick, colors)
            val st = rememberMenuScroll("$key/$selected")
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(st).padding(vertical = 6.dp), content = content)
        }
    }
}
