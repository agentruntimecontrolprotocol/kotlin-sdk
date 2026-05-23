package com.arcp.samples.leaseviolation

import com.arcp.samples.envelope
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import dev.arcp.lease.ModelUseLease
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

/**
 * Demonstrates three categories of lease violation (RFC §9.5):
 *
 * 1. **PERMISSION_DENIED** — agent calls a tool it was never granted.
 * 2. **LEASE_EXPIRED**    — agent tries to use a lease whose TTL elapsed.
 * 3. **LEASE_SUBSET_VIOLATION** — sub-agent requests a budget/model-use
 *    lease that is not a subset of the parent's own lease.
 *
 * Each scenario is isolated so you can observe the exact exception type
 * thrown by the SDK when the runtime rejects the request.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="lease-violation"
 * ```
 */

// ---------------------------------------------------------------------------
// Scenario 1: PERMISSION_DENIED — calling an un-granted tool
// ---------------------------------------------------------------------------

private suspend fun permissionDeniedScenario(client: ARCPClient) {
    println("\n--- Scenario 1: PERMISSION_DENIED ---")
    println("Attempting to call 'send_reply' without a write lease ...")

    try {
        // Agent only holds 'inbox_read' + 'inbox_summarise'.
        // 'send_reply' is not in the lease → runtime returns permission.deny.
        val reply = client.request(
            envelope = client.envelope(
                type = "tool.invoke",
                payload = mapOf(
                    "tool" to "send_reply",
                    "arguments" to mapOf(
                        "to" to "user@example.com",
                        "body" to "Here is your answer.",
                    ),
                ),
            ),
            timeoutMs = 10_000,
        )
        if (reply.type == "permission.deny") {
            throw ARCPException.PermissionDenied(
                permission = PermissionName("email.write"),
                resource = "inbox:user@example.com",
                message = reply.payloadMap()["reason"]?.toString() ?: "denied",
            )
        }
    } catch (e: ARCPException.PermissionDenied) {
        println("caught: ${e::class.simpleName} — permission=${e.permission}, resource=${e.resource}")
    }
}

// ---------------------------------------------------------------------------
// Scenario 2: LEASE_EXPIRED — using a stale lease
// ---------------------------------------------------------------------------

private suspend fun leaseExpiredScenario(client: ARCPClient) {
    println("\n--- Scenario 2: LEASE_EXPIRED ---")
    println("Attempting a tool call with an expired lease ...")

    try {
        val reply = client.request(
            envelope = client.envelope(
                type = "tool.invoke",
                payload = mapOf(
                    "tool" to "db_query",
                    "lease_id" to "lease_expired_00000000",  // intentionally expired
                    "arguments" to mapOf("sql" to "SELECT count(*) FROM orders"),
                ),
            ),
            timeoutMs = 10_000,
        )
        if (reply.type == "nack" && reply.payloadMap()["code"] == "LEASE_EXPIRED") {
            throw ARCPException.LeaseExpired(
                leaseId = LeaseId("lease_expired_00000000"),
                expiredAt = Clock.System.now(),
            )
        }
    } catch (e: ARCPException.LeaseExpired) {
        println("caught: ${e::class.simpleName} — ${e.message}")
        println("action: re-request the lease and retry the tool call")
    }
}

// ---------------------------------------------------------------------------
// Scenario 3: LEASE_SUBSET_VIOLATION — sub-agent exceeds parent's scope
// ---------------------------------------------------------------------------

private fun leaseSubsetViolationScenario() {
    println("\n--- Scenario 3: LEASE_SUBSET_VIOLATION (local check) ---")

    // Parent agent's model-use lease: only claude-3-* models.
    val parentLease = ModelUseLease(patterns = listOf("claude-3-*"))

    // Sub-agent requests claude-3-* AND gpt-4* — the second pattern exceeds
    // the parent's scope.  ModelUseLease.subset() detects this locally
    // before the network round-trip.
    val childLease = ModelUseLease(patterns = listOf("claude-3-*", "gpt-4*"))

    val valid = ModelUseLease.subset(parent = parentLease, child = childLease)
    if (!valid) {
        val e = ARCPException.LeaseSubsetViolation(capability = "model.use")
        println("caught: ${e::class.simpleName} — capability=${e.capability}")
        println("fix: restrict child patterns to a subset of parent's ${parentLease.patterns}")
    }

    // Correct sub-lease: claude-3-opus-* is a subset of claude-3-*.
    val narrowedChild = ModelUseLease(patterns = listOf("claude-3-opus-*"))
    val narrowedOk = ModelUseLease.subset(parent = parentLease, child = narrowedChild)
    println("narrowed child valid=$narrowedOk  (patterns=${narrowedChild.patterns})")
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity (constrained), auth elided")
    client.open()

    permissionDeniedScenario(client)
    leaseExpiredScenario(client)

    client.close()

    // Local check — no network needed.
    leaseSubsetViolationScenario()
}
