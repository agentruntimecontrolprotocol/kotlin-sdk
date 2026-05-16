package dev.arcp.auth

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import dev.arcp.error.ARCPException
import java.util.Date

/**
 * Validates `signed_jwt` tokens at session establishment (RFC §8.2).
 *
 * The verifier accepts tokens signed with the configured [JWSVerifier] and
 * returns the principal carried in the JWT `sub` claim. The `aud` claim is
 * matched against the runtime's expected audience identifier.
 */
public class JwtAuth(
    private val verifier: JWSVerifier,
    private val expectedAudience: String,
) {
    /** Verifies [token] and returns the principal (`sub` claim). */
    public fun verify(token: String): String {
        val jwt = parseSignedJwt(token)
        verifySignature(jwt)
        val claims = jwt.jwtClaimsSet
        verifyAudience(claims.audience ?: emptyList())
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

    private fun verifyTimeBounds(
        exp: Date?,
        nbf: Date?,
    ) {
        val now = Date()
        if (exp != null && !exp.after(now)) {
            throw ARCPException.Unauthenticated("JWT expired")
        }
        if (nbf != null && nbf.after(now)) {
            throw ARCPException.Unauthenticated("JWT not yet valid")
        }
    }

    public companion object {
        /** Convenience: HMAC-SHA256 verifier for shared-secret JWTs. */
        public fun hmac(
            secret: ByteArray,
            audience: String,
        ): JwtAuth = JwtAuth(MACVerifier(secret), audience)
    }
}
