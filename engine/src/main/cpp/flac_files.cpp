// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for app.croonvox.engine.FlacFiles: streaming 24-bit FLAC files of float PCM with any
// channel count libFLAC supports (1..8), for the stems and Separation checkpoints (ADR 8, ADR 15).
#include <jni.h>

#include <FLAC/stream_decoder.h>
#include <FLAC/stream_encoder.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <deque>
#include <string>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CroonvoxFlac", __VA_ARGS__)

namespace {

constexpr int kBitsPerSample = 24;

std::string toString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

struct Writer {
    FLAC__StreamEncoder *encoder;
    int channels;
    std::vector<FLAC__int32> scratch;
};

struct Reader {
    FLAC__StreamDecoder *decoder = nullptr;
    int channels = 0;
    int sampleRate = 0;
    int bits = 0;
    FLAC__uint64 totalFrames = 0;
    std::deque<float> pending;  // interleaved samples decoded but not yet handed out
    bool error = false;
};

FLAC__StreamDecoderWriteStatus onFrame(const FLAC__StreamDecoder *, const FLAC__Frame *frame,
                                       const FLAC__int32 *const buffer[], void *data) {
    auto *reader = static_cast<Reader *>(data);
    const int channels = static_cast<int>(frame->header.channels);
    const float scale = 1.0f / static_cast<float>(1 << (frame->header.bits_per_sample - 1));
    for (uint32_t i = 0; i < frame->header.blocksize; ++i) {
        for (int c = 0; c < channels; ++c) reader->pending.push_back(static_cast<float>(buffer[c][i]) * scale);
    }
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

void onMetadata(const FLAC__StreamDecoder *, const FLAC__StreamMetadata *metadata, void *data) {
    if (metadata->type != FLAC__METADATA_TYPE_STREAMINFO) return;
    auto *reader = static_cast<Reader *>(data);
    reader->channels = static_cast<int>(metadata->data.stream_info.channels);
    reader->sampleRate = static_cast<int>(metadata->data.stream_info.sample_rate);
    reader->bits = static_cast<int>(metadata->data.stream_info.bits_per_sample);
    reader->totalFrames = metadata->data.stream_info.total_samples;
}

void onError(const FLAC__StreamDecoder *, FLAC__StreamDecoderErrorStatus status, void *data) {
    LOGE("decode: %s", FLAC__StreamDecoderErrorStatusString[status]);
    static_cast<Reader *>(data)->error = true;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_croonvox_engine_FlacFiles_##name

JNI_FN(jlong, openWriter)(JNIEnv *env, jobject, jstring path, jint channels, jint sampleRate) {
    FLAC__StreamEncoder *encoder = FLAC__stream_encoder_new();
    if (!encoder) return 0;
    bool ok = FLAC__stream_encoder_set_channels(encoder, static_cast<uint32_t>(channels)) &&
              FLAC__stream_encoder_set_bits_per_sample(encoder, kBitsPerSample) &&
              FLAC__stream_encoder_set_sample_rate(encoder, static_cast<uint32_t>(sampleRate)) &&
              FLAC__stream_encoder_set_compression_level(encoder, 5);
    ok = ok && FLAC__stream_encoder_init_file(encoder, toString(env, path).c_str(), nullptr, nullptr) ==
                       FLAC__STREAM_ENCODER_INIT_STATUS_OK;
    if (!ok) {
        LOGE("open writer: %s", FLAC__stream_encoder_get_resolved_state_string(encoder));
        FLAC__stream_encoder_delete(encoder);
        return 0;
    }
    return reinterpret_cast<jlong>(new Writer{encoder, channels, {}});
}

JNI_FN(jboolean, write)(JNIEnv *env, jobject, jlong handle, jfloatArray interleaved, jint frames) {
    auto *writer = reinterpret_cast<Writer *>(handle);
    const size_t count = static_cast<size_t>(frames) * static_cast<size_t>(writer->channels);
    if (writer->scratch.size() < count) writer->scratch.resize(count);
    jfloat *samples = env->GetFloatArrayElements(interleaved, nullptr);
    if (!samples) return JNI_FALSE;
    constexpr float scale = static_cast<float>((1 << (kBitsPerSample - 1)) - 1);
    for (size_t i = 0; i < count; ++i) {
        writer->scratch[i] = static_cast<FLAC__int32>(std::lround(std::clamp(samples[i], -1.0f, 1.0f) * scale));
    }
    env->ReleaseFloatArrayElements(interleaved, samples, JNI_ABORT);
    const bool ok = FLAC__stream_encoder_process_interleaved(writer->encoder, writer->scratch.data(),
                                                             static_cast<uint32_t>(frames));
    if (!ok) LOGE("write: %s", FLAC__stream_encoder_get_resolved_state_string(writer->encoder));
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Completes the file (STREAMINFO) and frees the writer. Also used to abort; the caller deletes the file.
JNI_FN(jboolean, closeWriter)(JNIEnv *, jobject, jlong handle) {
    auto *writer = reinterpret_cast<Writer *>(handle);
    const bool ok = FLAC__stream_encoder_finish(writer->encoder);
    if (!ok) LOGE("finish: %s", FLAC__stream_encoder_get_resolved_state_string(writer->encoder));
    FLAC__stream_encoder_delete(writer->encoder);
    delete writer;
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Returns 0 if the file can't be opened as FLAC. info receives channels, sample rate.
JNI_FN(jlong, openReader)(JNIEnv *env, jobject, jstring path, jintArray info) {
    auto *reader = new Reader();
    reader->decoder = FLAC__stream_decoder_new();
    bool ok = reader->decoder &&
              FLAC__stream_decoder_init_file(reader->decoder, toString(env, path).c_str(), onFrame, onMetadata, onError,
                                             reader) == FLAC__STREAM_DECODER_INIT_STATUS_OK &&
              FLAC__stream_decoder_process_until_end_of_metadata(reader->decoder) && reader->channels > 0 &&
              !reader->error;
    if (!ok) {
        if (reader->decoder) FLAC__stream_decoder_delete(reader->decoder);
        delete reader;
        return 0;
    }
    jint values[2] = {reader->channels, reader->sampleRate};
    env->SetIntArrayRegion(info, 0, 2, values);
    return reinterpret_cast<jlong>(reader);
}

// Fills out with up to maxFrames interleaved frames. Returns the frames written, 0 at the end, -1 on error.
JNI_FN(jint, read)(JNIEnv *env, jobject, jlong handle, jfloatArray out, jint maxFrames) {
    auto *reader = reinterpret_cast<Reader *>(handle);
    const size_t wanted = static_cast<size_t>(maxFrames) * static_cast<size_t>(reader->channels);
    while (reader->pending.size() < wanted &&
           FLAC__stream_decoder_get_state(reader->decoder) != FLAC__STREAM_DECODER_END_OF_STREAM) {
        if (!FLAC__stream_decoder_process_single(reader->decoder) || reader->error) return -1;
    }
    const size_t count = std::min(wanted, reader->pending.size());
    std::vector<float> chunk(reader->pending.begin(), reader->pending.begin() + static_cast<long>(count));
    reader->pending.erase(reader->pending.begin(), reader->pending.begin() + static_cast<long>(count));
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(count), chunk.data());
    return static_cast<jint>(count / static_cast<size_t>(reader->channels));
}

JNI_FN(void, closeReader)(JNIEnv *, jobject, jlong handle) {
    auto *reader = reinterpret_cast<Reader *>(handle);
    FLAC__stream_decoder_finish(reader->decoder);
    FLAC__stream_decoder_delete(reader->decoder);
    delete reader;
}
