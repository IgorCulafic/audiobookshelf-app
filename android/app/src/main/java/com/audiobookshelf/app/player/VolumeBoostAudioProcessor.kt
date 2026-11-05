package com.audiobookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Boosts audio inside ExoPlayer's audio pipeline, using [VolumeBoostLimiter] to prevent clipping.
 *
 * This stays active at 0 dB because ExoPlayer only checks whether a processor is active when it
 * configures the pipeline, so one that started inactive couldn't be turned up during playback.
 * ExoPlayer converts decoded audio to 16-bit before custom processors, so that is the only format
 * needed. Anything else passes through unboosted rather than failing playback.
 */
class VolumeBoostAudioProcessor : BaseAudioProcessor() {
  companion object {
    private const val SHORT_SCALE = 32768f
    private const val BYTES_PER_SAMPLE = 2
  }

  // Set from the main thread, read on the playback thread
  @Volatile private var targetGain = 1f

  private lateinit var limiter: VolumeBoostLimiter
  private var frame = FloatArray(0)

  fun setBoostDb(boostDb: Float) {
    targetGain = VolumeBoostLimiter.dbToLinear(boostDb)
  }

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
    else AudioFormat.NOT_SET
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    var position = inputBuffer.position()
    val limit = inputBuffer.limit()
    if (position == limit) return

    val channelCount = inputAudioFormat.channelCount
    val bytesPerFrame = inputAudioFormat.bytesPerFrame
    val outputBuffer = replaceOutputBuffer(limit - position)

    while (position < limit) {
      for (channel in 0 until channelCount) {
        val samplePosition = position + channel * BYTES_PER_SAMPLE
        frame[channel] = inputBuffer.getShort(samplePosition) / SHORT_SCALE
      }
      limiter.processFrame(frame, targetGain)
      writeFrame(outputBuffer)
      position += bytesPerFrame
    }

    inputBuffer.position(limit)
    outputBuffer.flip()
  }

  /** The limiter holds back the last few milliseconds of audio. Let them out. */
  override fun onQueueEndOfStream() {
    if (limiter.lookaheadFrames == 0) return

    val outputBuffer = replaceOutputBuffer(limiter.lookaheadFrames * inputAudioFormat.bytesPerFrame)
    repeat(limiter.lookaheadFrames) {
      frame.fill(0f)
      limiter.processFrame(frame, targetGain)
      writeFrame(outputBuffer)
    }
    outputBuffer.flip()
  }

  private fun writeFrame(outputBuffer: ByteBuffer) {
    for (channel in 0 until inputAudioFormat.channelCount) {
      val scaled = (frame[channel] * SHORT_SCALE).roundToInt()
      val sample = min(max(scaled, Short.MIN_VALUE.toInt()), Short.MAX_VALUE.toInt())
      outputBuffer.putShort(sample.toShort())
    }
  }

  /** Runs on every seek as well as after a format change, starting the limiter clean. */
  override fun onFlush() {
    if (!isActive) return
    limiter =
            VolumeBoostLimiter(
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                    targetGain
            )
    frame = FloatArray(inputAudioFormat.channelCount)
  }
}
