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

        "rejects negative amount (§9.6)" {
            shouldThrow<IllegalArgumentException> {
                BudgetAmount.parse("USD:-5.00")
            }
        }

        "rejects explicit plus sign (§9.6)" {
            shouldThrow<IllegalArgumentException> {
                BudgetAmount.parse("USD:+5")
            }
        }

        "rejects scientific notation (§9.6)" {
            shouldThrow<IllegalArgumentException> {
                BudgetAmount.parse("USD:1e3")
            }
        }

        "accepts plain integer credits amount" {
            BudgetAmount.parse("credits:1000").render() shouldBe "credits:1000"
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
