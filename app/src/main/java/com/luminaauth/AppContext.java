package com.luminaauth;

import android.content.Context;

import com.liulishuo.filedownloader.FileDownloader;
import com.liulishuo.filedownloader.services.DownloadMgrInitialParams;

/**
 * 全局 Context 单例，供静态方法使用。
 * 在 MainActivity.onCreate 中初始化。
 */
public class AppContext {
    private static Context context;

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
        // 初始化开源多线程下载引擎：单任务 32 连接分片下载
        FileDownloader.init(context,
                new DownloadMgrInitialParams.InitCustomMaker()
                        .maxNetworkThreadCount(32)
                        .connectionCountAdapter((id, url, path, total) -> 32));
    }

    public static Context get() {
        return context;
    }
}
