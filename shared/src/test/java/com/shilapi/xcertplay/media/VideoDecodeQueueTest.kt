package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    private fun h264Frame(idr: Boolean, receivedNs: Long) = VideoJob.Frame(
        byteArrayOf(0, 0, 0, 1, if (idr) 0x65 else 0x41, 1), receivedNs)

    @Test fun queuedKeyframeInspectionValidatesLengthPrefixedAccessUnitsWithoutConversion() {
        val valid = byteArrayOf(0, 0, 0, 2, 0x67, 1, 0, 0, 0, 2, 0x65, 1)
        assertTrue(MediaCodecSupport.isH264KeyFrame(valid))
        assertTrue(MediaCodecSupport.isH264KeyFrame(h264Frame(true, 0).nalus))
        assertFalse(MediaCodecSupport.isH264KeyFrame(h264Frame(false, 0).nalus))
        assertFalse(MediaCodecSupport.isH264KeyFrame(valid + byteArrayOf(0)))
        assertFalse(MediaCodecSupport.isH264KeyFrame(byteArrayOf(0, 0, 0, 8, 0x65)))
        assertFalse(MediaCodecSupport.isH264KeyFrame(ByteArray(0)))
    }

    @Test fun catchUpSelectsLatestFreshIdrAndKeepsItsDependents() {
        val queue = VideoDecodeQueue()
        queue.offer(h264Frame(false, 1))
        queue.offer(h264Frame(true, 80))
        queue.offer(h264Frame(false, 85))
        val latest = h264Frame(true, 90)
        val dependent = h264Frame(false, 95)
        queue.offer(latest)
        queue.offer(dependent)
        val result = queue.takeFreshH264KeyFrame(100, 25)!!
        assertSame(latest, result.frame)
        assertEquals(3, result.discarded)
        assertSame(dependent, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun catchUpNeverCrossesConfigSurfaceOrResync() {
        val controls = listOf(VideoJob.Config(VideoCodec.H264, byteArrayOf(1)),
            VideoJob.SurfaceChanged(null), VideoJob.Resync())
        for (control in controls) {
            val queue = VideoDecodeQueue()
            queue.offer(control)
            val idr = h264Frame(true, 90)
            queue.offer(idr)
            assertNull(queue.takeFreshH264KeyFrame(100, 25))
            assertSame(control, queue.poll(0))
            assertSame(idr, queue.poll(0))
        }
    }

    @Test fun noFreshIdrLeavesQueueUntouchedAndClosePreventsCatchUp() {
        val queue = VideoDecodeQueue()
        val oldIdr = h264Frame(true, 1)
        val dependent = h264Frame(false, 95)
        queue.offer(oldIdr)
        queue.offer(dependent)
        assertNull(queue.takeFreshH264KeyFrame(100, 25))
        assertSame(oldIdr, queue.poll(0))
        assertSame(dependent, queue.poll(0))
        queue.offer(h264Frame(true, 90))
        queue.close()
        queue.offer(h264Frame(true, 95))
        assertNull(queue.takeFreshH264KeyFrame(100, 25))
        assertNull(queue.poll(0))
    }

    @Test fun catchUpKeepsOrderedControlAfterSelectedIdr() {
        val queue = VideoDecodeQueue()
        val firstIdr = h264Frame(true, 90)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        queue.offer(firstIdr)
        queue.offer(config)
        val laterIdr = h264Frame(true, 95)
        queue.offer(laterIdr)
        assertSame(firstIdr, queue.takeFreshH264KeyFrame(100, 25)!!.frame)
        assertSame(config, queue.poll(0))
        assertSame(laterIdr, queue.poll(0))
    }

    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync(), queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync(), queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync(), queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun receiveDiscontinuityPreservesControlsAndOrdersRecoveryBeforeNewKeyframe() {
        val queue = VideoDecodeQueue()
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Resync("receive queue expired"))
        val nextKeyframe = VideoJob.Frame(byteArrayOf(0, 0, 0, 1, 0x65))
        queue.offer(nextKeyframe)
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync("receive queue expired"), queue.poll(0))
        assertSame(nextKeyframe, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun closedQueueReleasesBuffersAndRejectsLateCallbacks() {
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.Frame(ByteArray(100)))
        queue.close()
        queue.offer(VideoJob.Config(VideoCodec.H264, byteArrayOf(1)))
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Resync("late callback"))
        assertNull(queue.poll(0))
    }

    @Test fun cControlFloodFailsExplicitlyWithoutDroppingAcceptedConfigurations() {
        val queue = VideoDecodeQueue(maxFrames = 10, maxBytes = 100, boundedControls = true)
        val first = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val second = VideoJob.Config(VideoCodec.H265, byteArrayOf(2))
        queue.offer(first)
        queue.offer(second)
        try {
            queue.offer(VideoJob.Config(VideoCodec.H264, byteArrayOf(3)))
            fail("Unbounded configuration flood must fail the receive path")
        } catch (_: IOException) { }
        assertSame(first, queue.poll(0))
        assertSame(second, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun cCountsConfigurationAndFrameBytesInOneMemoryBudget() {
        val queue = VideoDecodeQueue(maxFrames = 10, maxBytes = 5, boundedControls = true)
        val config = VideoJob.Config(VideoCodec.H264, ByteArray(3))
        queue.offer(config)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertSame(config, queue.poll(0))
        assertEquals(VideoJob.Resync(), queue.poll(0))
        assertNull(queue.poll(0))

        queue.offer(VideoJob.Frame(ByteArray(3)))
        try {
            queue.offer(config)
            fail("CSD cannot bypass the total payload budget")
        } catch (_: IOException) { }
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
    }

    @Test fun cRejectsSingleOversizedConfigurationRatherThanDroppingItSilently() {
        val queue = VideoDecodeQueue(maxBytes = 5, boundedControls = true)
        try {
            queue.offer(VideoJob.Config(VideoCodec.H264, ByteArray(6)))
            fail("Oversized CSD must terminate the producer path")
        } catch (_: IOException) { }
        assertNull(queue.poll(0))
    }

    @Test fun cCoalescesPendingSurfaceAndRecoveryControlsAndCapsTotalJobCount() {
        val queue = VideoDecodeQueue(maxFrames = 10, maxBytes = 100, boundedControls = true)
        val first = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val second = VideoJob.Config(VideoCodec.H264, byteArrayOf(2))
        queue.offer(first)
        repeat(100) { queue.offer(VideoJob.SurfaceChanged(null)) }
        val finalSurface = VideoJob.SurfaceChanged(null)
        queue.offer(finalSurface)
        queue.offer(second)
        repeat(100) { queue.offer(VideoJob.Resync("gap-$it")) }
        repeat(10) { queue.offer(VideoJob.Frame(byteArrayOf(it.toByte()))) }
        assertSame(first, queue.poll(0))
        assertSame(finalSurface, queue.poll(0))
        assertSame(second, queue.poll(0))
        assertEquals(VideoJob.Resync("gap-99"), queue.poll(0))
        repeat(10) { assertTrue(queue.poll(0) is VideoJob.Frame) }
        assertNull(queue.poll(0)) // At most 10 frames + 2 config + 1 surface + 1 resync.
    }

    @Test fun bKeepsItsExistingControlQueueContract() {
        val queue = VideoDecodeQueue(maxBytes = 1)
        repeat(3) { queue.offer(VideoJob.Config(VideoCodec.H264, ByteArray(2))) }
        repeat(3) { assertTrue(queue.poll(0) is VideoJob.Config) }
        assertNull(queue.poll(0))
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
}
