package dev.arcp.messages

import dev.arcp.credentials.Credential
import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.TraceId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Heartbeat-recovery policy advertised in [Capabilities] (RFC §10.3).
 */
@Serializable
public enum class HeartbeatRecovery {
    @SerialName("fail")
    FAIL,

    @SerialName("block")
    BLOCK,
}

/**
 * Negotiated capability set (RFC §7).
 *
 * Absent boolean fields default to `false` per §7. The `extensions` list
 * advertises the namespaces accepted on this session (RFC §21.2).
 */
@Serializable
public data class Capabilities(
    val streaming: Boolean = false,
    @SerialName("durable_jobs")
    val durableJobs: Boolean = false,
    val checkpoints: Boolean = false,
    @SerialName("binary_streams")
    val binaryStreams: Boolean = false,
    @SerialName("agent_handoff")
    val agentHandoff: Boolean = false,
    @SerialName("human_input")
    val humanInput: Boolean = false,
    val artifacts: Boolean = false,
    val subscriptions: Boolean = false,
    @SerialName("scheduled_jobs")
    val scheduledJobs: Boolean = false,
    @SerialName("provisioned_credentials")
    val provisionedCredentials: Boolean = false,
    @SerialName("model.use")
    val modelUse: Boolean = false,
    val anonymous: Boolean = false,
    val interrupt: Boolean = true,
    @SerialName("heartbeat_interval_seconds")
    val heartbeatIntervalSeconds: Int = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
    @SerialName("heartbeat_recovery")
    val heartbeatRecovery: HeartbeatRecovery = HeartbeatRecovery.FAIL,
    @SerialName("binary_encoding")
    val binaryEncoding: List<String> = listOf("base64"),
    val extensions: List<String> = emptyList(),
    val agents: List<AgentDescriptor> = emptyList(),
) {
    public companion object {
        /** Default heartbeat interval per RFC §10.3 (30 seconds). */
        public const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS: Int = 30
    }
}

/** Authentication scheme (RFC §8.2). */
@Serializable
public enum class AuthScheme {
    @SerialName("bearer")
    BEARER,

    @SerialName("mtls")
    MTLS,

    @SerialName("oauth2")
    OAUTH2,

    @SerialName("signed_jwt")
    SIGNED_JWT,

    @SerialName("none")
    NONE,
}

/** Credentials block on `session.open` (RFC §8.2). */
@Serializable
public data class Auth(
    val scheme: AuthScheme,
    val token: String? = null,
    val fingerprint: String? = null,
)

/** Client identity attestation (RFC §8.2). */
@Serializable
public data class ClientInfo(
    val kind: String,
    val version: String,
    val fingerprint: String? = null,
    val principal: String? = null,
)

/** Runtime identity returned in `session.accepted` (RFC §8.3). */
@Serializable
public data class RuntimeIdentity(
    val kind: String,
    val version: String,
    val fingerprint: String? = null,
    @SerialName("trust_level")
    val trustLevel: TrustLevel = TrustLevel.UNTRUSTED,
)

/** Trust classification (RFC §15.3). */
@Serializable
public enum class TrustLevel {
    @SerialName("untrusted")
    UNTRUSTED,

    @SerialName("constrained")
    CONSTRAINED,

    @SerialName("trusted")
    TRUSTED,

    @SerialName("privileged")
    PRIVILEGED,
}

/** Lease window declared on session acceptance (RFC §8.3). */
@Serializable
public data class SessionLease(
    @SerialName("expires_at")
    val expiresAt: Instant,
)

/** `session.open` (RFC §8.1 step 1). */
@Serializable
@SerialName("session.open")
public data class SessionOpen(
    val auth: Auth,
    val client: ClientInfo,
    val capabilities: Capabilities,
) : MessageType

/**
 * `session.challenge` — runtime requires further proof (RFC §8.1 step 2).
 *
 * The challenge nonce semantics are scheme-specific; for `signed_jwt` a `nonce`
 * is signed and returned in the corresponding [SessionAuthenticate].
 */
@Serializable
@SerialName("session.challenge")
public data class SessionChallenge(
    val scheme: AuthScheme,
    val nonce: String,
    @SerialName("expires_at")
    val expiresAt: Instant? = null,
) : MessageType

/** `session.authenticate` — client's response to a challenge (RFC §8.1 step 3). */
@Serializable
@SerialName("session.authenticate")
public data class SessionAuthenticate(
    val token: String,
    val fingerprint: String? = null,
) : MessageType

/** `session.accepted` — runtime concluded the handshake (RFC §8.1 step 4). */
@Serializable
@SerialName("session.accepted")
public data class SessionAccepted(
    @SerialName("session_id")
    val sessionId: SessionId,
    val runtime: RuntimeIdentity,
    val capabilities: Capabilities,
    val lease: SessionLease? = null,
) : MessageType

/** `session.unauthenticated` — credentials missing/invalid (RFC §18.2 / §8). */
@Serializable
@SerialName("session.unauthenticated")
public data class SessionUnauthenticated(
    val message: String,
) : MessageType

/** `session.rejected` — handshake refused for a non-auth reason (RFC §8.1). */
@Serializable
@SerialName("session.rejected")
public data class SessionRejected(
    val code: ErrorCode,
    val message: String,
) : MessageType

/** `session.refresh` — runtime requires re-authentication (RFC §8.4). */
@Serializable
@SerialName("session.refresh")
public data class SessionRefresh(
    val scheme: AuthScheme,
    val nonce: String,
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `session.evicted` — runtime closed the session (RFC §8.5). */
@Serializable
@SerialName("session.evicted")
public data class SessionEvicted(
    val code: ErrorCode,
    val reason: String,
) : MessageType

/** `session.close` — graceful close (RFC §9). */
@Serializable
@SerialName("session.close")
public data class SessionClose(
    val reason: String? = null,
) : MessageType

/** Read-only filters for `session.list_jobs` (RFC v1.1 §6.6). */
@Serializable
public data class JobListFilter(
    val status: List<String> = emptyList(),
    val agent: String? = null,
    @SerialName("created_after")
    val createdAfter: Instant? = null,
    @SerialName("created_before")
    val createdBefore: Instant? = null,
)

/** `session.list_jobs` — request a paginated job inventory slice. */
@Serializable
@SerialName("session.list_jobs")
public data class SessionListJobs(
    val filter: JobListFilter = JobListFilter(),
    val limit: Int = DEFAULT_LIMIT,
    val cursor: String? = null,
) : MessageType {
    public companion object {
        public const val DEFAULT_LIMIT: Int = 100
    }
}

/** Lease summary returned from `session.jobs`. */
@Serializable
public data class JobListLease(
    @SerialName("expires_at")
    val expiresAt: Instant? = null,
    val capabilities: Map<String, List<String>> = emptyMap(),
)

/** Job entry returned from `session.jobs`. */
@Serializable
public data class JobListEntry(
    @SerialName("job_id")
    val jobId: JobId,
    val agent: String,
    val status: String,
    val lease: JobListLease? = null,
    @SerialName("parent_job_id")
    val parentJobId: JobId? = null,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("trace_id")
    val traceId: TraceId? = null,
    @SerialName("last_event_seq")
    val lastEventSeq: Long? = null,
    val credentials: List<Credential>? = null,
)

/** `session.jobs` — response to [SessionListJobs]. */
@Serializable
@SerialName("session.jobs")
public data class SessionJobs(
    @SerialName("request_id")
    val requestId: MessageId,
    val jobs: List<JobListEntry>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
) : MessageType
