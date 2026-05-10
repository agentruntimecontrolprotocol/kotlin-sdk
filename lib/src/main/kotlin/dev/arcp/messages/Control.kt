package dev.arcp.messages

import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `ping` — liveness probe (RFC §6.2 Control). Receivers reply with [Pong].
 */
@Serializable
@SerialName("ping")
public data class Ping(
    val nonce: String? = null,
) : MessageType

/** `pong` — reply to [Ping]. */
@Serializable
@SerialName("pong")
public data class Pong(
    val nonce: String? = null,
) : MessageType

/**
 * `ack` — generic positive acknowledgement of a command (RFC §6.3).
 */
@Serializable
@SerialName("ack")
public data class Ack(
    @SerialName("ack_for")
    val ackFor: MessageId,
) : MessageType

/**
 * `nack` — negative acknowledgement carrying a structured error (RFC §6.3).
 */
@Serializable
@SerialName("nack")
public data class Nack(
    @SerialName("nack_for")
    val nackFor: MessageId,
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean? = null,
    val details: JsonElement? = null,
) : MessageType

/** Target kind referenced by [Cancel] and [Interrupt] (RFC §10.4 / §10.5). */
@Serializable
public enum class CancelTarget {
    @SerialName("job")
    JOB,

    @SerialName("stream")
    STREAM,

    @SerialName("session")
    SESSION,
}

/** `cancel` — cooperative cancellation request (RFC §10.4). */
@Serializable
@SerialName("cancel")
public data class Cancel(
    val target: CancelTarget,
    @SerialName("target_id")
    val targetId: String,
    val reason: String? = null,
    @SerialName("deadline_ms")
    val deadlineMs: Long? = null,
) : MessageType

/** `cancel.accepted` — runtime acknowledged cancellation (RFC §10.4). */
@Serializable
@SerialName("cancel.accepted")
public data class CancelAccepted(
    @SerialName("target_id")
    val targetId: String,
) : MessageType

/** `cancel.refused` — runtime refused cancellation (RFC §10.4). */
@Serializable
@SerialName("cancel.refused")
public data class CancelRefused(
    @SerialName("target_id")
    val targetId: String,
    val reason: String,
) : MessageType

/** `interrupt` — pause-and-ask, distinct from cancellation (RFC §10.5). */
@Serializable
@SerialName("interrupt")
public data class Interrupt(
    val target: CancelTarget,
    @SerialName("target_id")
    val targetId: String,
    val prompt: String,
) : MessageType

/** `resume` — replay session/job stream after [afterMessageId] (RFC §19). */
@Serializable
@SerialName("resume")
public data class Resume(
    @SerialName("session_id")
    val sessionId: SessionId,
    @SerialName("after_message_id")
    val afterMessageId: MessageId? = null,
    @SerialName("job_id")
    val jobId: JobId? = null,
    @SerialName("checkpoint_id")
    val checkpointId: String? = null,
    @SerialName("include_open_streams")
    val includeOpenStreams: Boolean = false,
) : MessageType

/** `backpressure` — slow-down signal for a stream (RFC §11.2). */
@Serializable
@SerialName("backpressure")
public data class Backpressure(
    @SerialName("stream_id")
    val streamId: StreamId,
    @SerialName("desired_rate_per_second")
    val desiredRatePerSecond: Int,
    @SerialName("buffer_remaining_bytes")
    val bufferRemainingBytes: Long? = null,
    val reason: String? = null,
) : MessageType

/** `checkpoint.create` — request a job to checkpoint at the next safe point. */
@Serializable
@SerialName("checkpoint.create")
public data class CheckpointCreate(
    @SerialName("job_id")
    val jobId: JobId,
    val label: String? = null,
) : MessageType

/** `checkpoint.restore` — request a job to restore from [checkpointId]. */
@Serializable
@SerialName("checkpoint.restore")
public data class CheckpointRestore(
    @SerialName("job_id")
    val jobId: JobId,
    @SerialName("checkpoint_id")
    val checkpointId: String,
    @SerialName("restored_at")
    val restoredAt: Instant? = null,
) : MessageType
