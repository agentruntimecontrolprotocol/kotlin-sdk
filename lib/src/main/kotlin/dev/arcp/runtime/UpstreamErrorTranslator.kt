package dev.arcp.runtime

import dev.arcp.error.ARCPException

/** Translates upstream provider errors to ARCP errors at integration boundaries. */
public fun interface UpstreamErrorTranslator {
    /** Returns an ARCP error for [error], or null when no mapping is known. */
    public fun translate(error: Throwable): ARCPException?
}

/** Conservative default translator for common budget-exhaustion signals. */
public object DefaultUpstreamErrorTranslator : UpstreamErrorTranslator {
    override fun translate(error: Throwable): ARCPException? {
        val message = error.message?.lowercase().orEmpty()
        return if ("budget" in message && ("exhaust" in message || "exceed" in message)) {
            ARCPException.BudgetExhausted(
                currency = "unknown",
                message =
                    error.message ?: "budget exhausted",
            )
        } else {
            null
        }
    }
}
