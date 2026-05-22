package dev.arcp.lease

import dev.arcp.json.arcpJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CostBudgetTest :
    StringSpec({
        "parses USD budget round-trip via wire string" {
            BudgetAmount.parse("USD:5.00").render() shouldBe "USD:5.00"
        }

        "rejects missing colon" {
            shouldThrow<IllegalArgumentException> {
                BudgetAmount.parse("USD5.00")
            }
        }

        "rejects duplicate currency in CostBudget" {
            shouldThrow<IllegalArgumentException> {
                CostBudget(
                    listOf(
                        BudgetAmount.parse("USD:1"),
                        BudgetAmount.parse("USD:2"),
                    ),
                )
            }
        }

        "serializes through arcpJson as a JSON string" {
            val encoded = arcpJson.encodeToString(
                BudgetAmount.serializer(),
                BudgetAmount.parse("USD:5.00"),
            )
            encoded shouldBe "\"USD:5.00\""
        }
    })
