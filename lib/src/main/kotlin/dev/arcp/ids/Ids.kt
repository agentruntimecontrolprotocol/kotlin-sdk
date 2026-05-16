package dev.arcp.ids

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant

/**
 * Generates a Crockford-base32 ULID (Universally Unique Lexicographically
 * Sortable Identifier). Implements the public ULID specification
 * (https://github.com/ulid/spec) without an external dependency.
 *
 * 26 characters: 10 timestamp + 16 randomness, encoded in Crockford base32.
 */
public object Ulid {
    private const val ENCODING: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIMESTAMP_LEN: Int = 10
    private const val RANDOMNESS_LEN: Int = 16
    private val random: SecureRandom = SecureRandom()

    /** Returns a new lower-case ULID prefixed with [prefix] and an underscore. */
    public fun next(prefix: String): String = "${prefix}_${nextRaw().lowercase()}"

    /** Returns a new uppercase 26-char ULID without a prefix. */
    public fun nextRaw(): String {
        val now = Instant.now().toEpochMilli()
        val ts = encodeTimestamp(now)
        val rnd = encodeRandomness()
        return ts + rnd
    }

    private fun encodeTimestamp(millis: Long): String {
        require(millis >= 0) { "ulid timestamp must be non-negative: $millis" }
        val out = CharArray(TIMESTAMP_LEN)
        var t = millis
        for (i in TIMESTAMP_LEN - 1 downTo 0) {
            out[i] = ENCODING[(t and 0x1F).toInt()]
            t = t ushr 5
        }
        return String(out)
    }

    private fun encodeRandomness(): String {
        val out = CharArray(RANDOMNESS_LEN)
        val bytes = ByteArray(RANDOMNESS_LEN)
        random.nextBytes(bytes)
        for (i in 0 until RANDOMNESS_LEN) {
            out[i] = ENCODING[(bytes[i].toInt() and 0x1F)]
        }
        return String(out)
    }
}

private fun requireNonBlank(
    value: String,
    name: String,
) {
    require(value.isNotBlank()) { "$name must not be blank" }
}

/** Identifier of a single message envelope (RFC §6.1.1). */
@JvmInline
@Serializable
public value class MessageId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "MessageId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped MessageId. */
        public fun random(): MessageId = MessageId(Ulid.next("msg"))
    }
}

/** Identifier of a session (RFC §9). */
@JvmInline
@Serializable
public value class SessionId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "SessionId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped SessionId. */
        public fun random(): SessionId = SessionId(Ulid.next("sess"))
    }
}

/** Identifier of a durable job (RFC §10). */
@JvmInline
@Serializable
public value class JobId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "JobId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped JobId. */
        public fun random(): JobId = JobId(Ulid.next("job"))
    }
}

/** Identifier of a stream (RFC §11). */
@JvmInline
@Serializable
public value class StreamId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "StreamId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped StreamId. */
        public fun random(): StreamId = StreamId(Ulid.next("str"))
    }
}

/** Identifier of a subscription (RFC §13). */
@JvmInline
@Serializable
public value class SubscriptionId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "SubscriptionId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped SubscriptionId. */
        public fun random(): SubscriptionId = SubscriptionId(Ulid.next("sub"))
    }
}

/** Identifier of a permission lease (RFC §15.5). */
@JvmInline
@Serializable
public value class LeaseId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "LeaseId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped LeaseId. */
        public fun random(): LeaseId = LeaseId(Ulid.next("lse"))
    }
}

/** Identifier of an artifact (RFC §16). */
@JvmInline
@Serializable
public value class ArtifactId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "ArtifactId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped ArtifactId. */
        public fun random(): ArtifactId = ArtifactId(Ulid.next("art"))
    }
}

/** Distributed-trace identifier (RFC §17.1). */
@JvmInline
@Serializable
public value class TraceId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "TraceId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped TraceId. */
        public fun random(): TraceId = TraceId(Ulid.next("trace"))
    }
}

/** Span identifier within a trace (RFC §17.1). */
@JvmInline
@Serializable
public value class SpanId(
    public val value: String,
) {
    init {
        requireNonBlank(value, "SpanId")
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted, ULID-shaped SpanId. */
        public fun random(): SpanId = SpanId(Ulid.next("span"))
    }
}

/** Permission name in the §15.1 vocabulary (e.g. `payment.refund.create`). */
@JvmInline
@Serializable
public value class PermissionName(
    public val value: String,
) {
    init {
        requireNonBlank(value, "PermissionName")
    }

    override fun toString(): String = value
}

/** Tool name addressed by `tool.invoke` (RFC §6.2 / §10.1). */
@JvmInline
@Serializable
public value class ToolName(
    public val value: String,
) {
    init {
        requireNonBlank(value, "ToolName")
    }

    override fun toString(): String = value
}

/** Idempotency key for a logical command (RFC §6.4). */
@JvmInline
@Serializable
public value class IdempotencyKey(
    public val value: String,
) {
    init {
        requireNonBlank(value, "IdempotencyKey")
    }

    override fun toString(): String = value
}
