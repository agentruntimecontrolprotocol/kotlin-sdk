package com.arcp.samples.delegation

import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.JobId
import dev.arcp.ids.TraceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Fan a request out to peer runtimes; tolerate partial failure. */

private val PEERS = listOf("research.web", "research.code", "research.docs")
private val TERMINAL = setOf("job.completed", "job.failed", "job.cancelled")

internal data class DelegatedJob(
    val target: String,
    var jobId: JobId? = null,
    var final: Map<String, Any?>? = null,
    var error: Map<String, Any?>? = null,
)

private suspend fun delegate(
    client: ARCPClient,
    target: String,
    task: String,
    traceId: TraceId,
): DelegatedJob {
    val accepted = client.request(
        envelope = client.envelope(
            type = "agent.delegate",
            traceId = traceId.value,
            payload = mapOf(
                "target" to target,
                "task" to task,
                // trace_id propagates so peers join one distributed trace.
                "context" to mapOf("trace_id" to traceId.value),
            ),
        ),
        timeoutMs = 10_000,
    )
    if (accepted.type != "job.accepted") {
        return DelegatedJob(
            target = target,
            error = mapOf(
                "code" to accepted.payloadMap()["code"],
                "message" to accepted.payloadMap()["message"],
            ),
        )
    }
    return DelegatedJob(target = target, jobId = JobId(accepted.payloadMap()["job_id"].toString()))
}

/**
 * Single reader on `client.events()`; fans out by `job_id`.
 *
 * Without this, parallel `events().collect { ... }` loops starve
 * each other — only one wins per await.
 */
internal class JobMux(private val client: ARCPClient) {
    private val queues: MutableMap<JobId, Channel<Envelope>> = mutableMapOf()
    private val ready = CompletableDeferred<Unit>()
    private var reader: Job? = null

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        reader = scope.launch {
            client.events().collect { env ->
                val jid = env.jobId ?: return@collect
                queues[jid]?.send(env)
                if (env.type in TERMINAL) {
                    queues[jid]?.close()
                }
            }
        }
    }

    fun register(jobId: JobId) {
        queues[jobId] = Channel(Channel.UNLIMITED)
    }

    fun stream(job: DelegatedJob): Flow<Envelope> = flow {
        val jid = job.jobId ?: return@flow
        val q = queues[jid] ?: return@flow
        for (env in q) {
            emit(env)
            if (env.type in TERMINAL) return@flow
        }
    }
}

private suspend fun collectJob(mux: JobMux, job: DelegatedJob): DelegatedJob {
    if (job.error != null) return job
    mux.stream(job).collect { env ->
        when (env.type) {
            "job.completed" -> job.final = env.payloadMap()
            "job.failed" -> job.error = mapOf(
                "code" to env.payloadMap()["code"],
                "message" to env.payloadMap()["message"],
            )
            "job.cancelled" -> job.error = mapOf("code" to "CANCELLED", "message" to "cancelled")
        }
    }
    return job
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val mux = JobMux(client)
    coroutineScope {
        mux.start(this)

        val request = "what changed in our auth stack in the last 30 days?"
        val traceId = TraceId.random()

        val jobs = PEERS.map { peer ->
            val job = delegate(client, target = peer, task = request, traceId = traceId)
            job.jobId?.let { mux.register(it) }
            job
        }

        val completed = jobs.map { async { collectJob(mux, it) } }.awaitAll()
        println(synthesize(request, completed))
    }
    client.close()
}
