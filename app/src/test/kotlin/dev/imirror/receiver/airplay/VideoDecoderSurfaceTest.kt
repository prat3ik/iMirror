package dev.imirror.receiver.airplay

import android.media.MediaCodec
import android.view.Surface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class VideoDecoderSurfaceTest {
    private fun decoder(codec: MediaCodec, initial: Surface): VideoDecoder {
        return VideoDecoder(initial).also {
            VideoDecoder::class.java.getDeclaredField("mediaCodec").apply { isAccessible = true }.set(it, codec)
        }
    }

    @Test fun `changing surface preserves codec reference frames`() {
        val codec = mockk<MediaCodec>(relaxed = true)
        val old = mockk<Surface>(relaxed = true)
        val next = mockk<Surface> { every { isValid } returns true }
        assertTrue(decoder(codec, old).switchOutputSurface(next, true))
        verify(exactly = 1) { codec.setOutputSurface(next) }
        verify(exactly = 0) { codec.stop(); codec.release(); codec.flush() }
    }

    @Test fun `invalid replacement does not touch healthy decoder`() {
        val codec = mockk<MediaCodec>(relaxed = true)
        val invalid = mockk<Surface> { every { isValid } returns false }
        val d = decoder(codec, mockk(relaxed = true))
        assertFalse(d.switchOutputSurface(invalid, true))
        assertTrue(d.isHealthy)
        verify(exactly = 0) { codec.setOutputSurface(any()); codec.release() }
    }

    @Test fun `unsupported switching reports fallback without claiming success`() {
        val codec = mockk<MediaCodec>(relaxed = true) {
            every { setOutputSurface(any()) } throws IllegalStateException("unsupported")
        }
        val next = mockk<Surface> { every { isValid } returns true }
        assertFalse(decoder(codec, mockk(relaxed = true)).switchOutputSurface(next, true))
    }

    @Test fun `release still frees codec when stopping a failed codec throws`() {
        val codec = mockk<MediaCodec>(relaxed = true) {
            every { stop() } throws IllegalStateException("not started")
        }
        val d = decoder(codec, mockk(relaxed = true))
        d.release()
        d.release()
        verify(exactly = 1) { codec.release() }
    }
}
