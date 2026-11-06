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

    @JvmStatic
    fun get(): Context = context!!
}
