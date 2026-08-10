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

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyPinningTest {

    @Test
    fun tofuPinsFirstSeenKeyAndRejectsChange() {
        val dir = Files.createTempDirectory("syna-pins").resolve("pins.bin").toString()
        KeyPinning.pathOverride = dir
        try {
        val keyA = "AAAA-key-A"
        val keyB = "BBBB-key-B"

        // 首次：固定
        assertEquals(KeyPinning.PinResult.PINNED_FIRST, KeyPinning.checkAndPin("peer1", keyA))
        // 一致：通过
        assertEquals(KeyPinning.PinResult.PINNED_MATCH, KeyPinning.checkAndPin("peer1", keyA))
        // 变更：拒绝
        assertEquals(KeyPinning.PinResult.PINNED_CHANGED, KeyPinning.checkAndPin("peer1", keyB))
        // 旧密钥保留
        assertEquals(keyA, KeyPinning.pinnedKey("peer1"))
        // 重新信任
        KeyPinning.rePin("peer1", keyB)
        assertEquals(keyB, KeyPinning.pinnedKey("peer1"))
        } finally {
            KeyPinning.pathOverride = null
        }
    }

    @Test
    fun fingerprintFormat() {
        val fp = KeyPinning.fingerprint("some-public-key")
        assertEquals(8, fp.length)
        assertEquals(fp, fp.uppercase())
        val full = KeyPinning.fingerprintFull("some-public-key")
        // 格式：16 位十六进制，4+4+4+4 分组
        assertEquals(19, full.length)
        assertEquals('-', full[4])
        assertEquals(full.replace("-", ""), full.replace("-", "").uppercase())
    }
}
