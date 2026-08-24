package com.orbit.mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.mobile.data.HealthDto
import com.orbit.mobile.data.OrbitApiException
import com.orbit.mobile.data.OpenCodeClient
import com.orbit.mobile.data.Pairing
import com.orbit.mobile.data.PairingStore
import com.orbit.mobile.data.ServerTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PairUiState(
    val saved: Pairing? = null,
    val manualHost: String = "",
    val manualPort: String = "4096",
    val manualPassword: String = "",
    val connecting: Boolean = false,
    val statusMessage: String? = null,
    val connected: Boolean = false,
    val serverVersion: String? = null,
)

class PairViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairingStore(application)
    private val _state = MutableStateFlow(PairUiState(saved = store.load()))
    val state: StateFlow<PairUiState> = _state

    fun updateManual(host: String, port: String, password: String) {
        _state.value = _state.value.copy(manualHost = host, manualPort = port, manualPassword = password)
    }

    fun consumeStatus() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    private fun setStatus(message: String?) {
        _state.value = _state.value.copy(statusMessage = message)
    }

    fun connect(target: ServerTarget, label: String) {
        if (_state.value.connecting) return
        _state.value = _state.value.copy(connecting = true, statusMessage = "Connecting…")
        viewModelScope.launch {
            val probe = OpenCodeClient(target)
            try {
                probe.detectFlavor()
                val health = probe.health()
                store.save(Pairing(target = target, label = label.ifBlank { target.host }))
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    saved = store.load(),
                    serverVersion = health.version,
                    statusMessage = "Paired with ${target.host}",
                )
            } catch (e: OrbitApiException) {
                probe.close()
                val reason = if (e.status.value == 401) "pairing password rejected"
                else "server said ${e.message ?: "error"}"
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    statusMessage = "Could not pair with ${target.host}:${target.port} — $reason",
                )
            } catch (e: Exception) {
                probe.close()
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = false,
                    statusMessage =
                        "Could not reach ${target.host}:${target.port} — ${e.message ?: "unknown error"}",
                )
            }
        }
    }

    fun connectScanned(raw: String) {
        val target = OpenCodeClient.fromUrl(raw)
        if (target == null) {
            setStatus("Not a valid omni-serve code.")
            return
        }
        connect(target, label = target.host)
    }

    fun connectManual() {
        val s = _state.value
        val host = s.manualHost.trim()
        if (host.isEmpty()) {
            setStatus("Enter the host shown by omni-serve.")
            return
        }
        val port = s.manualPort.toIntOrNull() ?: 4096
        val pass = s.manualPassword.trim()
        connect(ServerTarget(host = host, port = port, password = pass.ifBlank { null }), label = host)
    }

    fun forgetPairing() {
        store.clear()
        _state.value = PairUiState()
    }
}
