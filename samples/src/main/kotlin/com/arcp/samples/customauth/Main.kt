package com.arcp.samples.customauth

import dev.arcp.auth.BearerAuth
import dev.arcp.auth.JwtAuth
import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates all three auth modes: static bearer, JWT HMAC, and a custom
 * `BearerAuth` functor (RFC §6.1).
 *
 * - [staticBearerExample] — token lookup in a fixed map; constant-time comparison
 * - [jwtHmacExample]     — HS256-signed tokens; SDK validates sub/aud/exp/nbf
 * - [customAuthExample]  — implement `BearerAuth` as a lambda or class to call
 *                          your own identity store at verify time
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="custom-auth"
 * ```
 */

// ---------------------------------------------------------------------------
// 1. Static bearer tokens
// ---------------------------------------------------------------------------

private fun staticBearerExample() {
    // Map token → principal name.  Comparison is constant-time so
    // probing attacks learn nothing from timing differences.
    val auth: BearerAuth = StaticBearerAuth(
        mapOf(
            "tok-dev-alice-001" to "alice",
            "tok-dev-bob-002"   to "bob",
        ),
    )

    val principal = auth.verify("tok-dev-alice-001")
    println("static bearer: principal=$principal")

    // Unknown token raises ARCPException.Unauthenticated
    runCatching { auth.verify("not-a-real-token") }
        .onFailure { println("unknown token rejected: ${it::class.simpleName}") }
}

// ---------------------------------------------------------------------------
// 2. JWT HMAC-SHA-256
// ---------------------------------------------------------------------------

private fun jwtHmacExample() {
    val secret = "change-me-before-prod".toByteArray()
    val audience = "arcp-runtime.example.com"

    // JwtAuth.hmac() creates a JWSVerifier + wraps it in JwtAuth.
    // Validates: alg=HS256, exp, nbf, aud == audience.
    // Returns: the `sub` claim as the principal name.
    val auth = JwtAuth.hmac(secret, audience)

    // In production, generate the JWT externally and hand it to the client.
    // Here we just illustrate that the verifier is ready.
    println("JWT auth configured; audience=$audience")
    println("verify a real token: auth.verify(jwtString)")
}

// ---------------------------------------------------------------------------
// 3. Custom BearerAuth lambda — calls an imaginary identity store
// ---------------------------------------------------------------------------

private object FakeIdentityStore {
    private val db = mapOf("svc-token-xyz" to "ci-pipeline")

    fun lookup(token: String): String? = db[token]
}

private fun customAuthExample(): Unit = runBlocking {
    // BearerAuth is a fun interface — any (String) -> String lambda works.
    // Throw ARCPException.Unauthenticated to reject.
    val auth = BearerAuth { token ->
        FakeIdentityStore.lookup(token)
            ?: throw dev.arcp.error.ARCPException.Unauthenticated("unknown token")
    }

    val (clientTransport, serverTransport) = MemoryTransport.pair()

    val runtime = ARCPRuntime(
        supportedCapabilities = Capabilities(),
        bearerAuth = auth,
    )
    val serverJob = runtime.accept(serverTransport)

    val client = ARCPClient(
        transport = clientTransport,
        auth = ARCPClient.bearer("svc-token-xyz"),
        client = ARCPClient.defaultClientInfo(),
        capabilities = Capabilities(),
    )

    val accepted = client.open()
    println("custom auth: sessionId=${accepted.sessionId}")
    client.close()
    serverJob.cancel()
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

public fun main(): Unit {
    staticBearerExample()
    jwtHmacExample()
    customAuthExample()
}
