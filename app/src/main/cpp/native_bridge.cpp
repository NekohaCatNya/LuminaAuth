#include <jni.h>
#include <string>
#include <android/log.h>

#include "log_ring_buffer.h"
#include "fast_json.h"
#include "native_http.h"

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

JNIEXPORT void JNICALL
Java_com_luminaauth_NativeBridge_nativeSetBindIface(JNIEnv* env, jclass cls, jstring iface) {
    const char* ciface = env->GetStringUTFChars(iface, nullptr);
    lumina::setBindInterface(std::string(ciface));
    env->ReleaseStringUTFChars(iface, ciface);
}

// 把响应体转义成 JSON 字符串字面量（引号、反斜杠、换行、控制字符）
static std::string jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 16);
    for (size_t i = 0; i < in.size(); ++i) {
        unsigned char c = (unsigned char)in[i];
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char)c;
                }
        }
    }
    return out;
}

// 认证等 HTTP 请求：走原生 socket，按 SO_BINDTODEVICE 绑定的网卡出站。
// 返回 {"code":200,"body":"...","error":""}；error 非空表示传输层失败。
JNIEXPORT jstring JNICALL
Java_com_luminaauth_NativeBridge_nativeHttpRequest(JNIEnv* env, jclass cls,
                                                  jstring method, jstring url,
                                                  jstring body, jstring referer,
                                                  jint timeoutMs) {
    const char* cm = env->GetStringUTFChars(method, nullptr);
    const char* cu = env->GetStringUTFChars(url, nullptr);
    const char* cb = (body != nullptr) ? env->GetStringUTFChars(body, nullptr) : "";
    const char* cr = (referer != nullptr) ? env->GetStringUTFChars(referer, nullptr) : "";

    lumina::HttpResult res = lumina::httpRequest(std::string(cm), std::string(cu),
                                                 std::string(cb), std::string(cr),
                                                 (int)timeoutMs);

    env->ReleaseStringUTFChars(method, cm);
    env->ReleaseStringUTFChars(url, cu);
    if (body != nullptr) env->ReleaseStringUTFChars(body, cb);
    if (referer != nullptr) env->ReleaseStringUTFChars(referer, cr);

    std::string json = "{\"code\":" + std::to_string(res.code)
                     + ",\"error\":\"" + jsonEscape(res.error) + "\""
                     + ",\"body\":\"" + jsonEscape(res.body) + "\"}";
    return env->NewStringUTF(json.c_str());
}

}
