@file:Suppress("CyclomaticComplexMethod", "LongParameterList")

package dev.arcp.runtime

import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobListFilter
import dev.arcp.messages.SessionJobs
import java.util.concurrent.ConcurrentHashMap

/** Principal-scoped job inventory backing `session.list_jobs`. */
public interface JobInventory {
    /** Lists jobs visible to [principal]. */
    public suspend fun list(
        principal: String,
        requestId: MessageId,
        filter: JobListFilter,
        limit: Int,
        cursor: String?,
    ): SessionJobs

    /** Records a job owned by [ownerPrincipal]. */
    public fun record(
        entry: JobListEntry,
        ownerPrincipal: String,
    )

    /** Updates the stored lifecycle status for [jobId]. */
    public fun updateStatus(
        jobId: JobId,
        status: String,
    )
}

/** In-memory inventory with stable index cursors. */
public class InMemoryJobInventory : JobInventory {
    private val records: ConcurrentHashMap<JobId, JobRecord> = ConcurrentHashMap()

    override suspend fun list(
        principal: String,
        requestId: MessageId,
        filter: JobListFilter,
        limit: Int,
        cursor: String?,
    ): SessionJobs {
        val start = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val boundedLimit = limit.coerceIn(1, SessionListLimit.MAX)
        val visible =
            records.values
                .filter { it.ownerPrincipal == principal }
                .map { it.entry }
                .filter { entry -> filter.matches(entry) }
                .sortedWith(compareBy<JobListEntry> { it.createdAt }.thenBy { it.jobId.value })
        val page = visible.drop(start).take(boundedLimit)
        val next = (start + page.size).takeIf { it < visible.size }?.toString()
        return SessionJobs(requestId = requestId, jobs = page, nextCursor = next)
    }

    override fun record(
        entry: JobListEntry,
        ownerPrincipal: String,
    ) {
        records[entry.jobId] = JobRecord(entry, ownerPrincipal)
    }

    override fun updateStatus(
        jobId: JobId,
        status: String,
    ) {
        records.computeIfPresent(jobId) { _, record ->
            record.copy(
                entry = record.entry.copy(status = status, credentials = record.entry.credentials),
            )
        }
    }

    private fun JobListFilter.matches(entry: JobListEntry): Boolean =
        (status.isEmpty() || entry.status in status) &&
            (agent == null || entry.agent == agent) &&
            (createdAfter?.let { entry.createdAt > it } ?: true) &&
            (createdBefore?.let { entry.createdAt < it } ?: true)

    private data class JobRecord(
        val entry: JobListEntry,
        val ownerPrincipal: String,
    )
}

private object SessionListLimit {
    const val MAX: Int = 1_000
}
