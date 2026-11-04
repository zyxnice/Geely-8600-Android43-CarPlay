package com.shilapi.xcertplay.media

import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * B-only diagnostic metadata; never used for media scheduling or the stale-frame limit.
 * All times are System.nanoTime nanoseconds on this device, not phone or wall-clock time.
 * @property streamId Process-local receive-stream generation; pair with process identity.
 * @property sequence Video access-unit ordinal, independent of the encryption nonce.
 * @property bytes Sealed message body size, excluding the 128-byte wire header.
 * @property readBeginNs Start of header read; includes idle waiting and thread scheduling.
 * @property headerDoneNs Complete header available locally.
 * @property bodyDoneNs Complete sealed body available locally.
 * @property decryptBeginNs Start of authenticated decryption.
 * @property decryptDoneNs End of authenticated decryption, before format conversion.
 */
data class VideoFrameTrace(
    val streamId: Long,
    val sequence: Long,
    val bytes: Int,
    val readBeginNs: Long,
    val headerDoneNs: Long,
    val bodyDoneNs: Long,
    val decryptBeginNs: Long,
    val decryptDoneNs: Long,
)

/** Metadata-only event. A zero PTS means this stage has no codec association. */
internal data class FrameTraceEvent(
    val stage: String,
    val frame: VideoFrameTrace?,
    val timeNs: Long,
    val ptsUs: Long = 0,
) {
    /** Writer-owned scratch space avoids one formatted String and nullable boxing per field. */
    fun appendCsv(out: StringBuilder) {
        val f = frame
        out.append(stage).append(',')
        out.append(if (f == null) 0L else f.streamId).append(',')
        out.append(if (f == null) 0L else f.sequence).append(',')
        out.append(timeNs).append(',').append(ptsUs).append(',')
        out.append(if (f == null) 0 else f.bytes).append(',')
        out.append(if (f == null) 0L else f.readBeginNs).append(',')
        out.append(if (f == null) 0L else f.headerDoneNs).append(',')
        out.append(if (f == null) 0L else f.bodyDoneNs).append(',')
        out.append(if (f == null) 0L else f.decryptBeginNs).append(',')
        out.append(if (f == null) 0L else f.decryptDoneNs).append('\n')
    }
}

/**
 * Nonblocking diagnostic admission; a full queue drops diagnostics, never video frames.
 * One background writer per traced main decoder. No media bytes, coordinates or credentials.
 * File/queue bounds intentionally make incomplete capture explicit instead of adding backpressure.
 */
internal class FrameTraceLog(
    private val file: File,
    private val status: (String) -> Unit = {},
    capacity: Int = 1024,
    private val maxBytes: Long = 8L * 1024 * 1024,
) : Closeable {
    private val queue = ArrayBlockingQueue<FrameTraceEvent>(capacity)
    private val dropped = AtomicLong()
    @Volatile private var closed = false
    private val worker = Thread(::write, "video-frame-trace").apply { isDaemon = true }

    fun start(): Boolean = try { worker.start(); true } catch (error: Exception) {
        closed = true
        notifyStatus("start_failed=${error.javaClass.simpleName}")
        false
    }

    @Synchronized fun event(stage: String, frame: VideoFrameTrace?, ptsUs: Long = 0, timeNs: Long = System.nanoTime()) {
        if (!closed && !queue.offer(FrameTraceEvent(stage, frame, timeNs, ptsUs))) dropped.incrementAndGet()
    }

    @Synchronized override fun close() { closed = true }

    /** Tests and callers may wait away from media threads; normal close never joins. */
    fun awaitClosed(timeoutMillis: Long): Boolean { worker.join(timeoutMillis); return !worker.isAlive }

    private fun notifyStatus(message: String) { runCatching { status("FrameTrace: $message") } }

    private fun write() {
        try {
            val directory = checkNotNull(file.parentFile)
            check(directory.isDirectory || directory.mkdirs()) { "diagnostic directory unavailable" }
            // Never delete or overwrite previous evidence. Stop when this diagnostic directory is full.
            require(maxBytes >= 1024) { "file budget too small for schema and footer" }
            synchronized(directoryBudgetLock) {
                // Reserve a complete file budget even for old/partially written files. This also
                // covers reconnects whose previous writer is still draining; no evidence is deleted.
                val reserved = directory.listFiles()?.sumOf { maxOf(it.length(), maxBytes) } ?: 0L
                check(reserved + maxBytes <= 64L * 1024 * 1024) { "directory limit" }
                check(file.createNewFile()) { "diagnostic filename already exists" }
            }
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                val header = "stage,stream,frame,time_ns,pts_us,bytes,read_begin_ns,header_done_ns,body_done_ns,decrypt_begin_ns,decrypt_done_ns\n" +
                    "# clock_ns=${System.nanoTime()} wall_ms=${System.currentTimeMillis()}\n"
                out.write(header)
                var written = header.length.toLong()
                var lastFlush = System.nanoTime()
                val line = StringBuilder(256)
                var characters = CharArray(256)
                notifyStatus("started file=${file.absolutePath} queueCapacity=${queue.remainingCapacity()} limitBytes=$maxBytes")
                while (!closed || queue.isNotEmpty()) {
                    val entry = queue.poll(250, TimeUnit.MILLISECONDS)
                    if (entry != null) {
                        line.setLength(0)
                        entry.appendCsv(line)
                        if (written + line.length + 128 > maxBytes) {
                            synchronized(this) {
                                closed = true
                                dropped.addAndGet(queue.size.toLong() + 1)
                                queue.clear()
                            }
                            out.write("# file_limit\n")
                            break
                        }
                        if (characters.size < line.length) characters = CharArray(line.length)
                        line.getChars(0, line.length, characters, 0)
                        out.write(characters, 0, line.length)
                        written += line.length
                    }
                    if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                        out.flush()
                        lastFlush = System.nanoTime()
                    }
                }
                out.write("# diagnostic_dropped=${dropped.get()}\n")
            }
            notifyStatus("closed file=${file.absolutePath} dropped=${dropped.get()}")
        } catch (error: Exception) {
            notifyStatus("writer_failed=${error.javaClass.simpleName} dropped=${dropped.get()} pending=${queue.size}")
        } finally {
            synchronized(this) { closed = true; queue.clear() }
        }
    }

    private companion object { val directoryBudgetLock = Any() }
}

/** Decoder-worker-owned PTS association, bounded independently from the playback queues. */
internal class FrameTraceIndex(private val emit: (String, VideoFrameTrace?, Long, Long) -> Unit) {
    private val pending = LinkedHashMap<Long, VideoFrameTrace?>()

    fun input(ptsUs: Long, frame: VideoFrameTrace, nowNs: Long) {
        if (pending.containsKey(ptsUs)) {
            emit("pts_collision", pending[ptsUs], ptsUs, nowNs)
            emit("pts_collision", frame, ptsUs, nowNs)
            pending[ptsUs] = null // Neither output may be falsely attributed to either input.
        } else pending[ptsUs] = frame
        if (pending.size > 128) {
            val first = pending.entries.iterator()
            val entry = first.next()
            emit("pts_evicted", entry.value, entry.key, nowNs)
            first.remove()
        }
        emit("input_queued", frame, ptsUs, nowNs)
    }

    fun output(ptsUs: Long, nowNs: Long) {
        emit(if (pending[ptsUs] == null) "output_unmatched" else "output", pending[ptsUs], ptsUs, nowNs)
    }

    fun release(ptsUs: Long, render: Boolean, nowNs: Long, notRenderedReason: String = "not_rendered") {
        val frame = pending.remove(ptsUs)
        emit(if (frame == null) "release_unmatched" else if (render) "submitted" else notRenderedReason, frame, ptsUs, nowNs)
    }

    fun reset(nowNs: Long) {
        pending.forEach { (pts, frame) -> emit("decoder_reset", frame, pts, nowNs) }
        pending.clear()
    }
}
