// SPDX-License-Identifier: GPL-3.0-or-later
#include "SpikeEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>

#include "WavWriter.h"

#define LOG_TAG "MeaVoxSpike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace meavox::spike {

namespace {

constexpr float kTonalityLimitHz = 8000.0f;

// Adapts deinterleaved backing buffers to Signalsmith's buffer[channel][index] access.
struct BackingInput {
    const float *l;
    const float *r;
    int64_t offset;
    const float *operator[](int c) const { return (c == 0 ? l : r) + offset; }
};

struct ScratchOutput {
    float *l;
    float *r;
    float *operator[](int c) { return c == 0 ? l : r; }
};

int64_t nowNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

// Spike-quality linear resampler; production needs a band-limited one.
void resampleLinear(const std::vector<float> &src, int32_t srcRate, int32_t dstRate,
                    std::vector<float> &dst, size_t padding) {
    const size_t srcFrames = src.size();
    if (srcFrames == 0 || srcRate <= 0 || dstRate <= 0) {
        dst.assign(padding, 0.0f);
        return;
    }
    const double ratio = static_cast<double>(srcRate) / dstRate;
    const auto dstFrames = static_cast<size_t>(static_cast<double>(srcFrames) / ratio);
    dst.assign(dstFrames + padding, 0.0f);
    for (size_t i = 0; i < dstFrames; ++i) {
        const double pos = static_cast<double>(i) * ratio;
        const auto i0 = static_cast<size_t>(pos);
        const auto frac = static_cast<float>(pos - static_cast<double>(i0));
        const float a = src[i0];
        const float b = (i0 + 1 < srcFrames) ? src[i0 + 1] : a;
        dst[i] = a + (b - a) * frac;
    }
}

}  // namespace

SpikeEngine::~SpikeEngine() { close(); }

// ---------------------------------------------------------------------------------------------
// Stream lifecycle
// ---------------------------------------------------------------------------------------------

oboe::Result SpikeEngine::open() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    return openLocked();
}

oboe::Result SpikeEngine::openLocked() {
    if (mOpen.load()) return oboe::Result::OK;

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = outBuilder.openStream(mOut);
    if (result != oboe::Result::OK) {
        LOGE("open output failed: %s", oboe::convertToText(result));
        mOut.reset();
        return result;
    }
    mSampleRate = mOut->getSampleRate();
    mOut->setBufferSizeInFrames(mOut->getFramesPerBurst() * 2);

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(oboe::InputPreset::VoicePerformance)
            ->setErrorCallback(this);
    result = inBuilder.openStream(mIn);
    if (result != oboe::Result::OK) {
        LOGE("open input failed: %s", oboe::convertToText(result));
        mOut->close();
        mOut.reset();
        mIn.reset();
        return result;
    }

    const int32_t capacity = std::max(mOut->getBufferCapacityInFrames(), 4096);
    mScratchL.assign(static_cast<size_t>(capacity), 0.0f);
    mScratchR.assign(static_cast<size_t>(capacity), 0.0f);

    if (mStretchSampleRate != mSampleRate) {
        mStretch.presetCheaper(2, static_cast<float>(mSampleRate), true);
        mStretchLead = mStretch.outputSeekLength(1.0f);
        mStretchSampleRate = mSampleRate;
    }
    // Device rate or scratch capacity may have changed: re-derive the playable backing.
    rebuildBackingLocked();

    setSharedInputStream(mIn);
    setSharedOutputStream(mOut);
    mState.store(State::Idle);
    result = FullDuplexStream::start();
    if (result != oboe::Result::OK) {
        LOGE("start failed: %s", oboe::convertToText(result));
        mOut->close();
        mIn->close();
        mOut.reset();
        mIn.reset();
        return result;
    }
    mOpen.store(true);
    LOGI("open: rate=%d burst=%d buffer=%d api=%d excl(out/in)=%d/%d lowlat(out/in)=%d/%d",
         mSampleRate, mOut->getFramesPerBurst(), mOut->getBufferSizeInFrames(),
         static_cast<int>(mOut->getAudioApi()),
         mOut->getSharingMode() == oboe::SharingMode::Exclusive,
         mIn->getSharingMode() == oboe::SharingMode::Exclusive,
         mOut->getPerformanceMode() == oboe::PerformanceMode::LowLatency,
         mIn->getPerformanceMode() == oboe::PerformanceMode::LowLatency);
    return oboe::Result::OK;
}

void SpikeEngine::close() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    closeLocked();
}

oboe::Result SpikeEngine::reopen() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    return reopenLocked();
}

oboe::Result SpikeEngine::reopenLocked() {
    closeLocked();
    // The route changed: a Take, its compensation and the last measurement belong to the old
    // route (and maybe the old sample rate), and Monitoring may now be routed to a speaker or
    // Bluetooth (ADR 4). Reset all of them before audio flows again.
    mMonitoring.store(false);
    mTakeFrames.store(0);
    mCalResult.store(-1);
    mCalClicksFound.store(0);
    const oboe::Result result = openLocked();
    mRouteGeneration.fetch_add(1);
    return result;
}

void SpikeEngine::closeLocked() {
    mState.store(State::Idle);
    if (mOut) {
        if (auto x = mOut->getXRunCount()) mOutXRunBase.fetch_add(x.value());
    }
    if (mIn) {
        if (auto x = mIn->getXRunCount()) mInXRunBase.fetch_add(x.value());
    }
    if (mOpen.load()) FullDuplexStream::stop();
    if (mOut) mOut->close();
    if (mIn) mIn->close();
    mOut.reset();
    mIn.reset();
    mOpen.store(false);
}

void SpikeEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGW("stream error: %s", oboe::convertToText(error));
    if (error != oboe::Result::ErrorDisconnected) return;
    std::lock_guard<std::mutex> lock(mControlMutex);
    // Input and output may both report; only react to the streams we currently own.
    if (stream != mOut.get() && stream != mIn.get()) return;
    reopenLocked();
}

void SpikeEngine::enterIdleLocked() {
    mState.store(State::Idle);
    if (!mOpen.load()) return;
    mIdleAck.store(false);
    for (int i = 0; i < 100 && !mIdleAck.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (!mIdleAck.load()) LOGW("audio thread did not acknowledge Idle within 200 ms");
}

// ---------------------------------------------------------------------------------------------
// Backing Audio
// ---------------------------------------------------------------------------------------------

void SpikeEngine::beginBacking(int channels, int sampleRate) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    mSrcL.clear();
    mSrcR.clear();
    mSrcRate = sampleRate;
    mLoadChannels = std::max(1, channels);
    // Drop the playable backing until endBacking() so the transport can't read stale buffers.
    mBackL.clear();
    mBackR.clear();
    mBackingFrames.store(0);
    mTakeFrames.store(0);
}

void SpikeEngine::appendBacking(const float *interleaved, int frames) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const int ch = mLoadChannels;
    if (ch <= 0 || frames <= 0) return;
    mSrcL.reserve(mSrcL.size() + static_cast<size_t>(frames));
    mSrcR.reserve(mSrcR.size() + static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        const float l = interleaved[i * ch];
        const float r = ch > 1 ? interleaved[i * ch + 1] : l;
        mSrcL.push_back(l);
        mSrcR.push_back(r);
    }
}

void SpikeEngine::endBacking() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    mLoadChannels = 0;
    rebuildBackingLocked();
    mPosition.store(0);
}

void SpikeEngine::generateTestBacking(double seconds) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    const int32_t rate = mSampleRate > 0 ? mSampleRate : 48000;
    const auto frames = static_cast<size_t>(seconds * rate);
    mSrcRate = rate;
    mLoadChannels = 0;
    mSrcL.assign(frames, 0.0f);
    mSrcR.assign(frames, 0.0f);
    // A-major pad (A3, C#4, E4) plus a 120 bpm click with an accent on beat 1.
    const double chord[] = {220.0, 277.18, 329.63};
    const auto beat = static_cast<size_t>(rate / 2);
    const auto clickLen = static_cast<size_t>(rate * 0.03);
    for (size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / rate;
        double pad = 0.0;
        for (double f : chord) pad += std::sin(2.0 * M_PI * f * t);
        pad *= 0.08;
        double click = 0.0;
        const size_t inBeat = i % beat;
        if (inBeat < clickLen) {
            const bool accent = (i / beat) % 4 == 0;
            const double env = std::exp(-static_cast<double>(inBeat) / (rate * 0.004));
            click = env * std::sin(2.0 * M_PI * (accent ? 1760.0 : 1320.0) * inBeat / rate) * 0.5;
        }
        mSrcL[i] = static_cast<float>(pad + click);
        mSrcR[i] = static_cast<float>(pad + click);
    }
    mTakeFrames.store(0);
    rebuildBackingLocked();
    mPosition.store(0);
}

void SpikeEngine::rebuildBackingLocked() {
    if (mSampleRate <= 0 || mSrcL.empty()) {
        mBackL.clear();
        mBackR.clear();
        mBackingFrames.store(0);
        return;
    }
    // Padding covers the stretcher's look-ahead plus one maximal callback, with margin.
    const size_t padding = static_cast<size_t>(mStretchLead) + mScratchL.size() + mSampleRate;
    resampleLinear(mSrcL, mSrcRate, mSampleRate, mBackL, padding);
    resampleLinear(mSrcR, mSrcRate, mSampleRate, mBackR, padding);
    mBackingFrames.store(static_cast<int64_t>(mBackL.size() - padding));
    LOGI("backing: %lld frames at %d Hz (source %d Hz)",
         static_cast<long long>(mBackingFrames.load()), mSampleRate, mSrcRate);
}

// ---------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------

bool SpikeEngine::startTransportLocked(int64_t fromFrame, int transpose) {
    const int64_t frames = mBackingFrames.load();
    if (!mOpen.load() || frames <= 0) return false;
    fromFrame = std::clamp<int64_t>(fromFrame, 0, frames - 1);

    mStretch.reset();
    mStretch.setTransposeSemitones(static_cast<float>(transpose),
                                   kTonalityLimitHz / static_cast<float>(mSampleRate));
    mAppliedTranspose = transpose;
    mActiveTranspose.store(transpose);
    // Pre-roll so the next process() output is aligned with fromFrame.
    mStretch.outputSeek(BackingInput{mBackL.data(), mBackR.data(), fromFrame}, mStretchLead);
    mEmitPos = fromFrame;
    mPosition.store(fromFrame);
    return true;
}

bool SpikeEngine::play(int64_t fromFrame) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    if (!startTransportLocked(fromFrame, mNextTranspose.load())) return false;
    mState.store(State::Playing, std::memory_order_release);
    // A setTranspose() racing with this call may have skipped mActiveTranspose because the
    // state was not Playing yet; pick up the latest value now that it is.
    mActiveTranspose.store(mNextTranspose.load());
    return true;
}

bool SpikeEngine::record(int64_t fromFrame) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    const int transpose = mNextTranspose.load();
    if (!startTransportLocked(fromFrame, transpose)) return false;
    const int64_t remaining = mBackingFrames.load() - mEmitPos;
    mTake.assign(static_cast<size_t>(remaining + mSampleRate * 5), 0.0f);
    mTakeFrames.store(0);
    mTakeStart = mEmitPos;
    mTakeTranspose = transpose;
    mState.store(State::Recording, std::memory_order_release);
    return true;
}

bool SpikeEngine::review() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    if (mTakeFrames.load() <= 0) return false;
    // Review always uses the Take's own Transpose (ADR 5).
    if (!startTransportLocked(mTakeStart, mTakeTranspose)) return false;
    mReviewComp = latencyCompLocked();
    mState.store(State::Reviewing, std::memory_order_release);
    return true;
}

void SpikeEngine::stopTransport() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
}

void SpikeEngine::setTranspose(int semitones) {
    semitones = std::clamp(semitones, -6, 6);
    mNextTranspose.store(semitones);
    // Takes are bound to their Transpose (ADR 5): only plain playback follows changes live.
    if (mState.load() == State::Playing) mActiveTranspose.store(semitones);
}

void SpikeEngine::setMonitoring(bool enabled) { mMonitoring.store(enabled); }

void SpikeEngine::setMonitorGain(float gain) { mMonitorGain.store(std::clamp(gain, 0.0f, 2.0f)); }

void SpikeEngine::setLatencyCompensation(int32_t frames) { mLatencyCompSetting.store(frames); }

int64_t SpikeEngine::latencyCompLocked() {
    const int32_t setting = mLatencyCompSetting.load();
    if (setting >= 0) return setting;
    if (!mOut || !mIn || mSampleRate <= 0) return 0;
    double ms = 0.0;
    auto outLat = mOut->calculateLatencyMillis();
    auto inLat = mIn->calculateLatencyMillis();
    ms += outLat ? outLat.value() : 1000.0 * mOut->getBufferSizeInFrames() / mSampleRate;
    ms += inLat ? inLat.value() : 1000.0 * mIn->getFramesPerBurst() / mSampleRate;
    return static_cast<int64_t>(ms * mSampleRate / 1000.0);
}

// ---------------------------------------------------------------------------------------------
// Latency Calibration
// ---------------------------------------------------------------------------------------------

bool SpikeEngine::startCalibration() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    if (!mOpen.load()) return false;
    mCalOutFrame = 0;
    mCalInFrame = 0;
    mCalDetectIndex = 0;
    mCalMeasuredCount = 0;
    mCalNoisePeak = 0.0f;
    mCalClickFrames.fill(-1);
    mCalMeasured.fill(0);
    mCalResult.store(-1);
    mCalClicksFound.store(0);
    mState.store(State::Calibrating, std::memory_order_release);
    return true;
}

// Input and output frame counters both start at the first Calibrating callback, exactly like
// the Take index and emit position start together in record(); the measured offset is
// therefore the compensation that Review needs.
void SpikeEngine::processCalibration(const float *in, int numIn, float *out, int numOut) {
    const int64_t first = mSampleRate / 2;              // 0.5 s noise-floor window
    const int64_t interval = mSampleRate * 6 / 10;      // one click every 0.6 s
    const int64_t clickLen = mSampleRate * 4 / 1000;    // 4 ms burst
    const int64_t window = interval * 8 / 10;

    for (int i = 0; i < numOut; ++i) {
        const int64_t f = mCalOutFrame + i;
        float v = 0.0f;
        if (f >= first) {
            const int64_t k = (f - first) / interval;
            const int64_t phase = (f - first) % interval;
            if (k < kCalClicks && phase < clickLen) {
                if (phase == 0) mCalClickFrames[static_cast<size_t>(k)] = f;
                v = ((phase / 6) % 2) ? 0.8f : -0.8f;  // ~4 kHz square burst at 48 kHz
            }
        }
        out[2 * i] = v;
        out[2 * i + 1] = v;
    }
    mCalOutFrame += numOut;

    const float threshold = std::max(0.02f, mCalNoisePeak * 4.0f);
    for (int j = 0; j < numIn; ++j) {
        const int64_t g = mCalInFrame + j;
        const float x = std::fabs(in[j]);
        if (g < first) {
            mCalNoisePeak = std::max(mCalNoisePeak, x);
            continue;
        }
        if (mCalDetectIndex >= kCalClicks) break;
        const int64_t c = mCalClickFrames[static_cast<size_t>(mCalDetectIndex)];
        if (c < 0 || g < c) continue;
        if (g >= c + window) {
            ++mCalDetectIndex;  // missed this click
        } else if (x > threshold) {
            mCalMeasured[static_cast<size_t>(mCalMeasuredCount++)] = g - c;
            ++mCalDetectIndex;
        }
    }
    mCalInFrame += numIn;

    if (mCalDetectIndex >= kCalClicks || mCalOutFrame > first + interval * (kCalClicks + 2)) {
        finishCalibration();
    }
}

void SpikeEngine::finishCalibration() {
    const int n = mCalMeasuredCount;
    if (n >= 3) {
        std::sort(mCalMeasured.begin(), mCalMeasured.begin() + n);
        mCalResult.store(mCalMeasured[static_cast<size_t>(n / 2)]);
    } else {
        mCalResult.store(-1);
    }
    mCalClicksFound.store(n);
    State expected = State::Calibrating;
    mState.compare_exchange_strong(expected, State::Idle);
}

// ---------------------------------------------------------------------------------------------
// Audio callback
// ---------------------------------------------------------------------------------------------

oboe::DataCallbackResult SpikeEngine::onBothStreamsReady(const void *inputData, int numInputFrames,
                                                         void *outputData, int numOutputFrames) {
    const int64_t t0 = nowNanos();
    const auto *in = static_cast<const float *>(inputData);
    auto *out = static_cast<float *>(outputData);
    const int numIn = in ? numInputFrames : 0;

    float peak = 0.0f;
    for (int i = 0; i < numIn; ++i) peak = std::max(peak, std::fabs(in[i]));
    mInputPeak.store(std::max(peak, mInputPeak.load(std::memory_order_relaxed) * 0.9f),
                     std::memory_order_relaxed);

    const State state = mState.load(std::memory_order_acquire);
    switch (state) {
        case State::Idle:
            std::fill(out, out + 2 * numOutputFrames, 0.0f);
            mIdleAck.store(true, std::memory_order_release);
            break;
        case State::Calibrating:
            processCalibration(in, numIn, out, numOutputFrames);
            break;
        default:
            processTransport(state, in, numIn, out, numOutputFrames);
            break;
    }

    if (state != State::Calibrating && mMonitoring.load(std::memory_order_relaxed)) {
        const float g = mMonitorGain.load(std::memory_order_relaxed);
        const int n = std::min(numIn, numOutputFrames);
        for (int i = 0; i < n; ++i) {
            out[2 * i] += g * in[i];
            out[2 * i + 1] += g * in[i];
        }
    }
    for (int i = 0; i < 2 * numOutputFrames; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);

    if (mSampleRate > 0 && numOutputFrames > 0) {
        const double budget = 1e9 * numOutputFrames / mSampleRate;
        const auto load = static_cast<float>(static_cast<double>(nowNanos() - t0) / budget);
        const float prev = mCpuLoad.load(std::memory_order_relaxed);
        mCpuLoad.store(load > prev ? load : prev * 0.995f + load * 0.005f, std::memory_order_relaxed);
    }
    return oboe::DataCallbackResult::Continue;
}

void SpikeEngine::processTransport(State state, const float *in, int numIn, float *out, int numOut) {
    if (numOut > static_cast<int>(mScratchL.size())) {
        std::fill(out, out + 2 * numOut, 0.0f);  // never expected; capacity sized at open
        return;
    }
    const int desired = mActiveTranspose.load(std::memory_order_relaxed);
    if (desired != mAppliedTranspose) {
        mStretch.setTransposeSemitones(static_cast<float>(desired),
                                       kTonalityLimitHz / static_cast<float>(mSampleRate));
        mAppliedTranspose = desired;
    }

    mStretch.process(BackingInput{mBackL.data(), mBackR.data(), mEmitPos + mStretchLead}, numOut,
                     ScratchOutput{mScratchL.data(), mScratchR.data()}, numOut);
    for (int i = 0; i < numOut; ++i) {
        out[2 * i] = mScratchL[static_cast<size_t>(i)];
        out[2 * i + 1] = mScratchR[static_cast<size_t>(i)];
    }

    const int64_t takeFrames = mTakeFrames.load(std::memory_order_relaxed);
    if (state == State::Recording) {
        // Index by timeline position, not by input frames received, so an input shortfall
        // leaves a silent gap instead of shifting the rest of the Take earlier.
        if (numIn < numOut) mInputShortfalls.fetch_add(1, std::memory_order_relaxed);
        const int64_t base = mEmitPos - mTakeStart;
        const auto capacity = static_cast<int64_t>(mTake.size());
        const int64_t n = std::min<int64_t>(std::min(numIn, numOut), capacity - base);
        if (n > 0) std::copy(in, in + n, mTake.begin() + base);
        const int64_t written = std::min<int64_t>(base + numOut, capacity);
        if (written > takeFrames) mTakeFrames.store(written, std::memory_order_relaxed);
    } else if (state == State::Reviewing) {
        for (int i = 0; i < numOut; ++i) {
            const int64_t j = mEmitPos + i - mTakeStart + mReviewComp;
            if (j >= 0 && j < takeFrames) {
                const float v = mTake[static_cast<size_t>(j)];
                out[2 * i] += v;
                out[2 * i + 1] += v;
            }
        }
    }

    mEmitPos += numOut;
    mPosition.store(mEmitPos, std::memory_order_relaxed);

    bool finished = mEmitPos >= mBackingFrames.load(std::memory_order_relaxed);
    if (state == State::Reviewing && mEmitPos - mTakeStart + mReviewComp >= takeFrames) {
        finished = true;
    }
    if (finished) {
        State expected = state;
        mState.compare_exchange_strong(expected, State::Idle);
    }
}

// ---------------------------------------------------------------------------------------------
// Stats & alignment export
// ---------------------------------------------------------------------------------------------

void SpikeEngine::getStats(double *out, int count) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    std::fill(out, out + count, 0.0);
    if (count < kStatCount) return;
    out[kStatState] = static_cast<double>(mState.load());
    out[kStatStreamOpen] = mOpen.load() ? 1.0 : 0.0;
    out[kStatSampleRate] = mSampleRate;
    out[kStatOutputLatencyMs] = -1.0;
    out[kStatInputLatencyMs] = -1.0;
    if (mOpen.load() && mOut && mIn) {
        out[kStatFramesPerBurst] = mOut->getFramesPerBurst();
        out[kStatBufferSize] = mOut->getBufferSizeInFrames();
        out[kStatOutputApi] = static_cast<double>(mOut->getAudioApi());
        out[kStatOutputExclusive] = mOut->getSharingMode() == oboe::SharingMode::Exclusive;
        out[kStatInputExclusive] = mIn->getSharingMode() == oboe::SharingMode::Exclusive;
        out[kStatOutputLowLatency] = mOut->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
        out[kStatInputLowLatency] = mIn->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
        if (auto l = mOut->calculateLatencyMillis()) out[kStatOutputLatencyMs] = l.value();
        if (auto l = mIn->calculateLatencyMillis()) out[kStatInputLatencyMs] = l.value();
        out[kStatXRuns] = static_cast<double>(mOutXRunBase.load());
        if (auto x = mOut->getXRunCount()) out[kStatXRuns] += x.value();
        out[kStatInputXRuns] = static_cast<double>(mInXRunBase.load());
        if (auto x = mIn->getXRunCount()) out[kStatInputXRuns] += x.value();
        out[kStatOutputDeviceId] = mOut->getDeviceId();
        out[kStatLatencyCompFrames] = static_cast<double>(latencyCompLocked());
    }
    out[kStatCpuLoad] = mCpuLoad.load();
    out[kStatInputPeak] = mInputPeak.load();
    out[kStatPositionFrames] = static_cast<double>(mPosition.load());
    out[kStatBackingFrames] = static_cast<double>(mBackingFrames.load());
    out[kStatTakeFrames] = static_cast<double>(mTakeFrames.load());
    out[kStatTakeStartFrame] = static_cast<double>(mTakeStart);
    out[kStatTakeTranspose] = mTakeTranspose;
    out[kStatCalibrationFrames] = static_cast<double>(mCalResult.load());
    out[kStatCalibrationClicks] = mCalClicksFound.load();
    out[kStatStretchLatencyFrames] = mStretchLead;
    out[kStatRouteGeneration] = mRouteGeneration.load();
    out[kStatInputShortfalls] = static_cast<double>(mInputShortfalls.load());
}

bool SpikeEngine::saveAlignmentWav(const std::string &path) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    enterIdleLocked();
    const int64_t takeFrames = mTakeFrames.load();
    if (takeFrames <= 0 || mSampleRate <= 0) return false;
    const int64_t comp = latencyCompLocked();
    const int64_t backingFrames = mBackingFrames.load();
    const int64_t length = std::max<int64_t>(takeFrames - comp, 0);
    std::vector<float> interleaved(static_cast<size_t>(length) * 2, 0.0f);
    for (int64_t k = 0; k < length; ++k) {
        const int64_t b = mTakeStart + k;
        const int64_t t = k + comp;
        const auto bi = static_cast<size_t>(b);
        interleaved[static_cast<size_t>(2 * k)] =
                b < backingFrames ? 0.5f * (mBackL[bi] + mBackR[bi]) : 0.0f;
        interleaved[static_cast<size_t>(2 * k + 1)] =
                (t >= 0 && t < takeFrames) ? mTake[static_cast<size_t>(t)] : 0.0f;
    }
    return writeWav16(path, interleaved.data(), length, 2, mSampleRate);
}

}  // namespace meavox::spike
