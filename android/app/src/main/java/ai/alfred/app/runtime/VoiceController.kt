package ai.alfred.app.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceController(
    context: Context,
    private val listener: Listener,
) : RecognitionListener {
    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(this@VoiceController)
    }

    fun start(locale: String?) {
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                locale?.takeIf { it.isNotBlank() }?.let {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                }
            }
        )
    }

    fun stop() = recognizer.stopListening()
    fun destroy() = recognizer.destroy()

    override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try once more."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I couldn't hear anything."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition is unavailable offline on this phone."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
            else -> "Voice recognition stopped (code $error)."
        }
        listener.onError(message)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        best(partialResults)?.let(listener::onPartial)
    }

    override fun onResults(results: Bundle?) {
        val value = best(results)
        if (value == null) listener.onError("I didn't catch that.") else listener.onResult(value)
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
