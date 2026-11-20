// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for eu.schicht8.meavox.spike.audio.NativeEngine. One process-wide engine instance.
#include <jni.h>

#include <string>

#include "SpikeEngine.h"

using meavox::spike::SpikeEngine;

namespace {

SpikeEngine &engine() {
    static SpikeEngine instance;
    return instance;
}

}  // namespace

#define JNI_FN(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_eu_schicht8_meavox_spike_audio_NativeEngine_##name

JNI_FN(jint, nativeOpen)(JNIEnv *, jobject) { return static_cast<jint>(engine().open()); }

JNI_FN(void, nativeClose)(JNIEnv *, jobject) { engine().close(); }

JNI_FN(jint, nativeReopen)(JNIEnv *, jobject) { return static_cast<jint>(engine().reopen()); }

JNI_FN(void, nativeBeginBacking)(JNIEnv *, jobject, jint channels, jint sampleRate) {
    engine().beginBacking(channels, sampleRate);
}

JNI_FN(void, nativeAppendBacking)(JNIEnv *env, jobject, jfloatArray data, jint frames) {
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return;
    engine().appendBacking(samples, frames);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
}

JNI_FN(void, nativeEndBacking)(JNIEnv *, jobject) { engine().endBacking(); }

JNI_FN(void, nativeGenerateTestBacking)(JNIEnv *, jobject, jdouble seconds) {
    engine().generateTestBacking(seconds);
}

JNI_FN(jboolean, nativePlay)(JNIEnv *, jobject, jlong fromFrame) {
    return engine().play(fromFrame) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeRecord)(JNIEnv *, jobject, jlong fromFrame) {
    return engine().record(fromFrame) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeReview)(JNIEnv *, jobject) {
    return engine().review() ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeStop)(JNIEnv *, jobject) { engine().stopTransport(); }

JNI_FN(jboolean, nativeStartCalibration)(JNIEnv *, jobject) {
    return engine().startCalibration() ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeSetTranspose)(JNIEnv *, jobject, jint semitones) {
    engine().setTranspose(semitones);
}

JNI_FN(void, nativeSetMonitoring)(JNIEnv *, jobject, jboolean enabled) {
    engine().setMonitoring(enabled == JNI_TRUE);
}

JNI_FN(void, nativeSetMonitorGain)(JNIEnv *, jobject, jfloat gain) { engine().setMonitorGain(gain); }

JNI_FN(void, nativeSetLatencyCompensation)(JNIEnv *, jobject, jint frames) {
    engine().setLatencyCompensation(frames);
}

JNI_FN(void, nativeGetStats)(JNIEnv *env, jobject, jdoubleArray out) {
    const jsize n = env->GetArrayLength(out);
    jdouble *values = env->GetDoubleArrayElements(out, nullptr);
    if (!values) return;
    engine().getStats(values, n);
    env->ReleaseDoubleArrayElements(out, values, 0);
}

JNI_FN(jboolean, nativeSaveAlignmentWav)(JNIEnv *env, jobject, jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return JNI_FALSE;
    const std::string p(chars);
    env->ReleaseStringUTFChars(path, chars);
    return engine().saveAlignmentWav(p) ? JNI_TRUE : JNI_FALSE;
}
