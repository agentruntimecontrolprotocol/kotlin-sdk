package dev.arcp.auth

import dev.arcp.error.ARCPException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Validates `bearer` tokens at session establishment (RFC §8.2).
 *
 * Implementations consult an external trust store and return a non-null
 * principal on success or throw [ARCPException.Unauthenticated] on failure.
 */
public fun interface BearerAuth {
    /** Returns the authenticated principal for [token], or throws on rejection. */
    public fun verify(token: String): String
}

/** Pre-shared-secret bearer auth for tests and minimal deployments. */
public class StaticBearerAuth(
    private val tokens: Map<String, String>,
) : BearerAuth {
    override fun verify(token: String): String {
        val presented = token.toByteArray(StandardCharsets.UTF_8)
        var principal: String? = null
        for ((secret, sub) in tokens) {
            if (!constantTimeEquals(secret, presented)) continue
            if (principal != null) {
                throw ARCPException.Unauthenticated("ambiguous bearer token")
            }
            principal = sub
        }
        return principal
            ?: throw ARCPException.Unauthenticated("invalid bearer token")
    }

    private fun constantTimeEquals(
        secret: String,
        presented: ByteArray,
    ): Boolean {
        val candidate = secret.toByteArray(StandardCharsets.UTF_8)
        if (candidate.size != presented.size) return false
        return MessageDigest.isEqual(candidate, presented)
    }
}
