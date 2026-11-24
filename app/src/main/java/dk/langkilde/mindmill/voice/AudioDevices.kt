package dk.langkilde.mindmill.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun cleanupEffects(sessionId: Int): List<AudioEffect> = buildList {
    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; add(it) }
    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.let { it.enabled = true; add(it) }
}

@SuppressLint("MissingPermission")
private fun openMic(context: Context, rate: Int, frameSamples: Int): AudioRecord? {
    if (!hasMic(context)) return null
    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameSamples * 2 * 8),
        )
    }.getOrNull() ?: return null
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
    return rec
}

/** Text to speech that tells when each utterance has been spoken. */
class Speaker(context: Context, private val onDone: (String) -> Unit, private val onProblem: (String) -> Unit) :
    TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false
    private val waiting = mutableListOf<Pair<String, String>>()
    private var rate = 1f
    private var locale: Locale = Locale.getDefault()
    private var voiceHint: String? = null
    var usage: Int = AudioAttributes.USAGE_ASSISTANT
        set(value) { field = value; if (ready) applyAttributes() }

    override fun onInit(status: Int) {
        main.post {
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                onProblem("Text to speech isn't available on this phone.")
                waiting.forEach { onDone(it.second) }
                waiting.clear()
                return@post
            }
            ready = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { utteranceId?.let { main.post { onDone(it) } } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let { main.post { onDone(it) } } }
                override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { main.post { onDone(it) } } }
            })
            applySettings()
            waiting.forEach { (text, id) -> speakNow(text, id) }
            waiting.clear()
        }
    }

    fun configure(rate: Float, locale: Locale, voiceHint: String?) {
        this.rate = rate
        this.locale = locale
        this.voiceHint = voiceHint
        if (ready) applySettings()
    }

    fun speak(text: String, id: String) {
        when {
            failed -> onDone(id)
            !ready -> waiting += text to id
            else -> speakNow(text, id)
        }
    }

    fun stop() {
        waiting.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speakNow(text: String, id: String) {
        val r = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) onDone(id)
    }

    private fun applySettings() {
        tts.setSpeechRate(rate)
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        voiceHint?.lowercase()?.let { hint ->
            runCatching { tts.voices?.firstOrNull { it.locale.language == locale.language && hint in it.name.lowercase() } }
                .getOrNull()?.let { tts.voice = it }
        }
        applyAttributes()
    }

    private fun applyAttributes() {
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
    }
}

/** Watches the microphone while the phone speaks, and calls [onSpeech] once the user starts talking over it. */
class BargeInMonitor(private val context: Context, private val onSpeech: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ watch() }, "barge-in").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(250)
        thread = null
    }

    private fun watch() {
        val frame = 320
        val rec = openMic(context, 16_000, frame) ?: run { running = false; return }
        val effects = cleanupEffects(rec.audioSessionId)
        val vad = EnergyVad(frameMs = 20)
        val buf = ShortArray(frame)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                if (vad.feed(buf, n)) {
                    running = false
                    main.post(onSpeech)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop() }
            rec.release()
            effects.forEach { it.release() }
        }
    }
}

/** Audio focus for a conversation, and routing to a Bluetooth headset or car while it lasts. */
class AudioRouting(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var commDeviceSet = false
    private var scoStarted = false

    var routedTo: String? = null
        private set

    fun acquireFocus(onLoss: () -> Unit, onGain: () -> Unit): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onLoss()
                    AudioManager.AUDIOFOCUS_GAIN -> onGain()
                }
            }, main)
            .build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** True when a headset or car took the conversation. */
    @Suppress("DEPRECATION")
    fun routeToHeadset(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val wanted = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            val device = am.availableCommunicationDevices.firstOrNull { it.type in wanted } ?: return false
            commDeviceSet = runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
            if (commDeviceSet) routedTo = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            return commDeviceSet
        }
        val hasSco = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (!hasSco || !am.isBluetoothScoAvailableOffCall) return false
        runCatching {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            scoStarted = true
            routedTo = "Bluetooth"
        }
        return scoStarted
    }

    @Suppress("DEPRECATION")
    fun clearRoute() {
        if (commDeviceSet && Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        if (scoStarted) runCatching {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        }
        commDeviceSet = false
        scoStarted = false
        routedTo = null
    }
}

/** The phone's half of the keyless engine: recogniser, speech, barge-in, focus and routing. */
class AndroidKeylessDevice(
    private val context: Context,
    private val routeToHeadset: () -> Boolean,
) : KeylessDevice {
    override var events: DeviceEvents? = null
    private var locale: Locale = Locale.getDefault()
    private val routing = AudioRouting(context)
    private val recognizer = ContinuousRecognizer(context, object : ContinuousRecognizer.Listener {
        override fun onPartial(text: String) { events?.partial(text) }
        override fun onFinal(text: String) { events?.heard(text) }
        override fun onProblem(message: String) { events?.deviceProblem(message) }
    })
    private val speaker = Speaker(context, { events?.utteranceDone(it) }, { events?.deviceProblem(it) })
    private val bargeIn = BargeInMonitor(context) { events?.userStartedTalking() }

    val routedTo: String? get() = routing.routedTo

    override fun configure(rate: Float, locale: Locale, voiceHint: String?) {
        this.locale = locale
        speaker.configure(rate, locale, voiceHint)
    }

    override fun begin() {
        if (!routing.acquireFocus(onLoss = { events?.focusLost() }, onGain = { events?.focusGained() })) {
            events?.deviceProblem("Another app is using audio. Mindmill will talk once it's free.")
        }
        val routed = routeToHeadset() && routing.routeToHeadset()
        speaker.usage = if (routed) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
    }

    override fun finish() {
        bargeIn.stop()
        recognizer.stop()
        speaker.stop()
        routing.abandonFocus()
        routing.clearRoute()
    }

    override fun startListening() {
        bargeIn.stop()
        recognizer.start(locale)
    }

    override fun stopListening() = recognizer.stop()
    override fun speak(text: String, utteranceId: String) = speaker.speak(text, utteranceId)
    override fun stopSpeaking() = speaker.stop()
    override fun startBargeInWatch() = bargeIn.start()
    override fun stopBargeInWatch() = bargeIn.stop()
}

/** The API engine's audio: 40 ms frames of 16 kHz PCM from the microphone, 24 kHz PCM from the Mac to the speaker. */
class AndroidApiAudio(private val context: Context) : ApiAudio {
    @Volatile private var streaming = false
    private var micThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var playing = false

    override fun startStreaming(onFrame: (ByteArray) -> Unit) {
        if (streaming) return
        streaming = true
        micThread = Thread({
            val frame = 640
            val rec = openMic(context, 16_000, frame) ?: run { streaming = false; return@Thread }
            val effects = cleanupEffects(rec.audioSessionId)
            val shorts = ShortArray(frame)
            try {
                rec.startRecording()
                while (streaming) {
                    var filled = 0
                    while (filled < frame && streaming) {
                        val n = rec.read(shorts, filled, frame - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled == 0) continue
                    val bytes = ByteBuffer.allocate(filled * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until filled) bytes.putShort(shorts[i])
                    onFrame(bytes.array())
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec.stop() }
                rec.release()
                effects.forEach { it.release() }
            }
        }, "voice-mic").apply { start() }
        startPlayer()
    }

    override fun stopStreaming() {
        streaming = false
        micThread?.join(300)
        micThread = null
        stopPlayer()
    }

    override fun play(pcm24k: ByteArray) {
        if (track == null) startPlayer()
        queue.offer(pcm24k)
    }

    override fun clear() {
        queue.clear()
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
    }

    private fun startPlayer() {
        if (track != null) return
        val rate = 24_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        playing = true
        playThread = Thread({
            while (playing) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                runCatching { t.write(chunk, 0, chunk.size) }
            }
        }, "voice-play").apply { start() }
    }

    private fun stopPlayer() {
        playing = false
        playThread?.join(300)
        playThread = null
        queue.clear()
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
