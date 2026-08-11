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
package com.syna.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import com.syna.util.ImagePickerButton
import com.syna.util.formatDate
import com.syna.util.formatTime
import com.syna.util.isSameDay
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
    // 密钥指纹核对对话框（存对端公钥）
    var fingerprintDialog by remember { mutableStateOf<String?>(null) }
    // 群管理对话框
    var showAdminDialog by remember { mutableStateOf(false) }
    val peer = peers.firstOrNull { it.id == peerId }
    val group = groups.firstOrNull { it.id == peerId }
    // 群权限（顶部计算，供管理对话框使用）
    val isCreator = group?.creatorId == engine.userId
    val isAdmin = engine.isGroupAdmin(peerId)
    val isGroup = group != null
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var burn by remember { mutableStateOf(engine.settings.burnAfterReadingEnabled) }
    var recallTarget by remember { mutableStateOf<String?>(null) }
    var forwardTarget by remember { mutableStateOf<ChatMessage?>(null) }
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
            // 密钥指纹（TOFU 安全号码）：1:1 且已加密时显示短码，点击核对完整指纹
            if (!isGroup) {
                val peerKey = engine.peerKeys.value[peerId]
                if (peerKey != null) {
                    val fp = com.syna.shield.KeyPinning.fingerprint(peerKey)
                    Text(
                        text = "指纹 $fp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { fingerprintDialog = peerKey }
                            .padding(horizontal = 16.dp),
                    )
                }
            }
            // 群管理：群主/管理员可管理成员（踢出/禁言/设管理员）；群主可解散，成员可退出
            if (isGroup && !engine.isServerGroup(peerId)) {
                var showLeaveDialog by remember { mutableStateOf(false) }
                if (isAdmin) {
                    Text(
                        text = "管理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { showAdminDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
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
            itemsIndexed(chatMessages.reversed(), key = { _, m -> m.id }) { index, message ->
                // 日期分隔线：与上一条（更早的）消息跨天时显示
                val prev = chatMessages.getOrNull(chatMessages.size - index - 2)
                if (prev == null || !isSameDay(prev.ts, message.ts)) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatDate(message.ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
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
            val canForward = target != null && !target.recalled
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
                        if (canForward) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    forwardTarget = target
                                    recallTarget = null
                                },
                            ) { Text("转发") }
                        }
                        if (target != null && target.body.isNotBlank()) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    com.syna.storage.copyTextToClipboard(target.body)
                                    // 剪贴板短 TTL：30 秒后自动清除（防敏感内容滞留）
                                    scope.launch {
                                        kotlinx.coroutines.delay(30_000)
                                        com.syna.shield.clearOwnClipboard()
                                    }
                                    recallTarget = null
                                },
                            ) { Text("复制") }
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
                text = "焚",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (burn) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { burn = !burn }
                    .padding(10.dp),
            )
            Spacer(Modifier.size(6.dp))
            ImagePickerButton(
                onImagePicked = { name, bytes ->
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
            // 语音消息：长按录音，松开发送（30 秒上限）
            var recording by remember { mutableStateOf(false) }
            var recordingSecs by remember { mutableStateOf(0) }
            val recorder = com.syna.util.VoiceRecorder
            // 录音兜底清理：离开页面/组合销毁时停止并释放麦克风（防录音线程/MediaRecorder 泄漏）
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    if (recording) {
                        recording = false
                        recorder.cancel()
                    }
                }
            }
            var voiceDuration by remember { mutableStateOf(0L) }
            var voiceFile by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(recording) {
                if (recording) {
                    recordingSecs = 0
                    while (recording) {
                        delay(1_000)
                        recordingSecs++
                        if (recordingSecs >= 30) {
                            val r = recorder.stop()
                            if (r != null) {
                                voiceFile = r.first
                                voiceDuration = r.second
                            }
                            recording = false
                        }
                    }
                }
            }
            Text(
                text = if (recording) "● ${recordingSecs}s" else "录音",
                style = MaterialTheme.typography.titleMedium,
                color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            // 点按：录音中=停止并发送；已录好=发送；否则请求权限
                            if (recording) {
                                recording = false
                                val r = recorder.stop()
                                if (r != null) {
                                    voiceFile = r.first
                                    voiceDuration = r.second
                                }
                            }
                            val f = voiceFile
                            if (f != null) {
                                voiceFile = null
                                scope.launch {
                                    val file = java.io.File(f)
                                    val isWav = f.endsWith(".wav")
                                    engine.sendFile(
                                        peerId,
                                        if (isWav) "语音消息.wav" else "语音消息.amr",
                                        file.readBytes(),
                                        if (isWav) "audio/wav" else "audio/amr",
                                    )
                                    file.delete()
                                }
                            } else if (!com.syna.util.canRecordVoice()) {
                                com.syna.util.requestRecordAudioPermission()
                            }
                        },
                        onLongClick = {
                            if (com.syna.util.canRecordVoice()) {
                                voiceFile = null
                                // start 失败（麦克风占用等）不进入假录音状态
                                if (recorder.start()) {
                                    recording = true
                                } else {
                                    com.syna.util.notifyMessage("Syna", "无法开始录音（麦克风不可用）")
                                }
                            } else {
                                com.syna.util.requestRecordAudioPermission()
                            }
                        },
                    )
                    .padding(8.dp),
            )
            Spacer(Modifier.size(4.dp))
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        val replyId = replyingTo?.id
                        val mentions = pendingMention?.let { listOf(it) } ?: emptyList()
                        val doSend = {
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
                        if (burn && com.syna.shield.ShieldController.current != null) {
                            // 敏感操作二次认证：发送阅后即焚消息前需生物识别确认（护盾启用时）
                            com.syna.shield.ShieldController.current!!.verifyIdentity { granted ->
                                if (granted) {
                                    doSend()
                                } else {
                                    com.syna.util.notifyMessage("Syna", "阅后即焚发送已取消（需生物识别确认）")
                                }
                            }
                        } else {
                            // 护盾未启用：无二次认证要求
                            doSend()
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
                Text("发送", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 2.dp))
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

        // 转发目标选择
        val forwardMsg = forwardTarget
        if (forwardMsg != null) {
            val allConvs = engine.chatStore.conversations.collectAsState().value
            val forwardGroups = engine.groups.collectAsState().value
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { forwardTarget = null },
                title = { Text("转发到…") },
                text = {
                    Column {
                        val targets = allConvs.map { it.peerId to it.peerName } +
                            forwardGroups.filter { g -> g.id !in allConvs.map { it.peerId } }.map { it.id to it.name }
                        if (targets.isEmpty()) {
                            Text("暂无可转发会话", style = MaterialTheme.typography.bodySmall)
                        }
                        targets.forEach { (id, name) ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val msg = forwardMsg
                                        forwardTarget = null
                                        if (msg != null) {
                                            scope.launch { engine.forwardMessage(id, msg) }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { forwardTarget = null }) { Text("取消") }
                },
            )
        }
    }
    } // BoxWithConstraints

        // 群管理：成员列表 + 踢出/禁言/设管理员（仅创建者/管理员）
        if (showAdminDialog && group != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAdminDialog = false },
                title = { Text("群管理 · ${group.name}") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        group.memberIds.filter { it != engine.userId }.forEach { memberId ->
                            val mName = group.memberNames[memberId] ?: memberId
                            val isAdminMember = group.admins.contains(memberId)
                            val muted = engine.isMuted(peerId, memberId)
                            // 成员密钥指纹（TOFU 安全号码，供核对防群内冒名）
                            val memberFp = engine.peerKeys.value[memberId]?.let {
                                com.syna.shield.KeyPinning.fingerprint(it)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$mName${if (memberId == group.creatorId) " [群主]" else if (isAdminMember) " [管理员]" else ""}${if (muted) " [禁言]" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (memberFp != null) {
                                        Text(
                                            text = "指纹 $memberFp",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (memberId != group.creatorId) {
                                    TextButton(onClick = {
                                        kotlinx.coroutines.GlobalScope.launch { engine.kickFromGroup(peerId, memberId) }
                                    }) { Text("踢出", color = MaterialTheme.colorScheme.error) }
                                    TextButton(onClick = {
                                        kotlinx.coroutines.GlobalScope.launch { engine.muteMember(peerId, memberId, if (muted) 0L else 60 * 60 * 1000L) }
                                    }) { Text(if (muted) "解禁" else "禁言") }
                                    if (isCreator) {
                                        TextButton(onClick = {
                                            kotlinx.coroutines.GlobalScope.launch { engine.setGroupAdmin(peerId, memberId, !isAdminMember) }
                                        }) { Text(if (isAdminMember) "撤管" else "设管") }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showAdminDialog = false }) { Text("关闭") }
                },
            )
        }
        // 密钥指纹核对：完整指纹 + 信任新密钥（重装/确认无中间人后重新固定）
        fingerprintDialog?.let { key ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { fingerprintDialog = null },
                title = { Text("对端密钥指纹") },
                text = {
                    Column {
                        Text(
                            "通过其他可信渠道（口头/线下）与对方核对以下指纹一致后，可放心通信。若指纹变化，可能是对方重装应用或中间人攻击。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = com.syna.shield.KeyPinning.fingerprintFull(key),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        val pinned = com.syna.shield.KeyPinning.pinnedKey(peerId)
                        if (pinned != null && pinned != key) {
                            Text(
                                "[警告] 该公钥与已固定指纹不一致（密钥变更被拒）——确认安全后可点击下方按钮信任新密钥",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        // 信任新密钥（重新固定 + 接受新公钥）
                        engine.retrustPeerKey(peerId, key)
                        fingerprintDialog = null
                    }) { Text("信任此密钥") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { fingerprintDialog = null }) { Text("关闭") }
                },
            )
        }
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
                    // 引用回复预览（独立引用块 + 左侧竖条，双端可见且不与正文重叠）
                    val quoted = message.replyToId?.let { id -> allMessages.firstOrNull { it.id == id } }
                    if (quoted != null && !quoted.recalled) {
                        val quoteBg = if (isMine) {
                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(if (isMine) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "↩ 引用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor.copy(alpha = 0.75f),
                                )
                                Text(
                                    text = quoted.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor.copy(alpha = 0.95f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row {
                        if (message.burnAfterReading) {
                            Text("焚", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(4.dp))
                        }
                        if (message.encrypted) {
                            Text("[锁]", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(4.dp))
                        }
                        when (message.kind) {
                            MessageKind.IMAGE -> {
                                val path = message.localPath
                                if (path != null && message.progress == null && !message.recalled) {
                                    val bitmap by produceState<ImageBitmap?>(null, path) {
                                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            runCatching {
                                                val bytes = readFileBytes(path)
                                                if (bytes.size <= 4 * 1024 * 1024) bytes.decodeToImageBitmap() else null
                                            }.getOrNull()
                                        }
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
                                        Text("图片 ${message.fileName ?: "图片"}", style = MaterialTheme.typography.bodyMedium, color = textColor)
                                    }
                                } else {
                                    Text(
                                        "图片 ${message.fileName ?: "图片"}${message.progress?.let { " ($it%)" } ?: ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                    )
                                }
                            }
                            MessageKind.FILE -> {
                                val isVoice = message.fileName?.contains("语音消息") == true ||
                                    message.fileName?.endsWith(".amr") == true ||
                                    message.fileName?.endsWith(".wav") == true ||
                                    message.localPath?.endsWith(".amr") == true ||
                                    message.localPath?.endsWith(".wav") == true
                                if (isVoice && message.localPath != null) {
                                    // 语音气泡：播放按钮 + 时长
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clickable {
                                                com.syna.util.playVoiceAudio(message.localPath!!)
                                            }
                                            .padding(vertical = 2.dp),
                                    ) {
                                        Text("[播放]", style = MaterialTheme.typography.titleMedium, color = textColor)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "语音 ${message.fileSize?.let { "${it / 1024}KB" } ?: ""}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor,
                                        )
                                    }
                                } else {
                                    Text("文件 ${message.fileName ?: "文件"}${message.fileSize?.let { " · ${it / 1024}KB" } ?: ""}${message.progress?.let { " ($it%)" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = textColor)
                                }
                            }
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
