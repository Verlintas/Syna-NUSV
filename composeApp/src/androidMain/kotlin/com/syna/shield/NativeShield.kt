/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

/**
 * ◇Mirtazapine Shield native 检测桥（NDK）：
 * TracerPid / frida maps / frida 线程名在 C 层读取，JVM 层 hook 无法覆盖。
 * 库加载失败（无 native 支持）时 [loaded] 为 false，调用方回退 JVM 实现。
 */
object NativeShield {

    @Volatile
    private var loadedInternal: Boolean = false

    val loaded: Boolean
        get() = loadedInternal

    init {
        try {
            System.loadLibrary("syna_shield")
            loadedInternal = true
        } catch (e: Throwable) {
            loadedInternal = false
        }
    }

    /** 内核 TracerPid（被 ptrace 注入时非零） */
    external fun tracerPid(): Int

    /** /proc/self/maps 中 frida/gum 特征映射 */
    external fun fridaMaps(): Int

    /** task 目录 comm 中 frida/gum-js 线程名 */
    external fun fridaThreads(): Int

    /**
     * 代码完整性位掩码（native 对抗层）：
     * bit0 = 自身代码段被修改（含全部 JNI 导出函数入口被 inline hook）
     * bit1 = libc 关键函数入口被修改（inline hook libc）
     */
    external fun integrity(): Int
}
