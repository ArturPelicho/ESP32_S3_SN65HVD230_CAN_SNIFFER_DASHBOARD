package pt.artur.motadash

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import pt.artur.motadash.ui.ControlScreen
import pt.artur.motadash.ui.DashboardScreen
import pt.artur.motadash.ui.DiagScreen
import pt.artur.motadash.ui.DtcScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.artur.motadash.ui.Palette

class MainActivity : ComponentActivity() {

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            MotaService.start(this)
        }

    private val askBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Permissions.ready(this)) MotaService.start(this) else askPermissions.launch(Permissions.needed())
        applyScreenOn()
        applyOrientation()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Palette.panel)) {
                App(this)
            }
        }
    }

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
    val dtc by Link.dtc.collectAsStateWithLifecycle()
    val titles = listOf("Painel", "Controlo", if (dtc.total > 0) "Erros (${dtc.total})" else "Erros", "Diagnóstico")
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            // Slim tabs so the dashboard keeps the height.
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                titles.forEachIndexed { i, t ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { tab = i }, contentAlignment = Alignment.Center) {
                        Text(t, fontSize = 13.sp, color = when {
                            tab == i -> Palette.amber
                            i == 2 && dtc.total > 0 -> Palette.orange
                            else -> Palette.label
                        })
                    }
                }
            }
        } else {
            TabRow(selectedTabIndex = tab, containerColor = Color.Black, contentColor = Palette.amber) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(t, maxLines = 1, color = if (i == 2 && dtc.total > 0 && tab != i) Palette.orange else Color.Unspecified) })
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen()
                1 -> ControlScreen(activity)
                2 -> DtcScreen()
                else -> DiagScreen()
            }
        }
    }
}

