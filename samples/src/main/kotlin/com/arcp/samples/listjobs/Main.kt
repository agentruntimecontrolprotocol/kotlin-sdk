package com.arcp.samples.listjobs

import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobListFilter
import dev.arcp.runtime.InMemoryJobInventory
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

public fun main(): Unit = runBlocking {
    val inventory = InMemoryJobInventory()
    inventory.record(
        JobListEntry(
            jobId = JobId("job_demo"),
            agent = "code-refactor@2.0.0",
            status = "running",
            createdAt = Clock.System.now(),
        ),
        ownerPrincipal = "demo@example",
    )

    val jobs = inventory.list(
        "demo@example",
        MessageId("msg_list"),
        JobListFilter(),
        limit = 100,
        cursor = null,
    )
    println("visible jobs: ${jobs.jobs.joinToString { it.jobId.value }}")
}
