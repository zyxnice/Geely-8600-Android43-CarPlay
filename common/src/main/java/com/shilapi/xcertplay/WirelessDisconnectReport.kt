package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.orchestration.WirelessDisconnectObservation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Small, app-private incident history. Disk writes and Android service queries use one worker. */
internal object WirelessDisconnectReport {
    private const val MAX_RECORDS = 24
    private const val FILE_NAME = "wifi-disconnects.jsonl"
    private const val SNAPSHOT_TIMEOUT_MS = 750L
    private const val MIB = 1024 * 1024L
    private val fileLock = Any()
    private val safeInterfaceName = Regex("[a-zA-Z0-9_.-]{1,32}")
    @Volatile private var lastStorageError: String? = null
    // A wedged Android service must not prevent the writer from saving the connection evidence.
    private val snapshotExecutor = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { task -> Thread(task, "diplay-wifi-state").apply { isDaemon = true } }

    private data class Pending(
        val context: Context,
        val observation: WirelessDisconnectObservation,
        val mainFrames: Long,
        val usedHeapBytes: Long,
    )

    private val worker = lazy {
        BoundedDiagnosticWriter<Pending>(maxPending = MAX_RECORDS, threadName = "diplay-wifi-disconnect") { pending ->
            runCatching { append(pending.context.filesDir, record(pending)) }.onFailure {
                lastStorageError = it.javaClass.simpleName
            }
        }
    }

    fun enqueue(context: Context, observation: WirelessDisconnectObservation, mainFrames: Long) {
        val runtime = Runtime.getRuntime()
        worker.value.enqueue(Pending(
            context.applicationContext, observation, mainFrames, runtime.totalMemory() - runtime.freeMemory(),
        ))
    }

    fun appendTo(report: StringBuilder, context: Context) {
        report.appendLine("--- Wi-Fi CarPlay disconnect observations (JSON lines) ---")
        if (worker.isInitialized()) {
            val writer = worker.value
            if (!writer.awaitIdle(1500)) report.appendLine("Snapshot writer still busy. Export again for the latest incident.")
            if (writer.droppedCount > 0) report.appendLine("Queued snapshots dropped: ${writer.droppedCount}")
        }
        lastStorageError?.let { report.appendLine("Last snapshot write error: $it") }
        runCatching {
            synchronized(fileLock) { read(file(context.filesDir)) }
        }.onSuccess { records ->
            if (records.isEmpty()) report.appendLine("No Wi-Fi disconnect observations saved.")
            else records.forEach { report.appendLine(it) }
        }.onFailure { report.appendLine("Could not read incident history: ${it.javaClass.simpleName}") }
        report.appendLine()
    }

    internal fun append(directory: File, record: JSONObject) = synchronized(fileLock) {
        val target = file(directory)
        val parent = target.baseFile.parentFile ?: throw IOException("Incident directory unavailable")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Incident directory unavailable")
        }
        val updated = (read(target) + record.toString()).takeLast(MAX_RECORDS)
        val output = target.startWrite()
        try {
            output.write(updated.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Exception) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun file(directory: File): AtomicFile = AtomicFile(File(directory, "logs/$FILE_NAME"))

    private fun read(target: AtomicFile): List<String> = try {
        target.openRead().bufferedReader(Charsets.UTF_8).use { it.readLines().takeLast(MAX_RECORDS) }
    } catch (error: FileNotFoundException) {
        if (target.baseFile.exists()) throw error
        emptyList()
    }

    private fun record(pending: Pending): JSONObject {
        val event = pending.observation
        val observedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(event.observedAtMillis))
        return JSONObject().apply {
            put("schema", 1)
            put("session", event.sessionId)
            put("generation", event.generation)
            put("observedAtUtc", observedAt)
            put("observedAtEpochMs", event.observedAtMillis)
            put("observedAtElapsedMs", event.observedElapsedMillis)
            put("connectedMs", event.connectedMillis)
            put("androidApi", Build.VERSION.SDK_INT)
            put("firmware", Build.DISPLAY)
            put("source", event.evidence.source.name)
            put("observedReason", event.evidence.reason.name)
            put("exceptionType", event.evidence.exceptionType ?: JSONObject.NULL)
            put("mainFramesSubmittedAtEvent", pending.mainFrames)
            put("appHeapUsedMiBAtEvent", pending.usedHeapBytes / MIB)
            put("hotspotMode", event.hotspotMode.name)
            put("factoryHotspot", event.factoryHotspot)
            put("hotspotBackend", event.hotspot?.backend?.name ?: JSONObject.NULL)
            put("negotiatedChannel", event.hotspot?.channel ?: JSONObject.NULL)
            put("negotiatedFrequencyMHz", event.hotspot?.frequencyMHz ?: JSONObject.NULL)
            put("handoffRequested", event.handoffRequested)
            put("tunnelReady", event.tunnelReady)
            put("handoffComplete", event.handoffComplete)
            put("bluetoothBootstrapOpen", event.bluetoothBootstrapOpen)
            put("systemSnapshot", collectSnapshot(pending))
        }
    }

    private fun collectSnapshot(pending: Pending): JSONObject {
        val future = try {
            snapshotExecutor.submit<JSONObject> { readSystemSnapshot(pending) }
        } catch (_: RejectedExecutionException) {
            return JSONObject().put("status", "BUSY")
        }
        return try {
            future.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            JSONObject().put("status", "TIMED_OUT")
        } catch (error: Exception) {
            JSONObject().put("status", "UNAVAILABLE").put("exceptionType", error.javaClass.simpleName)
        }
    }

    private fun readSystemSnapshot(pending: Pending): JSONObject {
        val event = pending.observation
        val context = pending.context
        val sampledAt = SystemClock.elapsedRealtime()
        val interfaceName = event.hotspot?.interfaceName?.takeIf(safeInterfaceName::matches)
        val networkInterface = interfaceName?.let { name -> runCatching { NetworkInterface.getByName(name) } }
        val network = networkInterface?.getOrNull()
        val interfaceUp = runCatching { network?.isUp }.getOrNull()
        val expected = event.hotspot?.expectedAddress
        val addressAssigned = if (expected != null) runCatching {
            network?.inetAddresses?.toList()?.contains(expected) ?: if (networkInterface?.isSuccess == true) false else null
        }.getOrNull() else null
        val hotspotState = if (event.hotspotMode == WirelessHotspotMode.WIFI_P2P) null
            else CarHotspotTools.readState(context)?.name
        val wifiState = runCatching {
            (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.wifiState
        }.getOrNull()
        @Suppress("DEPRECATION")
        val defaultNetwork = runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetworkInfo
        }.getOrNull()
        val memory = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }
        }.getOrNull()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        return JSONObject().apply {
            put("status", "COLLECTED")
            put("appVersion", appVersion ?: JSONObject.NULL)
            put("delayMs", (sampledAt - event.observedElapsedMillis).coerceAtLeast(0))
            put("readDurationMs", (SystemClock.elapsedRealtime() - sampledAt).coerceAtLeast(0))
            put("hotspotState", hotspotState ?: JSONObject.NULL)
            put("wifiClientStateCode", wifiState ?: JSONObject.NULL)
            put("interface", interfaceName ?: JSONObject.NULL)
            put("interfacePresent", networkInterface?.takeIf { it.isSuccess }?.let { network != null } ?: JSONObject.NULL)
            put("interfaceUp", interfaceUp ?: JSONObject.NULL)
            put("expectedAddressAssigned", addressAssigned ?: JSONObject.NULL)
            @Suppress("DEPRECATION")
            put("defaultNetworkType", defaultNetwork?.type ?: JSONObject.NULL)
            @Suppress("DEPRECATION")
            put("defaultNetworkConnected", defaultNetwork?.isConnected ?: JSONObject.NULL)
            put("availableRamMiB", memory?.availMem?.div(MIB) ?: JSONObject.NULL)
            put("lowMemory", memory?.lowMemory ?: JSONObject.NULL)
        }
    }
}
