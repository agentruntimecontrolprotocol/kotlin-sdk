@file:Suppress("LargeClass", "LongMethod", "LongParameterList")

package dev.arcp.runtime

import dev.arcp.Version
import dev.arcp.auth.BearerAuth
import dev.arcp.auth.JwtAuth
import dev.arcp.auth.StaticBearerAuth
import dev.arcp.credentials.Credential
import dev.arcp.credentials.CredentialId
import dev.arcp.credentials.CredentialProvisioner
import dev.arcp.credentials.CredentialStore
import dev.arcp.credentials.InMemoryCredentialStore
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.BudgetCounter
import dev.arcp.lease.BudgetRegistry
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Cancel
import dev.arcp.messages.CancelAccepted
import dev.arcp.messages.CancelTarget
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobCancelled
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobFailed
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobListLease
import dev.arcp.messages.JobStatusEvent
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Metric
import dev.arcp.messages.Nack
import dev.arcp.messages.Ping
import dev.arcp.messages.Pong
import dev.arcp.messages.RuntimeIdentity
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionClose
import dev.arcp.messages.SessionEvicted
import dev.arcp.messages.SessionLease
import dev.arcp.messages.SessionListJobs
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.SessionRejected
import dev.arcp.messages.SessionUnauthenticated
import dev.arcp.messages.StandardMetrics
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
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
    private val agentRegistry: AgentRegistry = AgentRegistry(),
    private val jobInventory: JobInventory = InMemoryJobInventory(),
    private val budgets: BudgetRegistry = BudgetRegistry(),
    private val credentialProvisioner: CredentialProvisioner? = null,
    private val credentialStore: CredentialStore = InMemoryCredentialStore(),
) : AutoCloseable {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope =
        CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("arcp-runtime"))
    private val sessions: ConcurrentHashMap<SessionId, SessionState.Authenticated> =
        ConcurrentHashMap()
    private val jobs: ConcurrentHashMap<JobId, RuntimeJob> = ConcurrentHashMap()

    init {
        credentialProvisioner?.let { provisioner ->
            scope.launch {
                credentialStore.pendingRevocations().forEach { credential ->
                    revokeWithRetry(provisioner, credential)
                }
            }
        }
    }

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
            sessions[outcome.session.sessionId] = outcome.session
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
            is SessionListJobs -> handleListJobs(env, payload, transport)
            is JobSubmit -> handleJobSubmit(env, payload, transport)
            is Metric -> handleMetric(env, payload, transport)
            is Cancel -> handleCancel(env, payload, transport)
            is JobCompleted -> handleTerminalJob(env, "completed", transport)
            is JobFailed -> handleTerminalJob(env, "failed", transport)
            is JobCancelled -> handleTerminalJob(env, "cancelled", transport)
            is SessionClose -> {
                env.sessionId?.let { sessions.remove(it) }
                transport.close()
            }
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

    private suspend fun handleListJobs(
        env: Envelope,
        request: SessionListJobs,
        transport: Transport,
    ) {
        val principal = principalFor(env.sessionId)
        val jobs =
            jobInventory.list(
                principal = principal,
                requestId = env.id,
                filter = request.filter,
                limit = request.limit,
                cursor = request.cursor,
            )
        transport.send(reply(env, jobs))
    }

    private suspend fun handleJobSubmit(
        env: Envelope,
        request: JobSubmit,
        transport: Transport,
    ) {
        val principal = principalFor(env.sessionId)
        val resolved =
            try {
                agentRegistry.resolve(
                    dev.arcp.messages.AgentRef
                        .parse(request.agent),
                )
            } catch (e: ARCPException.AgentVersionNotAvailable) {
                transport.send(nack(env, e))
                return
            } catch (e: ARCPException.NotFound) {
                transport.send(nack(env, e))
                return
            } catch (e: IllegalArgumentException) {
                transport.send(
                    nack(env, ARCPException.InvalidArgument(e.message ?: "invalid agent", "agent")),
                )
                return
            }

        val jobId = JobId.random()
        val lease = parseCostBudget(request.leaseRequest)
        val modelUse = parseModelUse(request.leaseRequest)
        val expiresAt = parseExpiresAt(request.leaseConstraints)
        lease?.let { budgets.register(jobId, it) }
        val credentials = issueCredentials(jobId, null, lease, modelUse, expiresAt)
        val acceptedAt = Clock.System.now()
        val accepted =
            JobAccepted(
                jobId = jobId,
                agent = resolved.render(),
                acceptedAt = acceptedAt,
                credentials = credentials.ifEmpty { null },
            )
        jobs[jobId] =
            RuntimeJob(
                costBudget = lease,
                modelUse = modelUse,
                expiresAt = expiresAt,
            )
        jobInventory.record(
            JobListEntry(
                jobId = jobId,
                agent = resolved.render(),
                status = "accepted",
                lease = leaseSummary(lease, modelUse, expiresAt),
                parentJobId = null,
                createdAt = acceptedAt,
                traceId = env.traceId,
                credentials = credentials.ifEmpty { null },
            ),
            ownerPrincipal = principal,
        )
        transport.send(
            Envelope(
                id = MessageId.random(),
                sessionId = env.sessionId,
                jobId = jobId,
                correlationId = env.id,
                payload = accepted,
            ),
        )
    }

    private suspend fun handleMetric(
        env: Envelope,
        metric: Metric,
        transport: Transport,
    ) {
        val jobId = env.jobId ?: return
        if (!metric.name.startsWith("cost.") ||
            metric.name == StandardMetrics.COST_BUDGET_REMAINING
        ) {
            return
        }
        val amount = BudgetAmount(dev.arcp.lease.Currency(metric.unit), metric.value.asBigDecimal())
        when (val outcome = budgets.consume(jobId, amount)) {
            BudgetCounter.Outcome.Ok -> emitRemainingBudget(env, jobId, amount, transport)
            is BudgetCounter.Outcome.Exhausted -> {
                emitRemainingBudget(env, jobId, amount, transport)
                val error = ARCPException.BudgetExhausted(outcome.currency.code, jobId)
                transport.send(nack(env, error))
            }
        }
    }

    private suspend fun handleCancel(
        env: Envelope,
        request: Cancel,
        transport: Transport,
    ) {
        if (request.target != CancelTarget.JOB) {
            transport.send(
                nack(
                    env,
                    ARCPException.Unimplemented("10.4", "only job cancellation is implemented"),
                ),
            )
            return
        }
        val jobId = JobId(request.targetId)
        terminalCleanup(jobId, "cancelled")
        transport.send(reply(env, CancelAccepted(targetId = request.targetId)))
    }

    private suspend fun handleTerminalJob(
        env: Envelope,
        status: String,
        transport: Transport,
    ) {
        env.jobId?.let { terminalCleanup(it, status) }
        transport.send(
            reply(
                env,
                dev.arcp.messages.Ack(ackFor = env.id),
            ),
        )
    }

    private suspend fun issueCredentials(
        jobId: JobId,
        parentJobId: JobId?,
        lease: CostBudget?,
        modelUse: ModelUseLease?,
        expiresAt: Instant?,
    ): List<Credential> {
        val provisioner = credentialProvisioner ?: return emptyList()
        if (lease == null && modelUse == null) return emptyList()
        val issued =
            provisioner.issue(
                CredentialProvisioner.IssuanceContext(
                    jobId = jobId,
                    parentJobId = parentJobId,
                    lease = lease,
                    modelUse = modelUse,
                    expiresAt = expiresAt,
                ),
            )
        issued.forEach { credentialStore.record(jobId, it) }
        return issued
    }

    private suspend fun terminalCleanup(
        jobId: JobId,
        status: String,
    ) {
        jobInventory.updateStatus(jobId, status)
        budgets.terminate(jobId)
        jobs.remove(jobId)
        val provisioner = credentialProvisioner ?: return
        credentialStore.outstanding(jobId).forEach { credential ->
            revokeWithRetry(provisioner, credential)
        }
    }

    private suspend fun revokeWithRetry(
        provisioner: CredentialProvisioner,
        credential: Credential,
    ) {
        repeat(REVOKE_ATTEMPTS) { attempt ->
            try {
                provisioner.revoke(credential.id)
                credentialStore.remove(credential.id)
                return
            } catch (e: IOException) {
                if (attempt == REVOKE_ATTEMPTS - 1) {
                    log.warn(e) { "credential revocation failed for ${credential.id}" }
                }
            }
        }
    }

    /** Reissues one outstanding credential and optionally emits a rotation status event. */
    public suspend fun rotateCredential(
        jobId: JobId,
        credentialId: CredentialId,
        transport: Transport? = null,
    ): Credential {
        val provisioner =
            credentialProvisioner
                ?: throw ARCPException.FailedPrecondition(
                    "credential provisioner is not configured",
                )
        val job = jobs[jobId] ?: throw ARCPException.NotFound("job $jobId is not active")
        val old = credentialStore.outstanding(jobId).firstOrNull { it.id == credentialId }
            ?: throw ARCPException.NotFound("credential $credentialId is not outstanding")
        val newCredential =
            provisioner
                .issue(
                    CredentialProvisioner.IssuanceContext(
                        jobId = jobId,
                        parentJobId = job.parentJobId,
                        lease = job.costBudget,
                        modelUse = job.modelUse,
                        expiresAt = job.expiresAt,
                    ),
                ).first()
        credentialStore.record(jobId, newCredential)
        revokeWithRetry(provisioner, old)
        transport?.send(
            Envelope(
                id = MessageId.random(),
                jobId = jobId,
                payload =
                    JobStatusEvent(
                        phase = "credential_rotated",
                        body =
                            buildJsonObject {
                                put("id", newCredential.id.value)
                                put("value", newCredential.value)
                            },
                    ),
            ),
        )
        return newCredential
    }

    private suspend fun emitRemainingBudget(
        env: Envelope,
        jobId: JobId,
        amount: BudgetAmount,
        transport: Transport,
    ) {
        val remaining = budgets.remaining(jobId)?.byCurrency(amount.currency)?.value ?: return
        transport.send(
            Envelope(
                id = MessageId.random(),
                sessionId = env.sessionId,
                jobId = jobId,
                causationId = env.id,
                payload =
                    Metric(
                        name = StandardMetrics.COST_BUDGET_REMAINING,
                        value = JsonPrimitive(remaining.toPlainString()),
                        unit = amount.currency.code,
                    ),
            ),
        )
    }

    private fun principalFor(sessionId: SessionId?): String =
        sessionId?.let { sessions[it]?.principal } ?: "anonymous"

    private fun reply(
        env: Envelope,
        payload: dev.arcp.messages.MessageType,
    ): Envelope = Envelope(
        id = MessageId.random(),
        sessionId = env.sessionId,
        correlationId = env.id,
        payload = payload,
    )

    private fun nack(
        env: Envelope,
        error: ARCPException,
    ): Envelope = reply(
        env,
        Nack(
            nackFor = env.id,
            code = error.code,
            message = error.message ?: error.code.wire,
            retryable = error.retryable,
        ),
    )

    private fun parseCostBudget(leaseRequest: JsonObject): CostBudget? {
        val values = leaseRequest.stringArray("cost.budget")
        return values
            .takeIf { it.isNotEmpty() }
            ?.map(BudgetAmount::parse)
            ?.let(::CostBudget)
    }

    private fun parseModelUse(leaseRequest: JsonObject): ModelUseLease? {
        val values = leaseRequest.stringArray("model.use")
        return values.takeIf { it.isNotEmpty() }?.let(::ModelUseLease)
    }

    private fun parseExpiresAt(leaseConstraints: JsonObject?): Instant? = leaseConstraints
        ?.get("expires_at")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let(Instant::parse)

    private fun leaseSummary(
        lease: CostBudget?,
        modelUse: ModelUseLease?,
        expiresAt: Instant?,
    ): JobListLease? {
        if (lease == null && modelUse == null && expiresAt == null) return null
        return JobListLease(
            expiresAt = expiresAt,
            capabilities =
                buildMap {
                    lease?.let { put("cost.budget", it.budgets.map { budget -> budget.render() }) }
                    modelUse?.let { put("model.use", it.patterns) }
                },
        )
    }

    private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()

    private fun JsonPrimitive.asBigDecimal(): BigDecimal {
        contentOrNull?.toBigDecimalOrNull()?.let { return it }
        throw ARCPException.InvalidArgument("metric value must be numeric", "value")
    }

    private fun handleHandshake(opener: Envelope): HandshakeOutcome {
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
            supportedCapabilities.provisionedCredentials && credentialProvisioner != null,
        modelUse = supportedCapabilities.modelUse && credentialProvisioner != null,
        agents = agentRegistry.descriptors(),
    )

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

    private data class RuntimeJob(
        val parentJobId: JobId? = null,
        val costBudget: CostBudget? = null,
        val modelUse: ModelUseLease? = null,
        val expiresAt: Instant? = null,
    )

    public companion object {
        /** Default session lease window (1 hour). */
        public val DEFAULT_SESSION_LEASE: Duration = 1.hours

        private const val REVOKE_ATTEMPTS: Int = 3
    }
}
