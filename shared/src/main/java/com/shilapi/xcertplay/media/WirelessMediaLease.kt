package com.shilapi.xcertplay.media

/** Owns only the selected wireless phone's factory Bluetooth media connection.
 * Calls are serialized by the guard's worker, including session replacement and restoration.
 */
internal class WirelessMediaLease(
    private val phone: String,
    private val link: Link,
    private val report: (String) -> Unit,
) {
    interface Link {
        /** Null means disconnected; failures must throw rather than masquerade as disconnection. */
        fun connectedAddress(): String?
        fun disconnect(address: String): Boolean
        fun connect(address: String): Boolean
    }

    private var owner: Any? = null
    private var released = false
    private var closed = false

    fun activate(session: Any) {
        if (closed) return
        owner = session
        refresh()
    }

    fun refresh() {
        if (closed || owner == null) return
        val connected = link.connectedAddress() ?: return
        if (!connected.equals(phone, ignoreCase = true)) return
        if (link.disconnect(phone)) {
            released = true
            report("selected phone Bluetooth media released; HFP and pairing unchanged")
        } else {
            report("selected phone Bluetooth media release rejected")
        }
    }

    fun end(session: Any) {
        // A late end from the previous AirPlay session must not restore media under its successor.
        if (owner !== session) return
        owner = null
        restore()
    }

    fun close() {
        closed = true
        owner = null
        restore()
    }

    private fun restore() {
        if (!released) return
        val connected = link.connectedAddress()
        if (connected != null) {
            // Respect a user's switch to another phone; never replace that connection on teardown.
            released = false
            report("Bluetooth media restoration unnecessary; a phone is already connected")
        } else if (link.connect(phone)) {
            released = false
            report("selected phone Bluetooth media restoration accepted")
        } else {
            report("selected phone Bluetooth media restoration rejected; retry on close")
        }
    }
}
