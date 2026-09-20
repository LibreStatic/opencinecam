/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import java.net.URI
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.*

/** Endpoint addresses stay in local settings, never in enrollment/outbox/presets. No credentials. */
data class WebDavQueueEndpoint(val id: String, val url: String, val ignoreTlsErrors: Boolean = false) {
    init {
        requirePublicationUuid(id)
        require(normalizeQueueEndpoint(url) == url)
        require(!ignoreTlsErrors || WebDavLanTls.isLocalAddress(URI(url)))
    }
}

class WebDavQueuePreferences(
    val revision: Long = 0,
    val enabled: Boolean = false,
    val allowCellular: Boolean = false,
    val activeEndpointId: String? = null,
    endpoints: List<WebDavQueueEndpoint> = emptyList(),
) {
    val endpoints: List<WebDavQueueEndpoint> = Collections.unmodifiableList(endpoints.sortedBy { it.id })
    val activeEndpoint: WebDavQueueEndpoint? get() = endpoints.singleOrNull { it.id == activeEndpointId }
    init {
        require(revision >= 0 && this.endpoints.size <= 32)
        require(this.endpoints.map { it.id }.toSet().size == this.endpoints.size)
        require(this.endpoints.map { it.url }.toSet().size == this.endpoints.size)
        require(activeEndpointId == null || activeEndpoint != null)
        require(!enabled || activeEndpoint != null)
    }
    fun updated(
        url: String,
        enabled: Boolean,
        allowCellular: Boolean,
        ignoreTlsErrors: Boolean? = null,
    ): WebDavQueuePreferences {
        val address = url.trim().takeIf { it.isNotEmpty() }?.let(::normalizeQueueEndpoint)
        require(!enabled || address != null)
        require(ignoreTlsErrors != true || address != null)
        // A policy belongs to its immutable URL/id binding, not the currently selected draft.
        val endpoint = address?.let { value ->
            val existing = endpoints.singleOrNull { it.url == value }
                ?: WebDavQueueEndpoint(UUID.randomUUID().toString(), value)
            if (ignoreTlsErrors == null || existing.ignoreTlsErrors == ignoreTlsErrors) existing
            else existing.copy(ignoreTlsErrors = ignoreTlsErrors)
        }
        if (activeEndpoint == endpoint && this.enabled == enabled && this.allowCellular == allowCellular) return this
        val all = when {
            endpoint == null -> endpoints
            endpoints.any { it.id == endpoint.id } -> endpoints.map { if (it.id == endpoint.id) endpoint else it }
            else -> endpoints + endpoint
        }
        return WebDavQueuePreferences(Math.addExact(revision, 1), enabled, allowCellular, endpoint?.id, all)
    }
    override fun equals(other: Any?): Boolean = other is WebDavQueuePreferences && revision == other.revision && enabled == other.enabled &&
        allowCellular == other.allowCellular && activeEndpointId == other.activeEndpointId && endpoints == other.endpoints
    override fun hashCode(): Int = listOf(revision, enabled, allowCellular, activeEndpointId, endpoints).hashCode()
}

internal fun normalizeQueueEndpoint(value: String): String {
    require(value.length in 1..2048 && value.none { it.code < 32 || it.code == 127 })
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    require(uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
    return uri.normalize().toASCIIString().also { require(it.length <= 2048) }
}

internal object WebDavQueuePreferencesCodec {
    const val MAX_BYTES = 80_000
    fun encode(value: WebDavQueuePreferences): ByteArray = encodeVersion(value, 2)
    private fun encodeVersion(value: WebDavQueuePreferences, version: Int): ByteArray = buildJsonObject {
        put("version", version); put("revision", value.revision); put("enabled", value.enabled); put("allowCellular", value.allowCellular)
        put("activeEndpointId", value.activeEndpointId?.let(::JsonPrimitive) ?: JsonNull)
        put("endpoints", buildJsonArray {
            value.endpoints.forEach { endpoint -> add(buildJsonObject {
                put("id", endpoint.id); put("url", endpoint.url)
                if (version == 2) put("ignoreTlsErrors", endpoint.ignoreTlsErrors)
            }) }
        })
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): WebDavQueuePreferences {
        require(bytes.size <= MAX_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 3) }; '}', ']' -> { depth--; require(depth >= 0) } }
        }
        require(depth == 0 && !quoted)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("version", "revision", "enabled", "allowCellular", "activeEndpointId", "endpoints"))
        fun string(value: JsonElement) = value.jsonPrimitive.also { require(it.isString) }.content
        fun bool(name: String) = root.getValue(name).jsonPrimitive.also { require(!it.isString) }.boolean
        val version = root.getValue("version").jsonPrimitive; require(!version.isString && version.content in setOf("1", "2"))
        val revision = root.getValue("revision").jsonPrimitive; require(!revision.isString)
        val values = root.getValue("endpoints").jsonArray; require(values.size <= 32)
        val decoded = WebDavQueuePreferences(revision.long, bool("enabled"), bool("allowCellular"),
            root.getValue("activeEndpointId").let { if (it == JsonNull) null else string(it) },
            values.map { node ->
                val item = node.jsonObject
                require(item.keys == if (version.content == "1") setOf("id", "url") else setOf("id", "url", "ignoreTlsErrors"))
                val ignoreTlsErrors = if (version.content == "1") false else item.getValue("ignoreTlsErrors")
                    .jsonPrimitive.also { require(!it.isString) }.boolean
                WebDavQueueEndpoint(string(item.getValue("id")), string(item.getValue("url")), ignoreTlsErrors)
            })
        // Validate the original schema before migrating: v1 stays canonical, never lenient.
        require(bytes.contentEquals(encodeVersion(decoded, version.int)))
        return decoded
    }
}
