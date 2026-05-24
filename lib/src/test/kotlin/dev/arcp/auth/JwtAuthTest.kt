package dev.arcp.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.arcp.error.ARCPException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Date
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val AUDIENCE = "test-runtime"
private val SECRET = "a-very-strong-32-byte-secret-key".toByteArray()

/**
 * Coverage for the v0.1 [JwtAuth]: skew tolerance for `exp`/`nbf` and
 * optional issuer matching (#61), plus the existing audience and missing-sub
 * paths.
 */
class JwtAuthTest :
    StringSpec({
        "verify succeeds for a valid token" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE)
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() + 10_000),
            )
            auth.verify(token) shouldBe "u@x"
        }

        "exp comparison allows configured clock skew (#61)" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE, allowedClockSkew = 30.seconds)
            // exp is 5 seconds in the past, but within the 30s skew window.
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() - 5_000),
            )
            auth.verify(token) shouldBe "u@x"
        }

        "exp outside the skew window still rejects" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE, allowedClockSkew = 1.seconds)
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() - 10_000),
            )
            shouldThrow<ARCPException.Unauthenticated> { auth.verify(token) }
        }

        "nbf comparison allows configured clock skew (#61)" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE, allowedClockSkew = 30.seconds)
            val now = System.currentTimeMillis()
            // nbf is 5 seconds in the future, within the 30s skew window.
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(now + 60_000),
                notBefore = Date(now + 5_000),
            )
            auth.verify(token) shouldBe "u@x"
        }

        "issuer match succeeds when expectedIssuer is set (#61)" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE, issuer = "issuer.example")
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() + 10_000),
                issuer = "issuer.example",
            )
            auth.verify(token) shouldBe "u@x"
        }

        "issuer mismatch is rejected (#61)" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE, issuer = "issuer.example")
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() + 10_000),
                issuer = "rogue.example",
            )
            shouldThrow<ARCPException.Unauthenticated> { auth.verify(token) }
        }

        "absent expectedIssuer accepts any iss (default behavior)" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE)
            val token = sign(
                subject = "u@x",
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() + 10_000),
                issuer = "whoever.example",
            )
            auth.verify(token) shouldBe "u@x"
        }

        "audience mismatch is rejected" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE)
            val token = sign(
                subject = "u@x",
                audience = "other-runtime",
                expiresAt = Date(System.currentTimeMillis() + 10_000),
            )
            shouldThrow<ARCPException.Unauthenticated> { auth.verify(token) }
        }

        "missing subject is rejected" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE)
            val token = sign(
                subject = null,
                audience = AUDIENCE,
                expiresAt = Date(System.currentTimeMillis() + 10_000),
            )
            shouldThrow<ARCPException.Unauthenticated> { auth.verify(token) }
        }

        "malformed token is rejected" {
            val auth = JwtAuth.hmac(SECRET, AUDIENCE)
            shouldThrow<ARCPException.Unauthenticated> { auth.verify("not.a.jwt") }
        }

        "default skew is one minute" {
            JwtAuth.DEFAULT_CLOCK_SKEW shouldBe 1.minutes
        }
    })

private data class SignInput(
    val subject: String? = "u@x",
    val audience: String = AUDIENCE,
    val expiresAt: Date = Date(System.currentTimeMillis() + 10_000),
    val notBefore: Date? = null,
    val issuer: String? = null,
)

@Suppress("LongParameterList")
private fun sign(
    subject: String?,
    audience: String,
    expiresAt: Date,
    issuer: String? = null,
    notBefore: Date? = null,
): String = sign(
    SignInput(
        subject = subject,
        audience = audience,
        expiresAt = expiresAt,
        notBefore = notBefore,
        issuer = issuer,
    ),
)

private fun sign(input: SignInput): String {
    val claims = JWTClaimsSet
        .Builder()
        .audience(input.audience)
        .expirationTime(input.expiresAt)
    input.subject?.let { claims.subject(it) }
    input.notBefore?.let { claims.notBeforeTime(it) }
    input.issuer?.let { claims.issuer(it) }
    val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims.build())
    jwt.sign(MACSigner(SECRET))
    return jwt.serialize()
}
