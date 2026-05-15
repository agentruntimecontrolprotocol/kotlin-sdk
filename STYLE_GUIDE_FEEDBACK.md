# Style Guide Feedback

Cases where literal application of `KOTLIN_STYLE.md` produced a worse
result for this SDK. Logged per `REFACTOR_AGENT.md` §"On Disagreement".

---

## §1 — "Never expose `data class` in the public API"

**Where:** `lib/src/main/kotlin/dev/arcp/messages/*.kt` (79 declarations),
`dev/arcp/envelope/Envelope.kt`, `dev/arcp/runtime/CapabilityNegotiation.kt`,
`dev/arcp/runtime/SessionState.kt`, `dev/arcp/trace/TraceContext.kt`.

**Observed downside:**

These are the ARCP wire-protocol message catalog. Every class is a
`@Serializable` value type whose property names exactly match the
protocol's JSON field names. The rule's premise — that adding a property
silently changes `componentN()` — does not apply because:

1. The catalog is versioned by the ARCP RFC, not freely extended; field
   additions are intentional protocol changes that bump the spec version.
2. Destructuring is not part of the SDK's published consumer surface;
   consumers read named properties.
3. Replacing `data class` with hand-rolled `equals`/`hashCode`/`toString`/
   `copy` per record requires either parallel `@Serializer(forClass=...)`
   plumbing or losing the kotlinx-serialization compiler-generated
   serializers — both degrade the API and increase the surface area we
   maintain.

**Proposed amendment:** Carve out `@Serializable` value types pinned to
an external schema (wire protocol, IPC catalog) from the §1 prohibition
on public `data class`. Require KDoc on the class and `@SerialName` on
every field — both already present here.

---

## §13 — "Every public symbol has KDoc. No exceptions."

**Where:** ~317 `@SerialName`-annotated public properties on the
catalog `data class`es above.

**Observed downside:** The property name *is* the protocol field name
(by construction, via `@SerialName`). A KDoc that says `/** The nonce. */`
above `val nonce: ByteArray` adds noise without information; the meaning
is established by the RFC and by the enclosing class KDoc. Enforcing
per-property KDoc on the catalog would inflate the line count by ~30%
purely for boilerplate.

**Proposed amendment:** When a property carries `@SerialName` and the
enclosing class KDoc references the spec section that defines the field,
a separate property-level KDoc is not required. Detekt's
`UndocumentedPublicProperty` remains off for this codebase;
`UndocumentedPublicClass` and `UndocumentedPublicFunction` are enforced.
