package dev.arcp.error

import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import kotlinx.datetime.Instant

/**
 * Base type for every error that originates inside the ARCP runtime or client.
 *
 * Every concrete subclass corresponds 1:1 to a canonical [ErrorCode] (RFC §18.2)
 * and carries typed context relevant to that code. Library boundaries throw
 * only [ARCPException] subclasses (or `IllegalArgumentException` for caller bugs).
 */
public sealed class ARCPException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Canonical error code carried on the wire (RFC §18.1). */
    public abstract val code: ErrorCode

    /** Whether retrying the operation is sensible by default (RFC §18.3). */
    public open val retryable: Boolean get() = code.retryableByDefault

    public class Cancelled(
        message: String = "operation cancelled",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.CANCELLED
    }

    public class Unknown(
        message: String = "unknown error",
        cause: Throwable? = null,
    ) : ARCPException(message, cause) {
        override val code: ErrorCode = ErrorCode.UNKNOWN
    }

    public class InvalidArgument(
        message: String,
        public val argument: String? = null,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.INVALID_ARGUMENT
    }

    public class DeadlineExceeded(
        message: String,
        public val deadline: Instant? = null,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.DEADLINE_EXCEEDED
    }

    public class NotFound(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.NOT_FOUND
    }

    public class AlreadyExists(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.ALREADY_EXISTS
    }

    public class PermissionDenied(
        public val permission: PermissionName,
        public val resource: String,
        message: String = "permission denied: $permission on $resource",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.PERMISSION_DENIED
    }

    public class ResourceExhausted(
        message: String,
        public val retryAfterSeconds: Long? = null,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.RESOURCE_EXHAUSTED
    }

    public class FailedPrecondition(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.FAILED_PRECONDITION
    }

    public class Aborted(
        message: String = "operation aborted",
        cause: Throwable? = null,
    ) : ARCPException(message, cause) {
        override val code: ErrorCode = ErrorCode.ABORTED
    }

    public class OutOfRange(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.OUT_OF_RANGE
    }

    public class Unimplemented(
        public val section: String,
        public val detail: String,
    ) : ARCPException("unimplemented (RFC §$section): $detail") {
        override val code: ErrorCode = ErrorCode.UNIMPLEMENTED
    }

    public class Internal(
        message: String,
        cause: Throwable? = null,
    ) : ARCPException(message, cause) {
        override val code: ErrorCode = ErrorCode.INTERNAL
    }

    public class Unavailable(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.UNAVAILABLE
    }

    public class DataLoss(
        message: String,
        cause: Throwable? = null,
    ) : ARCPException(message, cause) {
        override val code: ErrorCode = ErrorCode.DATA_LOSS
    }

    public class Unauthenticated(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.UNAUTHENTICATED
    }

    public class HeartbeatLost(
        public val missedDeadlines: Int,
        message: String = "missed $missedDeadlines consecutive heartbeats",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.HEARTBEAT_LOST
    }

    public class LeaseExpired(
        public val leaseId: LeaseId,
        public val expiredAt: Instant,
    ) : ARCPException("lease ${leaseId.value} expired at $expiredAt") {
        override val code: ErrorCode = ErrorCode.LEASE_EXPIRED
    }

    public class LeaseRevoked(
        public val leaseId: LeaseId,
        public val reason: String,
    ) : ARCPException("lease ${leaseId.value} revoked: $reason") {
        override val code: ErrorCode = ErrorCode.LEASE_REVOKED
    }

    public class LeaseSubsetViolation(
        public val capability: String,
        message: String = "lease subset violation in $capability",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.LEASE_SUBSET_VIOLATION
    }

    public class BudgetExhausted(
        public val currency: String,
        public val jobId: dev.arcp.ids.JobId? = null,
        message: String = "budget exhausted for $currency",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.BUDGET_EXHAUSTED
        override val retryable: Boolean get() = false
    }

    public class AgentVersionNotAvailable(
        public val agent: String,
        public val version: String,
        message: String = "agent version $agent@$version is not available",
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.AGENT_VERSION_NOT_AVAILABLE
    }

    public class BackpressureOverflow(
        message: String,
    ) : ARCPException(message) {
        override val code: ErrorCode = ErrorCode.BACKPRESSURE_OVERFLOW
    }
}
