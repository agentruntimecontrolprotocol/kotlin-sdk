package dev.fizzpop.arcp.envelope

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope priority (RFC §6.5).
 *
 * `CRITICAL` is reserved for messages that must not be deferred (e.g. real
 * human action permission requests, terminal job events). Implementations may
 * rate-limit `CRITICAL` traffic from misbehaving clients.
 */
@Serializable
public enum class Priority {
    @SerialName("low")
    LOW,

    @SerialName("normal")
    NORMAL,

    @SerialName("high")
    HIGH,

    @SerialName("critical")
    CRITICAL,
    ;

    public companion object {
        /** Default priority when the field is absent (RFC §6.1.1). */
        public val DEFAULT: Priority = NORMAL
    }
}
