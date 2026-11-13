package com.luminaauth

import android.content.Context
import java.util.zip.ZipFile

/**
 * 当前安装包自身包含的 ABI。
 * 用于应用内更新时挑选与自身架构匹配的安装包，避免下载到跑不了的架构。
 */
object AppAbi {

    /** 只包含一种 ABI 时返回它（如 "arm64-v8a"）；universal 包或读不到时返回 null。 */
    fun ownAbi(context: Context): String? {
        val abis = containedAbis(context)
        return if (abis.size == 1) abis.first() else null
    }

    /** 是否整合包（包含两种及以上 ABI）。 */
    fun isUniversal(context: Context): Boolean = containedAbis(context).size > 1

    private fun containedAbis(context: Context): List<String> {
        val apk = context.applicationInfo?.sourceDir ?: return emptyList()
        val found = LinkedHashSet<String>()
        try {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") }
                    .forEach { name ->
                        val rest = name.removePrefix("lib/")
                        val abi = rest.substringBefore('/')
                        if (abi.isNotEmpty() && abi != rest) found.add(abi)
                    }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return found.toList()
    }
}
