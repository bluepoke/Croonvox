// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for app.croonvox.engine.NativePlayback. Each engine is an owned native handle.
#include <jni.h>

#include "PlaybackEngine.h"

using croonvox::PlaybackEngine;

namespace {

PlaybackEngine &engine(jlong handle) { return *reinterpret_cast<PlaybackEngine *>(handle); }

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_croonvox_engine_NativePlayback_##name

// Never deleted: lives as long as the process (see PlaybackEngine.h).
JNI_FN(jlong, create)(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new PlaybackEngine()); }

JNI_FN(void, beginLoad)(JNIEnv *, jobject, jlong h, jint channels, jint sampleRate, jlong estimatedFrames) {
    engine(h).beginLoad(channels, sampleRate, estimatedFrames);
}

JNI_FN(jboolean, appendLoad)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames) {
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return JNI_FALSE;
    const bool fits = engine(h).appendLoad(samples, frames);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return fits ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, endLoad)(JNIEnv *, jobject, jlong h) { engine(h).endLoad(); }

JNI_FN(void, discardLoad)(JNIEnv *, jobject, jlong h) { engine(h).discardLoad(); }

JNI_FN(jboolean, play)(JNIEnv *, jobject, jlong h) { return engine(h).play() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(void, pause)(JNIEnv *, jobject, jlong h) { engine(h).pause(); }

JNI_FN(void, seekTo)(JNIEnv *, jobject, jlong h, jlong frame) { engine(h).seekTo(frame); }

JNI_FN(jboolean, isPlaying)(JNIEnv *, jobject, jlong h) { return engine(h).isPlaying() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(jlong, positionFrames)(JNIEnv *, jobject, jlong h) { return engine(h).positionFrames(); }

JNI_FN(jlong, durationFrames)(JNIEnv *, jobject, jlong h) { return engine(h).durationFrames(); }

JNI_FN(jlong, decodedFrames)(JNIEnv *, jobject, jlong h) { return engine(h).decodedFrames(); }

JNI_FN(jint, sampleRate)(JNIEnv *, jobject, jlong h) { return engine(h).sampleRate(); }
