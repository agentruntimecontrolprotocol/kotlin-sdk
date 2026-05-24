# Getting started

## Prerequisites

- **JDK 21** or newer ([Adoptium](https://adoptium.net) or Homebrew `openjdk@21`)
- **Gradle 8.10+** — the wrapper (`./gradlew`) is included; no separate install needed

## Install

Add the library to your Gradle project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.arcp:arcp:1.1.0")
}
```

The library requires the Kotlin coroutines and serialization runtimes; those
are declared as `api` dependencies and are pulled in automatically.

## Minimal example

The snippet below pairs a client and runtime over `MemoryTransport`,
registers one agent, opens a session, submits a job, and closes
cleanly. It is the same shape the integration tests use and runs
end-to-end as written.

`MemoryTransport` is the only transport on the v0.1 public API surface
(`dev.arcp.transport.Transport`); the WebSocket and stdio transports
referenced elsewhere in the docs are planned for a future SDK release.

```kotlin
import dev.arcp.client.ARCPClient
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import dev.arcp.auth.StaticBearerAuth
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Paired in-memory transport (client ↔ runtime).
    val (clientTransport, runtimeTransport) = MemoryTransport.pair()

    // 2. Runtime with one registered agent. The bearer auth on both
    //    sides must agree — here the runtime maps "my-token" to a
    //    principal name, and the client carries the same token.
    val registry = AgentRegistry()
    registry.register("summarise", "1.0.0", default = true)
    val runtime = ARCPRuntime(
        supportedCapabilities = Capabilities(streaming = true),
        agentRegistry = registry,
        bearerAuth = StaticBearerAuth(mapOf("my-token" to "quickstart")),
    )

    // 3. Let the runtime accept the connection in the background.
    runtime.accept(runtimeTransport)

    // 4. Open a session from the client side.
    val client = ARCPClient(
        transport = clientTransport,
        auth = ARCPClient.bearer("my-token"),
        client = ARCPClient.defaultClientInfo(),
        capabilities = Capabilities(streaming = true),
    )
    val session = client.open()   // returns SessionAccepted
    println("session: ${session.sessionId}")

    // 5. Submit a job.
    val msgId = client.send(
        session.sessionId,
        dev.arcp.messages.JobSubmit(agent = "summarise@1.0.0"),
    )
    println("submitted job command: $msgId")

    // 6. Graceful close.
    client.send(session.sessionId, dev.arcp.messages.SessionClose())
    runtime.close()
}
```

`client.send(...)` returns the `MessageId` of the envelope it just sent (this
is the command id, not the runtime-assigned `JobId`). The `JobId` arrives on
the correlated `JobAccepted` envelope received from the runtime.

## Build from source

```bash
git clone https://github.com/agentruntimecontrolprotocol/kotlin-sdk
cd kotlin-sdk
./gradlew build                       # compile, lint, test
./gradlew :lib:test                   # unit tests only
./gradlew :tests:test                 # integration tests over MemoryTransport
./gradlew :samples:runSubscriptions   # run the subscriptions sample
./gradlew :samples:tasks --group samples  # list all sample tasks
```

## Next steps

- [Architecture](architecture.md) — understand the layering before writing more code
- [Transports](transports.md) — currently only `MemoryTransport` is public; WebSocket/stdio are planned
- [Guides](README.md#guides) — deep-dives on sessions, jobs, leases, and more
