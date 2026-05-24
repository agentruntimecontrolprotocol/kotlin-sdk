package dev.arcp.runtime

import dev.arcp.error.ARCPException

/**
 * Translates upstream provider errors to ARCP errors at integration
 * boundaries.
 *
 * Implementations should be exhaustive about *their own* provider's
 * exception hierarchy — never substring-match on `Throwable.message`,
 * since provider error strings vary by version and locale.
 */
public fun interface UpstreamErrorTranslator {
    /** Returns an ARCP error for [error], or `null` when no mapping is known. */
    public fun translate(error: Throwable): ARCPException?
}

/**
 * No-op default translator.
 *
 * The previous default substring-matched `Throwable.message` for the
 * words "budget" and "exhausted"/"exceeded", which produced false
 * positives on unrelated rate-limit errors and discarded the original
 * cause. Deployments now ship their own translator — usually a small
 * `RuleBasedUpstreamErrorTranslator` keyed on provider exception types
 * — and accept that the SDK has no opinionated guesses to offer (#69).
 *
 * Use [RuleBasedUpstreamErrorTranslator] to build a translator from a
 * list of `(predicate, mapper)` pairs.
 */
public object DefaultUpstreamErrorTranslator : UpstreamErrorTranslator {
    override fun translate(error: Throwable): ARCPException? = null
}

/**
 * Rule-based [UpstreamErrorTranslator]. The first [rules] entry whose
 * [Rule.predicate] returns `true` is applied; its [Rule.mapper] receives
 * the original throwable and must return an [ARCPException] (carrying
 * the original as `cause` is strongly recommended).
 *
 * Example: map an upstream rate-limit exception type to a deferred-retry
 * [ARCPException.ResourceExhausted]:
 *
 * ```kotlin
 * val rateLimited = "com.openai.errors.RateLimitException"
 * val translator = RuleBasedUpstreamErrorTranslator(
 *     Rule(predicate = { it::class.qualifiedName == rateLimited }) { e ->
 *         ARCPException.ResourceExhausted(e.message ?: "rate-limited", retryAfterSeconds = 1)
 *     },
 * )
 * ```
 */
public class RuleBasedUpstreamErrorTranslator(
    private val rules: List<Rule>,
) : UpstreamErrorTranslator {
    public constructor(vararg rules: Rule) : this(rules.toList())

    override fun translate(error: Throwable): ARCPException? =
        rules.firstOrNull { it.predicate(error) }?.mapper?.invoke(error)

    /** One translation rule. */
    public data class Rule(
        public val predicate: (Throwable) -> Boolean,
        public val mapper: (Throwable) -> ARCPException,
    )
}
