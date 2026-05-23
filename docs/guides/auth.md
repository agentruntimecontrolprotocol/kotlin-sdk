# Authentication

ARCP supports two bearer-style auth mechanisms: static bearer tokens and
signed JWTs. Both are enforced during the session handshake (RFC §8.2).

## Static bearer tokens

`StaticBearerAuth` maps a token string to a principal name. Comparison uses
constant-time equality to resist timing attacks.

```kotlin
val auth = StaticBearerAuth(mapOf(
    "token-alice" to "alice",
    "token-bob"   to "bob",
))

val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(),
    bearerAuth = auth,
)
```

The principal name (e.g. `"alice"`) is stored in the session and used for
job-scoped lease and credential checks. An empty or whitespace token always
fails; `ARCPException.Unauthenticated` is thrown if the token is not in the
map.

## JWT authentication

`JwtAuth` validates a signed JWT against an expected audience. Use
`JwtAuth.hmac()` for HMAC-SHA256 shared-secret tokens:

```kotlin
val secret  = System.getenv("ARCP_JWT_SECRET").toByteArray()
val jwtAuth = JwtAuth.hmac(secret, audience = "arcp-runtime")

val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(),
    jwtAuth = jwtAuth,
)
```

`JwtAuth` validates:

- **Signature** — using the provided `JWSVerifier`
- **`aud`** — must match `expectedAudience`
- **`sub`** — must be non-blank (becomes the principal name)
- **`exp`** — token must not be expired
- **`nbf`** — token must be active (if present)

Any failure throws `ARCPException.Unauthenticated`.

### Custom JWS verifier

For asymmetric keys (RSA, EC), supply a `JWSVerifier` directly:

```kotlin
val publicKey: RSAPublicKey = loadPublicKey()
val verifier  = RSASSAVerifier(publicKey)
val jwtAuth   = JwtAuth(verifier, expectedAudience = "arcp-runtime")
```

## Client-side auth

`ARCPClient` takes an `Auth` block (the wire credentials block sent on
`session.open`); use `ARCPClient.bearer(token)` for the common case:

```kotlin
val client = ARCPClient(
    transport    = clientTransport,
    auth         = ARCPClient.bearer("token-alice"),  // returns Auth(scheme = BEARER, token = ...)
    client       = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(),
)
```

The credentials ride in `SessionOpen` itself rather than waiting for a
challenge round-trip; the four-message handshake (RFC §8.1) only adds a
`session.challenge`/`session.authenticate` pair when the runtime needs
additional proof (e.g. a JWT nonce signature).

## Session challenge flow

If the runtime requires authentication, the handshake is:

```
client ─── SessionOpen ──────────────────────> runtime
       <── SessionChallenge ─────────────────
       ─── SessionAuthenticate (bearer/JWT) ──>
       <── SessionAccepted ──────────────────
```

If the runtime accepts anonymous sessions (`Capabilities(anonymous = true)`),
it may skip the challenge and go straight to `SessionAccepted`.

## Trust levels

The runtime assigns each session a `TrustLevel` based on authentication
(RFC §15.3):

| Level | Description |
|-------|-------------|
| `UNTRUSTED` | No valid credentials |
| `CONSTRAINED` | Authenticated but restricted capabilities |
| `TRUSTED` | Fully authenticated principal |
| `PRIVILEGED` | Elevated administrative access |

The trust level is reported by the runtime on `SessionAccepted.runtime`:

```kotlin
val session = client.open()
val trust = session.runtime.trustLevel   // dev.arcp.messages.TrustLevel
```
