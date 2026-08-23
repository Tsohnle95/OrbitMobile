package com.omniagent.mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omniagent.mobile.data.MessageWithPartsDto
import com.omniagent.mobile.data.AgentInfoDto
import com.omniagent.mobile.data.OpenCodeClient
import com.omniagent.mobile.data.PairingStore
import com.omniagent.mobile.data.PartDto
import com.omniagent.mobile.data.PermissionRequestDto
import com.omniagent.mobile.data.ProviderListDto
import com.omniagent.mobile.data.ServerTarget
import com.omniagent.mobile.data.SessionDto
import com.omniagent.mobile.data.ToolStateDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val activity: List<ToolStateDto> = emptyList(),
    val streaming: Boolean = false,
    val time: Long = 0,
)

data class ModelOption(
    val providerId: String,
    val modelName: String,
    val modelId: String,
)

data class ProviderGroup(
    val id: String,
    val name: String,
    val connected: Boolean,
    val models: List<ModelOption>,
)

data class ChatUiState(
    val session: SessionDto? = null,
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val pendingPermission: PermissionRequestDto? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val connected: Boolean = true,
    val providers: List<ProviderGroup> = emptyList(),
    val currentProviderId: String? = null,
    val currentModelId: String? = null,
    val agents: List<AgentInfoDto> = emptyList(),
    val currentAgent: String = "build",
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PairingStore(application)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var client: OpenCodeClient? = null
    private var streamJob: Job? = null
    private var refreshJob: Job? = null

    fun start(sessionId: String, target: ServerTarget, password: String?) {
        if (client != null && _state.value.session?.id == sessionId) return
        streamJob?.cancel()
        refreshJob?.cancel()
        client?.close()
        client = OpenCodeClient(target)
        _state.value = ChatUiState(session = SessionDto(id = sessionId))
        viewModelScope.launch {
            val full = runCatching { client!!.session(sessionId) }.getOrNull()
            if (full != null) {
                val modelObj = full.model
                _state.value = _state.value.copy(
                    session = full,
                    currentProviderId = modelObj?.get("providerID")?.let { str(it) },
                    currentModelId = modelObj?.get("id")?.let { str(it) },
                )
            }
        }
        loadProviders()
        loadAgents()
        refresh()
        streamJob = client!!.eventStream().connect(
            scope = viewModelScope,
            password = password,
            onEvent = { type, payload -> handleEvent(type, payload) },
            onState = { connected ->
                _state.value = _state.value.copy(connected = connected)
                if (connected) refresh()
            },
        )
    }

    private fun loadProviders() {
        val c = client ?: return
        viewModelScope.launch {
            val list: ProviderListDto? = runCatching { c.providers() }.getOrNull()
            if (list != null) {
                val groups = list.all.map { p ->
                    ProviderGroup(
                        id = p.id,
                        name = p.name ?: p.id,
                        connected = list.connected.contains(p.id),
                        models = p.models.values.map { m ->
                            ModelOption(
                                providerId = p.id,
                                modelName = m.name ?: m.id,
                                modelId = m.id,
                            )
                        }.sortedBy { it.modelId.lowercase() },
                    )
                }.filter { it.models.isNotEmpty() }
                    .sortedWith(compareByDescending<ProviderGroup> { it.connected }.thenBy { it.name.lowercase() })
                _state.value = _state.value.copy(providers = groups)
            }
        }
    }

    private fun loadAgents() {
        val c = client ?: return
        viewModelScope.launch {
            val agents: List<AgentInfoDto> = runCatching { c.agents() }.getOrDefault(emptyList())
                .filter { it.mode != "subagent" }
                .map { AgentInfoDto(name = it.name, mode = it.mode, description = it.description) }
            _state.value = _state.value.copy(agents = agents)
        }
    }

    fun setModel(providerId: String, modelId: String) {
        val c = client ?: return
        val sessionId = _state.value.session?.id ?: return
        _state.value = _state.value.copy(currentProviderId = providerId, currentModelId = modelId)
        viewModelScope.launch {
            runCatching { c.setSessionModel(sessionId, providerId, modelId) }
            runCatching {
                client!!.session(sessionId).let { full ->
                    _state.value = _state.value.copy(
                        session = full,
                        currentProviderId = full.model?.get("providerID")?.let { str(it) },
                        currentModelId = full.model?.get("id")?.let { str(it) },
                    )
                }
            }
        }
    }

    fun setAgent(name: String) {
        _state.value = _state.value.copy(currentAgent = name)
    }

    fun updateDraft(value: String) {
        _state.value = _state.value.copy(draft = value)
    }

    fun send() {
        val s = _state.value
        val text = s.draft.trim()
        val c = client ?: return
        val sessionId = s.session?.id ?: return
        if (text.isEmpty() || s.sending || s.busy) return
        _state.value = s.copy(sending = true, draft = "")
        viewModelScope.launch {
            try {
                val resp = c.sendMessage(sessionId, text, s.currentAgent)
                if (resp.status.value !in 200..299) throw Exception("HTTP ${resp.status.value}")
                _state.value = _state.value.copy(sending = false)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    sending = false,
                    draft = _state.value.draft.ifBlank { text },
                )
            }
        }
    }

    fun abort() {
        val c = client ?: return
        val sessionId = _state.value.session?.id ?: return
        viewModelScope.launch { runCatching { c.abortSession(sessionId) } }
    }

    fun replyPermission(requestId: String, reply: String) {
        val c = client ?: return
        _state.value = _state.value.copy(pendingPermission = null)
        viewModelScope.launch { runCatching { c.replyPermission(requestId, reply) } }
    }

    fun stop() {
        streamJob?.cancel()
        refreshJob?.cancel()
        streamJob = null
        refreshJob = null
        client?.close()
        client = null
    }

    private fun refresh() {
        val c = client ?: return
        val sessionId = _state.value.session?.id ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching {
                val list = c.messages(sessionId).map { it.toChatMessage() }
                _state.value = _state.value.copy(messages = list)
                val p = runCatching { c.permissions() }.getOrDefault(emptyList())
                    .firstOrNull { it.sessionID == sessionId }
                _state.value = _state.value.copy(pendingPermission = p ?: _state.value.pendingPermission)
            }
        }
    }

    private suspend fun handleEvent(type: String, payload: JsonObject) {
        val sessionId = _state.value.session?.id ?: return
        when (type) {
            "message.updated" -> handleMessageDelta(sessionId)
            "message.part.updated" -> handleMessageDelta(sessionId)
            "session.status" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val statusType = (payload["status"] as? JsonObject)?.get("type")?.let { str(it) }
                    _state.value = _state.value.copy(busy = statusType == "busy")
                }
            }
            "permission.asked" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val id = str(payload["id"]) ?: return
                    val permission = str(payload["permission"]) ?: ""
                    val patterns = (payload["patterns"] as? JsonArray)?.mapNotNull { el ->
                        (el as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: emptyList()
                    _state.value = _state.value.copy(
                        pendingPermission = PermissionRequestDto(
                            id = id,
                            sessionID = sessionId,
                            permission = permission,
                            patterns = patterns,
                        ),
                        busy = true,
                    )
                }
            }
            "permission.replied" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    _state.value = _state.value.copy(pendingPermission = null)
                }
            }
        }
    }

    private suspend fun handleMessageDelta(sessionId: String) {
        val c = client ?: return
        runCatching {
            val list = c.messages(sessionId).map { it.toChatMessage() }
            _state.value = _state.value.copy(messages = list)
        }
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun MessageWithPartsDto.toChatMessage(): ChatMessage {
        val sb = StringBuilder()
        val tools = mutableListOf<ToolStateDto>()
        for (part in parts) {
            when (part.type) {
                "text" -> part.text?.let { sb.append(it) }
                "reasoning" -> Unit
                "tool" -> parseToolState(part)?.let { tools.add(it) }
            }
        }
        val streaming = info.role == "assistant" && info.time?.completed == null
        return ChatMessage(
            id = info.id,
            role = info.role,
            text = sb.toString(),
            activity = tools,
            streaming = streaming,
            time = info.time?.created ?: 0,
        )
    }

    private fun parseToolState(part: PartDto): ToolStateDto? {
        val obj = part.state as? JsonObject ?: return null
        return runCatching { Json.decodeFromJsonElement(ToolStateDto.serializer(), obj) }.getOrNull()
    }
}
