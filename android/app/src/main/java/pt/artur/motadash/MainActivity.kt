package pt.artur.motadash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import pt.artur.motadash.ui.ControlScreen
import pt.artur.motadash.ui.DashboardScreen
import pt.artur.motadash.ui.DiagScreen
import pt.artur.motadash.ui.MapScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.ui.Palette
import pt.artur.motadash.ui.ThemeStore
import pt.artur.motadash.ui.LightSensor
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import pt.artur.motadash.ui.UiScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

class MainActivity : ComponentActivity() {

    /** Bumped when a track file arrives, so the app switches to the map. */
    val openMap = MutableStateFlow(0)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            MotaService.start(this)
        }

    private val askBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        if (Permissions.ready(this)) MotaService.start(this) else askPermissions.launch(Permissions.needed())
        applyScreenOn()
        applyOrientation()
        UiScale.load(this)
        ThemeStore.apply(this)
        handleTrackIntent(intent)
        setContent {
            val scale by UiScale.value.collectAsStateWithLifecycle()
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
                val scheme = Palette.scheme
                SideEffect { ThemeStore.bars(this, scheme) }
                MaterialTheme(colorScheme = ThemeStore.material(scheme)) {
                    App(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LightSensor.start(this)     // listens only when the theme follows the light sensor
        ThemeStore.apply(this)
    }

    override fun onPause() {
        LightSensor.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTrackIntent(intent)
    }

    /** A track file opened or shared with the app: import it and show the map. */
    private fun handleTrackIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        TrackStore.import(this, uris)
        openMap.value++
        intent.action = null      // don't import again on rotation
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> = getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    fun applyScreenOn() {
        if (Link.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun applyOrientation() {
        requestedOrientation = Link.orientation(this).value
    }

    fun requestBackgroundLocation() {
        askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun batteryOptimised(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife") // a personal tool that must keep running with the screen off
    fun requestNoBatteryOptimisation() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}

@Composable
private fun App(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val openMap by activity.openMap.collectAsStateWithLifecycle()
    LaunchedEffect(openMap) { if (openMap > 0) tab = 0 }
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    // Map and dashboard first, then diagnostics (with the fault codes), settings at the far end.
    val titles = listOf(
        stringResource(R.string.tab_map), stringResource(R.string.tab_dashboard),
        if (dtc.total > 0) stringResource(R.string.tab_diag_count, dtc.total) else stringResource(R.string.tab_diag),
        stringResource(R.string.tab_settings),
    )
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // AUTO theme by sunset: look again every minute.
    LaunchedEffect(Unit) { while (true) { ThemeStore.apply(activity); delay(60_000) } }
    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        // Tabs as separate keys, so each one's edges are plain; slimmer in landscape to keep the height.
        Row(
            Modifier.fillMaxWidth().height(if (landscape) 34.dp else 46.dp).padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            titles.forEachIndexed { i, t ->
                val on = tab == i
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (on) Palette.amber else Palette.panel, RoundedCornerShape(8.dp))
                        .border(1.dp, if (on) Palette.amber else Palette.frame, RoundedCornerShape(8.dp))
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t, fontSize = if (landscape) 13.sp else 14.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            on -> Palette.bg
                            i == 2 && dtc.total > 0 -> Palette.orange
                            else -> Palette.text
                        })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> MapScreen()
                1 -> DashboardScreen()
                2 -> DiagScreen()
                else -> ControlScreen(activity)
            }
        }
    }
}

