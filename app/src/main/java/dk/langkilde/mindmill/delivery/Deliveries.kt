package dk.langkilde.mindmill.delivery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dk.langkilde.mindmill.MindmillApp
import dk.langkilde.mindmill.remote.BinaryFrame
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.RpcChannel
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.int
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.long
import dk.langkilde.mindmill.remote.string
import dk.langkilde.mindmill.remote.text
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

data class Delivery(
    val id: String,
    val scope: String?,
    val target: String?,
    val kind: String,
    val title: String,
    val url: String?,
    val size: Long?,
    /** building, ready, sent, installed or failed; null from a Mac that doesn't say. */
    val state: String? = null,
    val message: String? = null,
    /** `test.log` has what the build printed. */
    val hasLog: Boolean = false,
    /** The Build session whose version was built; null for the Mac's own copy. */
    val session: String? = null,
) {
    val isBuilding get() = state == "building"
    val hasFailed get() = state == "failed"

    companion object {
        /** A delivery this phone can use; null for one meant for another platform (an iPhone's build, TestFlight). */
        fun parse(o: JsonObject): Delivery? {
            val id = o.text("id") ?: return null
            if (!isForAndroid(o)) return null
            return Delivery(
                id = id, scope = o.string("scope"), target = o.string("target"), kind = o.string("kind") ?: "url",
                title = o.string("title") ?: "Delivery", url = o.string("url"), size = o.long("size"),
                state = o.string("state"), message = o.string("message"),
                hasLog = o.bool("hasLog") == true, session = o.text("session")?.takeIf { it.isNotBlank() },
            )
        }

        /** True unless the Mac says it's for another platform, or it's a TestFlight build. */
        fun isForAndroid(o: JsonObject): Boolean =
            o.string("kind") != "testflight" && o.string("platform").let { it == null || it == dk.langkilde.mindmill.remote.PLATFORM }
    }
}

sealed interface InstallStep {
    data object NeedsPermission : InstallStep
    data class Downloading(val bytes: Long, val of: Long?) : InstallStep
    data object Installing : InstallStep
    data object Installed : InstallStep
    data class Failed(val message: String) : InstallStep
}

/** Test deliveries from the Mac: APKs to install, links and previews to open. */
class Deliveries(private val context: Context, private val rpc: RpcChannel, private val scope: CoroutineScope) {
    private val _list = MutableStateFlow<List<Delivery>>(emptyList())
    val list: StateFlow<List<Delivery>> = _list

    private val _steps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    val steps: StateFlow<Map<String, InstallStep>> = _steps

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun merge(items: List<Delivery>) = _list.update { old ->
        (items + old).distinctBy { it.id }
    }

    fun arrived(d: Delivery) = _list.update { old -> listOf(d) + old.filter { it.id != d.id } }

    /** Another Mac is active: its builds and previews aren't this one's. */
    fun clear() {
        _list.value = emptyList()
        _steps.value = emptyMap()
    }

    fun install(d: Delivery) {
        if (!canInstall()) { set(d.id, InstallStep.NeedsPermission); return }
        scope.launch { download(d) }
    }

    private suspend fun download(d: Delivery) {
        set(d.id, InstallStep.Downloading(0, d.size))
        val dir = File(context.cacheDir, "deliveries").apply { mkdirs() }
        val file = File(dir, "${d.id.filter { it.isLetterOrDigit() || it == '-' }}.apk")
        val early = java.util.Collections.synchronizedList(mutableListOf<BinaryFrame>())
        var assembler: DeliveryAssembler? = null
        val result = CompletableDeferred<DeliveryAssembler.Progress>()
        val lock = Any()
        var lastProgress = 0L
        fun feed(frame: BinaryFrame) {
            val a = assembler ?: run { early += frame; return }
            when (val p = a.add(frame)) {
                is DeliveryAssembler.Progress.Receiving -> if (p.bytes - lastProgress > 256 * 1024) {
                    lastProgress = p.bytes
                    set(d.id, InstallStep.Downloading(p.bytes, d.size))
                }
                else -> result.complete(p)
            }
        }
        val listener: (BinaryFrame) -> Unit = { f ->
            if (f.kind == "file" && f.header.string("delivery") == d.id) synchronized(lock) { feed(f) }
        }
        rpc.addFrameListener(listener)
        try {
            file.outputStream().buffered().use { out ->
                val info = rpc.call("test.download", jsonOf("delivery" to d.id), 60_000)
                val sha = info.string("sha256") ?: throw RemoteException("failed", "The Mac didn't send the file's SHA-256.")
                synchronized(lock) {
                    assembler = DeliveryAssembler(d.id, sha, info.long("size"), info.int("chunks"), out)
                    early.toList().also { early.clear() }.forEach { feed(it) }
                }
                val outcome = withTimeoutOrNull(10 * 60_000) {
                    var seen = -1L
                    var got: DeliveryAssembler.Progress? = null
                    while (got == null) {
                        got = withTimeoutOrNull(30_000) { result.await() }
                        if (got == null) {
                            val now = synchronized(lock) { assembler?.bytesReceived ?: 0 }
                            if (now == seen) got = DeliveryAssembler.Progress.Failed("The download stalled.")
                            seen = now
                        }
                    }
                    got
                } ?: DeliveryAssembler.Progress.Failed("The download took too long.")
                if (outcome is DeliveryAssembler.Progress.Failed) throw RemoteException("failed", outcome.reason)
            }
            set(d.id, InstallStep.Installing)
            withContext(Dispatchers.IO) { ApkInstaller.install(context, file, d.id) }
        } catch (e: Exception) {
            file.delete()
            set(d.id, InstallStep.Failed(e.userMessage()))
            report(d.id, false, e.userMessage())
        } finally {
            rpc.removeFrameListener(listener)
        }
    }

    fun installFinished(delivery: String, ok: Boolean, message: String?) {
        set(delivery, if (ok) InstallStep.Installed else InstallStep.Failed(message ?: "The install didn't finish."))
        File(File(context.cacheDir, "deliveries"), "${delivery.filter { it.isLetterOrDigit() || it == '-' }}.apk").delete()
        report(delivery, ok, message)
    }

    fun permissionGranted(delivery: String) {
        if (_steps.value[delivery] == InstallStep.NeedsPermission && canInstall()) {
            _list.value.firstOrNull { it.id == delivery }?.let(::install)
        }
    }

    private fun report(delivery: String, ok: Boolean, message: String?) {
        scope.launch {
            runCatching { rpc.call("test.installed", jsonOf("delivery" to delivery, "ok" to ok, "message" to message)) }
        }
    }

    private fun set(id: String, step: InstallStep) = _steps.update { it + (id to step) }

    /** One file of a preview page or a review card's image, from `preview.get`. */
    suspend fun previewFile(delivery: String, path: String): Pair<String, ByteArray> {
        val r = rpc.call("preview.get", jsonOf("delivery" to delivery, "path" to path), 20_000)
        val bytes = Base64.getMimeDecoder().decode(r.string("base64").orEmpty())
        return (r.string("mime") ?: "application/octet-stream") to bytes
    }
}

object ApkInstaller {
    const val EXTRA_DELIVERY = "delivery"

    fun install(context: Context, file: File, delivery: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("delivery.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(EXTRA_DELIVERY, delivery)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(pending.intentSender)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MindmillApp
        val delivery = intent.getStringExtra(ApkInstaller.EXTRA_DELIVERY) ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> app.deliveries.installFinished(delivery, true, null)
            else -> app.deliveries.installFinished(
                delivery, false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The install was cancelled or failed.",
            )
        }
    }
}

/**
 * Serves `https://preview.mindmill/<delivery>/<path>` from the Mac with `preview.get`, query included (a backend
 * preview's "try a call" panel asks for `try?method=…&path=…&body=…`). Everything else loads normally.
 */
class PreviewWebViewClient(private val deliveries: Deliveries) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val (delivery, path) = request?.url?.toString()?.let(::split) ?: return null
        return try {
            val (mime, bytes) = runBlocking { withTimeout(25_000) { deliveries.previewFile(delivery, path) } }
            val base = mime.substringBefore(';').trim()
            val charset = if (base.startsWith("text/") || base.contains("javascript") || base.contains("json")) "utf-8" else null
            WebResourceResponse(base, charset, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val PREVIEW_HOST = "preview.mindmill"
        fun url(delivery: String, path: String = "index.html") = "https://$PREVIEW_HOST/$delivery/$path"

        /** The delivery and the `preview.get` path of a preview URL, or null when it isn't one. */
        fun split(url: String): Pair<String, String>? {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (u.host != PREVIEW_HOST) return null
            val segments = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            val delivery = segments.firstOrNull() ?: return null
            val path = segments.drop(1).joinToString("/") { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                .ifEmpty { "index.html" }
            return delivery to path + (u.rawQuery?.let { "?$it" } ?: "")
        }
    }
}
