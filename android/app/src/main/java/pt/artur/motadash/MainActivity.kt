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
import androidx.compose.foundation.background
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
    val titles = listOf("Painel", "Controlo", "Diagnóstico")
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Black, contentColor = Palette.amber) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen()
                1 -> ControlScreen(activity)
                else -> DiagScreen()
            }
        }
    }
}

