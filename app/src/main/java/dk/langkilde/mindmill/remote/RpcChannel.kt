package dk.langkilde.mindmill.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Matches each answer to its request by `id`, and hands events and binary frames to whoever listens.
 * The connection sets [sender] while a socket is open.
 */
class RpcChannel(private val defaultTimeoutMs: Long = 30_000) {
    @Volatile var sender: ((String) -> Boolean)? = null
    @Volatile var binarySender: ((ByteArray) -> Boolean)? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong(1)

    private val _events = MutableSharedFlow<RemoteMessage.Event>(extraBufferCapacity = 512)
    val events: SharedFlow<RemoteMessage.Event> = _events

    private val _frames = MutableSharedFlow<BinaryFrame>(extraBufferCapacity = 1024)
    val frames: SharedFlow<BinaryFrame> = _frames

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(RemoteMessage.Event) -> Unit>()
    private val frameListeners = java.util.concurrent.CopyOnWriteArrayList<(BinaryFrame) -> Unit>()

    fun addEventListener(l: (RemoteMessage.Event) -> Unit) { listeners += l }
    fun removeEventListener(l: (RemoteMessage.Event) -> Unit) { listeners -= l }
    fun addFrameListener(l: (BinaryFrame) -> Unit) { frameListeners += l }
    fun removeFrameListener(l: (BinaryFrame) -> Unit) { frameListeners -= l }

    val isOpen get() = sender != null

    suspend fun call(method: String, params: JsonObject = EmptyJson, timeoutMs: Long = defaultTimeoutMs): JsonObject {
        val send = sender ?: throw RemoteException(ErrorCodes.OFFLINE, OFFLINE_MESSAGE)
        val id = nextId.getAndIncrement().toString()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            if (!send(RemoteMessage.Request(id, method, params).encode())) {
                throw RemoteException(ErrorCodes.OFFLINE, OFFLINE_MESSAGE)
            }
            return withTimeout(timeoutMs) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            throw RemoteException(ErrorCodes.TIMEOUT, "The Mac didn't answer in time.")
        } finally {
            pending.remove(id)
        }
    }

    fun sendFrame(frame: BinaryFrame): Boolean = binarySender?.invoke(frame.encode()) ?: false

    fun onText(text: String) {
        when (val m = RemoteMessage.decode(text)) {
            is RemoteMessage.Response -> pending.remove(m.id)?.complete(m.result as? JsonObject ?: EmptyJson)
            is RemoteMessage.Failure -> pending.remove(m.id)?.completeExceptionally(RemoteException(m.code, m.message))
            is RemoteMessage.Event -> {
                listeners.forEach { runCatching { it(m) } }
                _events.tryEmit(m)
            }
            is RemoteMessage.Request, null -> Unit
        }
    }

    fun onBinary(bytes: ByteArray) {
        val frame = BinaryFrame.decode(bytes) ?: return
        frameListeners.forEach { runCatching { it(frame) } }
        _frames.tryEmit(frame)
    }

    /** The socket closed: every waiting call fails now rather than at its timeout. */
    fun failAll(message: String = "The connection to the Mac closed before it answered.") {
        sender = null
        binarySender = null
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(RemoteException(ErrorCodes.OFFLINE, message)) }
    }

    val pendingCount get() = pending.size
}

const val OFFLINE_MESSAGE = "Can't reach the Mac right now."
