package com.arcp.samples.leaserevocation

import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Warehouse DB admin agent. Reads pre-granted; writes prompt operator. */

private val PRE_GRANTED =
    listOf(
        "public.orders",
        "public.customers",
        "warehouse.fct_revenue_daily",
    )
private const val READ_LEASE_SECONDS: Int = 60 * 60
private const val WRITE_LEASE_SECONDS: Int = 5 * 60

internal data class LeaseEntry(
    val leaseId: LeaseId,
    val expiresAt: Instant,
)

private suspend fun requestLease(
    client: ARCPClient,
    permission: String,
    table: String,
    operation: String,
    seconds: Int,
    reason: String,
): LeaseEntry {
    val reply =
        client.request(
            envelope =
                client.envelope(
                    type = "permission.request",
                    payload =
                        mapOf(
                            "permission" to permission,
                            "resource" to "table:$table",
                            "operation" to operation,
                            "reason" to reason,
                            "requested_lease_seconds" to seconds,
                        ),
                ),
            timeoutMs = 180_000,
        )
    if (reply.type == "permission.deny") {
        throw ARCPException.PermissionDenied(
            permission = PermissionName(permission),
            resource = "table:$table",
            message = "$permission denied on $table",
        )
    }
    val expires = Instant.parse(reply.payloadMap()["expires_at"].toString())
    return LeaseEntry(LeaseId(reply.payloadMap()["lease_id"].toString()), expires)
}

internal suspend fun authorize(
    client: ARCPClient,
    sql: String,
    leases: MutableMap<Pair<String, String>, LeaseEntry>,
): String {
    val klass = classify(sql)
    if (klass.tables.isEmpty()) {
        throw ARCPException.InvalidArgument("no table referenced", argument = "sql")
    }
    val op = klass.op // "read" / "write" / "ddl"
    val seconds = if (op == "read") READ_LEASE_SECONDS else WRITE_LEASE_SECONDS
    val now = Clock.System.now()
    for (table in klass.tables) {
        val cached = leases[table to op]
        if (cached != null && cached.expiresAt > now) continue
        leases[table to op] =
            requestLease(
                client,
                permission = "db.$op",
                table = table,
                operation = op,
                seconds = seconds,
                reason = "${op.uppercase()} on $table: ${sql.take(80)}",
            )
    }
    return op
}

/** Wire `lease.revoked` into the cache so the next call re-prompts. */
internal fun handleInbound(
    env: Envelope,
    leases: MutableMap<Pair<String, String>, LeaseEntry>,
) {
    if (env.type != "lease.revoked") return
    val lid = env.payloadMap()["lease_id"]?.toString() ?: return
    leases.entries.removeAll { it.value.leaseId.value == lid }
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val leases: MutableMap<Pair<String, String>, LeaseEntry> = mutableMapOf()

    coroutineScope {
        launch {
            client.events().collect { env -> handleInbound(env, leases) }
        }

        // Pre-grant the broad reads at session open.
        for (table in PRE_GRANTED) {
            leases[table to "read"] =
                requestLease(
                    client,
                    permission = "db.read",
                    table = table,
                    operation = "read",
                    seconds = READ_LEASE_SECONDS,
                    reason = "bootstrap",
                )
        }

        // SELECT — covered by the bootstrap lease.
        authorize(
            client,
            "SELECT count(*) FROM public.orders WHERE shipped_at::date = current_date - 1",
            leases,
        )
        // UPDATE — triggers permission.request; operator must approve.
        authorize(
            client,
            "UPDATE public.orders SET status='refunded' WHERE id=4812",
            leases,
        )
    }
    client.close()
}
