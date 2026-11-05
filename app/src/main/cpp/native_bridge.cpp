#include <jni.h>
#include <string>
#include <android/log.h>

#include "log_ring_buffer.h"
#include "fast_json.h"

#define TAG "LuminaNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static lumina::LogRingBuffer* g_mainLog = nullptr;
static lumina::LogRingBuffer* g_detailLog = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_luminaauth_NativeBridge_nativeInit(JNIEnv* env, jclass cls) {
    if (!g_mainLog) g_mainLog = new lumina::LogRingBuffer(300);
    if (!g_detailLog) g_detailLog = new lumina::LogRingBuffer(1000);
    LOGD("Native bridge initialized");
}

JNIEXPORT void JNICALL
Java_com_luminaauth_NativeBridge_nativeAddLog(JNIEnv* env, jclass cls, jstring line, jboolean isDetail) {
    const char* cstr = env->GetStringUTFChars(line, nullptr);
    if (isDetail) {
        g_detailLog->push(cstr);
    } else {
        g_mainLog->push(cstr);
    }
    env->ReleaseStringUTFChars(line, cstr);
}

JNIEXPORT jstring JNICALL
Java_com_luminaauth_NativeBridge_nativeGetAllText(JNIEnv* env, jclass cls, jboolean isDetail) {
    std::string out;
    if (isDetail) {
        out = g_detailLog->getAllText();
    } else {
        out = g_mainLog->getAllText();
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_luminaauth_NativeBridge_nativeClear(JNIEnv* env, jclass cls, jboolean isDetail) {
    if (isDetail) {
        g_detailLog->clear();
    } else {
        g_mainLog->clear();
    }
}

JNIEXPORT jstring JNICALL
Java_com_luminaauth_NativeBridge_nativeParseDrcom(JNIEnv* env, jclass cls, jstring body) {
    const char* cbody = env->GetStringUTFChars(body, nullptr);
    lumina::LoginResponse res = lumina::parseDrcomResponse(std::string(cbody));
    env->ReleaseStringUTFChars(body, cbody);

    // 返回 JSON 格式结果: {"valid":1,"result":1,"msg":"xxx"}
    char buf[1024];
    snprintf(buf, sizeof(buf), "{\"valid\":%d,\"result\":%d,\"msg\":\"%s\"}",
             res.valid ? 1 : 0, res.result, res.msg.c_str());
    return env->NewStringUTF(buf);
}

}
