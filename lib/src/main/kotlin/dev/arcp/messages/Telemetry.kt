package dev.arcp.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Standard log levels (RFC §17.2). */
@Serializable
public enum class LogLevel {
    @SerialName("trace")
    TRACE,

    @SerialName("debug")
    DEBUG,

    @SerialName("info")
    INFO,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR,

    @SerialName("critical")
    CRITICAL,
}

/** `event.emit` — generic structured event (RFC §6.2). */
@Serializable
@SerialName("event.emit")
public data class EventEmit(
    /** Application-defined event name; namespaced per RFC §21 when vendor-specific. */
    @SerialName("event_type")
    val eventType: String,
    /** Free-form structured payload. */
    val data: JsonObject = JsonObject(emptyMap()),
) : MessageType

/** `log` — structured log line (RFC §17.2). */
@Serializable
@SerialName("log")
public data class Log(
    /** Severity level. */
    val level: LogLevel,
    /** Human-readable message. */
    val message: String,
    /** Structured attributes (key/value bag). */
    val attributes: JsonObject = JsonObject(emptyMap()),
) : MessageType

/**
 * `metric` — telemetry sample (RFC §17.3).
 *
 * @property name Metric name; use [StandardMetrics] constants where possible
 *   and namespace per RFC §21 otherwise.
 * @property value Numeric or string value. The wire form is a JSON primitive
 *   so non-numeric metrics (e.g. error codes) round-trip without coercion.
 * @property unit Unit string ("USD", "ms", "tokens", ...). Non-nullable on
 *   the wire — emitters must always set it.
 * @property dims Optional dimension/label map, e.g. `{"phase": "exec"}`.
 */
@Serializable
@SerialName("metric")
public data class Metric(
    val name: String,
    val value: JsonPrimitive,
    val unit: String,
    val dims: JsonObject = JsonObject(emptyMap()),
) : MessageType

/** `trace.span` — span boundary marker (RFC §17.1). */
@Serializable
@SerialName("trace.span")
public data class TraceSpan(
    /** Span name. */
    val name: String,
    /** Optional span kind (`client`, `server`, `internal`, ...). */
    val kind: String? = null,
    /** Wall-clock start time. */
    @SerialName("started_at")
    val startedAt: Instant,
    /** Wall-clock end time, when the span has closed. */
    @SerialName("ended_at")
    val endedAt: Instant? = null,
    /** Structured span attributes. */
    val attributes: JsonObject = JsonObject(emptyMap()),
    /** Optional list of span events; opaque JSON to the SDK. */
    val events: JsonElement? = null,
) : MessageType

/**
 * Reserved metric names from RFC §17.3.1. Runtimes producing these concepts
 * MUST use these names with the indicated units; non-standard variants MUST
 * be namespaced.
 */
public object StandardMetrics {
    /** `tokens.used` — `dims.kind ∈ input, output, cache_read, cache_write`. */
    public const val TOKENS_USED: String = "tokens.used"

    /** `cost.usd` — decimal USD with up to 6 fractional digits. */
    public const val COST_USD: String = "cost.usd"

    /** `cost.budget.remaining` — remaining budget for a cost currency. */
    public const val COST_BUDGET_REMAINING: String = "cost.budget.remaining"

    /** `gpu.seconds` — wall-clock GPU time, summed across devices. */
    public const val GPU_SECONDS: String = "gpu.seconds"

    /** `tool.invocations` — one per `tool.invoke`. */
    public const val TOOL_INVOCATIONS: String = "tool.invocations"

    /** `latency.ms` — `dims.phase ∈ queue, exec, total`. */
    public const val LATENCY_MS: String = "latency.ms"

    /** `bytes.in` — bytes received at runtime boundary. */
    public const val BYTES_IN: String = "bytes.in"

    /** `bytes.out` — bytes sent at runtime boundary. */
    public const val BYTES_OUT: String = "bytes.out"

    /** `errors.total` — `dims.code` carries the canonical error code. */
    public const val ERRORS_TOTAL: String = "errors.total"
}
