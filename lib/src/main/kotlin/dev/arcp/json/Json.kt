package dev.arcp.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Canonical kotlinx.serialization configuration for ARCP wire JSON.
 *
 * Polymorphic dispatch is keyed on the `type` discriminator (RFC §6.1.1). The
 * envelope serializer in `dev.arcp.envelope` then *hoists* this
 * discriminator to the envelope level so the resulting wire bytes match the
 * RFC example:
 *
 *     { "type": "tool.invoke", "id": "...", "payload": { ... } }
 *
 * Unknown keys are ignored to preserve forward compatibility with extensions
 * (RFC §21).
 */
public val arcpJson: Json = buildArcpJson { }

/**
 * Builds an ARCP-flavored [Json] configuration. Useful when callers need to
 * extend the default with their own [kotlinx.serialization.modules.SerializersModule].
 */
public fun buildArcpJson(extra: JsonBuilder.() -> Unit): Json =
    Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
        extra()
    }
