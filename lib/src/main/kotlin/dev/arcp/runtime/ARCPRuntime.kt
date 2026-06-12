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
import dev.arcp.lease.BudgetRegistry
import dev.arcp.messages.Cancel
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobCancelled
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobFailed
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Metric
import dev.arcp.messages.Ping
import dev.arcp.messages.RuntimeIdentity
import dev.arcp.messages.SessionAuthenticate
import dev.arcp.messages.SessionChallenge
import dev.arcp.messages.SessionClose
import dev.arcp.messages.SessionEvicted
import dev.arcp.messages.SessionListJobs
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val log = KotlinLogging.logger {}

/**
 * Authoritative ARCP runtime (server side, RFC §5).
 *
 * Construct one runtime per process and call [accept] for each transport
 * connection — the runtime drives the session handshake, dispatches incoming
 * envelopes, and emits structured events. This class is a thin orchestration
 * facade: the handshake, job submission, job lifecycle, and credential
 * concerns live in dedicated collaborators ([SessionHandshakeHandler],
 * [JobCommandHandler], [JobLifecycleHandler], [CredentialLifecycle]).
 */
public class ARCPRuntime
    @Suppress("LongParameterList")
    constructor(
        supportedCapabilities: Capabilities,
        identity: RuntimeIdentity =
            RuntimeIdentity(
                kind = Version.SDK_KIND,
                version = Version.SDK_VERSION,
                trustLevel = TrustLevel.TRUSTED,
            ),
        bearerAuth: BearerAuth = StaticBearerAuth(emptyMap()),
        jwtAuth: JwtAuth? = null,
        sessionLeaseDuration: Duration = DEFAULT_SESSION_LEASE,
        agentRegistry: AgentRegistry = AgentRegistry(),
        jobInventory: JobInventory = InMemoryJobInventory(),
        budgets: BudgetRegistry = BudgetRegistry(),
        credentialProvisioner: CredentialProvisioner? = null,
        credentialStore: CredentialStore = InMemoryCredentialStore(),
        /**
         * When `true` (the default), terminal jobs are removed from the
         * [JobInventory] after their events have drained. Set to `false`
         * if the caller needs `session.list_jobs` to keep returning
         * completed jobs indefinitely — usually for tests.
         */
        evictTerminalJobs: Boolean = true,
    ) : AutoCloseable {
        private val supervisor: Job = SupervisorJob()
        private val scope: CoroutineScope =
            CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("arcp-runtime"))
        private val sessions: SessionRegistry = SessionRegistry()
        private val jobs: RuntimeJobs = RuntimeJobs()

        private val credentials: CredentialLifecycle =
            CredentialLifecycle(credentialProvisioner, credentialStore)
        private val handshake: SessionHandshakeHandler =
            SessionHandshakeHandler(
                supportedCapabilities = supportedCapabilities,
                identity = identity,
                sessionLeaseDuration = sessionLeaseDuration,
                agentRegistry = agentRegistry,
                authenticator =
                    SessionAuthenticator(bearerAuth, jwtAuth, supportedCapabilities.anonymous),
                hasCredentialProvisioner = credentialProvisioner != null,
            )
        private val jobCommands: JobCommandHandler =
            JobCommandHandler(agentRegistry, jobInventory, budgets, sessions, credentials, jobs)
        private val jobLifecycle: JobLifecycleHandler =
            JobLifecycleHandler(
                jobInventory,
                budgets,
                credentials,
                sessions,
                jobs,
                evictTerminalJobs,
            )

        init {
            if (credentialProvisioner != null) {
                scope.launch { credentials.drainPending() }
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

            val outcome = handshake.handle(opener)
            transport.send(outcome.reply)

            val session = outcome.session
            if (session is SessionState.Authenticated) {
                sessions.register(session)
                // Release the session entry whenever the connection ends —
                // whether via session.close, a swallowed dispatch-loop error, or
                // a silent transport drop. Without this the map grows without
                // bound as short or dropped connections accumulate (#79).
                try {
                    runDispatchLoop(transport)
                } finally {
                    sessions.remove(session.sessionId)
                }
            } else {
                transport.close()
            }
        }

        /** Number of authenticated sessions currently retained (for tests; #79). */
        internal val activeSessionCount: Int
            get() = sessions.size

        private suspend fun runDispatchLoop(transport: Transport) {
            try {
                transport.receive().collect { env ->
                    dispatchOne(env, transport)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info(e) { "session ended" }
            }
        }

        /**
         * Runs [handleEnvelope] inside a per-envelope try/catch. Application-level
         * errors translate to a correlated `Nack`; `CancellationException` still
         * unwinds the loop. The session stays usable after a single bad envelope
         * (RFC §17 — malformed wire input must not tear down the session).
         */
        private suspend fun dispatchOne(
            env: Envelope,
            transport: Transport,
        ) {
            try {
                handleEnvelope(env, transport)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ARCPException) {
                log.debug(e) { "envelope ${env.id.value} produced ${e.code.wire}" }
                transport.send(nack(env, e))
            } catch (e: IllegalArgumentException) {
                log.debug(e) { "envelope ${env.id.value} rejected as invalid" }
                transport.send(
                    nack(env, ARCPException.InvalidArgument(e.message ?: "invalid", null)),
                )
            } catch (e: NumberFormatException) {
                log.debug(e) { "envelope ${env.id.value} rejected as invalid number" }
                val invalid = ARCPException.InvalidArgument(e.message ?: "invalid number", null)
                transport.send(nack(env, invalid))
            }
        }

        private suspend fun handleEnvelope(
            env: Envelope,
            transport: Transport,
        ) {
            when (val payload = env.payload) {
                is Ping -> transport.send(pong(env, payload))
                is SessionListJobs -> jobCommands.listJobs(env, payload, transport)
                is JobSubmit -> jobCommands.submit(env, payload, transport)
                is Metric -> jobLifecycle.metric(env, payload, transport)
                is Cancel -> jobLifecycle.cancel(env, payload, transport)
                is JobCompleted -> jobLifecycle.terminal(env, "completed", transport)
                is JobFailed -> jobLifecycle.terminal(env, "failed", transport)
                is JobCancelled -> jobLifecycle.terminal(env, "cancelled", transport)
                is SessionChallenge, is SessionAuthenticate ->
                    transport.send(deferredHandshakeNack(env))
                is SessionClose -> {
                    env.sessionId?.let { sessions.remove(it) }
                    transport.close()
                }
                else -> transport.send(unimplemented(env))
            }
        }

        /** Reissues one outstanding credential and optionally emits a rotation status event. */
        public suspend fun rotateCredential(
            jobId: JobId,
            credentialId: CredentialId,
            transport: Transport? = null,
        ): Credential = credentials.rotate(jobId, jobs.active[jobId], credentialId, transport)

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
            // Drop the retained session state on eviction so an evicted session
            // does not linger in the map (#79).
            sessions.remove(sessionId)
            transport.close()
        }

        override fun close() {
            scope.cancel()
        }

        public companion object {
            /** Default session lease window (1 hour). */
            public val DEFAULT_SESSION_LEASE: Duration = 1.hours
        }
    }
