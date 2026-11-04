package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.ScreenStream
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VideoFrameTraceTest {
    private fun frame(id: Long = 1) = VideoFrameTrace(7, id, 123, 10, 20, 30, 40, 50)

    @Test fun reusableCsvBufferPreservesSchemaAndMissingIdentity() {
        val out = StringBuilder()
        FrameTraceEvent("enqueue", frame(), 60, 70).appendCsv(out)
        assertEquals("enqueue,7,1,60,70,123,10,20,30,40,50\n", out.toString())
        out.setLength(0)
        FrameTraceEvent("output_unmatched", null, 80, 90).appendCsv(out)
        assertEquals("output_unmatched,0,0,80,90,0,0,0,0,0,0\n", out.toString())
    }

    @Test fun legacyCallbacksKeepTheirOriginalDispatchWithoutUsingWireTimeAsAge() {
        val bytes = byteArrayOf(1, 2)
        var listenerLegacy = false
        val listener = object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) { listenerLegacy = naluBytes === bytes }
            override fun onFrame(naluBytes: ByteArray, receivedNs: Long) { fail("C callback must not be used") }
        }
        listener.onFrame(bytes, frame())
        assertTrue(listenerLegacy)
        var sinkLegacy = false
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { sinkLegacy = naluBytes === bytes && type == 110 }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray, receivedNs: Long) { fail("C callback must not be used") }
        }
        sink.onVideoFrame(110, bytes, frame())
        assertTrue(sinkLegacy)
        val job = VideoJob.Frame(bytes, 123456, frame())
        assertEquals(123456L, job.receivedNs)
    }

    @Test fun reorderedOutputsAndNotRenderedFramesKeepTheirOwnIdentity() {
        val events = mutableListOf<FrameTraceEvent>()
        val index = FrameTraceIndex { stage, trace, pts, time -> events.add(FrameTraceEvent(stage, trace, time, pts)) }
        index.input(100, frame(1), 60)
        index.input(200, frame(2), 70)
        index.output(200, 80)
        index.release(200, false, 90)
        index.output(100, 100)
        index.release(100, true, 110)
        assertEquals(2L, events.first { it.stage == "not_rendered" }.frame!!.sequence)
        assertEquals(1L, events.first { it.stage == "submitted" }.frame!!.sequence)
        assertEquals(110L, events.last().timeNs)
    }

    @Test fun collisionsAndEvictionsAreNeverSilentlyAttributedToAnotherFrame() {
        val events = mutableListOf<FrameTraceEvent>()
        val index = FrameTraceIndex { stage, trace, pts, time -> events.add(FrameTraceEvent(stage, trace, time, pts)) }
        index.input(10, frame(1), 60)
        index.input(10, frame(2), 70)
        index.output(10, 80)
        index.release(10, true, 90)
        assertEquals(2, events.count { it.stage == "pts_collision" })
        assertNull(events.first { it.stage == "release_unmatched" }.frame)
        for (n in 1L..129) index.input(n, frame(n), 100 + n)
        assertEquals(1L, events.first { it.stage == "pts_evicted" }.frame!!.sequence)
        index.reset(300)
        assertEquals(128, events.count { it.stage == "decoder_reset" })
        index.release(129, true, 400)
        assertEquals("release_unmatched", events.last().stage)
    }

    @Test fun queueDropsHaveDispositionAndDoNotAlterQueueCapacityPolicy() {
        val drops = mutableListOf<Pair<Long, String>>()
        val queue = VideoDecodeQueue(maxFrames = 1, onTraceDrop = { f, why -> drops.add(f.sequence to why) })
        queue.offer(VideoJob.Frame(byteArrayOf(1), 100, frame(1)))
        queue.offer(VideoJob.Frame(byteArrayOf(2), 200, frame(2)))
        assertEquals(listOf(1L to "queue_discard"), drops)
        assertTrue(queue.poll(0) is VideoJob.Resync)
        val retained = queue.poll(0) as VideoJob.Frame
        assertEquals(200L, retained.receivedNs)
        assertEquals(2L, retained.trace!!.sequence)
        queue.offer(retained)
        queue.close()
        assertEquals(2L to "queue_close", drops.last())
    }

    @Test fun boundedAdmissionDropsOnlyDiagnosticsAndWriterDrainsOnClose() {
        val file = Files.createTempDirectory("frame-trace").resolve("frames.csv").toFile()
        val log = FrameTraceLog(file, capacity = 2)
        repeat(3) { log.event("enqueue", frame(it.toLong()), timeNs = 60) }
        assertTrue(log.start())
        log.close()
        assertTrue(log.awaitClosed(3000))
        val text = file.readText()
        assertEquals(2, text.lineSequence().count { it.startsWith("enqueue,") })
        assertTrue(text.contains("diagnostic_dropped=1"))
        assertTrue(text.contains("read_begin_ns"))
    }

    @Test fun concurrentWritersReserveTheirWholeBudgetBeforeAdmission() {
        val root = Files.createTempDirectory("frame-trace-budget").toFile()
        // Seven small historical files still reserve seven complete production budgets.
        repeat(7) { root.resolve("old-$it.csv").writeText("evidence") }
        val writers = (1..4).map { FrameTraceLog(root.resolve("new-$it.csv")) }
        writers.forEach { it.start() }
        writers.forEach { it.close() }
        writers.forEach { assertTrue(it.awaitClosed(3000)) }
        assertEquals(8, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.sumOf { maxOf(it.length(), 8L * 1024 * 1024) } <= 64L * 1024 * 1024)
    }

    @Test fun fileLimitAndIoFailureDoNotEscapeIntoMediaAdmission() {
        val root = Files.createTempDirectory("frame-trace-failure").toFile()
        val file = root.resolve("limited.csv")
        val log = FrameTraceLog(file, maxBytes = 1024)
        repeat(30) { log.event("enqueue", frame()) }
        log.start()
        assertTrue(log.awaitClosed(3000))
        assertTrue(file.readText().contains("file_limit"))
        assertTrue(file.length() <= 1024)
        val blocked = root.resolve("not-directory").apply { writeText("blocked") }
        val messages = mutableListOf<String>()
        val failed = FrameTraceLog(blocked.resolve("frames.csv"), messages::add)
        failed.start()
        assertTrue(failed.awaitClosed(3000))
        failed.event("enqueue", frame())
        assertTrue(messages.any { it.contains("writer_failed") })
    }
}
