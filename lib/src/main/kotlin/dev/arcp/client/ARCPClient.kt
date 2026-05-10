package dev.arcp.client

import dev.arcp.Version
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.messages.Auth
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.messages.ClientInfo
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.SessionRejected
import dev.arcp.messages.SessionUnauthenticated
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Minimal ARCP client (RFC §5).
 *
 * v0.1 implements the four-message handshake (RFC §8.1) over an injected
 * [Transport]. Higher-order surfaces (jobs, streams, subscriptions) are added
 * in subsequent phases.
 */
public class ARCPClient(
    private val transport: Transport,
    private val auth: Auth,
    private val client: ClientInfo,
    private val capabilities: Capabilities,
) : AutoCloseable {
    /**
     * Drives the four-message handshake to completion. Returns the
     * [SessionAccepted] payload on success.
     *
     * @throws ARCPException.Unauthenticated if credentials were rejected.
     * @throws ARCPException for any other handshake failure.
     */
    public suspend fun open(): SessionAccepted {
        val opener =
            Envelope(
                id = MessageId.random(),
                payload =
                    SessionOpen(
                        auth = auth,
                        client = client,
                        capabilities = capabilities,
                    ),
            )
        transport.send(opener)
        val reply = transport.receive().first()
        return when (val payload = reply.payload) {
            is SessionAccepted -> payload
            is SessionUnauthenticated ->
                throw ARCPException.Unauthenticated(payload.message)
            is SessionRejected ->
                when (payload.code) {
                    ErrorCode.UNIMPLEMENTED ->
                        throw ARCPException.Unimplemented(
                            section = "7",
                            detail = payload.message,
                        )
                    ErrorCode.FAILED_PRECONDITION ->
                        throw ARCPException.FailedPrecondition(payload.message)
                    else -> throw ARCPException.Internal("session rejected: ${payload.message}")
                }
            else ->
                throw ARCPException.FailedPrecondition(
                    "unexpected handshake reply: ${reply.type}",
                )
        }
    }

    /** Sends [payload] tagged for [sessionId]. */
    public suspend fun send(
        sessionId: SessionId,
        payload: dev.arcp.messages.MessageType,
    ): MessageId {
        val id = MessageId.random()
        transport.send(
            Envelope(
                id = id,
                sessionId = sessionId,
                payload = payload,
            ),
        )
        return id
    }

    /** Returns the underlying transport's incoming-envelope flow. */
    public fun receive(): Flow<Envelope> = transport.receive()

    override fun close() {
        transport.close()
    }

    public companion object {
        /** Convenience: build [ClientInfo] for this SDK. */
        public fun defaultClientInfo(principal: String? = null): ClientInfo =
            ClientInfo(
                kind = Version.SDK_KIND,
                version = Version.SDK_VERSION,
                principal = principal,
            )

        /** Convenience: build a `bearer` [Auth] block. */
        public fun bearer(token: String): Auth = Auth(scheme = AuthScheme.BEARER, token = token)
    }
}
