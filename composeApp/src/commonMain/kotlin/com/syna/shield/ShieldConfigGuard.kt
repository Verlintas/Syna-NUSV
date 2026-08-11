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
 * Shield 设置完整性防护：对关键安全设置做 HMAC 签名，
 * 启动时校验——设置被外部篡改或存储被清除（密钥缺失）时返回不匹配，
 * 由上层强制恢复保护并触发 SHIELD_TAMPERED 威胁。
 */
expect object ShieldConfigGuard {
    /** 对设置负载签名（Android 用 Keystore 中的 HMAC 密钥，不可导出；桌面用本地密钥文件） */
    fun sign(payload: String): String

    /** 校验签名；密钥存在但签名不匹配 → 篡改；设置存在但密钥缺失 → 存储被清除 */
    fun verify(payload: String, signature: String): Boolean

    /** 密钥是否已初始化（用于区分"首次运行"与"存储被清除"） */
    fun keyExists(): Boolean
}
