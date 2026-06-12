package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobListFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

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
            runTest {
                val inventory = InMemoryJobInventory()
                repeat(3) { inventory.record(entry("job_$it"), "principal") }
                val jobs = inventory.list("principal", MessageId("msg_x"), JobListFilter(), 2, null)
                jobs.jobs.shouldHaveSize(2)
                jobs.nextCursor.shouldNotBeNull()
            }
        }

        "second page cursor returns subsequent slice" {
            runTest {
                val inventory = InMemoryJobInventory()
                repeat(3) { inventory.record(entry("job_$it"), "principal") }
                val first =
                    inventory.list("principal", MessageId("msg_x"), JobListFilter(), 2, null)
                val second = inventory.list(
                    "principal",
                    MessageId("msg_y"),
                    JobListFilter(),
                    2,
                    first.nextCursor,
                )
                second.jobs.map { it.jobId } shouldBe listOf(JobId("job_2"))
            }
        }

        "concurrent insert between pages does not skip or duplicate (#59)" {
            runTest {
                val inventory = InMemoryJobInventory()
                // Three jobs at distinct timestamps so ordering is unambiguous.
                inventory.record(entry("job_0", createdAt = "2026-05-09T12:00:00Z"), "principal")
                inventory.record(entry("job_1", createdAt = "2026-05-09T12:00:01Z"), "principal")
                inventory.record(entry("job_2", createdAt = "2026-05-09T12:00:02Z"), "principal")
                val first =
                    inventory.list("principal", MessageId("msg_x"), JobListFilter(), 2, null)
                // A new job is recorded that sorts BEFORE the last entry of page 1.
                inventory.record(
                    entry("job_inserted", createdAt = "2026-05-09T12:00:00.500Z"),
                    "principal",
                )
                val second = inventory.list(
                    "principal",
                    MessageId("msg_y"),
                    JobListFilter(),
                    2,
                    first.nextCursor,
                )
                // With an opaque sort-key cursor the second page returns exactly
                // the entries that sort strictly after the cursor — never the
                // newly inserted job (it sorts before the cursor) and never the
                // last entry of page 1 (the cursor is past it).
                second.jobs.map { it.jobId } shouldBe listOf(JobId("job_2"))
            }
        }

        "malformed cursor raises InvalidArgument (#59)" {
            runTest {
                val inventory = InMemoryJobInventory()
                inventory.record(entry("job_0"), "principal")
                shouldThrow<ARCPException.InvalidArgument> {
                    inventory.list(
                        "principal",
                        MessageId("msg_x"),
                        JobListFilter(),
                        100,
                        "not-base64!@#",
                    )
                }
            }
        }

        "evict drops the record (#60)" {
            runTest {
                val inventory = InMemoryJobInventory()
                inventory.record(entry("job_a"), "principal")
                inventory.size shouldBe 1
                inventory.evict(JobId("job_a")) shouldBe true
                inventory.size shouldBe 0
                val jobs =
                    inventory.list("principal", MessageId("msg_x"), JobListFilter(), 100, null)
                jobs.jobs.shouldHaveSize(0)
            }
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
    createdAt: String = "2026-05-09T12:00:00Z",
): JobListEntry = JobListEntry(
    jobId = JobId(id),
    agent = agent,
    status = status,
    createdAt = Instant.parse(createdAt),
)
