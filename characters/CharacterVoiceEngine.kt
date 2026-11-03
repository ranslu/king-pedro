package com.slusarchuk.kingpedro.characters

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Wraps Android's built-in TextToSpeech engine and applies per-character
 * pitch/rate/locale so each character sounds distinct without recorded voice
 * files. Text bubbles should ALWAYS be shown alongside speech.
 */
class CharacterVoiceEngine(
    context: Context,
    private val onSpeechStart: (utteranceId: String) -> Unit = {},
    private val onSpeechDone: (utteranceId: String) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled: Boolean = true

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { utteranceId?.let(onSpeechStart) }
                override fun onDone(utteranceId: String?) { utteranceId?.let(onSpeechDone) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let(onSpeechDone) }
            })
        }
    }

    fun speak(character: CharacterProfile, line: String, interrupt: Boolean = false): String? {
        val engine = tts ?: return null
        if (!enabled || !ready) return null

        val locale = localeFor(character.voice.locale)
        val availability = engine.isLanguageAvailable(locale)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            engine.language = Locale.getDefault()
        } else {
            engine.language = locale
        }

        engine.setPitch(character.voice.pitch)
        engine.setSpeechRate(character.voice.speechRate)

        val utteranceId = "${character.id}-${UUID.randomUUID()}"
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(line, queueMode, null, utteranceId)
        return utteranceId
    }

    fun stopAll() { tts?.stop() }
    fun shutdown() { tts?.shutdown(); tts = null }

    private fun localeFor(tag: String): Locale = when (tag) {
        "uk-UA" -> Locale("uk", "UA")
        "en-US" -> Locale.US
        else -> Locale.getDefault()
    }
}
