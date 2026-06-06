#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>

namespace {
std::atomic<bool> g_running(false);
constexpr const char* kTag = "NetScopeTun2Socks";
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_ai_arena_netscope_service_Tun2SocksBridge_nativeIsAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_ai_arena_netscope_service_Tun2SocksBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jint tunFd,
    jint mtu,
    jstring socksHost,
    jint socksPort
) {
    const char* hostChars = env->GetStringUTFChars(socksHost, nullptr);
    std::string host = hostChars ? hostChars : "";
    env->ReleaseStringUTFChars(socksHost, hostChars);

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "tun2socks scaffold start requested fd=%d mtu=%d host=%s port=%d",
        tunFd,
        mtu,
        host.c_str(),
        socksPort
    );

    // Scaffold only: replace this block with the real tun2socks engine wiring.
    // Typical integration point:
    // 1. create event loop / worker thread
    // 2. pass tunFd to tun2socks core
    // 3. configure SOCKS5 upstream host/port
    // 4. bridge packets until stop is requested
    g_running.store(false);
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_arena_netscope_service_Tun2SocksBridge_nativeStop(JNIEnv*, jobject) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "tun2socks scaffold stop requested");
    g_running.store(false);
}
