package dev.arcp.runtime

import dev.arcp.error.ErrorCode
import dev.arcp.ids.SessionId
import dev.arcp.messages.Capabilities
import kotlin.time.Instant

/**
 * Authoritative session state machine (RFC §8 / §9).
 *
 * Modeled as a sealed hierarchy so dispatch over a session's current state
 * is exhaustive at compile time. The transitions follow the four-message
 * handshake (RFC §8.1).
 */
public sealed class SessionState {
    /** No `session.open` has been received. */
    public data object Unauthenticated : SessionState()

    /** `session.open` received; awaiting auth/challenge resolution. */
    public data class Authenticating(
        val proposedCapabilities: Capabilities,
        val proposedClientKind: String,
    ) : SessionState()

    /** Handshake completed; session can carry any messages. */
    public data class Authenticated(
        val sessionId: SessionId,
        val principal: String,
        val capabilities: Capabilities,
        val acceptedAt: Instant,
    ) : SessionState()

    /** Closed via `session.close`, `session.evicted`, or `session.rejected`. */
    public data class Closed(
        val code: ErrorCode,
        val reason: String,
    ) : SessionState()
}
