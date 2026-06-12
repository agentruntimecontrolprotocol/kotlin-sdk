package dev.arcp.runtime

import dev.arcp.auth.BearerAuth
import dev.arcp.auth.JwtAuth
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.messages.RuntimeIdentity
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionLease
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.SessionRejected
import dev.arcp.messages.SessionUnauthenticated
import kotlin.time.Clock
import kotlin.time.Duration

/** Result of the session handshake: the resulting state and the reply to send. */
internal data class HandshakeOutcome(
    val session: SessionState,
    val reply: Envelope,
)

/**
 * Drives the four-message session handshake (RFC §8) and capability
 * negotiation (RFC §7), producing a [HandshakeOutcome] for the runtime to act
 * on. Extracted from the runtime facade so the connection surface stays
 * cohesive (#83).
 */
internal class SessionHandshakeHandler(
    private val supportedCapabilities: Capabilities,
    private val identity: RuntimeIdentity,
    private val sessionLeaseDuration: Duration,
    private val agentRegistry: AgentRegistry,
    private val authenticator: SessionAuthenticator,
    private val hasCredentialProvisioner: Boolean,
) {
    fun handle(opener: Envelope): HandshakeOutcome {
        val open =
            opener.payload as? SessionOpen
                ?: return rejectFirstMessage(opener)
        val negotiation = negotiate(open.capabilities, effectiveSupportedCapabilities())
        return if (negotiation.unsupported.isNotEmpty()) {
            rejectUnsupported(opener, negotiation.unsupported)
        } else {
            authenticateOrReject(opener, open, negotiation)
        }
    }

    private fun effectiveSupportedCapabilities(): Capabilities = supportedCapabilities.copy(
        provisionedCredentials =
            supportedCapabilities.provisionedCredentials && hasCredentialProvisioner,
        modelUse = supportedCapabilities.modelUse && hasCredentialProvisioner,
        agents = agentRegistry.descriptors(),
    )

    private fun authenticateOrReject(
        opener: Envelope,
        open: SessionOpen,
        negotiation: CapabilityNegotiation,
    ): HandshakeOutcome = try {
        acceptSession(opener, authenticator.authenticate(open), negotiation.negotiated)
    } catch (e: ARCPException.Unauthenticated) {
        rejectUnauthenticated(opener, e.message)
    }

    private fun rejectFirstMessage(opener: Envelope): HandshakeOutcome {
        val msg = "first message must be session.open"
        return HandshakeOutcome(
            SessionState.Closed(ErrorCode.FAILED_PRECONDITION, msg),
            Envelope(
                id = MessageId.random(),
                correlationId = opener.id,
                payload =
                    SessionRejected(
                        code = ErrorCode.FAILED_PRECONDITION,
                        message = msg,
                    ),
            ),
        )
    }

    private fun rejectUnsupported(
        opener: Envelope,
        unsupported: Collection<String>,
    ): HandshakeOutcome {
        val detail = "unsupported capabilities: ${unsupported.joinToString()}"
        return HandshakeOutcome(
            SessionState.Closed(ErrorCode.UNIMPLEMENTED, "unsupported capabilities"),
            Envelope(
                id = MessageId.random(),
                correlationId = opener.id,
                payload =
                    SessionRejected(
                        code = ErrorCode.UNIMPLEMENTED,
                        message = detail,
                    ),
            ),
        )
    }

    private fun rejectUnauthenticated(
        opener: Envelope,
        reason: String?,
    ): HandshakeOutcome {
        val msg = reason ?: "unauthenticated"
        return HandshakeOutcome(
            SessionState.Closed(ErrorCode.UNAUTHENTICATED, msg),
            Envelope(
                id = MessageId.random(),
                correlationId = opener.id,
                payload = SessionUnauthenticated(message = msg),
            ),
        )
    }

    private fun acceptSession(
        opener: Envelope,
        principal: String,
        negotiated: Capabilities,
    ): HandshakeOutcome {
        val sessionId = SessionId.random()
        val accepted =
            SessionState.Authenticated(
                sessionId = sessionId,
                principal = principal,
                capabilities = negotiated,
                acceptedAt = Clock.System.now(),
            )
        return HandshakeOutcome(accepted, acceptReply(opener, sessionId, negotiated))
    }

    private fun acceptReply(
        opener: Envelope,
        sessionId: SessionId,
        negotiated: Capabilities,
    ): Envelope = Envelope(
        id = MessageId.random(),
        sessionId = sessionId,
        correlationId = opener.id,
        payload =
            SessionAccepted(
                sessionId = sessionId,
                runtime = identity,
                capabilities = negotiated,
                lease = SessionLease(expiresAt = Clock.System.now().plus(sessionLeaseDuration)),
            ),
    )
}

/**
 * Verifies `session.open` credentials and resolves the authenticated
 * principal (RFC §8.2). Extracted from the handshake handler so the auth
 * scheme logic is independently testable.
 */
internal class SessionAuthenticator(
    private val bearerAuth: BearerAuth,
    private val jwtAuth: JwtAuth?,
    private val anonymousAllowed: Boolean,
) {
    fun authenticate(open: SessionOpen): String = when (open.auth.scheme) {
        AuthScheme.BEARER -> {
            val token =
                open.auth.token
                    ?: throw ARCPException.Unauthenticated("bearer scheme requires token")
            bearerAuth.verify(token)
        }
        AuthScheme.SIGNED_JWT -> {
            val token =
                open.auth.token
                    ?: throw ARCPException.Unauthenticated("signed_jwt scheme requires token")
            val auth =
                jwtAuth ?: throw ARCPException.Unauthenticated(
                    "runtime not configured for signed_jwt",
                )
            auth.verify(token)
        }
        AuthScheme.NONE -> {
            if (!anonymousAllowed) {
                throw ARCPException.Unauthenticated("anonymous (none) auth not negotiated")
            }
            "anonymous"
        }
        AuthScheme.MTLS, AuthScheme.OAUTH2 ->
            throw ARCPException.Unauthenticated(
                "auth scheme ${open.auth.scheme.name.lowercase()} is deferred to v0.2",
            )
    }
}
