package dev.arcp

/**
 * ARCP protocol and SDK version constants.
 *
 * Implements the version surface required by the envelope `arcp` field
 * (RFC §6.1.1). Senders advertise [PROTOCOL_VERSION] in every envelope; the
 * value is also compared at handshake time (RFC §8.1).
 */
public object Version {
    /** ARCP wire protocol version implemented by this SDK (RFC §6.1.1). */
    public const val PROTOCOL_VERSION: String = "1.0"

    /** Semantic version of the SDK artifact itself. */
    public const val SDK_VERSION: String = "0.1.0"

    /** Identifier emitted as the runtime/client `kind` (RFC §8.2). */
    public const val SDK_KIND: String = "arcp-kotlin-sdk"
}
