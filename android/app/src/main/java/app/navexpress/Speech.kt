package app.navexpress

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** The phone's text-to-speech in the app's language, for messages and turn instructions. */
object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private var app: Context? = null

    private fun params(): Bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (app?.let { MapStore.voiceVolume(it) } ?: 100) / 100f)
    }

    fun say(ctx: Context, text: String) {
        app = ctx.applicationContext
        if (MapStore.voiceVolume(ctx) <= 0) return
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_ADD, params(), "nx"); return }
        synchronized(pending) { pending += text }
        if (t != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = AppLocale.tag(ctx).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                synchronized(pending) { pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, params(), "nx") }; pending.clear() }
            }
        }
    }

    /** Stops whatever is being said and drops what is queued. */
    fun stop() {
        synchronized(pending) { pending.clear() }
        tts?.stop()
    }

    /** A warning beep at the warning volume (silent at 0). */
    fun beep(ctx: Context) {
        val v = MapStore.alertVolume(ctx)
        if (v <= 0) return
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_NOTIFICATION, v.coerceIn(1, 100))
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            Handler(Looper.getMainLooper()).postDelayed({ g.release() }, 800)
        }
    }
}
