package dev.arcp.runtime

import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobListFilter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

class ListJobsHandlerTest :
    StringSpec({
        "filters out entries belonging to other principals" {
            val inventory = InMemoryJobInventory()
            inventory.record(entry("job_a"), "principal_a")
            inventory.record(entry("job_b"), "principal_b")
            val jobs = inventory.list("principal_a", MessageId("msg_x"), JobListFilter(), 100, null)
            jobs.jobs.map { it.jobId } shouldBe listOf(JobId("job_a"))
        }

        "respects limit and emits next cursor when more remain" {
            val inventory = InMemoryJobInventory()
            repeat(3) { inventory.record(entry("job_$it"), "principal") }
            val jobs = inventory.list("principal", MessageId("msg_x"), JobListFilter(), 2, null)
            jobs.jobs.shouldHaveSize(2)
            jobs.nextCursor shouldBe "2"
        }

        "second page cursor returns subsequent slice" {
            val inventory = InMemoryJobInventory()
            repeat(3) { inventory.record(entry("job_$it"), "principal") }
            val first = inventory.list("principal", MessageId("msg_x"), JobListFilter(), 2, null)
            val second = inventory.list(
                "principal",
                MessageId("msg_y"),
                JobListFilter(),
                2,
                first.nextCursor,
            )
            second.jobs.map { it.jobId } shouldBe listOf(JobId("job_2"))
        }

        "filter status narrows results" {
            val inventory = InMemoryJobInventory()
            inventory.record(entry("job_a", status = "running"), "principal")
            inventory.record(entry("job_b", status = "completed"), "principal")
            val jobs =
                inventory.list(
                    "principal",
                    MessageId("msg_x"),
                    JobListFilter(status = listOf("running")),
                    100,
                    null,
                )
            jobs.jobs.map { it.jobId } shouldBe listOf(JobId("job_a"))
        }

        "filter agent matches exact agent name" {
            val inventory = InMemoryJobInventory()
            inventory.record(entry("job_a", agent = "a@1"), "principal")
            inventory.record(entry("job_b", agent = "b@1"), "principal")
            val jobs =
                inventory.list(
                    "principal",
                    MessageId("msg_x"),
                    JobListFilter(agent = "a@1"),
                    100,
                    null,
                )
            jobs.jobs.map { it.jobId } shouldBe listOf(JobId("job_a"))
        }
    })

private fun entry(
    id: String,
    status: String = "accepted",
    agent: String = "agent@1",
): JobListEntry = JobListEntry(
    jobId = JobId(id),
    agent = agent,
    status = status,
    createdAt = Instant.parse("2026-05-09T12:00:00Z"),
)
