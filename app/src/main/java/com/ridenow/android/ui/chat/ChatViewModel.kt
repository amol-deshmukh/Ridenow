package com.ridenow.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ridenow.android.RideNowApp
import com.ridenow.android.data.RideRepository
import com.ridenow.android.data.toUserMessage
import com.ridenow.proto.v1.ChatClientEvent
import com.ridenow.proto.v1.ChatMessage
import com.ridenow.proto.v1.ChatRole
import com.ridenow.proto.v1.chatClientEvent
import com.ridenow.proto.v1.joinChat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val role: ChatRole = ChatRole.CHAT_ROLE_RIDER,
    val connected: Boolean = false,
    val roomId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)

class ChatViewModel(private val repository: RideRepository) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Outgoing half of the bidi stream. A Channel turns imperative `send()` calls into a Flow. */
    private var outgoing: Channel<ChatClientEvent>? = null
    private var chatJob: Job? = null

    fun selectRole(role: ChatRole) = _state.update { it.copy(role = role) }

    // ---- 4. Bidirectional streaming ------------------------------------------
    fun connect(rideId: String) {
        disconnect()
        val out = Channel<ChatClientEvent>(Channel.BUFFERED)
        outgoing = out
        // Protocol: the first message on the stream must be `join` (a oneof case).
        out.trySend(chatClientEvent { join = joinChat { this.rideId = rideId; role = _state.value.role } })
        _state.update { it.copy(connected = true, roomId = rideId, messages = emptyList(), error = null) }

        chatJob = viewModelScope.launch {
            repository.tripChat(out.receiveAsFlow())
                .onCompletion { _state.update { it.copy(connected = false) } }
                .catch { e -> _state.update { it.copy(error = e.toUserMessage()) } }
                .collect { msg -> _state.update { it.copy(messages = it.messages + msg) } }
        }
    }

    /**
     * Messages are NOT added to the list locally. The server broadcasts them back
     * to everyone (including us) with a server timestamp + id, so every
     * participant sees the same order. (A production app would add an optimistic
     * "sending…" bubble and reconcile by a client-generated id.)
     */
    fun send(text: String) {
        if (text.isBlank()) return
        outgoing?.trySend(chatClientEvent { this.text = text.trim() })
    }

    /** Graceful: half-close our side. Server sees the request Flow complete, then ends the stream (OK). */
    fun disconnect() {
        outgoing?.close()
        outgoing = null
    }

    /** Abrupt: cancel the RPC. Server sees CANCELLED. */
    fun cancel() {
        chatJob?.cancel()
        outgoing = null
        _state.update { it.copy(connected = false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        val Factory = viewModelFactory {
            initializer { ChatViewModel((this[APPLICATION_KEY] as RideNowApp).container.rideRepository) }
        }
    }
}
