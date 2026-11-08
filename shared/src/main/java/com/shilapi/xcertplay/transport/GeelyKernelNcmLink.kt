package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.shilapi.xcertplay.compat.GeelyUsbDeviceAccess
import com.shilapi.xcertplay.compat.GeelyUsbDescriptorParser
import com.shilapi.xcertplay.compat.GeelyUsbIdentity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only binding to the existing H53 cdc_ncm driver. The application never changes interface
 * flags, IPv6 settings, USB configuration, routes or DNS through this owner.
 *
 * Discovery/address waits run off-main. Closing stops this binding, leaving the system NIC intact.
 */
class GeelyKernelNcmLink internal constructor(
    private val initial: KernelNcmInterface,
    private val dataInterfaceId: Int,
    private val dataAlternate: Int,
    private val io: KernelNcmIo,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val mac = initial.mac.copyOf()
    private var attachmentTxPackets: Long? = null
    @Volatile private var attachedAddress: Inet6Address? = null

    /** Linux netdev name, pinned together with ifindex and complete USB enumeration identity. */
    val interfaceName: String get() = initial.name
    /** The kernel NIC's six-byte Ethernet address; each caller receives a copy. */
    val hostMac: ByteArray get() = mac.copyOf()

    /** Wait only for an existing, non-tentative link-local address; never prepare the system NIC. */
    fun awaitAddress(isCancelled: () -> Boolean, timeoutMillis: Long = 2_000): Inet6Address {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (isCancelled() || closed.get()) throw IOException("Kernel USB binding cancelled")
            requireCurrent()
            if (!isUp()) throw IphoneUsbException.DeviceUnavailable("Kernel USB network is down; manual preparation is required")
            currentAddress()?.let { return it }
            try { Thread.sleep(50) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted waiting for kernel USB IPv6", error)
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IphoneUsbException.DeviceUnavailable("Kernel USB network has no usable link-local IPv6 address")
    }

    /** Service takes one attachment: pin its address and successful-TX baseline before listening. */
    @Synchronized
    fun beginAttachment(address: Inet6Address) {
        requireCurrent()
        check(attachmentTxPackets == null) { "Kernel USB binding already attached" }
        val available = currentAddress()
        require(available != null && available.address.contentEquals(address.address) &&
            address.scopeId == initial.ifindex) { "Kernel USB address does not belong to the pinned interface" }
        check(isUp()) { "Kernel USB network is down" }
        attachmentTxPackets = txPackets()
        attachedAddress = address
    }

    /** A current-generation successful USB TX increment, not carrier/up or historical traffic. */
    @Synchronized
    fun isOutputReady(): Boolean {
        val baseline = attachmentTxPackets ?: return false
        if (!isAttachedCurrent()) return false
        return try { txPackets() > baseline } catch (_: IOException) { false }
    }

    /** Used by the service's generation-fenced monitor, including after the first TCP is accepted. */
    fun isAttachedCurrent(): Boolean {
        val address = attachedAddress ?: return false
        if (!isCurrent() || !isUp()) return false
        val current = currentAddress() ?: return false
        return current.scopeId == address.scopeId && current.address.contentEquals(address.address)
    }

    override fun close() { closed.set(true) }

    private fun requireCurrent() {
        if (!isCurrent()) throw IphoneUsbException.DeviceUnavailable("Kernel USB network enumeration changed")
    }

    private fun isCurrent(): Boolean {
        if (closed.get()) return false
        val current = io.readInterface(initial.name, dataInterfaceId) ?: return false
        if (current.identity != initial.identity || current.devicePath != initial.devicePath ||
            current.ifindex != initial.ifindex || current.driver != "cdc_ncm" ||
            current.configuration != initial.configuration || current.interfaceNumber != initial.interfaceNumber ||
            !current.mac.contentEquals(mac)) return false
        return current.dataAlternate == dataAlternate
    }

    private fun isUp(): Boolean = try {
        io.interfaceFlags(initial.name) and 1 != 0
    } catch (_: Exception) { false }

    private fun currentAddress(): Inet6Address? = try {
        usableAddress(io.ipv6Table(), initial.name, initial.ifindex)
    } catch (_: IOException) { null }

    private fun txPackets(): Long {
        return io.txPackets(initial.name).takeIf { it >= 0 } ?: throw IOException("Kernel USB TX counter unavailable")
    }

    companion object {
        private const val METADATA = "com.shilapi.xcertplay.GEELY_KERNEL_NCM"
        internal const val NET_CLASS = "/sys/class/net"
        internal const val HOST_PREFIX = "/sys/devices/platform/fsl-ehci.0/"

        fun isEnabled(context: Context): Boolean = GeelyUsbDeviceAccess.isEnabled(context) && try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getBoolean(METADATA, false) == true
        } catch (_: PackageManager.NameNotFoundException) { false }

        /** Bind the selected USB enumeration to its actual kernel-owned NCM control interface. */
        fun open(device: UsbDevice, function: NcmFunctionDiscovery.NcmFunction, descriptors: ByteArray,
                 diagnostic: (String) -> Unit): GeelyKernelNcmLink {
            val candidates = File(NET_CLASS).listFiles().orEmpty().mapNotNull {
                SystemKernelNcmIo.readInterface(it.name, function.data.id)
            }
            val selected = selectInterface(device.deviceName, device.vendorId, device.productId,
                GeelyUsbIdentity.sha256(descriptors), function.control.id, candidates)
            diagnostic("kernel NCM selected iface=${selected.name} ifindex=${selected.ifindex} " +
                "usb=${selected.identity.devicePath()} control=${selected.interfaceNumber} driver=${selected.driver}")
            return GeelyKernelNcmLink(selected, function.data.id, function.dataAlternate, SystemKernelNcmIo)
        }

        internal fun selectInterface(devicePath: String, vendorId: Int, productId: Int, descriptorSha256: String,
                                     controlInterface: Int, candidates: List<KernelNcmInterface>): KernelNcmInterface {
            val matches = candidates.filter {
                it.devicePath.startsWith(HOST_PREFIX) && it.driver == "cdc_ncm" && it.ifindex > 0 &&
                    it.identity.devicePath() == devicePath && it.identity.vendorId == vendorId &&
                    it.identity.productId == productId && it.identity.descriptorSha256 == descriptorSha256 &&
                    it.configuration == 6 && it.interfaceNumber == controlInterface
            }
            if (matches.size != 1) throw IphoneUsbException.DeviceUnavailable(
                "Expected one kernel NCM interface for the selected USB enumeration; found ${matches.size}")
            return matches.single()
        }

        internal fun usableAddress(table: String, name: String, ifindex: Int): Inet6Address? {
            for (line in table.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size != 6 || fields[5] != name || fields[1].toIntOrNull(16) != ifindex) continue
                val flags = fields[4].toIntOrNull(16) ?: continue
                // Linux IFA_F_TENTATIVE, DADFAILED and DEPRECATED cannot advertise a fresh session.
                if (flags and (0x40 or 0x08 or 0x20) != 0 || !fields[0].matches(Regex("[0-9a-fA-F]{32}"))) continue
                val bytes = ByteArray(16) { fields[0].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val address = Inet6Address.getByAddress(null, bytes, ifindex)
                if (address.isLinkLocalAddress) return address
            }
            return null
        }
    }
}

/** Immutable sysfs facts for matching one kernel NIC to one complete USB enumeration. */
internal data class KernelNcmInterface(
    /** Netdev name is never sufficient identity by itself. */
    val name: String,
    /** Canonical USB control-interface directory, below the fixed phone host controller. */
    val devicePath: String,
    /** Actual bound USB driver; this candidate requires cdc_ncm. */
    val driver: String,
    /** Bus/device/VID/PID and complete descriptor SHA pin the enumeration. */
    val identity: GeelyUsbIdentity,
    /** Active USB configuration value, not an array index. */
    val configuration: Int,
    /** NCM control bInterfaceNumber from the selected configuration. */
    val interfaceNumber: Int,
    /** Linux netdev ifindex; changes when a same-named interface is recreated. */
    val ifindex: Int,
    /** Six-byte current kernel Ethernet address, copied by the binding owner. */
    val mac: ByteArray,
    /** Currently selected alternate of the discovered NCM data interface. */
    val dataAlternate: Int,
)

/** Read-only kernel boundary. No method can bring up an interface or change its configuration. */
internal interface KernelNcmIo {
    fun readInterface(name: String, dataInterfaceId: Int): KernelNcmInterface?
    fun interfaceFlags(name: String): Int
    fun ipv6Table(): String
    fun txPackets(name: String): Long
}

private object SystemKernelNcmIo : KernelNcmIo {
    override fun readInterface(name: String, dataInterfaceId: Int): KernelNcmInterface? {
        return try {
            if (!name.matches(Regex("[a-zA-Z0-9_.:-]{1,15}"))) return null
            val directory = File(GeelyKernelNcmLink.NET_CLASS, name)
            val port = File(directory, "device").canonicalFile
            if (!port.path.startsWith(GeelyKernelNcmLink.HOST_PREFIX) || !File(port, "bInterfaceNumber").isFile) return null
            val usb = port.parentFile ?: return null
            val driver = File(port, "driver").canonicalFile.name
            if (driver != "cdc_ncm") return null
            fun text(file: File) = readKernelNcmScalar(file)
            val descriptorHash = GeelyUsbIdentity.sha256(readKernelNcmDescriptors(File(usb, "descriptors")))
            val identity = GeelyUsbIdentity(
                usb.name, text(File(usb, "busnum")).toInt(), text(File(usb, "devnum")).toInt(),
                text(File(usb, "idVendor")).toInt(16), text(File(usb, "idProduct")).toInt(16), descriptorHash,
            )
            val mac = text(File(directory, "address")).split(':').map { it.toInt(16).toByte() }.toByteArray()
            if (mac.size != 6) return null
            val configuration = text(File(usb, "bConfigurationValue")).toInt()
            val data = File(usb, "${usb.name}:$configuration.$dataInterfaceId/bAlternateSetting")
            KernelNcmInterface(name, port.path, driver, identity, configuration,
                text(File(port, "bInterfaceNumber")).toInt(16), text(File(directory, "ifindex")).toInt(), mac,
                text(data).toInt(16))
        } catch (_: Exception) { null }
    }

    override fun interfaceFlags(name: String): Int =
        readKernelNcmScalar(File(GeelyKernelNcmLink.NET_CLASS, "$name/flags")).removePrefix("0x").toInt(16)
    override fun ipv6Table(): String = File("/proc/net/if_inet6").readText()
    override fun txPackets(name: String): Long =
        readKernelNcmScalar(File(GeelyKernelNcmLink.NET_CLASS, "$name/statistics/tx_packets")).toLongOrNull()
            ?: throw IOException("Kernel USB TX counter unavailable")
}

/** Match GeelyUsbDeviceAccess's bounded ASCII sysfs reads, avoiding a Reader buffer per scalar. */
internal fun readKernelNcmScalar(file: File): String = file.inputStream().use { input ->
    val bytes = ByteArray(128)
    val count = input.read(bytes)
    if (count < 1 || count == bytes.size) throw IOException("Invalid kernel USB scalar: ${file.name}")
    String(bytes, 0, count, Charsets.US_ASCII).trim()
}

/** sysfs reports a virtual descriptor length: stream all bytes without allocating that length. */
internal fun readKernelNcmDescriptors(file: File): ByteArray = file.inputStream().use { input ->
    val output = ByteArrayOutputStream(1024)
    val bytes = ByteArray(1024)
    while (true) {
        val count = input.read(bytes)
        if (count < 0) break
        if (count == 0) continue
        if (count > GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES - output.size()) {
            throw IOException("Kernel USB descriptors exceed 1 MiB limit")
        }
        output.write(bytes, 0, count)
    }
    output.toByteArray()
}
