package com.shilapi.xcertplay.media

import android.os.IBinder
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** H53 Wi-Fi only. Uses the firmware's media-profile API, never the adapter or HFP switch. */
internal class GeelyWifiMediaGuard(phone: String, private val report: (String) -> Unit) : Closeable {
    private val lease = WirelessMediaLease(phone, FactoryMediaLink(), report)
    private var poll: ScheduledFuture<*>? = null
    @Volatile private var closed = false
    private var lastFailure: String? = null

    @Synchronized
    fun activate(session: Any) {
        if (closed) return
        worker.execute { if (!closed) safely { lease.activate(session) } }
        if (poll == null) {
            // Firmware may reconnect A2DP after a phone reconnect. Poll outside media/UI threads.
            poll = worker.scheduleWithFixedDelay({
                if (!closed) safely { lease.refresh() }
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun end(session: Any) {
        if (!closed) worker.execute { safely { lease.end(session) } }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        poll?.cancel(false)
        poll = null
        // One shared worker serializes an old owner's restore before a new owner's release.
        // Teardown may terminate the app process immediately afterwards. Finish the restore
        // request before returning, without waiting on the UI or media render threads.
        worker.submit { safely { lease.close() } }.get(3, TimeUnit.SECONDS)
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
            lastFailure = null
        } catch (error: Exception) {
            val failure = "${error.javaClass.simpleName}: ${error.message}"
            if (failure != lastFailure) report("Bluetooth media guard failed: $failure")
            lastFailure = failure
        }
    }

    private class FactoryMediaLink : WirelessMediaLease.Link {
        override fun connectedAddress(): String? {
            if (!call(88) { readInt() != 0 }) return null
            return call(86) { readString() }.also {
                require(it != null && ADDRESS.matches(it)) { "Invalid factory A2DP peer" }
            }
        }

        override fun disconnect(address: String): Boolean = request(91, address)
        override fun connect(address: String): Boolean = request(90, address)

        private fun request(code: Int, address: String): Boolean {
            require(ADDRESS.matches(address)) { "Invalid selected phone" }
            return call(code, address) { readInt() != 0 }
        }

        private fun <T> call(code: Int, address: String? = null, read: Parcel.() -> T): T {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "NeuC3AlfusBluetooth") as? IBinder
                ?: error("Factory Bluetooth service unavailable")
            check(binder.interfaceDescriptor == DESCRIPTOR) { "Factory Bluetooth descriptor mismatch" }
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(DESCRIPTOR)
                if (address != null) request.writeString(address)
                check(binder.transact(code, request, reply, 0)) { "Factory Bluetooth method unavailable: $code" }
                reply.readException()
                check(reply.dataAvail() >= 4) { "Missing factory Bluetooth reply" }
                return reply.read().also { check(reply.dataAvail() == 0) { "Unexpected factory Bluetooth reply" } }
            } finally {
                reply.recycle()
                request.recycle()
            }
        }
    }

    companion object {
        /** Firmware Binder transaction numbers are verified only on this exact vehicle build. */
        internal fun supportsVehicle(api: Int, board: String?, display: String?): Boolean =
            api == 18 && board == "C3ALFUS" && display == "06.03.08600.H53.00060"

        private const val DESCRIPTOR = "com.neusoft.c3alfus.bluetooth.aidl.INeuC3AlfusBluetoothService"
        private val ADDRESS = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
        private val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "geely-wifi-media").apply { isDaemon = true }
        }
    }
}
