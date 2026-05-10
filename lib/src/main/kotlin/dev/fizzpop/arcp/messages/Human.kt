package dev.fizzpop.arcp.messages

import dev.fizzpop.arcp.error.ErrorCode
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `human.input.request` — solicit structured input (RFC §12.1). */
@Serializable
@SerialName("human.input.request")
public data class HumanInputRequest(
    val prompt: String,
    @SerialName("response_schema")
    val responseSchema: JsonObject? = null,
    val default: JsonElement? = null,
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `human.input.response` — corresponding response (RFC §12.1). */
@Serializable
@SerialName("human.input.response")
public data class HumanInputResponse(
    val value: JsonElement,
    @SerialName("responded_by")
    val respondedBy: String? = null,
    @SerialName("responded_at")
    val respondedAt: Instant? = null,
) : MessageType

/** Single option in [HumanChoiceRequest]. */
@Serializable
public data class HumanChoiceOption(
    val id: String,
    val label: String,
)

/** `human.choice.request` — multi-option picker (RFC §12.2). */
@Serializable
@SerialName("human.choice.request")
public data class HumanChoiceRequest(
    val prompt: String,
    val options: List<HumanChoiceOption>,
    @SerialName("expires_at")
    val expiresAt: Instant,
) : MessageType

/** `human.choice.response` — chosen option (RFC §12.2). */
@Serializable
@SerialName("human.choice.response")
public data class HumanChoiceResponse(
    @SerialName("choice_id")
    val choiceId: String,
    @SerialName("responded_by")
    val respondedBy: String? = null,
    @SerialName("responded_at")
    val respondedAt: Instant? = null,
) : MessageType

/**
 * `human.input.cancelled` — request was cleared/expired (RFC §12.3 / §12.4).
 *
 * Emitted both for deadline expiry and for cross-channel resolution.
 */
@Serializable
@SerialName("human.input.cancelled")
public data class HumanInputCancelled(
    val code: ErrorCode = ErrorCode.CANCELLED,
    val reason: String? = null,
) : MessageType
