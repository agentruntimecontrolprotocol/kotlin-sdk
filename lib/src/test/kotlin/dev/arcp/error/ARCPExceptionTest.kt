package dev.arcp.error

import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

class ARCPExceptionTest :
    StringSpec({
        "every code maps to a concrete subclass with matching code" {
            ARCPException.Cancelled().code shouldBe ErrorCode.CANCELLED
            ARCPException.InvalidArgument("bad").code shouldBe ErrorCode.INVALID_ARGUMENT
            ARCPException.NotFound("missing").code shouldBe ErrorCode.NOT_FOUND
            ARCPException.AlreadyExists("dupe").code shouldBe ErrorCode.ALREADY_EXISTS
            ARCPException.FailedPrecondition("bad state").code shouldBe
                ErrorCode.FAILED_PRECONDITION
            ARCPException.Aborted().code shouldBe ErrorCode.ABORTED
            ARCPException.OutOfRange("oob").code shouldBe ErrorCode.OUT_OF_RANGE
            ARCPException.Unimplemented("§9", "no durable").code shouldBe ErrorCode.UNIMPLEMENTED
            ARCPException.Internal("oops").code shouldBe ErrorCode.INTERNAL
            ARCPException.Unavailable("retry").code shouldBe ErrorCode.UNAVAILABLE
            ARCPException.DataLoss("retention").code shouldBe ErrorCode.DATA_LOSS
            ARCPException.Unauthenticated("nope").code shouldBe ErrorCode.UNAUTHENTICATED
            ARCPException.HeartbeatLost(missedDeadlines = 2).code shouldBe ErrorCode.HEARTBEAT_LOST
            ARCPException
                .LeaseExpired(LeaseId("lse_x"), Instant.parse("2026-05-09T12:00:00Z"))
                .code shouldBe ErrorCode.LEASE_EXPIRED
            ARCPException
                .LeaseRevoked(LeaseId("lse_y"), "policy")
                .code shouldBe ErrorCode.LEASE_REVOKED
            ARCPException.BackpressureOverflow("flooded").code shouldBe
                ErrorCode.BACKPRESSURE_OVERFLOW
            ARCPException
                .ResourceExhausted("over", retryAfterSeconds = 30)
                .code shouldBe ErrorCode.RESOURCE_EXHAUSTED
            ARCPException.BudgetExhausted("USD").code shouldBe ErrorCode.BUDGET_EXHAUSTED
            ARCPException.AgentVersionNotAvailable("agent", "1.0.0").code shouldBe
                ErrorCode.AGENT_VERSION_NOT_AVAILABLE
            ARCPException.LeaseSubsetViolation("model.use").code shouldBe
                ErrorCode.LEASE_SUBSET_VIOLATION
        }

        "PermissionDenied carries permission and resource fields" {
            val ex =
                ARCPException.PermissionDenied(
                    PermissionName("payment.refund.create"),
                    "ord_4812",
                )
            ex.permission.value shouldBe "payment.refund.create"
            ex.resource shouldBe "ord_4812"
            ex.message!!.shouldContain("permission denied")
        }

        "Unimplemented preserves section reference" {
            val ex = ARCPException.Unimplemented("§10.6", "scheduled jobs")
            ex.section shouldBe "§10.6"
            ex.detail shouldBe "scheduled jobs"
            ex.message!!.shouldContain("§10.6")
        }

        "cause chains are preserved" {
            val cause = RuntimeException("upstream")
            val ex = ARCPException.Internal("wrapper", cause)
            ex.cause shouldBe cause
        }

        "hierarchy is sealed under ARCPException" {
            val ex: ARCPException = ARCPException.Cancelled()
            ex.shouldBeInstanceOf<ARCPException>()
        }

        "retryable defaults to ErrorCode.retryableByDefault" {
            ARCPException.Unavailable("x").retryable shouldBe true
            ARCPException.PermissionDenied(PermissionName("p"), "r").retryable shouldBe false
            ARCPException.BudgetExhausted("USD").retryable shouldBe false
        }
    })
