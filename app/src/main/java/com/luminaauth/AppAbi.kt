package com.luminaauth

import android.content.Context
import java.util.zip.ZipFile

/** 读取当前安装包自身包含的 ABI，供关于页显示「本包架构」。 */
object AppAbi {

    fun describe(context: Context): String {
        val abis = containedAbis(context)
        return when {
            abis.isEmpty() -> "无原生库"
            abis.size == 1 -> abis.first()
            else -> "universal（" + abis.joinToString(" / ") + "）"
        }
    }

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
