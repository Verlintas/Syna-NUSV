/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syna.shield

/**
 * 会话密钥层（数据级密钥门禁核心）：
 *
 * Android 实现：会话密钥 = 随机的 32 字节密钥，用"认证绑定密钥"（Keystore，
 * `setUserAuthenticationRequired(true)`）加密成 blob 落盘。
 * - 启用 Shield 时：加密数据优先使用会话密钥——攻击者**未通过生物识别认证**时
 *   无法获得会话密钥 → 新写入的数据不可解（数据级防护，不依赖检测）。
 * - 锁定（releaseSession）时：内存缓存被释放，认证窗口过期后数据再次不可解。
 * - 未启用 Shield 时：会话密钥不参与，数据走主密钥直解（保持兼容）。
 * - 历史数据（主密钥加密）解密回退主密钥：升级平滑、数据不丢。
 *
 * 桌面实现：无系统级生物识别认证，如实不提供该门禁（解密始终走主密钥）。
 */
expect object SessionKeyStore {
    /** 获取会话密钥（仅内存缓存；未认证/未启用时返回 null） */
    fun obtainSessionKey(): ByteArray?

    /** 释放会话密钥（锁定/Shield 停用时调用，数据立即回到不可解状态） */
    fun invalidateSession()
}
