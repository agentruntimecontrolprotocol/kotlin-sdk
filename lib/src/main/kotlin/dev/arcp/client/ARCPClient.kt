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
import dev.arcp.messages.JobListFilter
import dev.arcp.messages.Nack
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionJobs
import dev.arcp.messages.SessionListJobs
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.SessionRejected
import dev.arcp.messages.SessionUnauthenticated
import dev.arcp.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal ARCP client (RFC §5).
 *
 * v0.1 implements the four-message handshake (RFC §8.1) over an injected
 * [Transport]. Higher-order surfaces (jobs, streams, subscriptions) are
 * added in subsequent phases.
 *
 * The client multiplexes the transport's incoming envelopes through a
 * single hot [MutableSharedFlow], so awaiting a correlated reply (for
 * example via [listJobs]) does not steal envelopes from concurrent
 * [receive] subscribers (#58). Internally, correlated waiters register
 * a [CompletableDeferred] in [pendingReplies] *before* the request is
 * sent — guaranteeing the reply is delivered to the waiter even if it
 * arrives before any subsequent collect on [receive] subscribes.
 */
public class ARCPClient(
    private val transport: Transport,
    private val auth: Auth,
    private val client: ClientInfo,
    private val capabilities: Capabilities,
) : AutoCloseable {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope =
        CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("arcp-client"))

    /**
     * Hot flow of envelopes that arrived from the transport but were not
     * routed to a correlated waiter. The small [INBOUND_REPLAY] window
     * means a subscriber that registers slightly after the producing
     * emit still observes the event — without this, the
     * `receive()`-after-`send()` pattern races against the mirror.
     */
    private val inbound: MutableSharedFlow<Envelope> = MutableSharedFlow(
        replay = INBOUND_REPLAY,
        extraBufferCapacity = INBOUND_BUFFER,
    )
    private val pendingReplies: ConcurrentHashMap<MessageId, CompletableDeferred<Envelope>> =
        ConcurrentHashMap()

    @Volatile
    private var mirroring: Boolean = false

    @Synchronized
    private fun ensureMirroring() {
        if (mirroring) return
        mirroring = true
        scope.launch {
            transport.receive().collect { env ->
                val correlated = env.correlationId?.let { pendingReplies.remove(it) }
                if (correlated != null) {
                    correlated.complete(env)
                } else {
                    inbound.emit(env)
                }
            }
        }
    }

    /**
     * Drives the four-message handshake to completion. Returns the
     * [SessionAccepted] payload on success.
     *
     * @throws ARCPException.Unauthenticated if credentials were rejected.
     * @throws ARCPException for any other handshake failure.
     */
    public suspend fun open(): SessionAccepted {
        val opener = buildOpener()
        val reply = awaitCorrelated(opener.id) { transport.send(opener) }
        return interpretHandshakeReply(reply)
    }

    private fun buildOpener(): Envelope = Envelope(
        id = MessageId.random(),
        payload =
            SessionOpen(
                auth = auth,
                client = client,
                capabilities = capabilities,
            ),
    )

    private fun interpretHandshakeReply(reply: Envelope): SessionAccepted =
        when (val payload = reply.payload) {
            is SessionAccepted -> payload
            is SessionUnauthenticated ->
                throw ARCPException.Unauthenticated(payload.message)
            is SessionRejected -> throw rejectionFor(payload)
            else -> throw ARCPException.FailedPrecondition(
                "unexpected handshake reply: ${reply.type}",
            )
        }

    private fun rejectionFor(payload: SessionRejected): ARCPException = when (payload.code) {
        ErrorCode.UNIMPLEMENTED ->
            ARCPException.Unimplemented(section = "7", detail = payload.message)
        ErrorCode.FAILED_PRECONDITION ->
            ARCPException.FailedPrecondition(payload.message)
        else ->
            ARCPException.Internal("session rejected: ${payload.message}")
    }

    /** Sends [payload] tagged for [sessionId]. */
    public suspend fun send(
        sessionId: SessionId,
        payload: dev.arcp.messages.MessageType,
    ): MessageId {
        ensureMirroring()
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

    /**
     * Returns the multiplexed inbound flow. Correlated replies (handshake,
     * [listJobs]) are NOT delivered here — they are routed to the waiter
     * that registered for them. Everything else (job events, status,
     * metrics, streams, …) lands on this flow.
     */
    public fun receive(): Flow<Envelope> {
        ensureMirroring()
        return inbound.asSharedFlow()
    }

    /** Sends `session.list_jobs` and waits for the correlated `session.jobs` reply. */
    public suspend fun listJobs(
        sessionId: SessionId,
        filter: JobListFilter = JobListFilter(),
        limit: Int = SessionListJobs.DEFAULT_LIMIT,
        cursor: String? = null,
    ): SessionJobs {
        val request = Envelope(
            id = MessageId.random(),
            sessionId = sessionId,
            payload = SessionListJobs(filter = filter, limit = limit, cursor = cursor),
        )
        val reply = awaitCorrelated(request.id) { transport.send(request) }
        return when (val payload = reply.payload) {
            is SessionJobs -> payload
            is Nack -> throw ARCPException.FailedPrecondition(payload.message)
            else -> throw ARCPException.FailedPrecondition(
                "unexpected list_jobs reply: ${reply.type}",
            )
        }
    }

    /**
     * Registers a deferred for the reply correlated to [requestId], runs
     * [send] (which actually puts the request on the wire), and awaits the
     * matching envelope. The deferred is registered *before* the send so a
     * fast runtime reply cannot race the subscription.
     */
    private suspend fun awaitCorrelated(
        requestId: MessageId,
        send: suspend () -> Unit,
    ): Envelope {
        ensureMirroring()
        val deferred = CompletableDeferred<Envelope>()
        pendingReplies[requestId] = deferred
        try {
            send()
            return deferred.await()
        } finally {
            pendingReplies.remove(requestId)
        }
    }

    override fun close() {
        scope.cancel()
        transport.close()
    }

    public companion object {
        /** Convenience: build [ClientInfo] for this SDK. */
        public fun defaultClientInfo(principal: String? = null): ClientInfo = ClientInfo(
            kind = Version.SDK_KIND,
            version = Version.SDK_VERSION,
            principal = principal,
        )

        /** Convenience: build a `bearer` [Auth] block. */
        public fun bearer(token: String): Auth = Auth(scheme = AuthScheme.BEARER, token = token)

        private const val INBOUND_BUFFER: Int = 64
        private const val INBOUND_REPLAY: Int = 16
    }
}
