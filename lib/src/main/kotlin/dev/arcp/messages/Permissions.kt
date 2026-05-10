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
    val permission: PermissionName,
    val resource: String,
    val operation: String? = null,
    val reason: String? = null,
    @SerialName("requested_lease_seconds")
    val requestedLeaseSeconds: Long? = null,
) : MessageType

/** `permission.grant` — caller granted the request (RFC §15.4). */
@Serializable
@SerialName("permission.grant")
public data class PermissionGrant(
    val permission: PermissionName,
    val resource: String,
    @SerialName("lease_seconds")
    val leaseSeconds: Long? = null,
) : MessageType

/** `permission.deny` — caller refused the request (RFC §15.4). */
@Serializable
@SerialName("permission.deny")
public data class PermissionDeny(
    val permission: PermissionName,
    val resource: String,
    val reason: String? = null,
) : MessageType

/** `lease.granted` — materialized lease (RFC §15.5). */
@Serializable
@SerialName("lease.granted")
public data class LeaseGranted(
    @SerialName("lease_id")
    val leaseId: LeaseId,
    val permission: PermissionName,
    val resource: String,
    val operation: String? = null,
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `lease.refresh` — holder requests extension (RFC §15.5). */
@Serializable
@SerialName("lease.refresh")
public data class LeaseRefresh(
    @SerialName("lease_id")
    val leaseId: LeaseId,
    @SerialName("requested_extension_seconds")
    val requestedExtensionSeconds: Long? = null,
) : MessageType

/** `lease.extended` — successful refresh (RFC §15.5). */
@Serializable
@SerialName("lease.extended")
public data class LeaseExtended(
    @SerialName("lease_id")
    val leaseId: LeaseId,
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `lease.revoked` — grantor revoked before expiry (RFC §15.5). */
@Serializable
@SerialName("lease.revoked")
public data class LeaseRevoked(
    @SerialName("lease_id")
    val leaseId: LeaseId,
    val reason: String,
) : MessageType
