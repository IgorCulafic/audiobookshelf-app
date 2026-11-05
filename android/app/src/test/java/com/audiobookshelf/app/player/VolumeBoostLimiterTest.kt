package com.audiobookshelf.app.player

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBoostLimiterTest {
  private val sampleRate = 44100

  private fun gainOf(db: Float) = VolumeBoostLimiter.dbToLinear(db)

  private fun sine(frames: Int, amplitude: Float, hz: Double = 440.0) =
          FloatArray(frames) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

  /** Runs mono audio through the limiter, returning the output. */
  private fun process(
          limiter: VolumeBoostLimiter,
          input: FloatArray,
          targetGain: (Int) -> Float
  ): FloatArray {
    val frame = FloatArray(1)
    return FloatArray(input.size) {
      frame[0] = input[it]
      limiter.processFrame(frame, targetGain(it))
      frame[0]
    }
  }

  @Test
  fun `0 dB leaves audio untouched, even at full scale`() {
    val limiter = VolumeBoostLimiter(sampleRate, 1)
    val input = sine(sampleRate, 1f)
    val output = process(limiter, input) { 1f }

    for (i in limiter.lookaheadFrames until input.size) {
      assertEquals(input[i - limiter.lookaheadFrames], output[i], 0f)
    }
  }

  @Test
  fun `quiet audio gets exactly the requested boost`() {
    val limiter = VolumeBoostLimiter(sampleRate, 1, gainOf(12f))
    val input = sine(sampleRate, 0.01f)
    val output = process(limiter, input) { gainOf(12f) }

    for (i in limiter.lookaheadFrames until input.size) {
      val expected = input[i - limiter.lookaheadFrames] * gainOf(12f)
      assertEquals(expected, output[i], 0.000001f)
    }
  }

  @Test
  fun `no sample exceeds full scale at maximum boost`() {
    val random = Random(1234)
    // Noise with sudden loud bursts, the hardest case for a limiter's attack
    val input =
            FloatArray(sampleRate * 10) {
              val inBurst = (it / 2000) % 7 == 0
              (random.nextFloat() * 2f - 1f) * if (inBurst) 1f else 0.05f
            }
    val limiter = VolumeBoostLimiter(sampleRate, 1, gainOf(18f))
    val output = process(limiter, input) { gainOf(18f) }

    assertTrue("peak ${output.maxOf { abs(it) }}", output.all { abs(it) <= 1.00001f })
  }

  @Test
  fun `gain drops before a peak arrives, not at it`() {
    val input = sine(sampleRate, 0.05f)
    input[sampleRate / 2] = 1f
    val limiter = VolumeBoostLimiter(sampleRate, 1, gainOf(18f))
    val output = process(limiter, input) { gainOf(18f) }

    val peakOutputFrame = sampleRate / 2 + limiter.lookaheadFrames
    assertEquals(1f, abs(output[peakOutputFrame]), 0.00001f)

    val boostedBefore = input[peakOutputFrame - 1 - limiter.lookaheadFrames] * gainOf(18f)
    assertTrue(abs(output[peakOutputFrame - 1]) < abs(boostedBefore) * 0.5f)
  }

  @Test
  fun `gain recovers after a loud peak`() {
    val input = sine(sampleRate * 2, 0.01f)
    input[1000] = 1f
    val limiter = VolumeBoostLimiter(sampleRate, 1, gainOf(18f))
    val output = process(limiter, input) { gainOf(18f) }

    val tail = sampleRate * 2 - 1000
    val tailPeak = (tail until output.size).maxOf { abs(output[it]) }
    assertEquals(0.01f * gainOf(18f), tailPeak, 0.001f)
  }

  @Test
  fun `changing the boost mid playback ramps without a click`() {
    val input = FloatArray(sampleRate) { 0.1f }
    val limiter = VolumeBoostLimiter(sampleRate, 1)
    val output = process(limiter, input) { if (it < sampleRate / 2) 1f else gainOf(12f) }

    // Skips the start, where the audio arrives out of the limiter's empty delay line
    var largestStep = 0f
    for (i in sampleRate / 4 until output.size) {
      largestStep = max(largestStep, abs(output[i] - output[i - 1]))
    }
    assertTrue("step $largestStep", largestStep < 0.002f)
    assertEquals(0.1f * gainOf(12f), output.last(), 0.0001f)
  }

  @Test
  fun `a peak in one channel keeps the stereo balance`() {
    val limiter = VolumeBoostLimiter(sampleRate, 2, gainOf(18f))
    val frame = FloatArray(2)
    var largestLeft = 0f
    repeat(sampleRate) {
      frame[0] = sin(2 * PI * 440 * it / sampleRate).toFloat()
      frame[1] = frame[0] * 0.25f
      limiter.processFrame(frame, gainOf(18f))
      largestLeft = max(largestLeft, abs(frame[0]))
      assertEquals(frame[0] * 0.25f, frame[1], 0.00001f)
    }
    assertTrue(largestLeft <= 1.00001f)
  }
}
