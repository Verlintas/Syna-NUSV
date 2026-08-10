/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

/** 桌面：无系统级生物识别认证，不提供认证门禁——解密始终走主密钥（如实声明） */
actual object SessionKeyStore {
    actual fun captureAuth() = Unit
    actual fun obtainSessionKey(): ByteArray? = null
    actual fun previousSessionKey(): ByteArray? = null
    actual fun rotateSessionKey() = Unit
    actual fun clearMigration() = Unit
    actual fun invalidateSession() = Unit
}
