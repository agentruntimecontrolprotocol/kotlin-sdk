package com.arcp.samples.leaseexpiresat

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Demonstrates lease TTL tracking and proactive refresh before expiry (RFC §9.4).
 *
 * The agent holds a short-lived `analytics.read` lease (30 s). A background
 * coroutine watches `lease.extended` and `lease.revoked` events, refreshing
 * 10 s before the TTL elapses.  If the runtime refuses the extension the agent
 * falls back to re-requesting from scratch.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="lease-expires-at"
 * ```
 */

private const val LEASE_SECONDS: Int = 30
private const val REFRESH_BEFORE_SECONDS: Int = 10

internal data class ManagedLease(
    val leaseId: LeaseId,
    var expiresAt: Instant,
)

private suspend fun requestLease(
    client: ARCPClient,
    permission: String,
    resource: String,
): ManagedLease {
    val reply = client.request(
        envelope = client.envelope(
            type = "permission.request",
            payload = mapOf(
                "permission" to permission,
                "resource" to resource,
                "operation" to "read",
                "reason" to "analytics dashboard query",
                "requested_lease_seconds" to LEASE_SECONDS,
            ),
        ),
        timeoutMs = 60_000,
    )
    if (reply.type == "permission.deny") {
        throw ARCPException.PermissionDenied(
            permission = PermissionName(permission),
            resource = resource,
            message = reply.payloadMap()["reason"]?.toString() ?: "denied",
        )
    }
    val expires = Instant.parse(reply.payloadMap()["expires_at"].toString())
    val leaseId = LeaseId(reply.payloadMap()["lease_id"].toString())
    println("lease granted: $leaseId, expires at $expires")
    return ManagedLease(leaseId, expires)
}

private suspend fun refreshLease(
    client: ARCPClient,
    lease: ManagedLease,
) {
    println("refreshing lease ${lease.leaseId} before it expires at ${lease.expiresAt}")
    val reply = client.request(
        envelope = client.envelope(
            type = "lease.refresh",
            payload = mapOf(
                "lease_id" to lease.leaseId.value,
                "requested_extension_seconds" to LEASE_SECONDS,
            ),
        ),
        timeoutMs = 10_000,
    )
    when (reply.type) {
        "lease.extended" -> {
            lease.expiresAt = Instant.parse(reply.payloadMap()["expires_at"].toString())
            println("lease extended; new expiry ${lease.expiresAt}")
        }
        "lease.revoked" -> {
            println("lease revoked during refresh — must re-request")
            throw ARCPException.LeaseRevoked(leaseId = lease.leaseId, reason = "runtime revoked during refresh")
        }
        else -> println("unexpected reply to lease.refresh: ${reply.type}")
    }
}

/** Background loop that keeps [lease] alive, updating its fields in place. */
private fun kotlinx.coroutines.CoroutineScope.keepAlive(
    client: ARCPClient,
    lease: ManagedLease,
) = launch {
    while (true) {
        val now = Clock.System.now()
        val ttlMs = (lease.expiresAt - now).inWholeMilliseconds
        val sleepMs = ttlMs - (REFRESH_BEFORE_SECONDS * 1000L)

        if (sleepMs > 0) delay(sleepMs)

        try {
            refreshLease(client, lease)
        } catch (e: ARCPException.LeaseRevoked) {
            // Runtime evicted this lease — stop refreshing.
            break
        } catch (e: ARCPException) {
            // Transient error — try again next cycle.
            println("refresh error (${e::class.simpleName}): ${e.message}")
        }
    }
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val lease = requestLease(
        client,
        permission = "analytics.read",
        resource = "dataset:events",
    )

    coroutineScope {
        // Monitor runtime-initiated revocations in parallel.
        launch {
            client.events().collect { env ->
                if (env.type == "lease.revoked" &&
                    env.payloadMap()["lease_id"] == lease.leaseId.value
                ) {
                    println("runtime revoked lease ${lease.leaseId}: ${env.payloadMap()["reason"]}")
                }
            }
        }

        keepAlive(client, lease)

        // Simulate 2 minutes of work requiring the lease.
        repeat(4) { iteration ->
            delay(10_000L)
            println("iteration $iteration: running query under lease ${lease.leaseId}")
            runQuery(iteration)
        }
    }

    client.close()
}

@Suppress("UNUSED_PARAMETER")
private fun runQuery(iteration: Int) {
    // illustrative stub — real implementation would execute the analytics query
}
