package com.audiobookshelf.app.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies makeup gain to audio frames and limits the peaks so they cannot clip.
 *
 * Plain gain is not enough for quiet audiobooks: speech has peaks far above its average level, so
 * the boost that makes the narrator audible would otherwise clip every consonant. Instead the gain
 * is turned down just ahead of a peak, and recovers afterwards:
 *
 * 1. Each frame is boosted, then delayed by [lookaheadFrames] so the gain can react before a peak
 *    reaches the output.
 * 2. The gain reduction that frame requires holds for the lookahead window, then releases slowly.
 * 3. A moving average over that window ramps the reduction in, which avoids clicks. Every frame of
 *    the average is at or below what the peak needs, so the ramp arrives on time and cannot
 *    overshoot.
 *
 * The ceiling is full scale, so at 0 dB nothing is ever reduced and audio only gets delayed.
 *
 * Not thread safe. Allocates only in the constructor, so it is safe on the playback thread.
 */
class VolumeBoostLimiter(sampleRate: Int, private val channelCount: Int, initialGain: Float = 1f) {
  companion object {
    private const val LOOKAHEAD_SECONDS = 0.005
    private const val RELEASE_SECONDS = 0.15
    private const val GAIN_RAMP_SECONDS = 0.02
    private const val CEILING = 1f

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
  }

  private val windowFrames = max(1, (sampleRate * LOOKAHEAD_SECONDS).roundToInt())
  private val releaseCoefficient = exp(-1.0 / (sampleRate * RELEASE_SECONDS)).toFloat()
  private val gainRampCoefficient = exp(-1.0 / (sampleRate * GAIN_RAMP_SECONDS)).toFloat()

  /** Frames of delay added to the audio. */
  val lookaheadFrames = windowFrames - 1

  private val delayLine = FloatArray(max(1, lookaheadFrames) * channelCount)
  private var delayIndex = 0

  // Sliding window minimum: a monotonic queue of reductions, oldest and lowest first.
  private val minimumValues = FloatArray(windowFrames)
  private val minimumFrames = LongArray(windowFrames)
  private var minimumHead = 0
  private var minimumSize = 0

  // Moving average of the window minimum. Starts full of 1s: no reduction.
  private val averageWindow = FloatArray(windowFrames) { 1f }
  private var averageIndex = 0
  private var averageSum = windowFrames.toDouble()

  private var frameNumber = 0L
  private var gain = initialGain
  private var releasedReduction = 1f

  /**
   * Replaces one interleaved frame in place with the boosted, limited frame [lookaheadFrames]
   * earlier. Moves towards [targetGain] smoothly so changing the boost doesn't click.
   */
  fun processFrame(frame: FloatArray, targetGain: Float) {
    gain = targetGain + (gain - targetGain) * gainRampCoefficient
    if (abs(gain - targetGain) < 0.0001f) gain = targetGain

    var peak = 0f
    for (channel in 0 until channelCount) {
      frame[channel] *= gain
      peak = max(peak, abs(frame[channel]))
    }

    val requiredReduction = if (peak > CEILING) CEILING / peak else 1f
    releasedReduction =
            min(requiredReduction, 1f - (1f - releasedReduction) * releaseCoefficient)

    val reduction = pushToAverage(pushToMinimum(releasedReduction))
    frameNumber++

    if (lookaheadFrames == 0) {
      for (channel in 0 until channelCount) frame[channel] *= reduction
      return
    }

    val delayOffset = delayIndex * channelCount
    for (channel in 0 until channelCount) {
      val boosted = frame[channel]
      frame[channel] = delayLine[delayOffset + channel] * reduction
      delayLine[delayOffset + channel] = boosted
    }
    delayIndex = (delayIndex + 1) % lookaheadFrames
  }

  /** Returns the lowest value pushed during the last [windowFrames] frames. */
  private fun pushToMinimum(value: Float): Float {
    if (minimumSize > 0 && minimumFrames[minimumHead] <= frameNumber - windowFrames) {
      minimumHead = (minimumHead + 1) % windowFrames
      minimumSize--
    }
    // Queued values that aren't lower than this one can never be the minimum again
    while (minimumSize > 0) {
      val last = (minimumHead + minimumSize - 1) % windowFrames
      if (minimumValues[last] < value) break
      minimumSize--
    }
    val tail = (minimumHead + minimumSize) % windowFrames
    minimumValues[tail] = value
    minimumFrames[tail] = frameNumber
    minimumSize++
    return minimumValues[minimumHead]
  }

  /** Returns the average of the last [windowFrames] values pushed. */
  private fun pushToAverage(value: Float): Float {
    averageSum += value - averageWindow[averageIndex]
    averageWindow[averageIndex] = value
    averageIndex = (averageIndex + 1) % windowFrames
    return (averageSum / windowFrames).toFloat()
  }
}
