package dev.arcp.runtime

import dev.arcp.Version
import dev.arcp.auth.BearerAuth
import dev.arcp.auth.JwtAuth
import dev.arcp.auth.StaticBearerAuth
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.messages.Nack
import dev.arcp.messages.Ping
import dev.arcp.messages.Pong
import dev.arcp.messages.RuntimeIdentity
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionClose
import dev.arcp.messages.SessionEvicted
import dev.arcp.messages.SessionLease
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.SessionRejected
import dev.arcp.messages.SessionUnauthenticated
import dev.arcp.messages.TrustLevel
import dev.arcp.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val log = KotlinLogging.logger {}

/**
 * Authoritative ARCP runtime (server side, RFC §5).
 *
 * Construct one runtime per process and call [accept] for each transport
 * connection — the runtime drives the session handshake, dispatches incoming
 * envelopes, and emits structured events. v0.1 implements the handshake fully
 * (RFC §8) plus capability negotiation (RFC §7); deeper protocol surfaces
 * (jobs, streams, subscriptions, etc.) are layered on top in later phases.
 */
public class ARCPRuntime(
    private val supportedCapabilities: Capabilities,
    private val identity: RuntimeIdentity =
        RuntimeIdentity(
            kind = Version.SDK_KIND,
            version = Version.SDK_VERSION,
            trustLevel = TrustLevel.TRUSTED,
        ),
    private val bearerAuth: BearerAuth = StaticBearerAuth(emptyMap()),
    private val jwtAuth: JwtAuth? = null,
    private val sessionLeaseDuration: Duration = DEFAULT_SESSION_LEASE,
) : AutoCloseable {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope =
        CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("arcp-runtime"))

    /**
     * Accepts a single [transport] connection: drives the handshake to
     * completion, then suspends listening for further envelopes until the
     * session closes.
     *
     * Returns the launched [Job] so callers may await or cancel.
     */
    public fun accept(transport: Transport): Job = scope.launch {
        val opener =
            try {
                transport.receive().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "transport closed before session.open" }
                return@launch
            }

        val outcome = handleHandshake(opener)
        transport.send(outcome.reply)

        if (outcome.session is SessionState.Authenticated) {
            runDispatchLoop(transport)
        } else {
            transport.close()
        }
    }

    private suspend fun runDispatchLoop(transport: Transport) {
        try {
            transport.receive().collect { env ->
                handleEnvelope(env, transport)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info(e) { "session ended" }
        }
    }

    private suspend fun handleEnvelope(
        env: Envelope,
        transport: Transport,
    ) {
        when (val payload = env.payload) {
            is Ping ->
                transport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = env.sessionId,
                        correlationId = env.id,
                        payload = Pong(nonce = payload.nonce),
                    ),
                )
            is SessionClose -> transport.close()
            else ->
                transport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = env.sessionId,
                        correlationId = env.id,
                        payload =
                            Nack(
                                nackFor = env.id,
                                code = ErrorCode.UNIMPLEMENTED,
                                message = "message type ${env.type} not implemented in v0.1",
                            ),
                    ),
                )
        }
    }

    private fun handleHandshake(opener: Envelope): HandshakeOutcome {
        val open =
            opener.payload as? SessionOpen
                ?: return rejectFirstMessage(opener)
        val negotiation = negotiate(open.capabilities, supportedCapabilities)
        return if (negotiation.unsupported.isNotEmpty()) {
            rejectUnsupported(opener, negotiation.unsupported)
        } else {
            authenticateOrReject(opener, open, negotiation)
        }
    }

    private fun authenticateOrReject(
        opener: Envelope,
        open: SessionOpen,
        negotiation: CapabilityNegotiation,
    ): HandshakeOutcome = try {
        acceptSession(opener, authenticate(open), negotiation.negotiated)
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
        val reply =
            Envelope(
                id = MessageId.random(),
                sessionId = sessionId,
                correlationId = opener.id,
                payload =
                    SessionAccepted(
                        sessionId = sessionId,
                        runtime = identity,
                        capabilities = negotiated,
                        lease =
                            SessionLease(
                                expiresAt = Clock.System.now().plus(sessionLeaseDuration),
                            ),
                    ),
            )
        return HandshakeOutcome(accepted, reply)
    }

    private fun authenticate(open: SessionOpen): String = when (open.auth.scheme) {
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
            if (!supportedCapabilities.anonymous) {
                throw ARCPException.Unauthenticated("anonymous (none) auth not negotiated")
            }
            "anonymous"
        }
        AuthScheme.MTLS, AuthScheme.OAUTH2 ->
            throw ARCPException.Unauthenticated(
                "auth scheme ${open.auth.scheme.name.lowercase()} is deferred to v0.2",
            )
    }

    /** Emits a [SessionEvicted] event then closes [transport]. */
    public suspend fun evict(
        transport: Transport,
        sessionId: SessionId,
        reason: String,
    ) {
        transport.send(
            Envelope(
                id = MessageId.random(),
                sessionId = sessionId,
                payload = SessionEvicted(code = ErrorCode.CANCELLED, reason = reason),
            ),
        )
        transport.close()
    }

    override fun close() {
        scope.cancel()
    }

    private data class HandshakeOutcome(
        val session: SessionState,
        val reply: Envelope,
    )

    public companion object {
        /** Default session lease window (1 hour). */
        public val DEFAULT_SESSION_LEASE: Duration = 1.hours
    }
}
