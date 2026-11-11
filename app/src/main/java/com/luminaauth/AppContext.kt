package com.luminaauth

import android.content.Context
import com.liulishuo.filedownloader.FileDownloader
import com.liulishuo.filedownloader.services.DownloadMgrInitialParams

object AppContext {
    private var context: Context? = null

    @JvmStatic
    fun init(ctx: Context) {
        context = ctx.applicationContext
        FileDownloader.init(context,
            DownloadMgrInitialParams.InitCustomMaker()
                .maxNetworkThreadCount(32)
                .connectionCountAdapter { _, _, _, _ -> 32 })
    }

    /**
     * 轻量挂接 Context（不初始化 FileDownloader）。
     * 后台服务可能在从未打开过界面时自启，此时 [context] 仍为 null，
     * 登录链路调用 [get] 会空指针；服务 onCreate 先调本方法兜底。
     */
    @JvmStatic
    fun attach(ctx: Context) {
        if (context == null) context = ctx.applicationContext
    }

    @JvmStatic
    fun get(): Context = context!!
}
