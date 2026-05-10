package dev.fizzpop.arcp.extensions

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of accepted ARCP extension namespaces (RFC §21).
 *
 * Extensions must be advertised at session establishment via
 * `capabilities.extensions`; this registry holds the currently-accepted set
 * and offers helpers for validation and unknown-message classification.
 */
public class ExtensionRegistry {
    private val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Currently-advertised extensions. */
    public val advertised: Set<String> get() = accepted.toSet()

    /** Adds an extension to the accepted set after validating its name. */
    public fun advertise(name: String) {
        require(isValidName(name)) {
            "extension name '$name' violates §21.1 naming rules"
        }
        accepted.add(name)
    }

    /** True iff the wire `type` belongs to an advertised extension namespace. */
    public fun acceptsType(wireType: String): Boolean = accepted.any { wireType.startsWith(it) }

    public companion object {
        private val ARCPX_REGEX = Regex("^arcpx\\.[a-z0-9-]+(\\.[a-z0-9-]+)+\\.v\\d+$")

        // Reverse-DNS form: at least three dot-separated labels ending in .v<n> (RFC §21.1).
        private val REVERSE_DNS_REGEX =
            Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+){2,}\\.v\\d+$")

        /**
         * Validates an extension name against the §21.1 grammar:
         * `arcpx.<vendor>.<name>.v<n>` or reverse-DNS form. The bare `x-` prefix
         * is **not** accepted here — it is reserved for transport-internal fields.
         */
        public fun isValidName(name: String): Boolean {
            if (name.startsWith("x-")) return false
            return ARCPX_REGEX.matches(name) || REVERSE_DNS_REGEX.matches(name)
        }
    }
}

/**
 * Classification of an unknown message type per RFC §21.3.
 *
 * - [UnknownAction.Drop] — silently drop (the sender flagged the message
 *   `extensions.optional: true` and the type is namespaced).
 * - [UnknownAction.Nack] — respond with `nack` and `code: UNIMPLEMENTED`.
 */
public sealed interface UnknownAction {
    public data object Drop : UnknownAction

    public data object Nack : UnknownAction
}

/**
 * Determines how to handle an unknown message type per RFC §21.3.
 */
public fun classifyUnknown(
    wireType: String,
    optional: Boolean,
    advertisedExtensions: Set<String>,
): UnknownAction {
    val coreLikeRegex = Regex("^[a-z]+\\.[a-z._]+$")
    val isNamespacedExtension =
        wireType.startsWith("arcpx.") ||
            // reverse-DNS extension types contain ".v<n>" suffix
            Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+){2,}\\.v\\d+(\\..+)?$").containsMatchIn(wireType)
    val isAdvertised = advertisedExtensions.any { wireType.startsWith(it) }

    return when {
        // Recognized core prefix — UNIMPLEMENTED nack regardless of optional.
        !isNamespacedExtension && coreLikeRegex.matches(wireType) -> UnknownAction.Nack
        // Namespaced extension that's not advertised but optional → silently drop.
        isNamespacedExtension && !isAdvertised && optional -> UnknownAction.Drop
        // Namespaced extension that's not advertised and not optional → nack.
        isNamespacedExtension && !isAdvertised -> UnknownAction.Nack
        // Anything else → nack as a safe default.
        else -> UnknownAction.Nack
    }
}
