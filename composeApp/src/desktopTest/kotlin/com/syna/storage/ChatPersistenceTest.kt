package com.syna.storage

import com.syna.chat.ChatMessage
import com.syna.chat.ChatStore
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatPersistenceTest {

    private fun tempPersistence(): ChatPersistence {
        val dir = Files.createTempDirectory("syna-chat-persist")
        return ChatPersistence(dir.resolve("chat.jsonl").toString())
    }

    @Test
    fun messagesSurviveRestart() = runBlocking {
        val p1 = tempPersistence()
        val store1 = ChatStore(p1)
        store1.addIncoming(
            peerId = "peer-1",
            peerName = "Alice",
            msg = ChatMessage(
                id = "m1", conversationId = "peer-1", senderId = "peer-1",
                body = "持久化消息", ts = 1000L, status = MessageStatus.READ,
                burnAfterReading = false, encrypted = true,
                replyToId = "m0", mentions = listOf("x"),
            ),
            preview = "持久化消息",
        )
        store1.addOutgoing(
            peerId = "peer-1",
            peerName = "Alice",
            msg = ChatMessage(
                id = "m2", conversationId = "peer-1", senderId = "me",
                body = "图片", ts = 2000L, status = MessageStatus.SENT,
                burnAfterReading = false, encrypted = false,
                kind = MessageKind.IMAGE, fileName = "a.png", fileSize = 1024L,
            ),
        )
        delay(1_200)

        val store2 = ChatStore(p1)
        val msgs = store2.messages.value["peer-1"].orEmpty()
        assertEquals(2, msgs.size, "重启后应恢复全部消息")
        val m1 = msgs.first { it.id == "m1" }
        assertEquals("持久化消息", m1.body)
        assertTrue(m1.encrypted)
        assertEquals("m0", m1.replyToId)
        assertEquals(listOf("x"), m1.mentions)
        val m2 = msgs.first { it.id == "m2" }
        assertEquals(MessageKind.IMAGE, m2.kind)
        assertEquals("a.png", m2.fileName)
        assertEquals(1024L, m2.fileSize)
        assertTrue(store2.conversations.value.any { it.peerId == "peer-1" })
    }

    @Test
    fun recalledAndRemovedStatesPersist() = runBlocking {
        val p = tempPersistence()
        val store = ChatStore(p)
        store.addIncoming(
            "peer-1", "Bob",
            ChatMessage("m1", "peer-1", "peer-1", "会被撤回", 1L, MessageStatus.READ, false, false),
        )
        store.addIncoming(
            "peer-1", "Bob",
            ChatMessage("m2", "peer-1", "peer-1", "会被删除", 2L, MessageStatus.READ, false, false),
        )
        delay(1_200)
        store.markRecalledByMsgId("m1")
        store.removeMessageById("m2")
        delay(1_200)

        val restored = ChatStore(p)
        val msgs = restored.messages.value["peer-1"].orEmpty()
        assertEquals(1, msgs.size)
        assertEquals("m1", msgs.first().id)
        assertTrue(msgs.first().recalled, "撤回状态应持久化")
    }
}

class EncryptedPersistenceTest {

    @Test
    fun encryptedRoundTripAndTamperDetect() = runBlocking {
        val dir = Files.createTempDirectory("syna-enc-persist")
        val p = ChatPersistence(dir.resolve("chat.jsonl").toString())
        val store = ChatStore(p)
        store.addIncoming(
            "peer-1", "Alice",
            ChatMessage("m1", "peer-1", "peer-1", "加密存储的消息", 1L, MessageStatus.READ, false, true),
        )
        delay(1_200)

        // 文件应为加密格式（不可读明文）
        val raw = Files.readAllBytes(dir.resolve("chat.jsonl"))
        val text = raw.decodeToString()
        assertTrue(text.startsWith(ChatPersistence.MAGIC), "文件应为加密格式")
        assertTrue("加密存储的消息" !in text, "文件中不应出现明文")

        // 正常重载可解密
        val restored = ChatStore(p)
        assertTrue(restored.messages.value["peer-1"].orEmpty().any { it.body == "加密存储的消息" })

        // 篡改密文 → 解密失败 → 按无记录处理（不崩溃）
        val tampered = raw.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        Files.write(dir.resolve("chat.jsonl"), tampered)
        val afterTamper = ChatStore(p)
        assertTrue(afterTamper.messages.value.isEmpty(), "篡改后应无法解密读取")
    }

    @Test
    fun legacyPlainFileStillLoads() = runBlocking {
        // 旧版本明文文件（无 MAGIC 头）应兼容解析
        val dir = Files.createTempDirectory("syna-legacy")
        val file = dir.resolve("chat.jsonl")
        val legacy = """{"id":"old1","conversationId":"peer-1","senderId":"peer-1","body":"旧消息","ts":1,"status":"READ","burnAfterReading":false,"encrypted":false,"kind":"TEXT"}"""
        Files.writeString(file, legacy + "\n")
        val p = ChatPersistence(file.toString())
        val store = ChatStore(p)
        assertTrue(store.messages.value["peer-1"].orEmpty().any { it.body == "旧消息" }, "旧明文文件应兼容")
    }
}
