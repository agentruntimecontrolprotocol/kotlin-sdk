package dev.arcp.messages

import dev.arcp.error.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Stream kind (RFC §11.1). Unknown kinds are decoded as [EVENT]. */
@Serializable
public enum class StreamKind {
    @SerialName("text")
    TEXT,

    @SerialName("binary")
    BINARY,

    @SerialName("event")
    EVENT,

    @SerialName("log")
    LOG,

    @SerialName("metric")
    METRIC,

    @SerialName("thought")
    THOUGHT,
}

/** `stream.open` (RFC §11.1). */
@Serializable
@SerialName("stream.open")
public data class StreamOpen(
    val kind: StreamKind,
    @SerialName("content_type")
    val contentType: String? = null,
    val encoding: String? = null,
) : MessageType

/**
 * `stream.chunk` — one piece of stream data (RFC §11).
 *
 * For `kind: binary` we use base64 in-envelope (`data` is the base64 payload)
 * per RFC §11.3 — sidecar binary frames are deferred to v0.2. For
 * `kind: thought` chunks SHOULD include `role`, `content`, and `redacted`
 * (RFC §11.4). For job result streaming use [JobResultChunk] (RFC v1.1 §8.4).
 */
@Serializable
@SerialName("stream.chunk")
public data class StreamChunk(
    val sequence: Long,
    val data: String? = null,
    val content: String? = null,
    val role: String? = null,
    val redacted: Boolean? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val sha256: String? = null,
    val event: JsonElement? = null,
) : MessageType

/** `stream.close` (RFC §11). */
@Serializable
@SerialName("stream.close")
public data class StreamClose(
    @SerialName("total_chunks")
    val totalChunks: Long? = null,
) : MessageType

/** `stream.error` (RFC §11). */
@Serializable
@SerialName("stream.error")
public data class StreamError(
    val code: ErrorCode,
    val message: String,
) : MessageType
