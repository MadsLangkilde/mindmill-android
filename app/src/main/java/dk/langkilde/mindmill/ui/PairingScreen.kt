package dk.langkilde.mindmill.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import dk.langkilde.mindmill.remote.ErrorCodes
import dk.langkilde.mindmill.remote.PairingFailed
import dk.langkilde.mindmill.remote.RemoteException
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dk.langkilde.mindmill.remote.PairingInvite
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** What went wrong pairing, with what to do next. */
private data class PairingProblem(val title: String, val text: String, val tips: List<String> = emptyList(), val scanAgain: Boolean = false)

private fun pairingProblem(e: Exception, mac: String): PairingProblem = when {
    e is PairingFailed -> PairingProblem("Can't reach $mac", e.problem.message(mac), e.problem.tips(mac))
    e is RemoteException && (e.code == ErrorCodes.BAD_CODE || e.isUnauthorized) -> PairingProblem(
        "That code didn't work",
        listOfNotNull(
            e.message?.takeIf { it.isNotBlank() },
            "A code lasts 10 minutes, works for one phone and stops after five wrong tries. On the Mac, show a new pairing code and scan it.",
        ).joinToString(" "),
        scanAgain = true,
    )
    else -> PairingProblem("Pairing didn't work", e.userMessage())
}

/**
 * Pairs with a Mac. Full screen while the phone has no Mac it can use ([reason] says why, e.g. the Mac removed the
 * phone; other paired Macs are offered to switch to). [embedded] is "Pair another Mac" from inside the app: the Macs
 * already paired stay, and the new one becomes the active one.
 */
@Composable
fun PairingScreen(reason: String?, embedded: Boolean = false, onPaired: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<PairingInvite?>(null) }
    var code by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<PairingProblem?>(null) }
    var scanProblem by remember { mutableStateOf<String?>(null) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else scanProblem = "Mindmill needs the camera to scan the code. You can paste the invite instead."
    }

    fun accept(text: String): Boolean {
        val i = PairingInvite.parse(text) ?: return false
        invite = i
        code = i.code
        scanning = false
        problem = null
        scanProblem = null
        return true
    }

    fun scanAgain() {
        invite = null
        problem = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun pair(found: PairingInvite) {
        busy = true
        problem = null
        scope.launch {
            try {
                app.connection.pair(found, code, app.deviceName())
                app.done("Paired with ${found.name}.")
                onPaired()
            } catch (e: Exception) {
                problem = pairingProblem(e, found.name)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().let { if (embedded) it else it.safeDrawingPadding().padding(horizontal = 16.dp) }
            .verticalScroll(rememberScrollState()),
    ) {
        if (!embedded) ScreenTitle("Pair with your Mac")
        reason?.let { NoticeBar(it, error = true, modifier = Modifier.padding(bottom = 8.dp)) }
        if (!embedded) OtherMacs()
        val found = invite
        if (found == null) {
            Text(
                if (embedded) {
                    "Pair this phone with another Mac. The Macs you've paired stay; switch between them from the top bar or Connection."
                } else {
                    "The phone works through Mindmill on your Mac. Pair them once; after that it connects by itself on the same " +
                        "Wi-Fi or over Tailscale."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            SectionCard("On the Mac") {
                Text("1. Open Mindmill → Settings → Remote.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Turn Remote on.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Click Show Pairing Code.", style = MaterialTheme.typography.bodyMedium)
            }
            scanProblem?.let { NoticeBar(it, error = true, onDismiss = { scanProblem = null }, modifier = Modifier.padding(vertical = 4.dp)) }
            if (scanning) {
                Text("Point the camera at the QR code.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                QrScanner(
                    onCode = { text -> if (!accept(text)) scanProblem = "That QR code isn't a Mindmill pairing code." },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 12.dp),
                )
                TextButton(onClick = { scanning = false }) { Text("Stop scanning") }
            } else {
                Button(onClick = ::scanAgain, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Scan the QR code") }
            }
            MoreOptions("Paste the invite instead") {
                OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it },
                    label = { Text("kb://pair?…") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                TextButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { pasted = it }
                }) { Text("Paste from clipboard") }
                Button(
                    onClick = { if (!accept(pasted)) scanProblem = "That isn't a Mindmill invite. It starts with kb://pair?" },
                    enabled = pasted.isNotBlank(),
                ) { Text("Use this invite") }
            }
        } else {
            SectionCard("Pairing with ${found.name}") {
                Text(
                    "Check that the 6-digit code matches the one the Mac shows, then tap Pair.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Pairing with ${found.name}…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                    }
                } else {
                    Button(
                        enabled = code.length == 6,
                        onClick = { pair(found) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("Pair") }
                }
            }
            problem?.let { p ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(p.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        p.tips.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        ChipFlow(Modifier.padding(top = 8.dp)) {
                            if (p.scanAgain) Button(onClick = ::scanAgain) { Text("Scan again") }
                            else Button(onClick = { pair(found) }, enabled = !busy) { Text("Try again") }
                            if (!p.scanAgain) TextButton(onClick = ::scanAgain) { Text("Scan again") }
                        }
                    }
                }
            }
            if (problem == null && !busy) {
                OutlinedButton(onClick = ::scanAgain, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Scan a different code") }
            }
            MoreOptions("Details") {
                Text("Addresses: ${found.hosts.joinToString(", ")}, port ${found.port}", style = MaterialTheme.typography.bodySmall)
                Text("Certificate: ${found.fingerprint.chunked(4).joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** While the active Mac needs pairing again: the other paired Macs to use instead, and removing this one. */
@Composable
private fun OtherMacs() {
    val app = LocalApp.current
    val pairings by app.connection.pairings.collectAsState()
    val active = pairings.active ?: return
    val others = pairings.macs.filter { !pairings.isActive(it) }
    if (others.isEmpty()) return
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard("Or use another Mac") {
        others.forEachIndexed { i, mac ->
            ListRow(
                mac.macName, subtitle = mac.lastHost?.let { "Last reached at $it" },
                leading = { IconBadge(Icons.Default.LaptopMac, size = 36.dp) },
                divider = i < others.lastIndex,
                below = { OutlinedButton(onClick = { app.switchMac(mac.fingerprint) }) { Text("Switch to ${mac.macName}") } },
            )
        }
        TextButton(onClick = { confirmRemove = true }) { Text("Remove ${active.macName} from this phone", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove ${active.macName}?",
            "The phone forgets ${active.macName}. To use it again, pair with a new code from that Mac." +
                (others.firstOrNull()?.let { " The phone switches to ${it.macName}." } ?: ""),
            "Remove",
            onConfirm = { app.removeMac(active.fingerprint) },
            onDismiss = { confirmRemove = false },
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var delivered by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || delivered) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val text = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.startsWith("kb://", true) } }
                            ?: codes.firstOrNull()?.rawValue
                        if (text != null && !delivered) {
                            delivered = true
                            ContextCompat.getMainExecutor(context).execute { onCode(text); delivered = false }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
