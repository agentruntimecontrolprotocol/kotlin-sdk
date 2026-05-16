package com.arcp.samples.leases

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import dev.arcp.ids.StreamId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/** Sandboxed on-call agent. Lease-gated shell, reasoning streamed. */

private val READ_BINARIES =
    setOf("/usr/bin/journalctl", "/usr/bin/cat", "/usr/bin/ss", "/usr/bin/ps")
private val WRITE_BINARIES = setOf("/usr/bin/systemctl", "/usr/bin/kill")
private const val READ_LEASE_SECONDS: Int = 30 * 60
private const val WRITE_LEASE_SECONDS: Int = 60

internal data class Classified(
    val permission: String,
    val resource: String,
    val operation: String,
    val leaseSeconds: Int,
)

internal fun classify(
    argv: List<String>,
    host: String,
): Classified {
    val binary = argv[0]
    return when {
        binary in READ_BINARIES ->
            Classified("host.read", "host:$host", "read", READ_LEASE_SECONDS)
        binary in WRITE_BINARIES -> {
            val target = if (binary == "/usr/bin/systemctl") argv[2] else argv[1]
            Classified("host.write", "host:$host/$binary/$target", "write", WRITE_LEASE_SECONDS)
        }
        else -> throw ARCPException.PermissionDenied(
            permission = PermissionName("host.exec"),
            resource = "host:$host/$binary",
            message = "binary not allowed: $binary",
        )
    }
}

private suspend fun acquireLease(
    client: ARCPClient,
    permission: String,
    resource: String,
    operation: String,
    seconds: Int,
    reason: String,
): LeaseId {
    val reply =
        client.request(
            envelope =
                client.envelope(
                    type = "permission.request",
                    payload =
                        mapOf(
                            "permission" to permission,
                            "resource" to resource,
                            "operation" to operation,
                            "reason" to reason,
                            "requested_lease_seconds" to seconds,
                        ),
                ),
            timeoutMs = 120_000,
        )
    if (reply.type == "permission.deny") {
        val msg = reply.payloadMap()["reason"]?.toString() ?: "denied"
        throw ARCPException.PermissionDenied(
            permission = PermissionName(permission),
            resource = resource,
            message = msg,
        )
    }
    return LeaseId(reply.payloadMap()["lease_id"].toString())
}

internal suspend fun runCommand(
    client: ARCPClient,
    argv: List<String>,
    reason: String,
    host: String,
): String {
    val k = classify(argv, host)
    val lease =
        acquireLease(
            client,
            permission = k.permission,
            resource = k.resource,
            operation = k.operation,
            seconds = k.leaseSeconds,
            reason = reason,
        )
    // The lease is the only guard. Spawn the subprocess elsewhere.
    return "<would run $argv under lease $lease>"
}

internal suspend fun emitThought(
    client: ARCPClient,
    streamId: StreamId,
    sequence: Int,
    text: String,
) {
    client.dispatch(
        client.envelope(
            type = "stream.chunk",
            streamId = streamId,
            payload =
                mapOf(
                    "sequence" to sequence,
                    "kind" to "thought",
                    "role" to "assistant_thought",
                    "content" to text,
                ),
        ),
    )
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity (constrained), auth elided")
    client.open()

    val streamId = StreamId.random()
    client.dispatch(
        client.envelope(
            type = "stream.open",
            streamId = streamId,
            payload = mapOf("kind" to "thought"),
        ),
    )

    var seq = 0
    llmLoop("api-gateway pod is OOMing every 4 minutes").collect { step ->
        emitThought(client, streamId, seq, step.thought)
        seq += 1
        step.toolCall?.let { call ->
            try {
                runCommand(client, call.argv, reason = call.reason, host = "edge-pod-04")
            } catch (e: ARCPException.PermissionDenied) {
                // PERMISSION_DENIED feeds back into the next prompt
            }
        }
        step.final?.let { println(it) }
    }
    client.close()
}
