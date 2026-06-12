package dev.arcp.messages

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
 * Absent boolean fields default to `false` per RFC §7. The single
 * deliberate exception is [interrupt], which defaults to `true` for
 * pause-and-ask ergonomics — a peer that intends to *opt out* must
 * advertise `interrupt = false` explicitly. See RFC §10.5 for the
 * interrupt semantics this aligns with.
 *
 * The `extensions` list advertises the vendor namespaces accepted on
 * this session (RFC §21.2). Unknown extensions on the wire are
 * silently dropped from the negotiated result and do not reject a
 * session — unsupported *required* features still do.
 */
@Serializable
public data class Capabilities(
    /** Streaming via `stream.*` / `result_chunk`. */
    val streaming: Boolean = false,
    /** Jobs persist across transport disconnects (RFC §10). */
    @SerialName("durable_jobs")
    val durableJobs: Boolean = false,
    /** `job.checkpoint` / resume from checkpoint (RFC §10). */
    val checkpoints: Boolean = false,
    /** Sidecar binary frames (RFC §11.3). v0.1 supports inline base64 only. */
    @SerialName("binary_streams")
    val binaryStreams: Boolean = false,
    /** `agent.handoff` between runtimes (RFC §14). */
    @SerialName("agent_handoff")
    val agentHandoff: Boolean = false,
    /** Inline artifact references (RFC §16). */
    val artifacts: Boolean = false,
    /** Read-only subscriptions to live jobs (RFC §13). */
    val subscriptions: Boolean = false,
    /** `job.schedule` for deferred or recurring work (RFC §10.6). */
    @SerialName("scheduled_jobs")
    val scheduledJobs: Boolean = false,
    /** Per-job credential issue/revoke (RFC §9.8). */
    @SerialName("provisioned_credentials")
    val provisionedCredentials: Boolean = false,
    /** `model.use` lease enforcement (RFC §9.7). */
    @SerialName("model.use")
    val modelUse: Boolean = false,
    /** Anonymous (`scheme = none`) sessions accepted. */
    val anonymous: Boolean = false,
    /**
     * Cooperative interrupt support (RFC §10.5). Deliberately defaults
     * to `true` — see the class KDoc for the rationale. A peer that
     * wants to opt out must advertise `interrupt = false` explicitly.
     */
    val interrupt: Boolean = true,
    /** Heartbeat cadence in seconds (RFC §10.3). */
    @SerialName("heartbeat_interval_seconds")
    val heartbeatIntervalSeconds: Int = DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
    /** Heartbeat-recovery policy (RFC §10.3). */
    @SerialName("heartbeat_recovery")
    val heartbeatRecovery: HeartbeatRecovery = HeartbeatRecovery.FAIL,
    /** Supported `binary` stream encodings (RFC §11.3). */
    @SerialName("binary_encoding")
    val binaryEncoding: List<String> = listOf("base64"),
    /** Vendor extensions advertised (`arcpx.*`, RFC §21). */
    val extensions: List<String> = emptyList(),
    /** Versioned agents this runtime exposes (RFC §7.5). */
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
    /** Authentication scheme used by [token]. */
    val scheme: AuthScheme,
    /** Opaque token (bearer string, JWT, etc.). Required for non-`none` schemes. */
    val token: String? = null,
    /** Optional client/runtime fingerprint, e.g. mTLS thumbprint. */
    val fingerprint: String? = null,
)

/** Client identity attestation (RFC §8.2). */
@Serializable
public data class ClientInfo(
    /** Client SDK kind, e.g. `arcp-kotlin`. */
    val kind: String,
    /** Client SDK version. */
    val version: String,
    /** Optional transport-level fingerprint (mTLS, etc.). */
    val fingerprint: String? = null,
    /** Optional principal hint; the runtime ultimately decides identity. */
    val principal: String? = null,
)

/** Runtime identity returned in `session.accepted` (RFC §8.3). */
@Serializable
public data class RuntimeIdentity(
    /** Runtime kind, e.g. `arcp-kotlin-runtime`. */
    val kind: String,
    /** Runtime SDK version. */
    val version: String,
    /** Optional transport-level fingerprint, e.g. mTLS leaf SHA-256. */
    val fingerprint: String? = null,
    /** Trust classification advertised to the client (RFC §15.3). */
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
