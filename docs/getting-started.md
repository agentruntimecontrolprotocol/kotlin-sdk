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

The snippet below opens a session, submits a job, and closes cleanly.
It uses `MemoryTransport` — the same transport the integration tests use;
swap it for `WebSocketTransport` or `StdioTransport` in production.

```kotlin
import dev.arcp.client.ARCPClient
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Paired in-memory transport (client ↔ runtime).
    val (clientTransport, runtimeTransport) = MemoryTransport.pair()

    // 2. Runtime with one registered agent.
    val registry = AgentRegistry()
    registry.register("summarise", listOf("1.0.0"))
    val runtime = ARCPRuntime(
        supportedCapabilities = Capabilities(streaming = true),
        agentRegistry = registry,
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
    val jobId = client.send(
        session.sessionId,
        dev.arcp.messages.JobSubmit(agent = "summarise@1.0.0"),
    )
    println("submitted job: $jobId")

    // 6. Graceful close.
    client.send(session.sessionId, dev.arcp.messages.SessionClose())
    runtime.close()
}
```

## Build from source

```bash
git clone https://github.com/agentruntimecontrolprotocol/kotlin-sdk
cd kotlin-sdk
./gradlew build              # compile, lint, test
./gradlew :lib:test          # unit tests only
./gradlew :tests:test        # integration tests over MemoryTransport
./gradlew :samples:run01     # run the minimal session sample
```

## Next steps

- [Architecture](architecture.md) — understand the layering before writing more code
- [Transports](transports.md) — connect over WebSocket or stdio
- [Guides](README.md#guides) — deep-dives on sessions, jobs, leases, and more
