package com.orbit.mobile.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2's /api/provider returns providers with EMPTY model maps; the real catalog
 * lives at /api/model as a flat list of {id, modelID, providerID, name}.
 */
object ProviderCatalog {

    private fun primitive(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    fun fromV2ModelList(models: List<JsonObject>): List<ProviderGroup> {
        val groups = linkedMapOf<String, MutableList<ModelOption>>()
        for (m in models) {
            val pid = primitive(m, "providerID") ?: continue
            val mid = primitive(m, "modelID") ?: primitive(m, "id") ?: continue
            val name = primitive(m, "name") ?: mid
            groups.getOrPut(pid) { mutableListOf() }
                .add(ModelOption(providerId = pid, modelName = name, modelId = mid))
        }
        return groups.map { (pid, models) ->
            ProviderGroup(
                id = pid,
                name = pid,
                connected = true,
                models = models.sortedBy { it.modelName.lowercase() },
            )
        }.sortedBy { it.name.lowercase() }
    }
}
