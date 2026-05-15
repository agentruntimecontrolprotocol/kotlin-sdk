package com.arcp.samples.permissionchallenge

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/** Generator proposes; reviewer holds veto via permission.request. */

private const val MAX_REVISIONS = 4

internal fun fingerprint(diff: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val hex = md.digest(diff.toByteArray()).joinToString("") { "%02x".format(it) }
    return hex.substring(0, 16)
}

private suspend fun requestApply(
    client: ARCPClient,
    ticketId: String,
    patch: Patch,
): LeaseId {
    val fp = fingerprint(patch.diff)
    val reply =
        client.request(
            envelope =
                client.envelope(
                    type = "permission.request",
                    // Same key per (ticket, diff): identical patch dedupes at runtime.
                    idempotencyKey = "review:$ticketId:$fp",
                    payload =
                        mapOf(
                            "permission" to "repo.write",
                            "resource" to "ticket:$ticketId/$fp",
                            "operation" to "apply_patch",
                            "reason" to "apply patch",
                            "requested_lease_seconds" to 90,
                        ),
                ),
            timeoutMs = 300_000,
        )
    if (reply.type == "permission.deny") {
        throw ARCPException.PermissionDenied(
            permission = PermissionName("repo.write"),
            resource = "ticket:$ticketId/$fp",
            message = reply.payloadMap()["reason"]?.toString() ?: "denied",
        )
    }
    return LeaseId(reply.payloadMap()["lease_id"].toString())
}

private suspend fun respond(
    reviewer: ARCPClient,
    request: Envelope,
    verdict: ReviewVerdict,
) {
    if (verdict.grant) {
        reviewer.dispatch(
            reviewer.envelope(
                type = "permission.grant",
                correlationId = request.id,
                payload =
                    mapOf(
                        "permission" to request.payloadMap()["permission"],
                        "resource" to request.payloadMap()["resource"],
                        "operation" to request.payloadMap()["operation"],
                        "lease_seconds" to 90,
                    ),
            ),
        )
    } else {
        reviewer.dispatch(
            reviewer.envelope(
                type = "permission.deny",
                correlationId = request.id,
                payload =
                    mapOf(
                        "permission" to request.payloadMap()["permission"],
                        "reason" to verdict.reason,
                        "code" to ErrorCode.FAILED_PRECONDITION.wire,
                    ),
            ),
        )
    }
}

private fun CoroutineScope.reviewerLoop(
    reviewer: ARCPClient,
    ticket: String,
) = launch {
    reviewer.events().collect { env ->
        if (env.type == "permission.request") {
            val verdict = review(ticket = ticket, request = env)
            respond(reviewer, env, verdict)
        }
    }
}

public fun main(): Unit = runBlocking {
    // Two sessions, one per agent. In production they'd be in different
    // processes on different runtimes; the message contract is identical.
    val generator: ARCPClient = TODO("transport, identity, auth elided")
    val reviewer: ARCPClient = TODO("transport, identity, auth elided")
    generator.open()
    reviewer.open()

    val ticketId = "JIRA-4812"
    val ticket = "Reject JWTs whose `aud` does not match the configured audience. Add a unit test."

    coroutineScope {
        reviewerLoop(reviewer, ticket)

        var priorDenial: String? = null
        for (n in 0 until MAX_REVISIONS) {
            val patch = propose(ticket = ticket, priorDenial = priorDenial)
            try {
                val lease = requestApply(generator, ticketId, patch)
                println("applied ${fingerprint(patch.diff)} lease=$lease")
                return@coroutineScope
            } catch (e: ARCPException.PermissionDenied) {
                priorDenial = e.message
            }
        }
        println("abandoned after max_revisions")
    }
    generator.close()
    reviewer.close()
}
