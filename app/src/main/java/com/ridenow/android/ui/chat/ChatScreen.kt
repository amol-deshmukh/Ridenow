package com.ridenow.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridenow.proto.v1.ChatMessage
import com.ridenow.proto.v1.ChatRole
import com.ridenow.android.ui.common.ErrorCard
import com.ridenow.android.ui.common.SectionTitle

/**
 * @param activeRideId ride booked on the Ride tab (if any); used as the chat room id.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel, activeRideId: String?, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var roomId by rememberSaveable(activeRideId) { mutableStateOf(activeRideId ?: "demo-ride") }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("4 · Bidirectional streaming", "TripChat(stream ChatClientEvent) ↔ stream ChatMessage")

        if (!state.connected) {
            OutlinedTextField(
                value = roomId,
                onValueChange = { roomId = it },
                label = { Text("Room (ride_id)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Join as:")
                FilterChip(
                    selected = state.role == ChatRole.CHAT_ROLE_RIDER,
                    onClick = { viewModel.selectRole(ChatRole.CHAT_ROLE_RIDER) },
                    label = { Text("Rider") },
                )
                FilterChip(
                    selected = state.role == ChatRole.CHAT_ROLE_DRIVER,
                    onClick = { viewModel.selectRole(ChatRole.CHAT_ROLE_DRIVER) },
                    label = { Text("Driver") },
                )
                Button(onClick = { viewModel.connect(roomId.trim()) }, enabled = roomId.isNotBlank()) { Text("Connect") }
            }
            Text(
                "Tip: no driver connected? A bot replies. Run a 2nd emulator as Driver in the same room to chat for real.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🟢 ${state.roomId}", Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::disconnect) { Text("Leave") }
                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
            }
        }

        state.error?.let { ErrorCard(it, onDismiss = viewModel::dismissError) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.messages, key = { it.messageId }) { msg -> ChatBubble(msg, isMine = msg.senderRole == state.role) }
        }

        if (state.connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.send(draft); draft = "" }, enabled = draft.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, isMine: Boolean) {
    if (msg.senderRole == ChatRole.CHAT_ROLE_SYSTEM) {
        Text(
            msg.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(msg.senderId, style = MaterialTheme.typography.labelSmall)
                Text(msg.text)
            }
        }
    }
}
