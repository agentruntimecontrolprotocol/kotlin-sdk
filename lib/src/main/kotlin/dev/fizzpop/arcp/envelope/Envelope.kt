package dev.fizzpop.arcp.envelope

import dev.fizzpop.arcp.Version
import dev.fizzpop.arcp.ids.JobId
import dev.fizzpop.arcp.ids.MessageId
import dev.fizzpop.arcp.ids.SessionId
import dev.fizzpop.arcp.ids.SpanId
import dev.fizzpop.arcp.ids.StreamId
import dev.fizzpop.arcp.ids.SubscriptionId
import dev.fizzpop.arcp.ids.TraceId
import dev.fizzpop.arcp.json.arcpJson
import dev.fizzpop.arcp.messages.MessageType
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Canonical ARCP message envelope (RFC §6.1).
 *
 * The wire format hoists the polymorphic [MessageType] discriminator to the
 * top-level `type` field while the rest of the payload sits under `payload`.
 * The custom [EnvelopeSerializer] performs this hoist.
 */
@Serializable(with = EnvelopeSerializer::class)
public data class Envelope(
    /** Protocol version understood by the sender (RFC §6.1.1, `arcp`). */
    val arcp: String = Version.PROTOCOL_VERSION,
    /** Globally unique message id (RFC §6.1.1, `id`). Acts as the transport idempotency key. */
    val id: MessageId,
    /** Sender timestamp in RFC 3339 format (RFC §6.1.1, `timestamp`). */
    val timestamp: Instant = Clock.System.now(),
    /** Logical sender id (RFC §6.1.1, `source`). */
    val source: String? = null,
    /** Logical recipient id (RFC §6.1.1, `target`). */
    val target: String? = null,
    /** Required once a session exists (RFC §6.1.1, `session_id`). */
    val sessionId: SessionId? = null,
    /** Required for durable job events (RFC §6.1.1, `job_id`). */
    val jobId: JobId? = null,
    /** Required for stream events (RFC §6.1.1, `stream_id`). */
    val streamId: StreamId? = null,
    /** Required for subscription delivery (RFC §6.1.1, `subscription_id`). */
    val subscriptionId: SubscriptionId? = null,
    /** Stable id for one user-visible request or workflow (RFC §6.1.1, `trace_id`). */
    val traceId: TraceId? = null,
    /** Span id for the current operation (RFC §6.1.1, `span_id`). */
    val spanId: SpanId? = null,
    /** Parent span id when the message is part of a trace tree (RFC §6.1.1). */
    val parentSpanId: SpanId? = null,
    /** Id of the command this message answers (RFC §6.1.1, `correlation_id`). */
    val correlationId: MessageId? = null,
    /** Id of the message that directly caused this message (RFC §6.1.1, `causation_id`). */
    val causationId: MessageId? = null,
    /** Logical idempotency key for command intent (RFC §6.4, distinct from [id]). */
    val idempotencyKey: String? = null,
    /** Priority (RFC §6.5). Defaults to `normal`. */
    val priority: Priority = Priority.NORMAL,
    /** Namespaced extension fields (RFC §21). */
    val extensions: Map<String, JsonElement> = emptyMap(),
    /** Type-specific body validated by the message schema. */
    val payload: MessageType,
) {
    /** Returns the wire discriminator for [payload]. */
    public val type: String get() = wireTypeOf(payload)

    public companion object
}

/**
 * Discovers the wire `type` discriminator for [payload] using the registered
 * polymorphic serializer module on [arcpJson].
 */
private fun wireTypeOf(payload: MessageType): String {
    val element = arcpJson.encodeToJsonElement(MessageType.serializer(), payload).jsonObject
    return element["type"]?.jsonPrimitive?.contentOrNull
        ?: error("payload ${payload::class} has no @SerialName discriminator")
}

/**
 * Custom serializer that flattens the envelope wire format (RFC §6.1).
 *
 * Encode order: top-level scalars first, then `type`, then `payload`. Decode
 * tolerates fields appearing in any order; unknown fields are ignored.
 */
internal object EnvelopeSerializer : KSerializer<Envelope> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Envelope,
    ) {
        require(encoder is JsonEncoder) { "ARCP envelopes only encode through JSON" }
        val payloadObj =
            encoder.json.encodeToJsonElement(MessageType.serializer(), value.payload).jsonObject
        val wireType =
            payloadObj["type"]?.jsonPrimitive?.contentOrNull
                ?: error("payload ${value.payload::class} has no @SerialName discriminator")
        val payloadWithoutType = JsonObject(payloadObj.filterKeys { it != "type" })

        val out =
            buildJsonObject {
                put("arcp", value.arcp)
                put("id", value.id.value)
                put("type", wireType)
                put("timestamp", value.timestamp.toString())
                value.source?.let { put("source", it) }
                value.target?.let { put("target", it) }
                value.sessionId?.let { put("session_id", it.value) }
                value.jobId?.let { put("job_id", it.value) }
                value.streamId?.let { put("stream_id", it.value) }
                value.subscriptionId?.let { put("subscription_id", it.value) }
                value.traceId?.let { put("trace_id", it.value) }
                value.spanId?.let { put("span_id", it.value) }
                value.parentSpanId?.let { put("parent_span_id", it.value) }
                value.correlationId?.let { put("correlation_id", it.value) }
                value.causationId?.let { put("causation_id", it.value) }
                value.idempotencyKey?.let { put("idempotency_key", it) }
                if (value.priority != Priority.NORMAL) {
                    put("priority", value.priority.name.lowercase())
                }
                if (value.extensions.isNotEmpty()) {
                    put("extensions", JsonObject(value.extensions))
                }
                put("payload", payloadWithoutType)
            }

        encoder.encodeJsonElement(out)
    }

    override fun deserialize(decoder: Decoder): Envelope {
        require(decoder is JsonDecoder) { "ARCP envelopes only decode from JSON" }
        val obj = decoder.decodeJsonElement().jsonObject

        val arcp = obj.requireString("arcp")
        val id = MessageId(obj.requireString("id"))
        val type = obj.requireString("type")
        val timestamp = Instant.parse(obj.requireString("timestamp"))
        val payloadObj = (obj["payload"] as? JsonObject) ?: JsonObject(emptyMap())

        val payloadWithType =
            JsonObject(
                buildMap {
                    putAll(payloadObj)
                    put("type", JsonPrimitive(type))
                },
            )
        val payload =
            decoder.json.decodeFromJsonElement(MessageType.serializer(), payloadWithType)

        val priority =
            (obj["priority"] as? JsonPrimitive)?.contentOrNull?.let { p ->
                Priority.entries.firstOrNull { it.name.lowercase() == p } ?: Priority.NORMAL
            } ?: Priority.NORMAL

        val extensionsObj = obj["extensions"] as? JsonObject

        return Envelope(
            arcp = arcp,
            id = id,
            timestamp = timestamp,
            source = obj.optString("source"),
            target = obj.optString("target"),
            sessionId = obj.optString("session_id")?.let(::SessionId),
            jobId = obj.optString("job_id")?.let(::JobId),
            streamId = obj.optString("stream_id")?.let(::StreamId),
            subscriptionId = obj.optString("subscription_id")?.let(::SubscriptionId),
            traceId = obj.optString("trace_id")?.let(::TraceId),
            spanId = obj.optString("span_id")?.let(::SpanId),
            parentSpanId = obj.optString("parent_span_id")?.let(::SpanId),
            correlationId = obj.optString("correlation_id")?.let(::MessageId),
            causationId = obj.optString("causation_id")?.let(::MessageId),
            idempotencyKey = obj.optString("idempotency_key"),
            priority = priority,
            extensions = extensionsObj?.toMap() ?: emptyMap(),
            payload = payload,
        )
    }

    private fun JsonObject.requireString(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("envelope missing required field '$key'")

    private fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
