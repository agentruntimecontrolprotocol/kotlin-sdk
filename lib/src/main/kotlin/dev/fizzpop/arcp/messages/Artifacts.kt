package dev.fizzpop.arcp.messages

import dev.fizzpop.arcp.ids.ArtifactId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical artifact pointer (RFC §16.1). Embedded inside payloads where a
 * large value would otherwise be inlined.
 */
@Serializable
public data class ArtifactRefSpec(
    @SerialName("artifact_id")
    val artifactId: ArtifactId,
    val uri: String,
    @SerialName("media_type")
    val mediaType: String,
    val size: Long,
    val sha256: String? = null,
    @SerialName("expires_at")
    val expiresAt: Instant? = null,
)

/**
 * `artifact.put` — upload an artifact (RFC §16.2).
 *
 * v0.1 only supports inline base64; sidecar binary frames are deferred. The
 * `data` field is base64-encoded bytes; the runtime decodes server-side.
 */
@Serializable
@SerialName("artifact.put")
public data class ArtifactPut(
    @SerialName("artifact_id")
    val artifactId: ArtifactId,
    @SerialName("media_type")
    val mediaType: String,
    val size: Long,
    val sha256: String? = null,
    @SerialName("expires_at")
    val expiresAt: Instant? = null,
    val data: String,
) : MessageType

/** `artifact.fetch` — request an artifact by id (RFC §16.2). */
@Serializable
@SerialName("artifact.fetch")
public data class ArtifactFetch(
    @SerialName("artifact_id")
    val artifactId: ArtifactId,
) : MessageType

/** `artifact.ref` — response carrying the artifact pointer/contents (RFC §16). */
@Serializable
@SerialName("artifact.ref")
public data class ArtifactRefMessage(
    val ref: ArtifactRefSpec,
    val data: String? = null,
) : MessageType

/** `artifact.release` — caller no longer needs the artifact (RFC §16.2). */
@Serializable
@SerialName("artifact.release")
public data class ArtifactRelease(
    @SerialName("artifact_id")
    val artifactId: ArtifactId,
) : MessageType
