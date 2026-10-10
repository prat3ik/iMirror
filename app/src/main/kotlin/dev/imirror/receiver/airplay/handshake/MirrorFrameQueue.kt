package dev.imirror.receiver.airplay.handshake

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Single network producer, single decoder consumer. Every frame retains its own codec config. */
internal class MirrorFrameQueue(capacity: Int) {
    data class Config(val sps: ByteArray, val pps: ByteArray)
    data class Frame(val annexB: ByteArray, val config: Config?)

    private val frames = ArrayBlockingQueue<Frame>(capacity)
    private var currentConfig: Config? = null // network thread only
    val size: Int get() = frames.size

    fun configure(config: Config) { currentConfig = config }

    /** Returns true when a queued frame had to be evicted. Codec config is never a queue item. */
    fun offer(annexB: ByteArray): Boolean {
        val frame = Frame(annexB, currentConfig)
        if (frames.offer(frame)) return false
        val evicted = frames.poll() != null
        frames.offer(frame) // only this producer adds items; at least one slot is now available
        return evicted
    }

    fun poll(): Frame? = frames.poll(200, TimeUnit.MILLISECONDS)
    fun clear() = frames.clear()
}
