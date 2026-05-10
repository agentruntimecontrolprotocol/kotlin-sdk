package dev.arcp.error

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Canonical ARCP error taxonomy (RFC §18.2).
 *
 * Each constant carries [retryableByDefault] following §18.3. `RATE_LIMITED` is
 * defined by the RFC as an alias of [RESOURCE_EXHAUSTED]; the alias is accepted
 * on decode but never emitted on encode.
 */
@Serializable(with = ErrorCodeSerializer::class)
public enum class ErrorCode(
    public val wire: String,
    public val retryableByDefault: Boolean,
) {
    OK("OK", false),
    CANCELLED("CANCELLED", false),
    UNKNOWN("UNKNOWN", false),
    INVALID_ARGUMENT("INVALID_ARGUMENT", false),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", true),
    NOT_FOUND("NOT_FOUND", false),
    ALREADY_EXISTS("ALREADY_EXISTS", false),
    PERMISSION_DENIED("PERMISSION_DENIED", false),
    RESOURCE_EXHAUSTED("RESOURCE_EXHAUSTED", true),
    FAILED_PRECONDITION("FAILED_PRECONDITION", false),
    ABORTED("ABORTED", true),
    OUT_OF_RANGE("OUT_OF_RANGE", false),
    UNIMPLEMENTED("UNIMPLEMENTED", false),
    INTERNAL("INTERNAL", true),
    UNAVAILABLE("UNAVAILABLE", true),
    DATA_LOSS("DATA_LOSS", false),
    UNAUTHENTICATED("UNAUTHENTICATED", false),
    HEARTBEAT_LOST("HEARTBEAT_LOST", true),
    LEASE_EXPIRED("LEASE_EXPIRED", false),
    LEASE_REVOKED("LEASE_REVOKED", false),
    BACKPRESSURE_OVERFLOW("BACKPRESSURE_OVERFLOW", true),
    ;

    public companion object {
        /**
         * Decodes a wire string. Accepts the legacy alias `RATE_LIMITED` as
         * [RESOURCE_EXHAUSTED] per RFC §18.2, but always encodes the canonical form.
         */
        public fun fromWire(value: String): ErrorCode {
            if (value == "RATE_LIMITED") return RESOURCE_EXHAUSTED
            return entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown ARCP error code: $value")
        }
    }
}

internal object ErrorCodeSerializer : KSerializer<ErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.arcp.error.ErrorCode", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ErrorCode,
    ) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): ErrorCode = ErrorCode.fromWire(decoder.decodeString())
}
