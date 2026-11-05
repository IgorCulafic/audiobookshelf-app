//
//  VolumeBoostTap.swift
//  App
//

import AVFoundation
import MediaToolbox

/// The boost for a player, shared with the audio tap on each of its tracks.
final class VolumeBoostControl {
    // A raw pointer so the real-time audio thread reads it without any Swift runtime checks
    private let gain = UnsafeMutablePointer<Float>.allocate(capacity: 1)

    init() {
        gain.initialize(to: 1)
    }

    deinit {
        gain.deallocate()
    }

    var targetGain: Float {
        get { return gain.pointee }
        set { gain.pointee = newValue }
    }
}

/// What a tap keeps between audio callbacks.
private final class VolumeBoostTapState {
    let control: VolumeBoostControl

    var limiter: VolumeBoostLimiter?
    var channelCount = 0
    var isInterleaved = false
    var frame: UnsafeMutablePointer<Float>?
    var channelSamples: UnsafeMutablePointer<UnsafeMutablePointer<Float>?>?

    init(control: VolumeBoostControl) {
        self.control = control
    }

    static func from(_ tap: MTAudioProcessingTap) -> VolumeBoostTapState {
        return Unmanaged<VolumeBoostTapState>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    }

    func prepare(format: AudioStreamBasicDescription) {
        let isFloat = format.mFormatID == kAudioFormatLinearPCM && format.mFormatFlags & kAudioFormatFlagIsFloat != 0
        // Audio in any other format plays unboosted
        guard isFloat && format.mBitsPerChannel == 32 && format.mChannelsPerFrame > 0 else { return }

        channelCount = Int(format.mChannelsPerFrame)
        isInterleaved = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0
        frame = UnsafeMutablePointer<Float>.allocate(capacity: channelCount)
        channelSamples = UnsafeMutablePointer<UnsafeMutablePointer<Float>?>.allocate(capacity: channelCount)
        limiter = VolumeBoostLimiter(sampleRate: format.mSampleRate, channelCount: channelCount, initialGain: control.targetGain)
    }

    func unprepare() {
        limiter = nil
        frame?.deallocate()
        frame = nil
        channelSamples?.deallocate()
        channelSamples = nil
    }

    /// Runs on the real-time audio thread, so this must not allocate or lock.
    func process(_ bufferList: UnsafeMutablePointer<AudioBufferList>, frameCount: Int) {
        guard let limiter = limiter, let frame = frame, let channelSamples = channelSamples else { return }

        let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
        guard buffers.count == (isInterleaved ? 1 : channelCount) else { return }

        for channel in 0..<channelCount {
            guard let data = buffers[isInterleaved ? 0 : channel].mData else { return }
            channelSamples[channel] = data.assumingMemoryBound(to: Float.self) + (isInterleaved ? channel : 0)
        }

        let sampleStride = isInterleaved ? channelCount : 1
        let targetGain = control.targetGain

        for frameIndex in 0..<frameCount {
            for channel in 0..<channelCount {
                frame[channel] = channelSamples[channel]![frameIndex * sampleStride]
            }
            limiter.processFrame(frame, targetGain: targetGain)
            for channel in 0..<channelCount {
                channelSamples[channel]![frameIndex * sampleStride] = max(-1, min(1, frame[channel]))
            }
        }
    }
}

enum VolumeBoostTap {
    /// Creates an audio mix that boosts `track` by `control`'s gain.
    ///
    /// AVFoundation only supports taps on file based tracks, so this can't boost an HLS stream.
    static func audioMix(for track: AVAssetTrack, control: VolumeBoostControl) -> AVAudioMix? {
        let tapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, tapStorageOut in
            tapStorageOut.pointee = clientInfo
        }
        let tapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
            Unmanaged<VolumeBoostTapState>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
        }
        let tapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, processingFormat in
            VolumeBoostTapState.from(tap).prepare(format: processingFormat.pointee)
        }
        let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { tap in
            VolumeBoostTapState.from(tap).unprepare()
        }
        let tapProcess: MTAudioProcessingTapProcessCallback = { tap, numberFrames, _, bufferListInOut, numberFramesOut, flagsOut in
            guard MTAudioProcessingTapGetSourceAudio(tap, numberFrames, bufferListInOut, flagsOut, nil, numberFramesOut) == noErr else { return }
            VolumeBoostTapState.from(tap).process(bufferListInOut, frameCount: numberFramesOut.pointee)
        }

        let state = Unmanaged.passRetained(VolumeBoostTapState(control: control))
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: state.toOpaque(),
            init: tapInit,
            finalize: tapFinalize,
            prepare: tapPrepare,
            unprepare: tapUnprepare,
            process: tapProcess
        )

        // The type this API returns changed in the Xcode 26 SDK. `compiler` rather than `swift`,
        // because the project builds in Swift 5 language mode.
        #if compiler(>=6.2)
        var createdTap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PreEffects, &createdTap)
        let tap = createdTap
        #else
        var createdTap: Unmanaged<MTAudioProcessingTap>?
        let status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PreEffects, &createdTap)
        let tap = createdTap?.takeRetainedValue()
        #endif

        guard status == noErr, tap != nil else {
            AbsLogger.error(message: "Failed to create the volume boost audio tap: \(status)")
            state.release()
            return nil
        }

        let inputParameters = AVMutableAudioMixInputParameters(track: track)
        inputParameters.audioTapProcessor = tap

        let audioMix = AVMutableAudioMix()
        audioMix.inputParameters = [inputParameters]
        return audioMix
    }
}
