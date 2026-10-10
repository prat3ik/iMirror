package dev.imirror.receiver.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class MirrorFrameQueueTest {
    @Test fun `overload cannot lose the only codec configuration`() {
        val queue = MirrorFrameQueue(1)
        val config = MirrorFrameQueue.Config(byteArrayOf(1), byteArrayOf(2))
        queue.configure(config)
        assertFalse(queue.offer(byteArrayOf(3)))
        assertTrue(queue.offer(byteArrayOf(4)))
        val frame = queue.poll()!!
        assertArrayEquals(byteArrayOf(4), frame.annexB)
        assertSame(config, frame.config)
    }

    @Test fun `format change never applies new config to older queued frames`() {
        val queue = MirrorFrameQueue(2)
        val old = MirrorFrameQueue.Config(byteArrayOf(1), byteArrayOf(2))
        val next = MirrorFrameQueue.Config(byteArrayOf(5), byteArrayOf(6))
        queue.configure(old)
        queue.offer(byteArrayOf(3))
        queue.configure(next)
        queue.offer(byteArrayOf(7))
        assertSame(old, queue.poll()!!.config)
        assertSame(next, queue.poll()!!.config)
    }

    @Test fun `frames received before configuration stay unconfigured`() {
        val queue = MirrorFrameQueue(1)
        queue.offer(byteArrayOf(3))
        queue.configure(MirrorFrameQueue.Config(byteArrayOf(1), byteArrayOf(2)))
        assertNull(queue.poll()!!.config)
    }
}
