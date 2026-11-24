package dk.langkilde.mindmill.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Android speech recognition in a loop. Prefers the on-device recogniser (API 31+) and falls back to the normal one
 * with offline preferred. Ends each utterance on the recogniser's own end-of-speech detection. With [continuous]
 * it starts again after every result (meetings); otherwise only after silence or a miss, until the caller stops it.
 */
class ContinuousRecognizer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onProblem(message: String)
    }

    var continuous = false
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var onDeviceFailed = false
    private var active = false
    private var listening = false
    private var locale: Locale = Locale.getDefault()
    private var errorsInARow = 0

    val usesOnDevice get() = onDevice

    fun start(locale: Locale) {
        main.post {
            this.locale = locale
            active = true
            errorsInARow = 0
            begin()
        }
    }

    fun stop() {
        main.post {
            active = false
            if (listening) recognizer?.cancel()
            listening = false
        }
    }

    fun release() {
        main.post {
            active = false
            listening = false
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun ensure(): SpeechRecognizer? {
        recognizer?.let { return it }
        val useOnDevice = !onDeviceFailed && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!useOnDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onProblem("This phone has no speech recognition. Install or enable Google's speech services.")
            active = false
            return null
        }
        val r = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        onDevice = useOnDevice
        r.setRecognitionListener(callbacks)
        recognizer = r
        return r
    }

    private fun begin() {
        if (!active) return
        val r = ensure() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (!onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (continuous) 1500L else 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (continuous) 1200L else 700L)
        }
        listening = true
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            retry(500)
        }
    }

    private fun retry(delayMs: Long) {
        if (!active) return
        main.postDelayed({ if (active && !listening) begin() }, delayMs)
    }

    private fun dropRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) listener.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            errorsInARow = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (!active) return
            if (text.isBlank()) { retry(100); return }
            if (!continuous) active = false
            listener.onFinal(text)
            if (continuous) retry(100)
        }

        override fun onError(error: Int) {
            listening = false
            if (!active) return
            errorsInARow++
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errorsInARow = 0
                    retry(100)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false
                    listener.onProblem("Mindmill needs the microphone permission to listen.")
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (onDevice) {
                        onDeviceFailed = true
                        dropRecognizer()
                        retry(100)
                    } else {
                        active = false
                        listener.onProblem("Speech recognition doesn't support ${locale.displayName} on this phone.")
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    retry(400)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    if (onDevice) { onDeviceFailed = true; dropRecognizer() }
                    if (errorsInARow >= 4) {
                        active = false
                        listener.onProblem("Speech recognition isn't available right now. Check the connection or download the offline language.")
                    } else retry(1000)
                }
                else -> {
                    if (errorsInARow >= 6) {
                        active = false
                        listener.onProblem("Speech recognition keeps failing (error $error).")
                    } else {
                        if (errorsInARow >= 3) dropRecognizer()
                        retry(300)
                    }
                }
            }
        }
    }
}
