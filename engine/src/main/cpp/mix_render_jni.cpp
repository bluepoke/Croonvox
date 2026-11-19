// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for app.croonvox.engine.NativeMixRender: one owned OfflineMixRender per handle.
#include <jni.h>

#include "OfflineMixRender.h"

using croonvox::OfflineMixRender;

namespace {

OfflineMixRender &render(jlong handle) { return *reinterpret_cast<OfflineMixRender *>(handle); }

// Hands a Java float[] to [use] without copying when the VM allows it.
template <typename Use>
void withSamples(JNIEnv *env, jfloatArray data, Use use) {
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (!samples) return;
    use(samples);
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_croonvox_engine_NativeMixRender_##name

JNI_FN(jlong, create)(JNIEnv *, jobject, jint sampleRate, jlong from, jlong to, jlong takeStart, jlong alignment,
                      jint transpose, jfloat backingGain, jfloat takeGain) {
    return reinterpret_cast<jlong>(
            new OfflineMixRender(sampleRate, from, to, takeStart, alignment, transpose, backingGain, takeGain));
}

JNI_FN(void, appendTake)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames, jint channels) {
    withSamples(env, data, [&](const jfloat *samples) { render(h).appendTake(samples, frames, channels); });
}

JNI_FN(void, appendBacking)(JNIEnv *env, jobject, jlong h, jfloatArray data, jint frames, jint channels) {
    withSamples(env, data, [&](const jfloat *samples) { render(h).appendBacking(samples, frames, channels); });
}

JNI_FN(void, endBacking)(JNIEnv *, jobject, jlong h) { render(h).endBacking(); }

JNI_FN(jboolean, needsBacking)(JNIEnv *, jobject, jlong h) { return render(h).needsBacking() ? JNI_TRUE : JNI_FALSE; }

JNI_FN(jint, drain)(JNIEnv *env, jobject, jlong h, jfloatArray out, jint maxFrames) {
    jfloat *samples = env->GetFloatArrayElements(out, nullptr);
    if (!samples) return 0;
    const jint n = render(h).drain(samples, maxFrames);
    env->ReleaseFloatArrayElements(out, samples, 0);  // copy back
    return n;
}

JNI_FN(void, release)(JNIEnv *, jobject, jlong h) { delete reinterpret_cast<OfflineMixRender *>(h); }
