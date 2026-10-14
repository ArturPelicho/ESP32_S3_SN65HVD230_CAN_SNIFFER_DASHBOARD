package pt.artur.motadash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the link after a phone restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Starting a location service from the background needs "allow all the time".
        if (Permissions.background(context)) MotaService.start(context)
    }
}
