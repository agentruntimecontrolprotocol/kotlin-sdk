package dev.arcp.lease

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

/** Currency identifier for `cost.budget` leases (RFC v1.1 §9.6). */
@JvmInline
@Serializable
public value class Currency(
    public val code: String,
) {
    init {
        require(code.isNotBlank()) { "currency must not be blank" }
    }

    override fun toString(): String = code
}

/** A single budget amount encoded as `currency:decimal`. */
@Serializable(with = BudgetAmountSerializer::class)
public data class BudgetAmount(
    val currency: Currency,
    val value: BigDecimal,
) {
    /** Renders this amount in `currency:decimal` wire form. */
    public fun render(): String = "${currency.code}:${value.toPlainString()}"

    public companion object {
        /**
         * §9.6 amount grammar: `decimal ::= digits ( "." digits )?` — no
         * sign, no exponent, no leading/trailing dot.
         */
        private val DECIMAL_GRAMMAR = Regex("^[0-9]+(\\.[0-9]+)?$")

        /** Parses a `currency:decimal` budget amount (RFC v1.1 §9.6). */
        public fun parse(value: String): BudgetAmount {
            val split = value.split(":", limit = 2)
            require(split.size == 2 && split[0].isNotBlank() && split[1].isNotBlank()) {
                "budget amount must use currency:decimal"
            }
            val decimal = split[1]
            require(DECIMAL_GRAMMAR.matches(decimal)) {
                "budget amount decimal must match digits(.digits)? without sign or exponent: " +
                    "'$decimal'"
            }
            return BudgetAmount(Currency(split[0]), decimal.toBigDecimal())
        }
    }
}

/** Per-currency `cost.budget` lease. */
public data class CostBudget(
    val budgets: List<BudgetAmount>,
) {
    init {
        require(budgets.groupBy { it.currency }.all { it.value.size == 1 }) {
            "duplicate currency in cost.budget"
        }
    }

    /** Returns the budget amount for [currency], if present. */
    public fun byCurrency(currency: Currency): BudgetAmount? =
        budgets.firstOrNull { it.currency == currency }
}

internal object BudgetAmountSerializer : KSerializer<BudgetAmount> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.arcp.lease.BudgetAmount", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BudgetAmount,
    ) {
        encoder.encodeString(value.render())
    }

    override fun deserialize(decoder: Decoder): BudgetAmount =
        BudgetAmount.parse(decoder.decodeString())
}
