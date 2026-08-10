/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.util

/**
 * 安全覆写删除：删除前用随机数据覆写 [PASSES] 遍并 fsync，
 * 再删除文件并 fsync 父目录——显著提高取证恢复难度。
 * 说明：SSD 磨损均衡使物理覆写无法 100% 保证（如实声明），
 * Android 上 FBE 加密 + Keystore 密钥销毁（[com.syna.shield.ShieldStorageKey.wipe]）
 * 是数据不可恢复的真正保证。
 */
object SecureWipe {
    const val PASSES = 2
    private const val BLOCK = 64 * 1024

    /** 覆写并删除单个文件（文件不存在时静默成功） */
    fun wipeFile(path: String) {
        try {
            val f = java.io.File(path)
            if (!f.exists()) return
            val size = f.length()
            if (size > 0 && size <= 512L * 1024 * 1024) {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    raf.setLength(size)
                    val buf = ByteArray(BLOCK)
                    for (pass in 0 until PASSES) {
                        java.security.SecureRandom().nextBytes(buf)
                        raf.seek(0)
                        var written = 0L
                        while (written < size) {
                            val n = minOf(BLOCK.toLong(), size - written).toInt()
                            raf.write(buf, 0, n)
                            written += n
                        }
                        raf.fd.sync()
                    }
                }
            }
            f.delete()
            fsyncDir(f.parentFile)
        } catch (e: Exception) {
            // 覆写失败仍尽力删除
            try {
                java.io.File(path).delete()
            } catch (e2: Exception) {
            }
        }
    }

    /** 覆写并删除目录下全部内容（递归） */
    fun wipeDir(dir: java.io.File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { f ->
            if (f.isFile) wipeFile(f.absolutePath) else f.delete()
        }
        fsyncDir(dir.parentFile)
    }

    private fun fsyncDir(dir: java.io.File?) {
        try {
            if (dir != null && dir.exists()) {
                java.io.FileOutputStream(dir, true).use { it.fd.sync() }
            }
        } catch (e: Exception) {
        }
    }
}
