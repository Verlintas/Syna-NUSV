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
package com.syna.net

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTest {

    @Test
    fun announcementRoundTrip() {
        val ann = DiscoveryAnnouncement(
            id = "peer-1",
            username = "Alice",
            device = "桌面(macOS)",
            tcpPort = 54321,
            version = "0.1.0",
        )
        val decoded = decodeAnnouncement(ann.encode())
        assertEquals(ann, decoded)
    }

    @Test
    fun frameRoundTrip() {
        val frame = TransportFrame(
            type = FrameType.TEXT,
            from = "peer-1",
            to = "peer-2",
            msgId = "msg-42",
            ts = 123456789L,
            body = "你好，Syna！",
        )
        val decoded = decodeFrame(frame.encode())
        assertEquals(frame, decoded)
    }

    @Test
    fun frameWithNullBody() {
        val frame = TransportFrame(
            type = FrameType.HELLO,
            from = "peer-1",
            to = "",
            msgId = "",
            ts = 0L,
            body = null,
        )
        val decoded = decodeFrame(frame.encode())
        assertEquals(frame, decoded)
    }

    @Test
    fun ignoresUnknownKeys() {
        val json = """{"type":"TEXT","from":"a","to":"b","msgId":"m","ts":1,"body":"hi","future":123}"""
        val decoded = synaJson.decodeFromString<TransportFrame>(json)
        assertEquals("hi", decoded.body)
    }
}
