package pt.artur.motadash

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** The phone's text-to-speech in the app's language, for messages and turn instructions. */
object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    fun say(ctx: Context, text: String) {
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_ADD, null, "nx"); return }
        synchronized(pending) { pending += text }
        if (t != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = AppLocale.tag(ctx).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                synchronized(pending) { pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "nx") }; pending.clear() }
            }
        }
    }
}
