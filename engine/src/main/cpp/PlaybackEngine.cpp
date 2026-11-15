// SPDX-License-Identifier: GPL-3.0-or-later
#include "PlaybackEngine.h"

#include <android/log.h>

#include <algorithm>

#define LOG_TAG "CroonvoxEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace croonvox {

namespace {
constexpr int64_t kMaxBlocksWithoutEstimate = 4096;  // ~100 min at 44.1 kHz
}

PlaybackEngine::~PlaybackEngine() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();
    freeBlocksLocked();
}

void PlaybackEngine::freeBlocksLocked() {
    for (float *block : mBlocks) delete[] block;
    std::vector<float *>().swap(mBlocks);
    mDecoded.store(0);
}

void PlaybackEngine::beginLoad(int channels, int sampleRate, int64_t estimatedFrames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();  // the callback is guaranteed not to run past this point
    freeBlocksLocked();   // release the previous song before decoding the next
    mPlaying.store(false);
    mPosition.store(0);
    mSeekRequest.store(-1);
    mComplete.store(false);
    mEstimated.store(std::max<int64_t>(estimatedFrames, 0));
    // Generous table so appends never reallocate it (duration estimates of e.g. VBR MP3 can be off).
    const int64_t maxBlocks = estimatedFrames > 0 ? estimatedFrames * 5 / 4 / kBlockFrames + 16 : kMaxBlocksWithoutEstimate;
    mBlocks.reserve(static_cast<size_t>(maxBlocks));
    mLoadChannels = std::max(1, channels);
    mSampleRate.store(sampleRate);
}

bool PlaybackEngine::appendLoad(const float *interleaved, int frames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const int ch = mLoadChannels;
    if (ch <= 0 || frames <= 0) return ch > 0;
    int64_t decoded = mDecoded.load();
    bool fits = true;
    for (int i = 0; i < frames; ++i) {
        const int64_t block = decoded / kBlockFrames;
        if (block >= static_cast<int64_t>(mBlocks.size())) {
            if (mBlocks.size() == mBlocks.capacity()) {
                LOGW("audio longer than reserved; truncated at %lld frames", static_cast<long long>(decoded));
                fits = false;
                break;
            }
            mBlocks.push_back(new float[kBlockFrames * 2]);  // within capacity: no reallocation
        }
        float *dst = mBlocks[static_cast<size_t>(block)] + (decoded % kBlockFrames) * 2;
        const float l = interleaved[i * ch];
        dst[0] = l;
        dst[1] = ch > 1 ? interleaved[i * ch + 1] : l;  // mono -> both; >2 -> first two
        ++decoded;
    }
    mDecoded.store(decoded, std::memory_order_release);  // publish after the samples are written
    return fits;
}

void PlaybackEngine::endLoad() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLoadChannels = 0;
    mComplete.store(true, std::memory_order_release);  // after the last mDecoded publication
    LOGI("loaded %lld frames at %d Hz", static_cast<long long>(mDecoded.load()), mSampleRate.load());
}

void PlaybackEngine::discardLoad() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeStreamLocked();
    freeBlocksLocked();
    mPlaying.store(false);
    mPosition.store(0);
    mLoadChannels = 0;
    mComplete.store(false);
    mEstimated.store(0);
}

int64_t PlaybackEngine::durationFrames() const {
    const int64_t decoded = mDecoded.load();
    return mComplete.load() ? decoded : std::max(decoded, mEstimated.load());
}

bool PlaybackEngine::play() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mDecoded.load() <= 0) return false;
    if (mComplete.load() && mPosition.load() >= mDecoded.load()) {  // replay from the start after the end
        mPosition.store(0);
        mSeekRequest.store(0);
    }
    mPlaying.store(true);
    if (!startStreamLocked()) {
        mPlaying.store(false);
        return false;
    }
    return true;
}

void PlaybackEngine::pause() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mPlaying.store(false);
    closeStreamLocked();
}

void PlaybackEngine::seekTo(int64_t frame) {
    const int64_t clamped = std::clamp<int64_t>(frame, 0, std::max<int64_t>(durationFrames(), 0));
    // Position first: a callback that consumes the request then stores a later position.
    mPosition.store(clamped);
    mSeekRequest.store(clamped);
}

bool PlaybackEngine::startStreamLocked() {
    if (mStream) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            // Plain playback: favour battery over latency. Sessions get their own low-latency path.
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(mSampleRate.load())
            // Oboe resamples band-limited to the device rate.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = builder.openStream(mStream);
    if (result == oboe::Result::OK) result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("could not start output: %s", oboe::convertToText(result));
        if (mStream) mStream->close();
        mStream.reset();
        return false;
    }
    return true;
}

void PlaybackEngine::closeStreamLocked() {
    if (!mStream) return;
    mStream->stop();  // synchronous: no callback runs after this returns
    mStream->close();
    mStream.reset();
}

void PlaybackEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGW("stream error: %s", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (stream != mStream.get()) return;  // already replaced or closed
    mStream.reset();                      // Oboe has closed it
    // Route change (e.g. headphones unplugged) or other failure: pause, as media players do.
    // The next play() opens a stream on the new route.
    mPlaying.store(false);
}

oboe::DataCallbackResult PlaybackEngine::onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    // Completion first: if it is set, the decoded count read after it is final (see endLoad()).
    const bool complete = mComplete.load(std::memory_order_acquire);
    const int64_t decoded = mDecoded.load(std::memory_order_acquire);
    const int64_t seek = mSeekRequest.exchange(-1);
    int64_t pos = seek >= 0 ? seek : mPosition.load(std::memory_order_relaxed);

    int32_t written = 0;
    if (mPlaying.load(std::memory_order_relaxed)) {
        // Copy what is decoded; past that, output silence and wait (still decoding).
        while (written < numFrames && pos < decoded) {
            const int64_t inBlock = pos % kBlockFrames;
            const auto n = static_cast<int32_t>(
                    std::min<int64_t>({numFrames - written, decoded - pos, kBlockFrames - inBlock}));
            const float *src = mBlocks[static_cast<size_t>(pos / kBlockFrames)] + inBlock * 2;
            std::copy_n(src, static_cast<size_t>(n) * 2, out + static_cast<size_t>(written) * 2);
            written += n;
            pos += n;
        }
        if (complete && pos >= decoded) mPlaying.store(false);  // reached the end; Kotlin stops the stream
    }
    std::fill(out + static_cast<size_t>(written) * 2, out + static_cast<size_t>(numFrames) * 2, 0.0f);
    // A seek that arrived while this callback ran wins over our position.
    if (mSeekRequest.load(std::memory_order_relaxed) < 0) mPosition.store(pos, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

}  // namespace croonvox
