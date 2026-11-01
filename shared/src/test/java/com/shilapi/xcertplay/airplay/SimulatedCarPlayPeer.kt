package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.compat.GeelyMediaOptions
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Test-only iPhone peer using real control/event/screen/audio sockets and production codecs.
 * Starts at an explicitly seeded post-pairing boundary: no Apple identity, MFi or USB is simulated.
 * Synthetic NAL markers check transport integrity; they are not decodable H264 pictures.
 */
internal class SimulatedCarPlayPeer : Closeable {
    val frames = LinkedBlockingQueue<ByteArray>()
    val audio = LinkedBlockingQueue<ByteArray>()
    val configurations = LinkedBlockingQueue<ByteArray>()
    val active = LinkedBlockingQueue<Boolean>()
    val commands = LinkedBlockingQueue<String>()
    var requestKeyFrame: (() -> Unit)? = null
        private set
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val sockets = mutableListOf<Socket>()
    private val control: Socket
    private val controlReader: WireReader
    private var sequence = 0
    private val audioCounters = mutableMapOf<Long, Long>()
    val session: AirPlaySession

    init {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted: Socket
        try {
            control = Socket(InetAddress.getLoopbackAddress(), server.localPort).also {
                it.soTimeout = 4000
                sockets.add(it)
            }
            accepted = server.accept()
        } finally { server.close() }
        val sink = object : MediaSink {
            override fun onVideoConfig(type: Int, codecData: ByteArray) { configurations.offer(codecData) }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames.offer(naluBytes) }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                audio.offer(rtp)
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) { this@SimulatedCarPlayPeer.active.offer(active) }
            override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { requestKeyFrame = handler }
        }
        session = AirPlaySession(
            socket = accepted,
            config = AirPlayConfig(
                deviceName = "local-simulation", deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01", sourceVersion = "simulation",
                main = AirPlayDisplayConfig(1920, 720),
                mediaPerformance = GeelyMediaOptions(stage = "B"),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { commands.offer("RECORD") }
            },
            media = CarPlayMediaEngine(sink),
        )
        // Same fixture boundary as CarPlayScreenReplacementTest, never shipped in the application.
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        val hostRead = derive("simulation-control", "host-read")
        val hostWrite = derive("simulation-control", "host-write")
        session.javaClass.getDeclaredField("cipher").apply { isAccessible = true }
            .set(session, ControlCipher(hostRead, hostWrite))
        controlReader = WireReader(control, ControlCipher(hostWrite, hostRead))
        session.start()
    }

    /** Real encrypted RTSP SETUP/RECORD/TEARDOWN, with explicit status checking. */
    fun request(method: String, body: Map<String, Any?> = emptyMap()): Map<*, *> {
        val payload = BplistCodec.encode(body)
        val header = "$method /simulation RTSP/1.0\r\nCSeq: ${++sequence}\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${payload.size}\r\n\r\n"
        controlReader.write(header.toByteArray(Charsets.US_ASCII) + payload)
        val response = controlReader.read()
        check(response.path == "200") { "Simulated $method failed: ${response.path}" }
        check(response.headers["cseq"] == sequence.toString()) { "Mismatched control response" }
        return if (response.body.isEmpty()) emptyMap<Any, Any>() else BplistCodec.decode(response.body) as Map<*, *>
    }

    fun openEvents(): WireReader {
        val response = request("SETUP", mapOf("name" to "simulated-phone"))
        val socket = connect((response["eventPort"] as Number).toInt())
        val reader = WireReader(socket, ControlCipher(
            derive("Events-Salt", "Events-Write-Encryption-Key"),
            derive("Events-Salt", "Events-Read-Encryption-Key"),
        ))
        // A phone-originated command and its response fence event initialization without sleeps.
        reader.write("POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
        check(reader.read().path == "200")
        return reader
    }

    fun openScreen(connectionId: Long): ScreenSender {
        val response = request("SETUP", mapOf("streams" to listOf(mapOf(
            "type" to 110, "streamConnectionID" to connectionId,
        ))))
        val stream = (response["streams"] as List<*>).single() as Map<*, *>
        return ScreenSender(connect((stream["dataPort"] as Number).toInt()), mediaKey(connectionId))
    }

    fun audioPort(connectionId: Long): Int {
        val response = request("SETUP", mapOf("streams" to listOf(mapOf(
            "type" to 100, "audioType" to "media", "audioFormat" to 0x8000L,
            "streamConnectionID" to connectionId,
        ))))
        val stream = (response["streams"] as List<*>).single() as Map<*, *>
        return (stream["dataPort"] as Number).toInt()
    }

    fun sendAudio(port: Int, connectionId: Long, sample: Int, payload: ByteArray) {
        // One counter per stream key; never reuse a media nonce for a subsequent packet.
        val counter = audioCounters.getOrDefault(connectionId, 1L)
        audioCounters[connectionId] = counter + 1
        val header = byteArrayOf(0x80.toByte(), 96, (counter ushr 8).toByte(), counter.toByte(),
            (sample ushr 24).toByte(), (sample ushr 16).toByte(), (sample ushr 8).toByte(), sample.toByte(),
            0, 0, 0, 1)
        val nonce = AirPlayCrypto.nonce64(counter)
        val sealed = AirPlayCrypto.chachaSeal(mediaKey(connectionId), nonce, payload, header.copyOfRange(4, 12))
        val wire = header + sealed + nonce.takeLast(8).toByteArray()
        DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), port)) }
    }

    private fun mediaKey(connectionId: Long) = derive("DataStream-Salt$connectionId", "DataStream-Output-Encryption-Key")
    private fun derive(salt: String, label: String) = AirPlayCrypto.hkdfSha512(secret, salt.toByteArray(), label.toByteArray())
    private fun connect(port: Int) = Socket(InetAddress.getLoopbackAddress(), port).also {
        it.soTimeout = 4000
        sockets.add(it)
    }

    override fun close() {
        session.close()
        sockets.forEach { runCatching { it.close() } }
    }

    class ScreenSender(private val socket: Socket, private val key: ByteArray) {
        private var counter = 0L
        fun config() {
            val body = byteArrayOf(1, 0, 0, 0, -1, 1, 0, 1, 0x67, 1)
            write(header(body.size, 1) + body, false)
        }
        fun frame(marker: Int, fragmented: Boolean = false, corrupt: Boolean = false) {
            val body = byteArrayOf(0, 0, 0, 2, 0x65, marker.toByte())
            val header = header(body.size + 16, 0)
            val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter++), body, header)
            if (corrupt) sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
            write(header + sealed, fragmented)
        }
        private fun write(wire: ByteArray, fragmented: Boolean) {
            val output = socket.getOutputStream()
            if (fragmented) {
                // Deliberately split header/body; TCP is free to coalesce writes again.
                for (offset in wire.indices step 7) output.write(wire, offset, minOf(7, wire.size - offset))
            } else output.write(wire)
            output.flush()
        }
        private fun header(size: Int, opcode: Int) = ByteArray(128).also {
            for (index in 0..3) it[index] = (size ushr (index * 8)).toByte()
            it[4] = opcode.toByte()
        }
    }

    class WireReader(private val socket: Socket, private val cipher: ControlCipher) {
        private var encrypted = ByteArray(0)
        private var plaintext = ByteArray(0)
        private val pending = ArrayDeque<RtspMessage.Request>()
        fun write(wire: ByteArray) { socket.getOutputStream().apply { write(cipher.encrypt(wire)); flush() } }
        fun read(): RtspMessage.Request {
            while (pending.isEmpty()) {
                val buffer = ByteArray(4096)
                val count = socket.getInputStream().read(buffer)
                check(count >= 0) { "Peer closed before expected message" }
                val decrypted = cipher.decrypt(encrypted + buffer.copyOf(count))
                encrypted = decrypted.rest
                val parsed = RtspMessage.parseMessages(plaintext + decrypted.data)
                plaintext = parsed.rest
                pending.addAll(parsed.messages)
            }
            return pending.removeFirst()
        }
        fun command(): Map<*, *> {
            val message = read()
            write(RtspMessage.buildResponse(message, RtspMessage.Response(status = 200)))
            return BplistCodec.decode(message.body) as Map<*, *>
        }
    }
}
