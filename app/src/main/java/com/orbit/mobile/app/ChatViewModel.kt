package com.orbit.mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orbit.mobile.data.MessageWithPartsDto
import com.orbit.mobile.data.AgentInfoDto
import com.orbit.mobile.data.ApiFlavor
import com.orbit.mobile.data.OpenCodeClient
import com.orbit.mobile.data.PairingStore
import com.orbit.mobile.data.PartDto
import com.orbit.mobile.data.PermissionRequestDto
import com.orbit.mobile.data.ProviderListDto
import com.orbit.mobile.data.ServerTarget
import com.orbit.mobile.data.SessionDto
import com.orbit.mobile.data.TodoItemDto
import com.orbit.mobile.data.ToolStateDto
import com.orbit.mobile.data.VcsDiffFileDto
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
    val toolName: String? = null,
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
    val todos: List<TodoItemDto> = emptyList(),
    val changes: List<VcsDiffFileDto> = emptyList(),
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
            runCatching { client!!.detectFlavor() }
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
            if (c.flavor == ApiFlavor.V2) {
                // v2: /api/provider has empty model maps; the real catalog is /api/model
                val catalog = runCatching { c.v2ModelCatalog() }.getOrNull().orEmpty()
                val groups = ProviderCatalog.fromV2ModelList(catalog)
                _state.value = _state.value.copy(providers = groups.filter { it.models.isNotEmpty() })
                return@launch
            }
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

    fun clientForFiles(): OpenCodeClient? = client

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
                val list = c.messages(sessionId).flatMap { it.toChatMessages() }
                    .sortedWith(compareBy<ChatMessage> { it.time }.thenBy { it.id })
                _state.value = _state.value.copy(messages = list)
                val p = runCatching { c.permissions() }.getOrDefault(emptyList())
                    .firstOrNull { it.sessionID == sessionId }
                _state.value = _state.value.copy(pendingPermission = p ?: _state.value.pendingPermission)
                loadTodos()
                loadChanges()
            }
        }
    }

    fun loadTodos() {
        val c = client ?: return
        val sessionId = _state.value.session?.id ?: return
        viewModelScope.launch {
            val todos = runCatching { c.todos(sessionId) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(todos = todos)
        }
    }

    fun loadChanges() {
        val c = client ?: return
        val directory = _state.value.session?.directory ?: return
        viewModelScope.launch {
            val changes = runCatching { c.vcsDiff(directory) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(changes = changes)
        }
    }

    private suspend fun handleEvent(type: String, payload: JsonObject) {
        val sessionId = _state.value.session?.id ?: return
        when (type) {
            // v2 granular streaming events — applied live, no refetch
            "session.reasoning.delta" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val msgId = str(payload["assistantMessageID"]) ?: return
                    val delta = str(payload["delta"]) ?: ""
                    appendStreaming(msgId, role = "reasoning", text = delta)
                }
            }
            "session.reasoning.started" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val msgId = str(payload["assistantMessageID"])
                    ensureStreamingEntry(msgId, role = "reasoning")
                }
            }
            "session.text.delta" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val msgId = str(payload["assistantMessageID"]) ?: return
                    val delta = str(payload["delta"]) ?: ""
                    appendStreaming(msgId, role = "assistant", text = delta)
                }
            }
            "session.text.started" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    val msgId = str(payload["assistantMessageID"])
                    ensureStreamingEntry(msgId, role = "assistant")
                }
            }
            "session.tool.called", "session.tool.input.started" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    handleToolEvent(payload)
                }
            }
            "session.tool.success", "session.tool.error", "session.tool.output" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    markToolDone(payload, failed = type != "session.tool.success")
                }
            }
            "session.step.started" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    _state.value = _state.value.copy(busy = true)
                }
            }
            "session.step.ended", "session.execution.completed", "session.idle" -> {
                if (str(payload["sessionID"]) == sessionId) {
                    flushStreamingIntoHistory(sessionId)
                    _state.value = _state.value.copy(busy = false)
                    loadTodos()
                }
            }
            // v1 events
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

    // ---- live streaming buffer ------------------------------------------------

    /** Streaming entries keyed by assistant message id; rendered above history. */
    private val streaming = linkedMapOf<String, StreamEntry>()

    data class StreamEntry(
        val role: String,
        var reasoning: StringBuilder,
        var text: StringBuilder,
        var toolName: String? = null,
        var toolState: ToolStateDto? = null,
    )

    private fun publishStreaming() {
        val history = _state.value.messages
        val liveEntries = streaming.map { (id, e) ->
            val body = when (e.role) {
                "reasoning" -> e.reasoning.toString()
                else -> e.text.toString()
            }
            ChatMessage(
                id = "live-$id-${e.role}",
                role = e.role,
                text = body,
                activity = listOfNotNull(e.toolState),
                toolName = e.toolName,
                streaming = true,
                time = Long.MAX_VALUE,
            )
        }
        _state.value = _state.value.copy(messages = history + liveEntries)
    }

    private fun ensureStreaming(msgId: String, role: String): StreamEntry =
        streaming.getOrPut(msgId) { StreamEntry(role = role, reasoning = StringBuilder(), text = StringBuilder()) }

    private fun ensureStreamingEntry(msgId: String?, role: String) {
        if (msgId.isNullOrBlank()) return
        ensureStreaming(msgId, role)
        publishStreaming()
    }

    private fun appendStreaming(msgId: String, role: String, text: String) {
        if (text.isEmpty()) return
        val entry = ensureStreaming(msgId, role)
        if (role == "reasoning") entry.reasoning.append(text) else entry.text.append(text)
        publishStreaming()
    }

    private fun handleToolEvent(payload: JsonObject) {
        val msgId = str(payload["assistantMessageID"]) ?: str(payload["callID"]) ?: return
        val toolName = str(payload["tool"]) ?: str(payload["name"])
        val entry = ensureStreaming(msgId, role = "assistant")
        entry.toolName = toolName ?: entry.toolName
        entry.toolState = ToolStateDto(status = "running")
        publishStreaming()
    }

    private fun markToolDone(payload: JsonObject, failed: Boolean) {
        val msgId = str(payload["assistantMessageID"]) ?: str(payload["callID"]) ?: return
        streaming[msgId]?.let { entry ->
            entry.toolName?.let {
                entry.toolState = ToolStateDto(status = if (failed) "error" else "completed")
            }
        }
        publishStreaming()
    }

    /** Replace the streamed tail with authoritative server history. */
    private suspend fun flushStreamingIntoHistory(sessionId: String) {
        streaming.clear()
        handleMessageDelta(sessionId)
    }

    private suspend fun handleMessageDelta(sessionId: String) {
        val c = client ?: return
        runCatching {
            val list = c.messages(sessionId).flatMap { it.toChatMessages() }
                .sortedWith(compareBy<ChatMessage> { it.time }.thenBy { it.id })
            _state.value = _state.value.copy(messages = list)
        }
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun MessageWithPartsDto.toChatMessages(): List<ChatMessage> {
        val created = info.time?.created ?: 0
        val streaming = info.role == "assistant" && info.time?.completed == null
        val out = mutableListOf<ChatMessage>()
        var seq = 0
        fun nextId() = "${info.id}#$seq"
        for (part in parts) {
            when (part.type) {
                "text" -> part.text?.takeIf { it.isNotBlank() }?.let {
                    out.add(
                        ChatMessage(
                            id = nextId(),
                            role = info.role,
                            text = it,
                            time = created,
                        )
                    )
                    seq++
                }
                "reasoning" -> part.text?.takeIf { it.isNotBlank() }?.let {
                    out.add(
                        ChatMessage(
                            id = nextId(),
                            role = "reasoning",
                            text = it,
                            time = created,
                        )
                    )
                    seq++
                }
                "tool" -> parseToolState(part)?.let { tool ->
                    out.add(
                        ChatMessage(
                            id = nextId(),
                            role = "tool",
                            text = "",
                            activity = listOf(tool),
                            toolName = part.tool,
                            time = created,
                        )
                    )
                    seq++
                }
            }
        }
        if (streaming && seq == 0) {
            out.add(ChatMessage(id = nextId(), role = info.role, text = "", streaming = true, time = created))
        }
        return out
    }

    private fun parseToolState(part: PartDto): ToolStateDto? {
        val obj = part.state as? JsonObject ?: return null
        return runCatching { Json.decodeFromJsonElement(ToolStateDto.serializer(), obj) }.getOrNull()
    }
}
