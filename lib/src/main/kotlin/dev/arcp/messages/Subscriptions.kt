package dev.arcp.messages

import dev.arcp.envelope.Priority
import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import dev.arcp.ids.SubscriptionId
import dev.arcp.ids.TraceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Filter expression for [Subscribe] (RFC §13.2). */
@Serializable
public data class SubscriptionFilter(
    @SerialName("session_id")
    val sessionId: List<SessionId> = emptyList(),
    @SerialName("trace_id")
    val traceId: List<TraceId> = emptyList(),
    @SerialName("job_id")
    val jobId: List<JobId> = emptyList(),
    @SerialName("stream_id")
    val streamId: List<StreamId> = emptyList(),
    val types: List<String> = emptyList(),
    @SerialName("min_priority")
    val minPriority: Priority? = null,
)

/** Backfill cursor (RFC §13.3). */
@Serializable
public data class SubscriptionSince(
    @SerialName("after_message_id")
    val afterMessageId: MessageId? = null,
)

/** `subscribe` (RFC §13.1). */
@Serializable
@SerialName("subscribe")
public data class Subscribe(
    val filter: SubscriptionFilter,
    val since: SubscriptionSince? = null,
) : MessageType

/** `subscribe.accepted` (RFC §13.1). */
@Serializable
@SerialName("subscribe.accepted")
public data class SubscribeAccepted(
    @SerialName("subscription_id")
    val subscriptionId: SubscriptionId,
) : MessageType

/** `subscribe.event` — wraps the original event (RFC §13.1). */
@Serializable
@SerialName("subscribe.event")
public data class SubscribeEvent(
    /**
     * Original envelope, encoded as a JSON object so the subscriber can
     * inspect any field. This intentionally matches the RFC `payload.event`
     * shape — the inner envelope is a full ARCP envelope.
     */
    val event: kotlinx.serialization.json.JsonObject,
) : MessageType

/** `unsubscribe` (RFC §13.4). */
@Serializable
@SerialName("unsubscribe")
public data class Unsubscribe(
    @SerialName("subscription_id")
    val subscriptionId: SubscriptionId,
) : MessageType

/** `subscribe.closed` — runtime-initiated termination (RFC §13.4). */
@Serializable
@SerialName("subscribe.closed")
public data class SubscribeClosed(
    @SerialName("subscription_id")
    val subscriptionId: SubscriptionId,
    val code: ErrorCode,
    val reason: String,
) : MessageType
