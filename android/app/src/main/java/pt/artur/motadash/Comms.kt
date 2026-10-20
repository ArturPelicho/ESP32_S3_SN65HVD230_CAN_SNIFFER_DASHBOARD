package pt.artur.motadash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A phone call the rider sees on screen. */
data class Call(val number: String?, val name: String?, val ringing: Boolean, val since: Long)

/** A message (SMS, WhatsApp, ...) or a missed call, shown for a moment and kept in a short list. */
data class Msg(val app: String, val from: String, val text: String, val at: Long, val missedCall: Boolean = false)

/**
 * Calls and messages on the riding screen: who is calling, with big answer / reject buttons, and the
 * messages that arrive, optionally read aloud. Calls come from the phone state broadcast; messages from
 * the notification listener, so any messaging app works.
 */
object Comms {
    private const val PREFS = "comms"

    /** Messaging apps followed by default, besides the phone's own SMS app. */
    val KNOWN = linkedMapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram", "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.orca" to "Messenger", "com.google.android.apps.messaging" to "Mensagens",
    )

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()
    private val _shown = MutableStateFlow<Msg?>(null)
    /** The message on screen now, if any. */
    val shown: StateFlow<Msg?> = _shown.asStateFlow()
    private val _recent = MutableStateFlow<List<Msg>>(emptyList())
    val recent: StateFlow<List<Msg>> = _recent.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun showCalls(ctx: Context) = prefs(ctx).getBoolean("calls", true)
    fun setShowCalls(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("calls", on).apply()
    fun showMessages(ctx: Context) = prefs(ctx).getBoolean("msgs", true)
    fun setShowMessages(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("msgs", on).apply()
    fun speak(ctx: Context) = prefs(ctx).getBoolean("speak", false)
    fun setSpeak(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("speak", on).apply()
    /** Message text on screen, or only who it is from. */
    fun showText(ctx: Context) = prefs(ctx).getBoolean("text", true)
    fun setShowText(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("text", on).apply()

    fun smsApp(ctx: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()

    /** Apps whose messages show, as package names. */
    fun apps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("apps", null) ?: (KNOWN.keys + listOfNotNull(smsApp(ctx))).toSet()
    fun setApp(ctx: Context, pkg: String, on: Boolean) {
        val s = apps(ctx).toMutableSet()
        if (on) s += pkg else s -= pkg
        prefs(ctx).edit().putStringSet("apps", s).apply()
    }

    // ---- Permissions ----

    fun phonePermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)        // the caller's number
        add(Manifest.permission.READ_CONTACTS)        // the caller's name
        add(Manifest.permission.ANSWER_PHONE_CALLS)
    }.toTypedArray()

    fun hasPhone(ctx: Context) = phonePermissions().all { Permissions.has(ctx, it) }

    fun hasListener(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun listenerSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, MsgListener::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    // ---- Calls ----

    @SuppressLint("MissingPermission")
    fun answer(ctx: Context) {
        runCatching { ctx.getSystemService(TelecomManager::class.java).acceptRingingCall() }
    }

    /** Rejects a ringing call or ends the one in progress. */
    @SuppressLint("MissingPermission")
    fun hangUp(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 28) runCatching { ctx.getSystemService(TelecomManager::class.java).endCall() }
    }

    internal fun phoneState(ctx: Context, state: String?, number: String?) {
        val cur = _call.value
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // The broadcast comes twice, the second time with the number.
                val n = number ?: cur?.number
                _call.value = Call(n, n?.let { contactName(ctx, it) }, true, cur?.since ?: System.currentTimeMillis())
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                _call.value = Call(number ?: cur?.number, cur?.name ?: number?.let { contactName(ctx, it) }, false,
                    cur?.takeIf { !it.ringing }?.since ?: System.currentTimeMillis())
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (cur?.ringing == true) add(ctx, Msg(L.s(R.string.comms_missed), cur.name ?: cur.number ?: "?", "",
                    System.currentTimeMillis(), missedCall = true), speakIt = false)
                _call.value = null
            }
        }
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (!Permissions.has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    // ---- Messages ----

    internal fun add(ctx: Context, m: Msg, speakIt: Boolean = true) {
        if (!m.missedCall && !showMessages(ctx)) return
        if (_recent.value.firstOrNull()?.let { it.from == m.from && it.text == m.text && m.at - it.at < 60_000 } == true) return
        _recent.value = (listOf(m) + _recent.value).take(30)
        _shown.value = m
        if (speakIt && speak(ctx)) say(ctx, L.s(R.string.comms_say, m.app, m.from, m.text))
    }

    fun dismiss(m: Msg) { if (_shown.value == m) _shown.value = null }

    private fun say(ctx: Context, text: String) = Speech.say(ctx, text)
}

/** Phone state: ringing (with the number), in a call, idle. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        @Suppress("DEPRECATION")
        Comms.phoneState(ctx, intent.getStringExtra(TelephonyManager.EXTRA_STATE),
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
    }
}

/** Reads message notifications of the chosen apps (only while the rider allowed notification access). */
class MsgListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (sbn.packageName !in Comms.apps(ctx)) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val ex = n.extras
        // Chat apps put the newest message last in the messaging style; plain ones use title + text.
        @Suppress("DEPRECATION")
        val last = (ex.getParcelableArray(Notification.EXTRA_MESSAGES))?.lastOrNull() as? Bundle
        val from = last?.getCharSequence("sender")?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = last?.getCharSequence("text")?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isBlank()) return
        val app = Comms.KNOWN[sbn.packageName]
            ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrDefault(sbn.packageName)
        Comms.add(ctx, Msg(app, from, text, System.currentTimeMillis()))
    }
}
