package dev.arcp.messages

import dev.arcp.ids.LeaseId
import dev.arcp.ids.PermissionName
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `permission.request` — runtime asks for a capability grant (RFC §15.4). */
@Serializable
@SerialName("permission.request")
public data class PermissionRequest(
    /** Permission name (`fs.read`, `tool.call`, ...). */
    val permission: PermissionName,
    /** Resource the permission applies to. */
    val resource: String,
    /** Optional operation hint (e.g. `read`, `write`, `delete`). */
    val operation: String? = null,
    /** Optional human-readable rationale shown to a reviewer. */
    val reason: String? = null,
    /** Desired lease duration; the grantor may shorten or override. */
    @SerialName("requested_lease_seconds")
    val requestedLeaseSeconds: Long? = null,
) : MessageType

/** `permission.grant` — caller granted the request (RFC §15.4). */
@Serializable
@SerialName("permission.grant")
public data class PermissionGrant(
    /** Permission being granted. */
    val permission: PermissionName,
    /** Resource the grant applies to. */
    val resource: String,
    /** Actual lease duration; null means "as requested". */
    @SerialName("lease_seconds")
    val leaseSeconds: Long? = null,
) : MessageType

/** `permission.deny` — caller refused the request (RFC §15.4). */
@Serializable
@SerialName("permission.deny")
public data class PermissionDeny(
    /** Permission denied. */
    val permission: PermissionName,
    /** Resource denied. */
    val resource: String,
    /** Optional reason surfaced to the requester. */
    val reason: String? = null,
) : MessageType

/** `lease.granted` — materialized lease (RFC §15.5). */
@Serializable
@SerialName("lease.granted")
public data class LeaseGranted(
    /** Server-assigned lease id. */
    @SerialName("lease_id")
    val leaseId: LeaseId,
    /** Permission the lease grants. */
    val permission: PermissionName,
    /** Resource the lease applies to. */
    val resource: String,
    /** Optional operation hint (`read`, `write`, ...). */
    val operation: String? = null,
    /** Absolute expiry timestamp. */
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `lease.refresh` — holder requests extension (RFC §15.5). */
@Serializable
@SerialName("lease.refresh")
public data class LeaseRefresh(
    /** Lease to extend. */
    @SerialName("lease_id")
    val leaseId: LeaseId,
    /** Desired additional duration; the grantor may shorten. */
    @SerialName("requested_extension_seconds")
    val requestedExtensionSeconds: Long? = null,
) : MessageType

/** `lease.extended` — successful refresh (RFC §15.5). */
@Serializable
@SerialName("lease.extended")
public data class LeaseExtended(
    /** Lease that was extended. */
    @SerialName("lease_id")
    val leaseId: LeaseId,
    /** New absolute expiry. */
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `lease.revoked` — grantor revoked before expiry (RFC §15.5). */
@Serializable
@SerialName("lease.revoked")
public data class LeaseRevoked(
    /** Lease that was revoked. */
    @SerialName("lease_id")
    val leaseId: LeaseId,
    /** Reason surfaced to the holder. */
    val reason: String,
) : MessageType
