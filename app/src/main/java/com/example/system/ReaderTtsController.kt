package com.example.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TtsPlaybackState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val currentParagraphIndex: Int = 0,
    val speechRate: Float = 1.0f
)

/**
 * On-device Android TextToSpeech narration engine for Reader Mode.
 * Works completely offline with zero permissions and cleans up cleanly on dispose.
 */
class ReaderTtsController(context: Context) {

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var currentParagraphs: List<String> = emptyList()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("p_")?.toIntOrNull() ?: return
                        _state.value = _state.value.copy(isSpeaking = true, currentParagraphIndex = idx)
                    }

                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("p_")?.toIntOrNull() ?: return
                        if (idx >= currentParagraphs.lastIndex) {
                            _state.value = _state.value.copy(isSpeaking = false, currentParagraphIndex = 0)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.value = _state.value.copy(isSpeaking = false)
                    }
                })
            }
            _state.value = _state.value.copy(isReady = ready)
        }
    }

    fun speakParagraphs(paragraphs: List<String>, startIndex: Int = 0) {
        val engine = tts ?: return
        if (!_state.value.isReady || paragraphs.isEmpty()) return
        currentParagraphs = paragraphs
        val safeStart = startIndex.coerceIn(0, paragraphs.lastIndex)
        engine.stop()
        engine.setSpeechRate(_state.value.speechRate)
        _state.value = _state.value.copy(isSpeaking = true, currentParagraphIndex = safeStart)

        for (i in safeStart until paragraphs.size) {
            val mode = if (i == safeStart) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(paragraphs[i].take(3500), mode, null, "p_$i")
        }
    }

    fun stop() {
        tts?.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.75f, 2.0f)
        _state.value = _state.value.copy(speechRate = clamped)
        tts?.setSpeechRate(clamped)
        if (_state.value.isSpeaking && currentParagraphs.isNotEmpty()) {
            speakParagraphs(currentParagraphs, _state.value.currentParagraphIndex)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
