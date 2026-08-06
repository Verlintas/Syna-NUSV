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
