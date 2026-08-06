package com.syna.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syna.chat.ChatMessage
import com.syna.chat.MessageStatus
import com.syna.net.SynaEngine
import com.syna.util.formatTime
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
    val peer = peers.firstOrNull { it.id == peerId }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var burn by remember { mutableStateOf(engine.settings.burnAfterReadingEnabled) }

    DisposableEffect(peerId) {
        engine.chatStore.activeConversationId = peerId
        engine.chatStore.markAllRead(peerId)
        onDispose {
            if (engine.chatStore.activeConversationId == peerId) {
                engine.chatStore.activeConversationId = null
            }
        }
    }

    val chatMessages = messages[peerId] ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
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
                    text = peer?.username ?: peerId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (peer?.online == true) "在线" else "离线")
                        append(" · ${engine.settings.connectionMode.label}${if (engine.peerKeys.value[peerId] != null) " · 加密" else ""}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    modifier = Modifier.fillMaxWidth(),
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
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (burn) "输入消息（阅后即焚）…" else "输入消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.size(6.dp))
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        scope.launch {
                            engine.sendText(peerId, text, burn = burn)
                        }
                        input = ""
                        scope.launch {
                            listState.scrollToItem(0)
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
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isMine: Boolean, modifier: Modifier = Modifier) {
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
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isMine) 16.dp else 4.dp,
                            topEnd = if (isMine) 4.dp else 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 16.dp,
                        ),
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row {
                    if (message.burnAfterReading) {
                        Text("🔥", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(4.dp))
                    }
                    if (message.encrypted) {
                        Text("🔒", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
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
