package dev.arcp.messages

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Agent reference in `name` or `name@version` form (RFC v1.1 §7.5). */
@Serializable(with = AgentRefSerializer::class)
public data class AgentRef(
    public val name: String,
    public val version: String? = null,
) {
    init {
        require(NAME.matchEntire(name) != null) { "invalid agent name: $name" }
        version?.let {
            require(VERSION.matchEntire(it) != null) { "invalid agent version: $it" }
        }
    }

    /** Renders this reference in wire form. */
    public fun render(): String = version?.let { "$name@$it" } ?: name

    public companion object {
        private val NAME: Regex = Regex("[a-z0-9][a-z0-9._-]*")
        private val VERSION: Regex = Regex("[a-zA-Z0-9.+_-]+")

        /** Parses a wire-form agent reference. */
        public fun parse(value: String): AgentRef {
            val at = value.indexOf('@')
            if (at < 0) return AgentRef(value)
            return AgentRef(value.substring(0, at), value.substring(at + 1))
        }
    }
}

/** Agent versions advertised by a runtime during session negotiation. */
@Serializable
public data class AgentDescriptor(
    val name: String,
    val versions: List<String> = emptyList(),
    val default: String? = null,
)

internal object AgentRefSerializer : KSerializer<AgentRef> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.arcp.messages.AgentRef", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AgentRef,
    ) {
        encoder.encodeString(value.render())
    }

    override fun deserialize(decoder: Decoder): AgentRef = AgentRef.parse(decoder.decodeString())
}
