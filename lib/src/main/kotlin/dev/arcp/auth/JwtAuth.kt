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
        val jwt =
            try {
                SignedJWT.parse(token)
            } catch (e: java.text.ParseException) {
                throw ARCPException.Unauthenticated("malformed JWT: ${e.message}")
            }

        if (!jwt.verify(verifier)) {
            throw ARCPException.Unauthenticated("JWT signature verification failed")
        }
        val claims = jwt.jwtClaimsSet
        val audiences = claims.audience ?: emptyList()
        if (expectedAudience !in audiences) {
            throw ARCPException.Unauthenticated(
                "JWT audience does not include expected '$expectedAudience'",
            )
        }
        val sub = claims.subject
        if (sub.isNullOrBlank()) {
            throw ARCPException.Unauthenticated("JWT missing sub claim")
        }
        val now = Date()
        claims.expirationTime?.let { exp ->
            if (!exp.after(now)) {
                throw ARCPException.Unauthenticated("JWT expired")
            }
        }
        claims.notBeforeTime?.let { nbf ->
            if (nbf.after(now)) {
                throw ARCPException.Unauthenticated("JWT not yet valid")
            }
        }
        return sub
    }

    public companion object {
        /** Convenience: HMAC-SHA256 verifier for shared-secret JWTs. */
        public fun hmac(
            secret: ByteArray,
            audience: String,
        ): JwtAuth = JwtAuth(MACVerifier(secret), audience)
    }
}
