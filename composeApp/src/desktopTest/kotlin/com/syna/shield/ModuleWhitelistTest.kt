/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 可疑模块分区白名单逻辑（v0.9.2）：
 * 系统/厂商分区（/system /apex /vendor /product /system_ext /odm /data/app /data/user）
 * 全部放行——厂商 ROM 自定义库不误报；仅非分区可执行映射判可疑。
 */
class ModuleWhitelistTest {

    /** 与 AndroidShieldEngine.suspiciousModules 相同的判定（纯逻辑抽取测试） */
    private fun suspiciousIn(maps: String): List<String> {
        val trustedPrefixes = listOf(
            "/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/",
            "/data/app/", "/data/user/", "/data/apex/",
        )
        val found = LinkedHashSet<String>()
        val lines = maps.split("\n")
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 6) continue
            val perms = parts[1]
            val path = parts[5]
            if (!perms.contains("x")) continue
            if (!path.startsWith("/")) continue
            if (trustedPrefixes.any { path.startsWith(it) }) continue
            if (path.contains("libsyna_shield") || path.contains("linker") ||
                path.contains("libc.so") || path.contains("libart") ||
                path.contains("libjavacore") || path.contains("libopenjdk") ||
                path.contains("libandroid_runtime") || path.contains("libnativeloader")
            ) continue
            found.add(path)
            if (found.size >= 5) break
        }
        return found.toList()
    }

    @Test
    fun vendorAndSystemPartitionsAreTrusted() {
        // 小米/真我/华为 ROM 的自定义库位置：全部放行
        val maps = """
            7f000000-7f001000 r-xp 00000000 08:01 123 /system/lib64/libc.so
            7f001000-7f002000 r-xp 00000000 08:01 456 /vendor/lib64/libMiuiPreload.so
            7f002000-7f003000 r-xp 00000000 08:01 789 /product/lib64/liboplus.so
            7f003000-7f004000 r-xp 00000000 08:01 321 /system_ext/lib64/libhwc.so
            7f004000-7f005000 r-xp 00000000 08:01 654 /odm/lib64/libqti_perf.so
            7f005000-7f006000 r-xp 00000000 08:01 987 /apex/com.android.runtime/lib64/bionic/libc.so
            7f006000-7f007000 r-xp 00000000 08:01 111 /data/app/~~abc/com.syna/lib/arm64/libsyna_shield.so
            7f007000-7f008000 r-xp 00000000 08:01 222 /data/user/0/com.syna/cache/libnative.so
        """.trimIndent()
        assertTrue(suspiciousIn(maps).isEmpty(), "系统/厂商/应用分区不应误报: ${suspiciousIn(maps)}")
    }

    @Test
    fun anonymousExecutableMappingsAreSuspicious() {
        // 注入代码的典型形态：匿名可执行映射 + 未知路径 so
        val maps = """
            7f000000-7f001000 r-xp 00000000 08:01 123 /system/lib64/libc.so
            7f100000-7f101000 r-xp 00000000 00:00 0 /data/local/tmp/.frida/gadget.so
            7f200000-7f201000 r-xp 00000000 00:00 0 /tmp/injected.so
        """.trimIndent()
        val found = suspiciousIn(maps)
        assertEquals(2, found.size, "注入路径应全部报出")
        assertTrue(found.any { it.contains("frida") }, "应包含 frida gadget 路径")
    }

    @Test
    fun nonExecutableMappingsIgnored() {
        val maps = """
            7f000000-7f001000 rw-p 00000000 08:01 123 /tmp/just-data.so
            7f001000-7f002000 r--p 00000000 08:01 456 /data/local/tmp/read-only.so
        """.trimIndent()
        assertTrue(suspiciousIn(maps).isEmpty(), "非可执行映射不应判可疑")
    }
}
