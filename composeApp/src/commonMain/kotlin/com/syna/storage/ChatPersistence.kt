package com.syna.storage

import com.syna.chat.ChatMessage
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import com.syna.net.synaJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
enum class PersistKind {
    TEXT,
    IMAGE,
    FILE,
}

@Serializable
enum class PersistStatus {
    SENDING,
    SENT,
    FAILED,
    READ,
}

@Serializable
data class PersistMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val ts: Long,
    val status: PersistStatus,
    val burnAfterReading: Boolean,
    val encrypted: Boolean,
    val kind: PersistKind,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val localPath: String? = null,
    val recalled: Boolean = false,
    val replyToId: String? = null,
    val mentions: List<String> = emptyList(),
)

fun PersistMessage.toChat(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
    ts = ts,
    status = when (status) {
        PersistStatus.SENDING -> MessageStatus.SENDING
        PersistStatus.SENT -> MessageStatus.SENT
        PersistStatus.FAILED -> MessageStatus.FAILED
        PersistStatus.READ -> MessageStatus.READ
    },
    burnAfterReading = burnAfterReading,
    encrypted = encrypted,
    kind = when (kind) {
        PersistKind.TEXT -> MessageKind.TEXT
        PersistKind.IMAGE -> MessageKind.IMAGE
        PersistKind.FILE -> MessageKind.FILE
    },
    fileName = fileName,
    fileSize = fileSize,
    localPath = localPath,
    recalled = recalled,
    replyToId = replyToId,
    mentions = mentions,
)

fun ChatMessage.toPersist(): PersistMessage = PersistMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
    ts = ts,
    status = when (status) {
        MessageStatus.SENDING -> PersistStatus.SENDING
        MessageStatus.SENT -> PersistStatus.SENT
        MessageStatus.FAILED -> PersistStatus.FAILED
        MessageStatus.READ -> PersistStatus.READ
    },
    burnAfterReading = burnAfterReading,
    encrypted = encrypted,
    kind = when (kind) {
        MessageKind.TEXT -> PersistKind.TEXT
        MessageKind.IMAGE -> PersistKind.IMAGE
        MessageKind.FILE -> PersistKind.FILE
    },
    fileName = fileName,
    fileSize = fileSize,
    localPath = localPath,
    recalled = recalled,
    replyToId = replyToId,
    mentions = mentions,
)

/** 聊天记录持久化路径（多端分平台：Android 应用私有目录 / 桌面用户目录） */
expect fun chatPersistencePath(): String

/** 接收文件目录占用大小（字节），用于存储清理展示 */
expect fun receivedFilesSize(): Long

/** 清除接收的文件（清空接收文件目录） */
expect fun clearReceivedFiles()

/**
 * 聊天记录 JSONL 文件持久化（零依赖、跨平台一致）：
 * 启动加载全部消息，变更后全量重写（保留最近 [MAX_MESSAGES] 条防无限增长）。
 */
class ChatPersistence(private val path: String = chatPersistencePath()) {

    fun load(): List<ChatMessage> {
        return try {
            val file = Path.of(path)
            if (!Files.exists(file)) return emptyList()
            Files.readAllLines(file).mapNotNull { line ->
                try {
                    synaJson.decodeFromString(PersistMessage.serializer(), line).toChat()
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            println("[Syna:Persist] 加载失败: ${e.message}")
            emptyList()
        }
    }

    fun rewrite(messages: Map<String, List<ChatMessage>>) {
        try {
            val all = messages.values.flatten().sortedBy { it.ts }.takeLast(MAX_MESSAGES)
            val lines = all.joinToString("\n") { msg ->
                synaJson.encodeToString(PersistMessage.serializer(), msg.toPersist())
            }
            val file = Path.of(path)
            Files.createDirectories(file.parent)
            // FileOutputStream：Android 的 java.nio.Files 无 write(Path, byte[]) 重载
            java.io.FileOutputStream(file.toFile()).use { out ->
                out.write(if (lines.isEmpty()) ByteArray(0) else "$lines\n".toByteArray())
            }
        } catch (e: Exception) {
            println("[Syna:Persist] 写入失败: ${e.message}")
        }
    }

    /** 清除本地聊天记录文件 */
    fun clear() {
        try {
            Files.deleteIfExists(Path.of(path))
        } catch (e: Exception) {
            println("[Syna:Persist] 清除失败: ${e.message}")
        }
    }

    /** 聊天记录文件占用（字节） */
    fun fileSize(): Long = try {
        Files.size(Path.of(path))
    } catch (e: Exception) {
        0L
    }

    companion object {
        const val MAX_MESSAGES = 5000
    }
}
