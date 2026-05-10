package dev.fizzpop.arcp.messages

import kotlinx.serialization.Serializable

/**
 * Sealed marker for every payload that may appear inside an
 * [dev.fizzpop.arcp.envelope.Envelope] (RFC §6.2).
 *
 * Concrete subtypes live alongside this declaration in the
 * `dev.fizzpop.arcp.messages` package. Each subtype is annotated
 * `@Serializable @SerialName("<wire-name>")` and contributes a polymorphic
 * discriminator value matching the envelope's `type` field.
 *
 * Any `when` expression over a [MessageType] is exhaustive — the compiler
 * errors on unhandled cases.
 */
@Serializable
public sealed interface MessageType
