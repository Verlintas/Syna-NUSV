package com.syna.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import com.syna.chat.ChatMessage
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import com.syna.net.SynaEngine
import com.syna.util.FilePickerButton
import com.syna.util.formatTime
import com.syna.util.readFileBytes
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    peerId: String,
    engine: SynaEngine,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peers by engine.peers.collectAsState()
    val messages by engine.chatStore.messages.collectAsState()
    val groups by engine.groups.collectAsState()
    val outbox by engine.outbox.collectAsState()
    val serverState by engine.serverState.collectAsState()
    val typing by engine.typing.collectAsState()
    val peer = peers.firstOrNull { it.id == peerId }
    val group = groups.firstOrNull { it.id == peerId }
    val isGroup = group != null
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var burn by remember { mutableStateOf(engine.settings.burnAfterReadingEnabled) }
    var recallTarget by remember { mutableStateOf<String?>(null) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var mentionTarget by remember { mutableStateOf(false) }
    var pendingMention by remember { mutableStateOf<String?>(null) }

    DisposableEffect(peerId) {
        engine.chatStore.activeConversationId.value = peerId
        engine.chatStore.markAllRead(peerId)
        onDispose {
            if (engine.chatStore.activeConversationId.value == peerId) {
                engine.chatStore.activeConversationId.value = null
            }
        }
    }

    val chatMessages = messages[peerId] ?: emptyList()
    val announcement by engine.serverAnnouncement.collectAsState()

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 气泡最大宽度：屏宽 72%，上限 560dp（手机/平板/桌面通用）
        val bubbleMaxWidth = (maxWidth * com.syna.ui.BUBBLE_MAX_WIDTH_RATIO)
            .coerceAtMost(com.syna.ui.BUBBLE_ABSOLUTE_MAX_DP.dp)
    Column(modifier = Modifier.fillMaxSize()) {
        // 服务器群公告条
        val ann = announcement
        if (ann != null && ann.groupId == peerId && ann.text.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📢 ${ann.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { engine.dismissAnnouncement() }.padding(4.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isGroup) group!!.name else (peer?.username ?: peerId),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                ) 
                Text(
                    text = if (isGroup) {
                        val server = engine.isServerGroup(groupId = peerId)
                        buildString {
                            if (server) append("🖥 ")
                            append(group!!.name)
                            append(" · ${group!!.memberIds.size} 名成员 · ${engine.settings.connectionMode.label}")
                            if (server) {
                                append(if (serverState == com.syna.net.ServerState.CONNECTED) " · 已连接" else " · 已断开")
                            }
                        }
                    } else {
                        val pending = outbox[peerId]?.size ?: 0
                        buildString {
                            append(if (peer?.online == true) "在线" else "离线")
                            append(" · ${engine.settings.connectionMode.label}${if (engine.peerKeys.value[peerId] != null) " · 加密" else ""}")
                            if (pending > 0) append(" · $pending 条待发送")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val typingInfo = typingSubtitle(engine, peerId, isGroup, group, peer, typing)
                if (typingInfo != null) {
                    Text(
                        text = typingInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 群管理：群主可解散，成员可退出（服务器群除外）
            if (isGroup && !engine.isServerGroup(peerId)) {
                val isCreator = group?.creatorId == engine.userId
                var showLeaveDialog by remember { mutableStateOf(false) }
                Text(
                    text = if (isCreator) "解散" else "退出",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { showLeaveDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                if (showLeaveDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showLeaveDialog = false },
                        title = { Text(if (isCreator) "解散群聊" else "退出群聊") },
                        text = {
                            Text(
                                if (isCreator) "确定解散「${group?.name}」吗？所有成员将收到通知，群聊记录将被清除。"
                                else "确定退出「${group?.name}」吗？退出后不再接收该群消息。",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showLeaveDialog = false
                                    scope.launch {
                                        if (isCreator) engine.dissolveGroup(peerId) else engine.leaveGroup(peerId)
                                    }
                                    onBack()
                                },
                            ) { Text("确定") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showLeaveDialog = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(chatMessages.reversed(), key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    isMine = message.senderId == engine.userId,
                    showSenderName = isGroup && message.senderId != engine.userId,
                    senderName = group?.memberNames?.get(message.senderId) ?: message.senderId,
                    onLongClick = { recallTarget = message.id },
                    allMessages = chatMessages,
                    maxBubbleWidth = bubbleMaxWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 撤回/回复确认
        val targetId = recallTarget
        if (targetId != null) {
            val target = chatMessages.firstOrNull { it.id == targetId }
            val canRecall = target != null &&
                target.senderId == engine.userId &&
                !target.recalled &&
                System.currentTimeMillis() - target.ts <= 2 * 60_000L
            val canReply = target != null && !target.recalled
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { recallTarget = null },
                title = { Text("消息操作") },
                text = {
                    Text(
                        when {
                            target == null -> "消息不存在"
                            target.recalled -> "该消息已撤回"
                            !canRecall -> "仅可撤回自己 2 分钟内发送的消息"
                            else -> "撤回这条消息？双方都将看到「已撤回」。"
                        },
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            recallTarget = null
                            if (canRecall) {
                                scope.launch { engine.recallMessage(peerId, targetId) }
                            }
                        },
                        enabled = canRecall,
                    ) { Text("撤回") }
                },
                dismissButton = {
                    Row {
                        if (canReply) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    replyingTo = target
                                    recallTarget = null
                                },
                            ) { Text("回复") }
                        }
                        androidx.compose.material3.TextButton(onClick = { recallTarget = null }) { Text("取消") }
                    }
                },
            )
        }

        // 回复条
        val replyMsg = replyingTo
        if (replyMsg != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↩ 回复 ${if (replyMsg.senderId == engine.userId) "自己" else (group?.memberNames?.get(replyMsg.senderId) ?: peer?.username ?: "对方")}: ${replyMsg.body.take(40)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { replyingTo = null }
                        .padding(6.dp),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "🔥",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (burn) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { burn = !burn }
                    .padding(10.dp),
            )
            Spacer(Modifier.size(6.dp))
            FilePickerButton(
                onFilePicked = { name, bytes ->
                    scope.launch {
                        engine.sendFile(peerId, name, bytes, mimeTypeFromName(name))
                    }
                },
            )
            if (isGroup) {
                Text(
                    text = "@",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable { mentionTarget = true }
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(4.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    engine.sendTyping(peerId)
                },
                placeholder = { Text(if (burn) "输入消息（阅后即焚）…" else "输入消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.size(6.dp))
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        val replyId = replyingTo?.id
                        val mentions = pendingMention?.let { listOf(it) } ?: emptyList()
                        scope.launch {
                            if (isGroup) {
                                engine.sendGroupText(peerId, text, burn = burn, replyTo = replyId, mentions = mentions)
                            } else {
                                engine.sendText(peerId, text, burn = burn, replyTo = replyId, mentions = mentions)
                            }
                        }
                        input = ""
                        replyingTo = null
                        pendingMention = null
                        scope.launch {
                            if (chatMessages.isNotEmpty()) {
                                listState.scrollToItem(0)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Text("➤", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 2.dp))
            }
        }

        // @成员选择对话框
        if (mentionTarget) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { mentionTarget = false },
                title = { Text("选择要 @ 的成员") },
                text = {
                    Column {
                        group?.memberIds?.filter { it != engine.userId }?.forEach { memberId ->
                            val name = group?.memberNames?.get(memberId) ?: memberId
                            Text(
                                text = "@$name",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        input = input.trimEnd() + (if (input.isBlank()) "" else " ") + "@$name "
                                        pendingMention = memberId
                                        mentionTarget = false
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { mentionTarget = false }) { Text("取消") }
                },
            )
        }
    }
    } // BoxWithConstraints
}

@Composable
private fun typingSubtitle(
    engine: SynaEngine,
    conversationId: String,
    isGroup: Boolean,
    group: com.syna.net.GroupInfo?,
    peer: com.syna.net.Peer?,
    typing: Map<String, Pair<Long, String>>,
): String? {
    val state = typing[conversationId] ?: return null
    val (ts, senderId) = state
    if (System.currentTimeMillis() - ts > 3_000L) return null
    val sender = if (isGroup) {
        group?.memberNames?.get(senderId) ?: "成员"
    } else {
        peer?.username ?: "对方"
    }
    return "$sender 正在输入…"
}

private fun mimeTypeFromName(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    modifier: Modifier = Modifier,
    showSenderName: Boolean = false,
    senderName: String = "",
    onLongClick: () -> Unit = {},
    allMessages: List<ChatMessage> = emptyList(),
    maxBubbleWidth: Dp = 280.dp,
) {
    val bubbleColor = if (isMine) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            if (showSenderName) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isMine) 16.dp else 4.dp,
                            topEnd = if (isMine) 4.dp else 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 16.dp,
                        ),
                    )
                    .background(bubbleColor)
                    .combinedClickable(onClick = {}, onLongClick = onLongClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (message.recalled) {
                    Text(
                        text = "[消息已撤回]",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.7f),
                    )
                } else {
                    // 引用回复预览
                    val quoted = message.replyToId?.let { id -> allMessages.firstOrNull { it.id == id } }
                    if (quoted != null && !quoted.recalled) {
                        Text(
                            text = "↩ ${quoted.body.take(30)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .background(if (isMine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    }
                    Row {
                        if (message.burnAfterReading) {
                            Text("🔥", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(4.dp))
                        }
                        if (message.encrypted) {
                            Text("🔒", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(4.dp))
                        }
                        when (message.kind) {
                            MessageKind.IMAGE -> {
                                val path = message.localPath
                                if (path != null && message.progress == null && !message.recalled) {
                                    val bitmap by produceState<ImageBitmap?>(null, path) {
                                        value = runCatching {
                                            val bytes = readFileBytes(path)
                                            if (bytes.size <= 4 * 1024 * 1024) bytes.decodeToImageBitmap() else null
                                        }.getOrNull()
                                    }
                                    val bmp = bitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = message.fileName,
                                            modifier = Modifier
                                                .widthIn(max = maxBubbleWidth * 0.8f)
                                                .clip(RoundedCornerShape(8.dp)),
                                        )
                                    } else {
                                        Text("🖼 ${message.fileName ?: "图片"}", style = MaterialTheme.typography.bodyMedium, color = textColor)
                                    }
                                } else {
                                    Text(
                                        "🖼 ${message.fileName ?: "图片"}${message.progress?.let { " ($it%)" } ?: ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                    )
                                }
                            }
                            MessageKind.FILE -> Text("📄 ${message.fileName ?: "文件"}${message.fileSize?.let { " · ${it / 1024}KB" } ?: ""}${message.progress?.let { " ($it%)" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = textColor)
                            MessageKind.TEXT -> Text(
                                text = message.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(message.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = if (isMine) 0.dp else 4.dp, end = if (isMine) 4.dp else 0.dp),
                )
                if (isMine) {
                    Text(
                        text = when (message.status) {
                            MessageStatus.SENDING -> "发送中"
                            MessageStatus.SENT -> "✓"
                            MessageStatus.READ -> "✓✓"
                            MessageStatus.FAILED -> "发送失败"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (message.status) {
                            MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
