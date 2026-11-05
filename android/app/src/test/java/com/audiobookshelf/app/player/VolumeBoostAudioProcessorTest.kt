package com.audiobookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBoostAudioProcessorTest {
  private val stereo = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

  private fun configured(format: AudioFormat = stereo): VolumeBoostAudioProcessor {
    val processor = VolumeBoostAudioProcessor()
    processor.configure(format)
    processor.flush()
    return processor
  }

  private fun buffer(samples: ShortArray): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
    samples.forEach { buffer.putShort(it) }
    buffer.flip()
    return buffer
  }

  private fun outputSamples(processor: AudioProcessor): ShortArray {
    val output = processor.output
    return ShortArray(output.remaining() / 2) { output.getShort() }
  }

  private fun run(processor: AudioProcessor, samples: ShortArray): ShortArray {
    val input = buffer(samples)
    processor.queueInput(input)
    assertEquals("all input consumed", 0, input.remaining())
    return outputSamples(processor)
  }

  @Test
  fun `stays in the pipeline at 0 dB so it can be turned up during playback`() {
    assertTrue(configured().isActive)
  }

  @Test
  fun `formats other than 16 bit are bypassed instead of failing playback`() {
    val processor = VolumeBoostAudioProcessor()
    processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
    assertFalse(processor.isActive)
  }

  @Test
  fun `a boost set during playback is heard`() {
    val processor = configured()
    val samples = ShortArray(44100 * 2) { 1000 }

    val before = run(processor, samples)
    processor.setBoostDb(6f)
    val after = run(processor, samples)

    assertEquals(1000, before.last().toInt())
    assertEquals(1995.0, after.last().toDouble(), 2.0)
    assertEquals("output matches input size", samples.size, after.size)
  }

  @Test
  fun `loud audio saturates rather than wrapping around to a loud crack`() {
    val processor = configured()
    processor.setBoostDb(18f)
    processor.flush()
    val samples = ShortArray(44100) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }

    val output = run(processor, samples)
    val settled = output.drop(output.size / 2)

    assertTrue(settled.filterIndexed { i, _ -> i % 2 == 0 }.all { it > 0 })
    assertTrue(settled.filterIndexed { i, _ -> i % 2 == 1 }.all { it < 0 })
  }

  @Test
  fun `the end of the audio is not lost in the limiter`() {
    val processor = configured()
    val samples = ShortArray(44100) { 1000 }

    run(processor, samples)
    processor.queueEndOfStream()
    val tail = outputSamples(processor)

    assertTrue("tail present", tail.isNotEmpty())
    assertTrue(tail.all { it.toInt() == 1000 })
    assertTrue(processor.isEnded)
  }

  @Test
  fun `seeking clears audio held back from before the seek`() {
    val processor = configured()
    run(processor, ShortArray(44100) { 12000 })

    processor.flush()
    val afterSeek = run(processor, ShortArray(2000) { 0 })

    assertTrue(afterSeek.all { abs(it.toInt()) == 0 })
  }
}
