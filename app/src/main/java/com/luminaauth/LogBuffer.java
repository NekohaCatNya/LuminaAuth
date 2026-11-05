package com.luminaauth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 高性能日志缓冲
 * Native C++ 环形缓冲（O(1) 插入，无数组移动）
 * 如果 native 库不可用，自动 fallback 到 Java 实现
 */
public class LogBuffer {
    private static final int MAX_ENTRIES = 300;
    private static final int DETAIL_MAX_ENTRIES = 1000;
    private static final int MAX_LINE_LENGTH = 512;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Java fallback 实现
    private static final List<String> logs = new ArrayList<>();
    private static final List<String> detailLogs = new ArrayList<>();

    private static LogListener listener;

    public interface LogListener {
        void onLogUpdated();
    }

    public static void setListener(LogListener l) {
        listener = l;
    }

    private static String format(String tag, String message) {
        String ts = SDF.format(new Date());
        String line = ts + " " + tag + " " + message;
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH);
        }
        return line;
    }

    public static void add(String tag, String message) {
        String line = format(tag, message);
        if (NativeBridge.isNativeAvailable()) {
            NativeBridge.nativeAddLog(line, false);
        } else {
            synchronized (logs) {
                logs.add(line);
                if (logs.size() > MAX_ENTRIES) logs.remove(0);
            }
        }
        if (listener != null) listener.onLogUpdated();
    }

    public static void addDetail(String tag, String message) {
        String line = format(tag, message);
        if (NativeBridge.isNativeAvailable()) {
            NativeBridge.nativeAddLog(line, true);
        } else {
            synchronized (detailLogs) {
                detailLogs.add(line);
                if (detailLogs.size() > DETAIL_MAX_ENTRIES) detailLogs.remove(0);
            }
        }
        if (listener != null) listener.onLogUpdated();
    }

    public static String getAllText() {
        if (NativeBridge.isNativeAvailable()) {
            return NativeBridge.nativeGetAllText(false);
        }
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            for (String line : logs) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static String getDetailAllText() {
        if (NativeBridge.isNativeAvailable()) {
            return NativeBridge.nativeGetAllText(true);
        }
        StringBuilder sb = new StringBuilder();
        synchronized (detailLogs) {
            for (String line : detailLogs) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        if (NativeBridge.isNativeAvailable()) {
            NativeBridge.nativeClear(false);
        } else {
            synchronized (logs) { logs.clear(); }
        }
        if (listener != null) listener.onLogUpdated();
    }

    public static void clearDetail() {
        if (NativeBridge.isNativeAvailable()) {
            NativeBridge.nativeClear(true);
        } else {
            synchronized (detailLogs) { detailLogs.clear(); }
        }
        if (listener != null) listener.onLogUpdated();
    }
}
