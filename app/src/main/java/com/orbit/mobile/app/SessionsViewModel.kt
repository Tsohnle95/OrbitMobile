package com.orbit.mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.mobile.data.OpenCodeClient
import com.orbit.mobile.data.Pairing
import com.orbit.mobile.data.PairingStore
import com.orbit.mobile.data.ProjectDto
import com.orbit.mobile.data.SessionDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val creating: Boolean = false,
)

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairingStore(application)
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state

    private var client: OpenCodeClient? = null

    fun ensureClient(): OpenCodeClient? {
        if (client == null) {
            val pairing = store.load() ?: return null
            client = OpenCodeClient(pairing.target)
        }
        return client
    }

    fun target(): Pairing? = store.load()

    fun targetLabel(): String {
        val t = store.load()?.target ?: return "not paired"
        return "${t.host}:${t.port}"
    }

    fun refresh() {
        val c = ensureClient() ?: run {
            _state.value = SessionsUiState(loading = false, error = "Not paired")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.sessions.isEmpty())
            try {
                c.detectFlavor()
                val list = c.sessions(null).filter { it.parentID == null }.sortedByDescending { it.time.updated }
                val projects = runCatching { c.projects() }.getOrDefault(emptyList())
                _state.value = SessionsUiState(sessions = list, projects = projects, loading = false)
            } catch (e: Exception) {
                client?.close()
                client = null
                _state.value = SessionsUiState(
                    loading = false,
                    error = friendlyError(e),
                    sessions = _state.value.sessions,
                )
            }
        }
    }

    fun newSession(directory: String?, title: String?, onReady: (SessionDto) -> Unit) {
        val c = ensureClient() ?: return
        if (_state.value.creating) return
        _state.value = _state.value.copy(creating = true)
        viewModelScope.launch {
            try {
                val created = c.createSession(title = title, directory = directory)
                _state.value = _state.value.copy(creating = false)
                onReady(created)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    creating = false,
                    error = "Could not create session: ${friendlyError(e)}",
                )
            }
        }
    }

    fun renameSession(id: String, title: String) {
        val c = ensureClient() ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { c.renameSession(id, title) }
            refresh()
        }
    }

    fun deleteSession(id: String) {
        val c = ensureClient() ?: return
        viewModelScope.launch {
            runCatching { c.deleteSession(id) }
            _state.value = _state.value.copy(sessions = _state.value.sessions.filter { it.id != id })
        }
    }

    fun loadDirectories(projectId: String, onDone: (List<String>) -> Unit) {
        val c = ensureClient() ?: return
        viewModelScope.launch {
            onDone(runCatching { c.projectDirectories(projectId) }.getOrDefault(emptyList()))
        }
    }

    fun forgetPairing() {
        store.clear()
        client?.close()
        client = null
        _state.value = SessionsUiState(loading = false)
    }

    fun consumeError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    override fun onCleared() {
        client?.close()
        client = null
    }
}

internal fun friendlyError(e: Exception): String {
    val msg = e.message ?: "unknown error"
    return when {
        msg.contains("Connect timeout", true) || msg.contains("Failed to connect", true) ||
            msg.contains("ECONNREFUSED", true) || msg.contains("Connection refused", true) ->
            "Connection refused — is omni-serve running?"
        else -> msg.take(160)
    }
}
