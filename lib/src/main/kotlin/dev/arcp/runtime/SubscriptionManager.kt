package dev.arcp.runtime

import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.SubscriptionId
import dev.arcp.json.arcpJson
import dev.arcp.messages.EventEmit
import dev.arcp.messages.SubscribeEvent
import dev.arcp.messages.SubscriptionFilter
import dev.arcp.store.EventLog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger {}

/**
 * Routes events to observer subscriptions (RFC §13).
 *
 * Subscriptions combine an optional backfill from an [EventLog] with a live
 * tail from an in-memory [MutableSharedFlow]. The boundary between historical
 * and live is marked with a synthetic `subscription.backfill_complete`
 * `event.emit` envelope, per RFC §13.3.
 */
public class SubscriptionManager(
    private val eventLog: EventLog? = null,
    /**
     * Replay buffer for the live-tail [MutableSharedFlow]. New subscribers see
     * up to this many recent events on attach, smoothing the backfill→live
     * handoff for short reconnect windows.
     */
    private val replayBuffer: Int = DEFAULT_REPLAY_BUFFER,
) {
    private val live: MutableSharedFlow<Envelope> =
        MutableSharedFlow(
            replay = replayBuffer,
            extraBufferCapacity = DEFAULT_EXTRA_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Hot view of envelopes currently flowing through the runtime. */
    public val liveEvents: Flow<Envelope> = live.asSharedFlow()

    /** Publishes [envelope] to all live subscribers. */
    public suspend fun publish(envelope: Envelope) {
        live.emit(envelope)
    }

    /**
     * Compiles [filter] into a typed predicate (RFC §13.2). The predicate is
     * evaluated for every produced envelope; subscribers receive only matches.
     *
     * Authorization is not modeled here — callers (the runtime) should reject
     * filters that would expose sessions the subscriber cannot observe with
     * [ARCPException.PermissionDenied] before invoking [open].
     */
    public fun compile(filter: SubscriptionFilter): (Envelope) -> Boolean {
        val compiled = CompiledSubscriptionFilter.from(filter)
        return compiled::matches
    }

    /**
     * Opens a subscription identified by [subscriptionId] under [filter].
     *
     * If [afterMessageId] is non-null and an [EventLog] is configured, the
     * returned flow first replays matching envelopes from the log, then emits a
     * synthetic `subscription.backfill_complete` `event.emit` envelope, then
     * continues with the live tail. With no backfill the flow goes straight
     * to live.
     */
    public fun open(
        subscriptionId: SubscriptionId,
        filter: SubscriptionFilter,
        afterMessageId: dev.arcp.ids.MessageId? = null,
    ): Flow<Envelope> {
        val matches = compile(filter)
        val replay = afterMessageId
        return flow {
            val sessionId = filter.sessionId.firstOrNull()
            if (replay != null && eventLog != null && sessionId != null) {
                eventLog
                    .replay(sessionId, replay)
                    .filter(matches)
                    .collect { emit(it) }
                emit(backfillCompleteEnvelope(subscriptionId))
            }
            live.asSharedFlow().filter(matches).collect { emit(it) }
        }
    }

    private fun backfillCompleteEnvelope(subscriptionId: SubscriptionId): Envelope = Envelope(
        id =
            dev.arcp.ids.MessageId
                .random(),
        subscriptionId = subscriptionId,
        payload =
            EventEmit(
                eventType = "subscription.backfill_complete",
                data = JsonObject(emptyMap()),
            ),
    )

    /**
     * Wraps [event] in a `subscribe.event` envelope so a subscription channel
     * can deliver it on the wire (RFC §13.1).
     */
    public fun toSubscribeEvent(
        event: Envelope,
        subscriptionId: SubscriptionId,
    ): Envelope {
        val encoded =
            try {
                arcpJson.encodeToString(Envelope.serializer(), event)
            } catch (e: Exception) {
                log.warn(e) { "failed to encode event for subscription delivery" }
                throw ARCPException.Internal("subscription delivery encode failed: ${e.message}", e)
            }
        val obj = arcpJson.parseToJsonElement(encoded).jsonObject
        return Envelope(
            id =
                dev.arcp.ids.MessageId
                    .random(),
            subscriptionId = subscriptionId,
            payload = SubscribeEvent(event = obj),
        )
    }

    public companion object {
        /** Default number of recent events new subscribers see on attach. */
        public const val DEFAULT_REPLAY_BUFFER: Int = 16

        /** Default extra capacity for the live channel. */
        public const val DEFAULT_EXTRA_BUFFER: Int = 256
    }
}
