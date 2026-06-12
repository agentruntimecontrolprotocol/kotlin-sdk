package com.arcp.samples.heartbeats

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.JobId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant

/** Supervisor + worker pool. Heartbeat loss reroutes via idempotency_key. */

private const val HEARTBEAT_INTERVAL_SECONDS: Int = 15
private const val DEADLINE_S: Int = HEARTBEAT_INTERVAL_SECONDS * 2 // RFC §10.3 default N=2

internal data class Worker(
    val workerId: String,
    val role: String,
    var lastHeartbeat: Instant,
    var inFlightJob: JobId? = null,
)

internal data class Task(
    val taskId: String,
    val role: String,
    val payload: Map<String, Any?>,
    val idempotencyKey: String, // safety net for re-dispatch
)

internal class Roster {
    val workers: MutableMap<String, Worker> = mutableMapOf()
    val byRole: MutableMap<String, MutableList<String>> = mutableMapOf()

    fun add(w: Worker) {
        workers[w.workerId] = w
        byRole.getOrPut(w.role) { mutableListOf() }.add(w.workerId)
    }

    fun candidates(role: String): List<Worker> = byRole[role]
        .orEmpty()
        .mapNotNull { workers[it] }
        .filter { it.inFlightJob == null }
}

// Supervisor side --------------------------------------------------------

internal suspend fun dispatch(
    client: ARCPClient,
    task: Task,
    roster: Roster,
    jobsToTasks: MutableMap<JobId, Task>,
) {
    val candidates = roster.candidates(task.role)
    require(candidates.isNotEmpty()) { "no idle workers for role=${task.role}" }
    val worker = candidates.minBy { it.lastHeartbeat }
    // Same idempotency_key on every re-dispatch (RFC §6.4): a worker
    // that survived the network blip dedupes; it doesn't re-execute.
    val accepted =
        client.request(
            envelope =
                client.envelope(
                    type = "agent.delegate",
                    idempotencyKey = task.idempotencyKey,
                    payload =
                        mapOf(
                            "target" to worker.workerId,
                            "task" to task.taskId,
                            "context" to mapOf("task_payload" to task.payload),
                        ),
                ),
            timeoutMs = 10_000,
        )
    val jobId = JobId(accepted.payloadMap()["job_id"].toString())
    worker.inFlightJob = jobId
    jobsToTasks[jobId] = task
}

internal fun CoroutineScope.supervise(
    client: ARCPClient,
    roster: Roster,
    jobsToTasks: MutableMap<JobId, Task>,
): Job = launch {
    launch {
        while (true) {
            delay(HEARTBEAT_INTERVAL_SECONDS * 1000L)
            val now = Clock.System.now()
            for (w in roster.workers.values.toList()) {
                val ageS =
                    (now.toEpochMilliseconds() - w.lastHeartbeat.toEpochMilliseconds()) / 1000
                if (ageS <= DEADLINE_S) continue
                val jid = w.inFlightJob
                val task = jid?.let { jobsToTasks.remove(it) }
                if (task != null) {
                    dispatch(client, task, roster, jobsToTasks)
                }
                roster.workers.remove(w.workerId)
                roster.byRole[w.role]?.remove(w.workerId)
            }
        }
    }

    client.events().collect { env ->
        when (env.type) {
            "job.heartbeat" -> {
                roster.workers.values
                    .filter { it.inFlightJob == env.jobId }
                    .forEach { it.lastHeartbeat = Clock.System.now() }
            }
            "job.completed", "job.failed", "job.cancelled" -> {
                env.jobId?.let { jobsToTasks.remove(it) }
                roster.workers.values
                    .filter { it.inFlightJob == env.jobId }
                    .forEach { it.inFlightJob = null }
            }
        }
    }
}

// Worker side ------------------------------------------------------------

internal suspend fun heartbeatLoop(
    client: ARCPClient,
    jobId: JobId,
    stop: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
) {
    var seq = 0
    while (!stop.value) {
        client.dispatch(
            client.envelope(
                type = "job.heartbeat",
                jobId = jobId,
                payload =
                    mapOf(
                        "sequence" to seq,
                        "deadline_ms" to HEARTBEAT_INTERVAL_SECONDS * 2000,
                        "state" to "running",
                    ),
            ),
        )
        seq += 1
        delay(HEARTBEAT_INTERVAL_SECONDS * 1000L)
    }
}

internal suspend fun execute(
    client: ARCPClient,
    env: Envelope,
) {
    val jobId = JobId.random()
    client.dispatch(
        client.envelope(
            type = "job.accepted",
            jobId = jobId,
            correlationId = env.id,
            payload = mapOf("job_id" to jobId.value, "state" to "accepted"),
        ),
    )
    client.dispatch(
        client.envelope(
            type = "job.started",
            jobId = jobId,
            payload = mapOf("job_id" to jobId.value),
        ),
    )
    val stop = kotlinx.coroutines.flow.MutableStateFlow(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hb = scope.launch { heartbeatLoop(client, jobId, stop) }
    try {
        val result = doWork(env.payloadMap()["context"] as? Map<*, *>)
        client.dispatch(
            client.envelope(
                type = "job.completed",
                jobId = jobId,
                payload = mapOf("result" to result),
            ),
        )
    } catch (e: Exception) {
        client.dispatch(
            client.envelope(
                type = "job.failed",
                jobId = jobId,
                payload =
                    mapOf(
                        "code" to "INTERNAL",
                        "message" to (e.message ?: ""),
                        "retryable" to true,
                    ),
            ),
        )
    } finally {
        stop.value = true
        hb.cancel()
        scope.cancel()
    }
}

internal suspend fun runWorker(client: ARCPClient) {
    coroutineScope {
        client.events().collect { env ->
            when (env.type) {
                "agent.delegate" -> launch { execute(client, env) }
                "session.evicted" -> cancel()
            }
        }
    }
}

public fun main(): Unit = runBlocking {
    val supervisor: ARCPClient = TODO("transport, identity (privileged), auth elided")
    supervisor.open()

    val roster = Roster()
    val jobsToTasks: MutableMap<JobId, Task> = mutableMapOf()

    coroutineScope {
        // In production each worker is its own process; co-hosted here for the demo.
        for (role in listOf("indexer", "extractor", "archiver")) {
            repeat(2) {
                val w: ARCPClient = TODO("worker session, capabilities advertise role=$role")
                w.open()
                launch { runWorker(w) }
                roster.add(
                    Worker(
                        workerId = "$role-${java.util.UUID.randomUUID().toString().take(6)}",
                        role = role,
                        lastHeartbeat = Clock.System.now(),
                    ),
                )
            }
        }

        supervise(supervisor, roster, jobsToTasks)

        val roles = listOf("indexer", "extractor", "archiver")
        for (n in 0 until 6) {
            dispatch(
                supervisor,
                task =
                    Task(
                        taskId = "t%03d".format(n),
                        role = roles[n % 3],
                        payload = mapOf("shard" to n),
                        idempotencyKey = "openclaw:t%03d".format(n),
                    ),
                roster = roster,
                jobsToTasks = jobsToTasks,
            )
        }

        delay(60_000)
    }
    supervisor.close()
}
