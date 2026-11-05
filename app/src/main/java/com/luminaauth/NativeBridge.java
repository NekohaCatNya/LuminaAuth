package com.luminaauth;

/**
 * Native JNI 桥接层
 * 对应 C++ 实现的高性能日志缓冲、快速 JSON 解析
 * 如果加载 native 库失败，自动 fallback 到 Java 实现
 */
public class NativeBridge {
    private static boolean sNativeAvailable = false;

    static {
        try {
            System.loadLibrary("lumina-native");
            nativeInit();
            sNativeAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            sNativeAvailable = false;
        }
    }

    public static boolean isNativeAvailable() {
        return sNativeAvailable;
    }

    public static native void nativeInit();
    public static native void nativeAddLog(String line, boolean isDetail);
    public static native String nativeGetAllText(boolean isDetail);
    public static native void nativeClear(boolean isDetail);
    public static native String nativeParseDrcom(String body);
}
