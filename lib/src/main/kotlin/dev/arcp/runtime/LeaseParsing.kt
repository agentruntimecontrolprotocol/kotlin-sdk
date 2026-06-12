package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import dev.arcp.messages.JobListLease
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** Parses the `cost.budget` lease patterns, or `null` when absent. */
internal fun parseCostBudget(leaseRequest: JsonObject): CostBudget? {
    val values = leaseRequest.stringArray("cost.budget")
    return values
        .takeIf { it.isNotEmpty() }
        ?.map(BudgetAmount::parse)
        ?.let(::CostBudget)
}

/** Parses the `model.use` lease patterns, or `null` when absent. */
internal fun parseModelUse(leaseRequest: JsonObject): ModelUseLease? {
    val values = leaseRequest.stringArray("model.use")
    return values.takeIf { it.isNotEmpty() }?.let(::ModelUseLease)
}

/**
 * Parses `lease_constraints.expires_at` (§9.5): ISO 8601, UTC (`Z` suffix),
 * and strictly in the future. Offsets and past/invalid values are
 * INVALID_REQUEST.
 */
internal fun parseExpiresAt(leaseConstraints: JsonObject?): Instant? {
    val raw =
        leaseConstraints
            ?.get("expires_at")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null
    if (!raw.endsWith("Z")) {
        throw ARCPException.InvalidArgument(
            "expires_at must be UTC with a 'Z' suffix",
            "expires_at",
        )
    }
    return parseFutureInstant(raw)
}

private fun parseFutureInstant(raw: String): Instant {
    val parsed =
        try {
            Instant.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw ARCPException.InvalidArgument(
                "expires_at is not a valid ISO 8601 timestamp: ${e.message}",
                "expires_at",
            )
        }
    if (parsed <= Clock.System.now()) {
        throw ARCPException.InvalidArgument(
            "expires_at must be in the future",
            "expires_at",
        )
    }
    return parsed
}

/** Renders the effective lease summary echoed on `job.accepted` (§7.1). */
internal fun leaseSummary(
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

internal fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)
    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
    .orEmpty()

internal fun JsonPrimitive.asBigDecimal(): BigDecimal {
    contentOrNull?.toBigDecimalOrNull()?.let { return it }
    throw ARCPException.InvalidArgument("metric value must be numeric", "value")
}
