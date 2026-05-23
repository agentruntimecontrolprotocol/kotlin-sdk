# Vendor Extensions

ARCP reserves the `arcpx.*` namespace for vendor-defined message types and
event names (RFC §§15, 21). Extensions let runtime operators add proprietary
messages without forking the protocol.

## Naming convention

Extension names must match one of two patterns:

```
arcpx.<vendor>.<feature>.v<n>
com.example.feature.v1      (reverse-DNS form)
```

Examples:
- `arcpx.acme.email.v1`
- `arcpx.anthropic.reasoning.v2`
- `com.mycompany.billing.v1`

Names that do not match these patterns are rejected by `ExtensionRegistry`.

## ExtensionRegistry

```kotlin
val extensions = ExtensionRegistry()
extensions.advertise("arcpx.acme.email.v1")
extensions.advertise("arcpx.acme.billing.v1")
```

Advertise extensions in the runtime's `Capabilities`:

```kotlin
val capabilities = Capabilities(
    extensions = listOf("arcpx.acme.email.v1", "arcpx.acme.billing.v1"),
)
val runtime = ARCPRuntime(supportedCapabilities = capabilities, ...)
```

Both sides must advertise an extension for it to be considered active. The
`SessionAccepted.capabilities.extensions` list contains the negotiated
intersection.

## Handling unknown message types

When a message arrives with an unrecognised `type` field, the runtime asks
the top-level `classifyUnknown` helper what to do (the function lives in
`dev.arcp.extensions`, not on `ExtensionRegistry`):

```kotlin
import dev.arcp.extensions.UnknownAction
import dev.arcp.extensions.classifyUnknown

when (classifyUnknown(wireType, optional, extensions.advertised)) {
    UnknownAction.Drop -> { /* silently ignore */ }
    UnknownAction.Nack -> { /* send Nack with UNIMPLEMENTED */ }
}
```

A namespaced extension type is `Drop`ped when it is not advertised *and*
the sender flagged the message as optional. If the type looks like a core
`a.b` name, or is namespaced and not optional, the runtime responds with
`Nack` carrying `ErrorCode.UNIMPLEMENTED`.

## Checking acceptance

`acceptsType` does a *prefix* match against any advertised namespace, so
both the namespace itself and any message type underneath it return `true`:

```kotlin
extensions.acceptsType("arcpx.acme.email.v1")          // true — advertised
extensions.acceptsType("arcpx.acme.email.v1.parsed")   // true — same namespace
extensions.acceptsType("arcpx.acme.weather.v1")        // false — not advertised
```

`extensions.advertised: Set<String>` returns the live snapshot.

## Emitting extension events

Use `EventEmit` with a namespaced `eventType`:

```kotlin
client.send(sessionId, EventEmit(
    eventType = "arcpx.acme.email.v1.parsed",
    data      = buildJsonObject {
        put("subject",  "Q3 report")
        put("sender",   "alice@acme.com")
        put("thread",   "t_xyz")
    },
))
```

The wire `type` for `EventEmit` is always `event.emit`; the vendor namespace
lives in the `event_type` payload field, not the envelope `type` discriminator.

## Custom wire message types (advanced)

To define a fully custom wire type that participates in polymorphic
deserialization, register a `@SerialName` subclass of `MessageType` and
configure `arcpJson`:

```kotlin
@Serializable
@SerialName("arcpx.acme.email.v1.send")
data class AcmeEmailSend(
    val to:      String,
    val subject: String,
    val body:    String,
) : MessageType

// Then extend arcpJson with a module that includes AcmeEmailSend
```

This is an advanced integration point; for most use cases `EventEmit` is
sufficient.
