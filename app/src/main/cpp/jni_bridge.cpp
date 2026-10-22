#include <jni.h>

#include <memory>
#include <mutex>

#include "usb_audio_core.h"

static std::unique_ptr<uac::Capture> g_cap;
static std::mutex g_cap_mutex;

extern "C" {

JNIEXPORT jstring JNICALL Java_com_onegoodtake_app_audio_NativeUsb_start(
        JNIEnv* env, jobject, jint fd, jbyteArray desc, jint rate) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    g_cap = std::make_unique<uac::Capture>();
    jsize n = env->GetArrayLength(desc);
    jbyte* d = env->GetByteArrayElements(desc, nullptr);
    std::string r = g_cap->start(fd, reinterpret_cast<const uint8_t*>(d), (size_t)n, rate);
    env->ReleaseByteArrayElements(desc, d, JNI_ABORT);
    return env->NewStringUTF(r.c_str());
}

JNIEXPORT jint JNICALL Java_com_onegoodtake_app_audio_NativeUsb_captureInterface(
        JNIEnv* env, jobject, jbyteArray desc) {
    jsize n = env->GetArrayLength(desc);
    jbyte* d = env->GetByteArrayElements(desc, nullptr);
    int iface = uac::captureInterface(reinterpret_cast<const uint8_t*>(d), (size_t)n);
    env->ReleaseByteArrayElements(desc, d, JNI_ABORT);
    return iface;
}

JNIEXPORT void JNICALL Java_com_onegoodtake_app_audio_NativeUsb_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    g_cap.reset();
}

JNIEXPORT jint JNICALL Java_com_onegoodtake_app_audio_NativeUsb_channels(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    return g_cap ? g_cap->channels() : 0;
}

JNIEXPORT jint JNICALL Java_com_onegoodtake_app_audio_NativeUsb_read(
        JNIEnv* env, jobject, jfloatArray buf, jint maxFrames) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    if (!g_cap) return 0;
    jfloat* b = env->GetFloatArrayElements(buf, nullptr);
    int n = g_cap->read(b, maxFrames);
    env->ReleaseFloatArrayElements(buf, b, 0);
    return n;
}

JNIEXPORT void JNICALL Java_com_onegoodtake_app_audio_NativeUsb_peaks(
        JNIEnv* env, jobject, jfloatArray out) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    if (!g_cap) return;
    jsize n = env->GetArrayLength(out);
    jfloat* b = env->GetFloatArrayElements(out, nullptr);
    g_cap->takePeaks(b, n);
    env->ReleaseFloatArrayElements(out, b, 0);
}

JNIEXPORT jlongArray JNICALL Java_com_onegoodtake_app_audio_NativeUsb_stats(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_cap_mutex);
    long long s[3] = {0, 0, 0};
    if (g_cap) g_cap->stats(s);
    jlongArray arr = env->NewLongArray(3);
    jlong t[3] = {s[0], s[1], s[2]};
    env->SetLongArrayRegion(arr, 0, 3, t);
    return arr;
}

}
