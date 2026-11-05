//
//  VolumeBoostLimiter.swift
//  App
//

import Foundation

/// Applies makeup gain to audio frames and limits the peaks so they cannot clip.
///
/// Plain gain is not enough for quiet audiobooks: speech has peaks far above its average level, so
/// the boost that makes the narrator audible would otherwise clip every consonant. Instead the gain
/// is turned down just ahead of a peak, and recovers afterwards:
///
/// 1. Each frame is boosted, then delayed by `lookaheadFrames` so the gain can react before a peak
///    reaches the output.
/// 2. The gain reduction that frame requires holds for the lookahead window, then releases slowly.
/// 3. A moving average over that window ramps the reduction in, which avoids clicks. Every frame of
///    the average is at or below what the peak needs, so the ramp arrives on time and cannot
///    overshoot.
///
/// The ceiling is full scale, so at 0 dB nothing is ever reduced and audio only gets delayed.
///
/// This is the same algorithm as VolumeBoostLimiter.kt on Android, which has the unit tests.
///
/// Not thread safe. Allocates only in `init`, so it is safe on the real-time audio thread.
final class VolumeBoostLimiter {
    static func dbToLinear(_ db: Float) -> Float {
        return pow(10, db / 20)
    }

    /// Frames of delay added to the audio.
    let lookaheadFrames: Int

    private let ceiling: Float = 1
    private let channelCount: Int
    private let windowFrames: Int
    private let releaseCoefficient: Float
    private let gainRampCoefficient: Float

    private let delayLine: UnsafeMutablePointer<Float>
    private var delayIndex = 0

    // Sliding window minimum: a monotonic queue of reductions, oldest and lowest first.
    private let minimumValues: UnsafeMutablePointer<Float>
    private let minimumFrames: UnsafeMutablePointer<Int64>
    private var minimumHead = 0
    private var minimumSize = 0

    // Moving average of the window minimum. Starts full of 1s: no reduction.
    private let averageWindow: UnsafeMutablePointer<Float>
    private var averageIndex = 0
    private var averageSum: Double

    private var frameNumber: Int64 = 0
    private var gain: Float
    private var releasedReduction: Float = 1

    init(sampleRate: Double, channelCount: Int, initialGain: Float = 1) {
        let windowFrames = max(1, Int((sampleRate * 0.005).rounded()))
        self.windowFrames = windowFrames
        self.lookaheadFrames = windowFrames - 1
        self.channelCount = channelCount
        self.releaseCoefficient = Float(exp(-1.0 / (sampleRate * 0.15)))
        self.gainRampCoefficient = Float(exp(-1.0 / (sampleRate * 0.02)))
        self.gain = initialGain

        let delaySamples = max(1, windowFrames - 1) * channelCount
        self.delayLine = UnsafeMutablePointer<Float>.allocate(capacity: delaySamples)
        self.delayLine.initialize(repeating: 0, count: delaySamples)

        self.minimumValues = UnsafeMutablePointer<Float>.allocate(capacity: windowFrames)
        self.minimumValues.initialize(repeating: 0, count: windowFrames)
        self.minimumFrames = UnsafeMutablePointer<Int64>.allocate(capacity: windowFrames)
        self.minimumFrames.initialize(repeating: 0, count: windowFrames)

        self.averageWindow = UnsafeMutablePointer<Float>.allocate(capacity: windowFrames)
        self.averageWindow.initialize(repeating: 1, count: windowFrames)
        self.averageSum = Double(windowFrames)
    }

    deinit {
        delayLine.deallocate()
        minimumValues.deallocate()
        minimumFrames.deallocate()
        averageWindow.deallocate()
    }

    /// Replaces one interleaved frame in place with the boosted, limited frame `lookaheadFrames`
    /// earlier. Moves towards `targetGain` smoothly so changing the boost doesn't click.
    func processFrame(_ frame: UnsafeMutablePointer<Float>, targetGain: Float) {
        gain = targetGain + (gain - targetGain) * gainRampCoefficient
        if abs(gain - targetGain) < 0.0001 {
            gain = targetGain
        }

        var peak: Float = 0
        for channel in 0..<channelCount {
            frame[channel] *= gain
            peak = max(peak, abs(frame[channel]))
        }

        let requiredReduction = peak > ceiling ? ceiling / peak : 1
        releasedReduction = min(requiredReduction, 1 - (1 - releasedReduction) * releaseCoefficient)

        let reduction = pushToAverage(pushToMinimum(releasedReduction))
        frameNumber += 1

        if lookaheadFrames == 0 {
            for channel in 0..<channelCount {
                frame[channel] *= reduction
            }
            return
        }

        let delayOffset = delayIndex * channelCount
        for channel in 0..<channelCount {
            let boosted = frame[channel]
            frame[channel] = delayLine[delayOffset + channel] * reduction
            delayLine[delayOffset + channel] = boosted
        }
        delayIndex = (delayIndex + 1) % lookaheadFrames
    }

    /// Returns the lowest value pushed during the last `windowFrames` frames.
    private func pushToMinimum(_ value: Float) -> Float {
        if minimumSize > 0 && minimumFrames[minimumHead] <= frameNumber - Int64(windowFrames) {
            minimumHead = (minimumHead + 1) % windowFrames
            minimumSize -= 1
        }
        // Queued values that aren't lower than this one can never be the minimum again
        while minimumSize > 0 {
            let last = (minimumHead + minimumSize - 1) % windowFrames
            if minimumValues[last] < value {
                break
            }
            minimumSize -= 1
        }
        let tail = (minimumHead + minimumSize) % windowFrames
        minimumValues[tail] = value
        minimumFrames[tail] = frameNumber
        minimumSize += 1
        return minimumValues[minimumHead]
    }

    /// Returns the average of the last `windowFrames` values pushed.
    private func pushToAverage(_ value: Float) -> Float {
        averageSum += Double(value) - Double(averageWindow[averageIndex])
        averageWindow[averageIndex] = value
        averageIndex = (averageIndex + 1) % windowFrames
        return Float(averageSum / Double(windowFrames))
    }
}
