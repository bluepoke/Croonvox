// SPDX-License-Identifier: GPL-3.0-or-later
//
// Production Audio Engine (ADR 4, ADR 13). Plays one audio file (Backing Audio) with play /
// pause / seek. In Session mode (ticket #8) it runs low-latency streams for as long as the
// Session is open: the microphone is read on the output callback, so playback and recording
// share one timeline, and a Take can be recorded from any Start Position and played back
// against the Backing Audio. Built on the design validated by spike #2.
//
// Timeline: frames at the loaded file's sample rate. Streams are opened at that rate and Oboe
// resamples to the device, so Backing Audio, Takes and positions all use one rate.
//
// Progressive loading: playback may start while the file is still being decoded. Decoded audio
// is appended in fixed-size blocks that are never reallocated; the callback reads up to the
// published decoded frame count. Playing past it outputs silence until more is decoded.
//
// Threading: public methods may be called from any non-audio thread; mutating ones are
// serialised by mControlMutex. Blocks are freed only while the stream is closed. The Take
// buffer is changed only while the transport is idle and the callback has acknowledged it
// (enterIdleLocked). One instance lives as long as the process (it is never destroyed while
// Oboe's error thread could still call back into it).
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace croonvox {

enum class Transport : int32_t {
    Idle = 0,
    Playing = 1,
    Recording = 2,
    PlayingTake = 3,
};

class PlaybackEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    PlaybackEngine() = default;
    ~PlaybackEngine() override;

    // Starts a new load (stopping playback and releasing the previous song and Take first):
    // decoded PCM at the file's own rate, 1..n channels interleaved. [estimatedFrames] (0 if
    // unknown) is reported as the duration until the load completes.
    void beginLoad(int channels, int sampleRate, int64_t estimatedFrames);
    // Returns false once the reserved capacity is exhausted (the rest is dropped; stop decoding).
    bool appendLoad(const float *interleaved, int frames);
    // Marks the load complete; the duration becomes exact.
    void endLoad();
    // Abandons a load and frees its memory.
    void discardLoad();

    // Returns false if nothing is loaded or the output stream could not be started.
    bool play();
    void pause();
    void seekTo(int64_t frame);

    // --- Session mode ---
    // Entering opens low-latency streams that keep running; leaving closes the microphone and
    // drops the Take.
    void setSessionMode(bool enabled);
    // Opens the microphone (Session mode only; needs the RECORD_AUDIO permission).
    bool enableInput();
    void setInputGain(float linear);
    // Backing Audio gain while the held Take plays (Review balance); recording is unaffected.
    void setReviewBackingGain(float linear);
    // Plays from [fromFrame] and records the microphone into a new Take. False without input.
    bool startRecording(int64_t fromFrame);
    // Stops recording (if running); the captured Take stays held.
    void stopRecording();
    // Plays the held Take with the Backing Audio from the Take's start until the Take ends.
    bool playTake(int64_t alignmentFrames);
    void clearTake();
    // Replaces the held Take with stored audio (mono, at the timeline rate).
    bool beginTakeLoad(int64_t startFrame, int sampleRate, int64_t estimatedFrames);
    void appendTakeLoad(const float *interleaved, int frames, int channels);
    // Writes the held Take as mono 24-bit FLAC. False on I/O or encoder errors.
    bool writeTakeFlac(const std::string &path);

    Transport transport() const { return mTransport.load(); }
    bool isPlaying() const { return mTransport.load() != Transport::Idle; }
    int64_t positionFrames() const { return mPosition.load(); }
    int64_t durationFrames() const;
    int64_t decodedFrames() const { return mDecoded.load(); }
    int32_t sampleRate() const { return mSampleRate.load(); }

    bool inputEnabled() const { return mInputOpen.load(); }
    // Decaying peak after gain, and the number of clipped callbacks so far.
    float inputPeak() const { return mInputPeak.load(); }
    int64_t clipCount() const { return mClipCount.load(); }
    int32_t outputDeviceId();
    int32_t inputDeviceId();
    int64_t takeStartFrame() const { return mTakeStart; }
    int64_t takeFrames() const { return mTakeFrames.load(); }
    // Output + input latency as reported when the last recording started (ADR 13).
    int64_t takeReportedLatencyFrames() const { return mTakeLatency; }
    // Frames of microphone input dropped to undo output xruns during the last recording.
    int64_t takeXRunCorrectionFrames() const { return mXRunCorrection.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int64_t kBlockFrames = 1 << 16;  // ~1.5 s at 44.1 kHz, 512 KiB stereo float
    static constexpr int kDrainCallbacks = 10;        // empty the input after a (re)start
    static constexpr int kXRunMeasureCallbacks = 16;  // measure input backlog after an xrun
    static constexpr int kLostInputCallbacks = 50;    // a deficit this old (~200 ms) was lost, not late

    bool startStreamLocked();
    bool openSessionStreamsLocked();
    void closeStreamLocked();
    void freeBlocksLocked();
    void enterIdleLocked();
    bool startTransportLocked(int64_t fromFrame, Transport transport);
    int64_t reportedLatencyFramesLocked();

    int readInput(int numFrames);
    void trackXRuns();
    void mixBacking(int64_t pos, int64_t decoded, float *out, int32_t numFrames, int32_t &written);

    std::mutex mControlMutex;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::shared_ptr<oboe::AudioStream> mInput;

    // Interleaved stereo blocks. The pointer table is reserved up front, so appending never
    // reallocates it while the callback reads; entries below mDecoded are immutable.
    std::vector<float *> mBlocks;
    int32_t mLoadChannels = 0;  // > 0 while a load is in progress

    std::atomic<int64_t> mDecoded{0};
    std::atomic<int64_t> mEstimated{0};
    std::atomic<bool> mComplete{false};
    std::atomic<int32_t> mSampleRate{0};
    std::atomic<Transport> mTransport{Transport::Idle};
    std::atomic<bool> mIdleAck{false};
    std::atomic<int64_t> mPosition{0};
    std::atomic<int64_t> mSeekRequest{-1};

    // Session mode.
    bool mSession = false;
    bool mInputWanted = false;
    std::atomic<bool> mInputOpen{false};
    std::atomic<float> mInputGain{1.0f};
    std::atomic<float> mReviewBackingGain{1.0f};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<int64_t> mClipCount{0};
    std::vector<float> mInputBuffer;  // sized when the input opens; callback only
    std::vector<float> mDropBuffer;
    int mDrainLeft = 0;
    bool mCushion = false;  // skip one read after draining, so reads don't race the input's writes

    // Take: mono, index i = timeline frame mTakeStart + i while recording.
    std::vector<float> mTake;
    std::atomic<int64_t> mTakeFrames{0};
    int64_t mTakeStart = 0;
    // While recording (audio thread): input is written contiguously at mTakeCursor, which
    // follows the output. A deficit (input arrived late) is read on top of the next callbacks.
    int64_t mTakeCursor = 0;
    int64_t mTakeDeficit = 0;
    int mDeficitCallbacks = 0;
    int64_t mTakeLatency = 0;
    std::atomic<int64_t> mTakeAlignment{0};

    // Xrun correction while recording (audio thread, ADR 13): an output underrun delays the
    // output without delaying the microphone, so the input backlog grows by the frames lost.
    int32_t mLastOutXRuns = -1;
    int32_t mBacklogFloor = INT32_MAX;
    int32_t mXRunBacklogMin = INT32_MAX;
    int mXRunMeasureLeft = 0;
    std::atomic<int64_t> mXRunCorrection{0};
};

}  // namespace croonvox
