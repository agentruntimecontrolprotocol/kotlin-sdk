package dev.arcp.runtime

import dev.arcp.ids.ArtifactId
import dev.arcp.ids.SessionId
import kotlinx.datetime.Instant

/**
 * Parameter object for [ArtifactStore.put].
 *
 * Bundles the artifact identity, body, and retention hint so callers do
 * not exceed the SDK's five-parameter cap and so additional optional
 * fields can be introduced as the protocol evolves without breaking the
 * call site.
 */
public data class ArtifactPutRequest(
    /** Session that owns the artifact; `null` denotes a runtime-scoped blob. */
    val sessionId: SessionId?,
    /** Stable identifier for the artifact. */
    val artifactId: ArtifactId,
    /** RFC §16 media type. */
    val mediaType: String,
    /** Raw artifact bytes. */
    val data: ByteArray,
    /** Optional retention override; clamped by [ArtifactStore.maxRetention]. */
    val expiresAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtifactPutRequest) return false
        return sessionId == other.sessionId &&
            artifactId == other.artifactId &&
            mediaType == other.mediaType &&
            expiresAt == other.expiresAt &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sessionId?.hashCode() ?: 0
        result = 31 * result + artifactId.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Parameter object for [ArtifactStore.putBase64]; identical to
 * [ArtifactPutRequest] except the body is a base64-encoded string.
 */
public data class ArtifactPutBase64Request(
    /** Session that owns the artifact; `null` denotes a runtime-scoped blob. */
    val sessionId: SessionId?,
    /** Stable identifier for the artifact. */
    val artifactId: ArtifactId,
    /** RFC §16 media type. */
    val mediaType: String,
    /** Base64-encoded artifact bytes (RFC §16.2 inline wire encoding). */
    val base64Body: String,
    /** Optional retention override; clamped by [ArtifactStore.maxRetention]. */
    val expiresAt: Instant? = null,
)
