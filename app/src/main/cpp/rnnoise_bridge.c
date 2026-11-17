#include <jni.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include "rnnoise.h"

/* Model weights compiled into libnarasi_ai.so. No network, no runtime model download. */
JNIEXPORT jlong JNICALL
Java_com_appik_narasi_RnNoiseNative_create(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    DenoiseState *state = rnnoise_create(NULL);
    return (jlong)(intptr_t)state;
}

JNIEXPORT jint JNICALL
Java_com_appik_narasi_RnNoiseNative_frameSize(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return rnnoise_get_frame_size();
}

JNIEXPORT void JNICALL
Java_com_appik_narasi_RnNoiseNative_processFrame(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray data) {
    (void)thiz;
    if (!handle || !data) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "RNNoise state or audio is missing");
        return;
    }
    const int frame_size = rnnoise_get_frame_size();
    if ((*env)->GetArrayLength(env, data) != frame_size) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "RNNoise expects exactly one frame");
        return;
    }
    float input[480], output[480];
    (*env)->GetFloatArrayRegion(env, data, 0, frame_size, input);
    for (int i=0; i<frame_size; i++) {
        float x = input[i];
        if (!isfinite(x)) x = 0.f;
        if (x > 1.f) x = 1.f;
        if (x < -1.f) x = -1.f;
        input[i] = x * 32767.f;
    }
    rnnoise_process_frame((DenoiseState*)(intptr_t)handle, output, input);
    for (int i=0; i<frame_size; i++) {
        float y = output[i] / 32768.f;
        output[i] = isfinite(y) ? fmaxf(-1.f, fminf(1.f, y)) : 0.f;
    }
    (*env)->SetFloatArrayRegion(env, data, 0, frame_size, output);
}

JNIEXPORT void JNICALL
Java_com_appik_narasi_RnNoiseNative_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (handle) rnnoise_destroy((DenoiseState*)(intptr_t)handle);
}
