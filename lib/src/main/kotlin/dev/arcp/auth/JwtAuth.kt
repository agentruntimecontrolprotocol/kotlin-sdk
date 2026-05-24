package dev.arcp.auth

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import dev.arcp.error.ARCPException
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Validates `signed_jwt` tokens at session establishment (RFC §8.2).
 *
 * The verifier accepts tokens signed with the configured [JWSVerifier]
 * and returns the principal carried in the JWT `sub` claim. The `aud`
 * claim is matched against [expectedAudience]; when [expectedIssuer] is
 * non-null the `iss` claim must also match before the token is accepted.
 *
 * [allowedClockSkew] applies to both `exp` and `nbf` and defaults to one
 * minute, matching the recommendation in RFC 7519 §4.1.4 — a wall-clock
 * drift of a few hundred milliseconds between issuer and verifier should
 * not turn a valid token into `ARCPException.Unauthenticated`.
 */
public class JwtAuth(
    private val verifier: JWSVerifier,
    private val expectedAudience: String,
    private val expectedIssuer: String? = null,
    private val allowedClockSkew: Duration = DEFAULT_CLOCK_SKEW,
) {
    /** Verifies [token] and returns the principal (`sub` claim). */
    public fun verify(token: String): String {
        val jwt = parseSignedJwt(token)
        verifySignature(jwt)
        val claims = jwt.jwtClaimsSet
        verifyAudience(claims.audience ?: emptyList())
        verifyIssuer(claims.issuer)
        verifyTimeBounds(claims.expirationTime, claims.notBeforeTime)
        return claims.subject?.takeIf { it.isNotBlank() }
            ?: throw ARCPException.Unauthenticated("JWT missing sub claim")
    }

    private fun parseSignedJwt(token: String): SignedJWT = try {
        SignedJWT.parse(token)
    } catch (e: java.text.ParseException) {
        throw ARCPException.Unauthenticated("malformed JWT: ${e.message}")
    }

    private fun verifySignature(jwt: SignedJWT) {
        if (!jwt.verify(verifier)) {
            throw ARCPException.Unauthenticated(
                "JWT signature verification failed",
            )
        }
    }

    private fun verifyAudience(audiences: List<String>) {
        if (expectedAudience !in audiences) {
            throw ARCPException.Unauthenticated(
                "JWT audience does not include expected '$expectedAudience'",
            )
        }
    }

    private fun verifyIssuer(issuer: String?) {
        val expected = expectedIssuer ?: return
        if (issuer != expected) {
            throw ARCPException.Unauthenticated(
                "JWT issuer '${issuer ?: ""}' does not match expected '$expected'",
            )
        }
    }

    private fun verifyTimeBounds(
        exp: Date?,
        nbf: Date?,
    ) {
        val now = Date()
        val skewMs = allowedClockSkew.inWholeMilliseconds
        if (exp != null && exp.time + skewMs <= now.time) {
            throw ARCPException.Unauthenticated("JWT expired")
        }
        if (nbf != null && nbf.time - skewMs > now.time) {
            throw ARCPException.Unauthenticated("JWT not yet valid")
        }
    }

    public companion object {
        /** Default clock skew tolerance applied to `exp` and `nbf` (1 minute). */
        public val DEFAULT_CLOCK_SKEW: Duration = 1.minutes

        /** Convenience: HMAC-SHA256 verifier for shared-secret JWTs. */
        public fun hmac(
            secret: ByteArray,
            audience: String,
            issuer: String? = null,
            allowedClockSkew: Duration = DEFAULT_CLOCK_SKEW,
        ): JwtAuth = JwtAuth(
            verifier = MACVerifier(secret),
            expectedAudience = audience,
            expectedIssuer = issuer,
            allowedClockSkew = allowedClockSkew,
        )
    }
}
