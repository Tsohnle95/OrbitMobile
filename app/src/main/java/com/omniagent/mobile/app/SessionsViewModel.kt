package com.omniagent.mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omniagent.mobile.data.OpenCodeClient
import com.omniagent.mobile.data.Pairing
import com.omniagent.mobile.data.PairingStore
import com.omniagent.mobile.data.SessionDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val creating: Boolean = false,
)

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairingStore(application)
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state

    private var client: OpenCodeClient? = null
    private var pollJob: Job? = null

    fun ensureClient(): OpenCodeClient? {
        if (client == null) {
            val pairing = store.load() ?: return null
            client = OpenCodeClient(pairing.target)
        }
        return client
    }

    fun target(): Pairing? = store.load()

    fun refresh() {
        val c = ensureClient() ?: run {
            _state.value = SessionsUiState(loading = false, error = "Not paired")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.sessions.isEmpty())
            try {
                val list = c.sessions(null)
                    .filter { it.parentID == null }
                    .sortedByDescending { it.time.updated }
                _state.value = SessionsUiState(sessions = list, loading = false)
            } catch (e: Exception) {
                _state.value = SessionsUiState(
                    loading = false,
                    error = e.message ?: "Failed to load sessions",
                    sessions = _state.value.sessions,
                )
            }
        }
    }

    fun newSession(onReady: (SessionDto) -> Unit) {
        val c = ensureClient() ?: return
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            try {
                val created = c.createSession(title = null, directory = null)
                _state.value = _state.value.copy(creating = false)
                onReady(created)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    creating = false,
                    error = "Could not create session: ${e.message ?: "unknown"}",
                )
            }
        }
    }

    fun consumeError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        client?.close()
        client = null
    }
}
