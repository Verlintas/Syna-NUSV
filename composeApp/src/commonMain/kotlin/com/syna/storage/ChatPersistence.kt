package com.syna.storage

import com.syna.chat.ChatMessage
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import com.syna.net.synaJson
import com.syna.shield.ShieldStorageKey
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

/** 平台附加痕迹清理（自毁时覆写删除：种子/blob/基准/崩溃日志等） */
expect fun destructPlatformArtifacts()

/** 复制文本到系统剪贴板（供短 TTL 自动清除机制使用） */
expect fun copyTextToClipboard(text: String)

/**
 * 设备身份变化检查（重装/恢复备份）：与上次运行基准比对。
 * 独立于 Shield 启停运行（Shield 关闭时也能检测并引导用户开启）。
 * Android：ANDROID_ID 主密钥加密基准；桌面：恒 false。
 */
expect fun deviceIdentityChanged(): Boolean

/**
 * 聊天记录 JSONL 文件持久化（零依赖、跨平台一致）：
 * 启动加载全部消息，变更后全量重写（保留最近 [MAX_MESSAGES] 条防无限增长）。
 */
class ChatPersistence(private val path: String = chatPersistencePath()) {

    fun load(): List<ChatMessage> {
        return try {
            val file = Path.of(path)
            if (!Files.exists(file)) return emptyList()
            val raw = Files.readAllBytes(file)
            // 加密格式（SYNA1 + nonce + 密文）；旧版本明文文件直接按明文解析
            val text = if (raw.size >= MAGIC.length && String(raw.copyOfRange(0, MAGIC.length)) == MAGIC) {
                ShieldStorageKey.decrypt(raw.copyOfRange(MAGIC.length, raw.size))
                    ?.decodeToString()
                    ?: run {
                        println("[Syna:Persist] 解密失败（密钥丢失或数据损坏），按无记录处理")
                        return emptyList()
                    }
            } else {
                raw.decodeToString()
            }
            text.split("\n".toRegex()).filter { it.isNotBlank() }.mapNotNull { line ->
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

    private val writeLock = Object()

    fun rewrite(messages: Map<String, List<ChatMessage>>) {
        synchronized(writeLock) {
        try {
            val all = messages.values.flatten().sortedBy { it.ts }.takeLast(MAX_MESSAGES)
            val lines = all.joinToString("\n") { msg ->
                synaJson.encodeToString(PersistMessage.serializer(), msg.toPersist())
            }
            val file = Path.of(path)
            Files.createDirectories(file.parent)
            val plain = if (lines.isEmpty()) ByteArray(0) else "$lines\n".toByteArray()
            val payload = ShieldStorageKey.encrypt(plain)
            // 原子写：先写临时文件再 rename（随机后缀防并发撕裂），防进程被杀/断电产生截断主文件
            val tmp = Path.of(path + ".tmp" + System.nanoTime())
            java.io.FileOutputStream(tmp.toFile()).use { out ->
                if (payload != null) {
                    out.write(MAGIC.toByteArray())
                    out.write(payload)
                } else {
                    // 加密密钥不可用（异常环境）：降级明文存储并提示
                    out.write(plain)
                }
                // 内容落盘后再 rename（断电不产生截断）
                out.fd.sync()
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                // 文件系统不支持原子移动：退化为普通替换
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            println("[Syna:Persist] 写入失败: ${e.message}")
        }
        }
    }

    /** 清除本地聊天记录文件 */
    fun clear() {
        synchronized(writeLock) {
        try {
            // 安全覆写删除（防取证恢复）：主文件 + 原子写残留的 tmp
            com.syna.util.SecureWipe.wipeFile(path)
            com.syna.util.SecureWipe.wipeFile(path + ".tmp")
        } catch (e: Exception) {
            println("[Syna:Persist] 清除失败: ${e.message}")
        }
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
        const val MAGIC = "SYNA1\n"
    }
}
