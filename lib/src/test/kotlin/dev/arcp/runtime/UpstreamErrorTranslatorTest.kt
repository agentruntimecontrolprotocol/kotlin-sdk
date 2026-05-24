package dev.arcp.runtime

import dev.arcp.error.ARCPException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Coverage for the post-fix translator surface: the empty default returns
 * `null` for every input (#69), and [RuleBasedUpstreamErrorTranslator]
 * dispatches the first matching rule and preserves the cause.
 */
class UpstreamErrorTranslatorTest :
    StringSpec({
        "DefaultUpstreamErrorTranslator returns null for every input (#69)" {
            DefaultUpstreamErrorTranslator.translate(
                RuntimeException("budget exceeded"),
            ) shouldBe null
            DefaultUpstreamErrorTranslator.translate(
                IllegalStateException("anything"),
            ) shouldBe null
        }

        "first matching rule wins" {
            val translator = RuleBasedUpstreamErrorTranslator(
                RuleBasedUpstreamErrorTranslator.Rule(
                    predicate = { it.message == "first" },
                    mapper = { ARCPException.FailedPrecondition("matched first") },
                ),
                RuleBasedUpstreamErrorTranslator.Rule(
                    predicate = { it.message == "first" },
                    mapper = { ARCPException.NotFound("would match too") },
                ),
            )
            val out = translator.translate(RuntimeException("first"))
            out.shouldBeInstanceOf<ARCPException.FailedPrecondition>()
        }

        "no matching rule returns null" {
            val translator = RuleBasedUpstreamErrorTranslator(
                RuleBasedUpstreamErrorTranslator.Rule(
                    predicate = { false },
                    mapper = { ARCPException.Internal("never") },
                ),
            )
            translator.translate(RuntimeException("anything")) shouldBe null
        }
    })
