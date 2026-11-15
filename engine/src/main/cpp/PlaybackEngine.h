// SPDX-License-Identifier: GPL-3.0-or-later
//
// Production Audio Engine, first slice (ticket #5): plays one audio file (a Track's Original
// Audio) with play / pause / seek. Built on the design validated by spike #2 (ADR 4, ADR 13): an Oboe output stream whose
// callback reads only atomics and memory that never moves under it.
//
// Progressive loading: playback may start while the file is still being decoded. Decoded audio
// is appended in fixed-size blocks that are never reallocated; the callback reads up to the
// published decoded frame count. Playing past it outputs silence until more is decoded.
//
// Threading: public methods may be called from any non-audio thread; mutating ones are
// serialised by mControlMutex. Blocks are freed only while the stream is closed, and the stream
// runs only while playing. One instance lives as long as the process (it is never destroyed
// while Oboe's error thread could still call back into it).
#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace croonvox {

class PlaybackEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    PlaybackEngine() = default;
    ~PlaybackEngine() override;

    // Starts a new load (stopping playback and releasing the previous song first): decoded PCM
    // at the file's own rate, 1..n channels interleaved. [estimatedFrames] (0 if unknown) is
    // reported as the duration until the load completes.
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

    bool isPlaying() const { return mPlaying.load(); }
    int64_t positionFrames() const { return mPosition.load(); }
    int64_t durationFrames() const;
    int64_t decodedFrames() const { return mDecoded.load(); }
    int32_t sampleRate() const { return mSampleRate.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int64_t kBlockFrames = 1 << 16;  // ~1.5 s at 44.1 kHz, 512 KiB stereo float

    bool startStreamLocked();
    void closeStreamLocked();
    void freeBlocksLocked();

    std::mutex mControlMutex;
    std::shared_ptr<oboe::AudioStream> mStream;

    // Interleaved stereo blocks. The pointer table is reserved up front, so appending never
    // reallocates it while the callback reads; entries below mDecoded are immutable.
    std::vector<float *> mBlocks;
    int32_t mLoadChannels = 0;  // > 0 while a load is in progress

    std::atomic<int64_t> mDecoded{0};
    std::atomic<int64_t> mEstimated{0};
    std::atomic<bool> mComplete{false};
    std::atomic<int32_t> mSampleRate{0};
    std::atomic<bool> mPlaying{false};
    std::atomic<int64_t> mPosition{0};
    std::atomic<int64_t> mSeekRequest{-1};
};

}  // namespace croonvox
