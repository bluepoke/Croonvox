// SPDX-License-Identifier: GPL-3.0-or-later
//
// Throwaway spike of the Croonvox Audio Engine (ticket #2, ADR 4, ADR 5).
//
// One full-duplex Oboe stream pair owns a single sample-accurate timeline:
//   - Backing Audio playback through Signalsmith Stretch (live Transpose)
//   - microphone capture into a Take
//   - optional dry Monitoring
//   - Review (Backing Audio + latency-compensated Take)
//   - Latency Calibration (click out -> detect in)
//
// Threading: public methods run on non-audio threads and are serialised by mControlMutex.
// Data shared with the audio callback (backing, take, stretcher) is only mutated while the
// engine is Idle *and* the audio thread has acknowledged Idle (see enterIdleLocked()).
#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace croonvox::spike {

enum class State : int32_t {
    Idle = 0,
    Playing = 1,
    Recording = 2,
    Reviewing = 3,
    Calibrating = 4,
};

// Indices into the stats array filled by getStats(). Mirrored in EngineStats.kt.
enum StatIndex : int {
    kStatState = 0,
    kStatStreamOpen,
    kStatSampleRate,
    kStatFramesPerBurst,
    kStatBufferSize,
    kStatOutputApi,           // 0 unspecified, 1 OpenSL ES, 2 AAudio
    kStatOutputExclusive,
    kStatInputExclusive,
    kStatOutputLowLatency,
    kStatInputLowLatency,
    kStatOutputLatencyMs,     // Oboe-reported, -1 if unknown
    kStatInputLatencyMs,      // Oboe-reported, -1 if unknown
    kStatXRuns,
    kStatCpuLoad,             // callback time / callback budget, peak-hold with decay
    kStatInputPeak,
    kStatPositionFrames,
    kStatBackingFrames,
    kStatTakeFrames,
    kStatTakeStartFrame,
    kStatTakeTranspose,
    kStatCalibrationFrames,   // measured round trip, -1 if none
    kStatCalibrationClicks,   // clicks detected in the last calibration
    kStatStretchLatencyFrames,
    kStatOutputDeviceId,
    kStatRouteGeneration,
    kStatLatencyCompFrames,   // compensation currently applied to Takes
    kStatInputXRuns,          // input overruns, accumulated across reopens
    kStatInputShortfalls,     // callbacks that delivered fewer input than output frames
    kStatCount
};

class SpikeEngine : public oboe::FullDuplexStream, public oboe::AudioStreamErrorCallback {
public:
    SpikeEngine() = default;
    ~SpikeEngine() override;

    oboe::Result open();
    void close();
    // Close and reopen the streams, e.g. after an output route change. Stops the transport,
    // discards the Take (it belongs to the old route and possibly the old sample rate) and
    // forces Monitoring off until the UI re-enables it for an allowed route.
    oboe::Result reopen();

    // Backing Audio loading: decoded PCM at any rate, 1..n channels interleaved.
    void beginBacking(int channels, int sampleRate);
    void appendBacking(const float *interleaved, int frames);
    void endBacking();
    void generateTestBacking(double seconds);

    bool play(int64_t fromFrame);
    bool record(int64_t fromFrame);
    bool review();
    void stopTransport();
    bool startCalibration();

    void setTranspose(int semitones);
    void setMonitoring(bool enabled);
    void setMonitorGain(float gain);
    // Round-trip compensation applied to Takes, in frames; negative = use Oboe's estimate.
    void setLatencyCompensation(int32_t frames);

    void getStats(double *out, int count);

    // Stereo 16-bit WAV: left = Backing Audio (untransposed), right = latency-compensated
    // Take, both starting at the Take's Start Position. For checking alignment in an editor.
    bool saveAlignmentWav(const std::string &path);

    // oboe::FullDuplexStream
    oboe::DataCallbackResult onBothStreamsReady(const void *inputData, int numInputFrames,
                                                void *outputData, int numOutputFrames) override;
    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kCalClicks = 8;

    oboe::Result openLocked();
    void closeLocked();
    oboe::Result reopenLocked();
    void enterIdleLocked();
    void rebuildBackingLocked();
    bool startTransportLocked(int64_t fromFrame, int transpose);
    int64_t latencyCompLocked();

    void processTransport(State state, const float *in, int numIn, float *out, int numOut);
    void processCalibration(const float *in, int numIn, float *out, int numOut);
    void finishCalibration();

    std::mutex mControlMutex;
    std::shared_ptr<oboe::AudioStream> mOut;
    std::shared_ptr<oboe::AudioStream> mIn;
    std::atomic<bool> mOpen{false};
    std::atomic<State> mState{State::Idle};
    std::atomic<bool> mIdleAck{false};
    std::atomic<int32_t> mRouteGeneration{0};
    int32_t mSampleRate = 0;
    int32_t mStretchSampleRate = 0;

    // Backing Audio as decoded (source rate, deinterleaved stereo).
    std::vector<float> mSrcL, mSrcR;
    int32_t mSrcRate = 0;
    int32_t mLoadChannels = 0;
    // Backing Audio resampled to the device rate, padded with silence for stretcher look-ahead.
    std::vector<float> mBackL, mBackR;
    std::atomic<int64_t> mBackingFrames{0};

    signalsmith::stretch::SignalsmithStretch<float> mStretch;
    int32_t mStretchLead = 0;   // input look-ahead so stretcher output is aligned to the timeline
    int32_t mAppliedTranspose = 0;
    std::vector<float> mScratchL, mScratchR;

    int64_t mEmitPos = 0;       // timeline frame of the next output frame (audio thread)
    std::atomic<int64_t> mPosition{0};
    std::atomic<int32_t> mNextTranspose{0};    // what the next Take will be sung at
    std::atomic<int32_t> mActiveTranspose{0};  // what the audio thread applies now

    std::atomic<bool> mMonitoring{false};
    std::atomic<float> mMonitorGain{1.0f};

    std::vector<float> mTake;   // mic samples, mono, indexed by timeline frame - Start Position
    std::atomic<int64_t> mTakeFrames{0};
    int64_t mTakeStart = 0;
    int32_t mTakeTranspose = 0;
    int64_t mReviewComp = 0;
    std::atomic<int32_t> mLatencyCompSetting{-1};

    // Calibration state (audio thread while Calibrating).
    int64_t mCalOutFrame = 0;
    int64_t mCalInFrame = 0;
    int mCalDetectIndex = 0;
    int mCalMeasuredCount = 0;
    float mCalNoisePeak = 0.0f;
    std::array<int64_t, kCalClicks> mCalClickFrames{};
    std::array<int64_t, kCalClicks> mCalMeasured{};
    std::atomic<int64_t> mCalResult{-1};
    std::atomic<int32_t> mCalClicksFound{0};

    // XRun counters reset when streams are reopened; keep running totals across reopens.
    std::atomic<int64_t> mOutXRunBase{0};
    std::atomic<int64_t> mInXRunBase{0};
    std::atomic<int64_t> mInputShortfalls{0};

    std::atomic<float> mCpuLoad{0.0f};
    std::atomic<float> mInputPeak{0.0f};
};

}  // namespace croonvox::spike
