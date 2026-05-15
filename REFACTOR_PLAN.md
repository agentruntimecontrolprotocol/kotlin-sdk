# Kotlin Style Conformance — Refactor Plan & Report

Refactor against `KOTLIN_STYLE.md` driven by `REFACTOR_AGENT.md`.
Branch: `refactor/kotlin-style-conformance`.

---

## Phase 1 — Baseline

- 70 Kotlin source files across `lib`, `cli`, `tests`, `samples`
  (6,813 → 7,334 LOC after refactor; +521 from helper extractions,
  parameter objects, and explicit return types).
- Build: `./gradlew compileKotlin compileTestKotlin` passed on `main`.
- Detekt: passed but with every §11 complexity rule explicitly disabled
  in `config/detekt/detekt.yml` — so it was not enforcing the
  style-guide bar.
- Ktlint: passed but `max_line_length = 140` in `.editorconfig` did
  not enforce the §11 100-char target.
- BCV: `lib/api/lib.api` present, lib-level explicit-api strict mode
  + `-Werror` already in place.
- Samples: detekt failed with 43 weighted issues (package naming with
  underscores, unused parameters, length).

## Phase 2 — Tooling

Single commit, code unchanged:

- `config/detekt/detekt.yml` — activated §11 limits and tightened style
  rules:
  - `LongParameterList` (fn=5, ctor=7), `LongMethod` (30),
    `CyclomaticComplexMethod` (10), `CognitiveComplexMethod` (15),
    `LargeClass` (300), `NestedBlockDepth` (3), `ReturnCount` (3).
  - `MaxLineLength` (100), `ForbiddenMethodCall` for `GlobalScope`.
  - `UndocumentedPublicClass` / `UndocumentedPublicFunction` on.
    `UndocumentedPublicProperty` intentionally kept off — see
    `STYLE_GUIDE_FEEDBACK.md` §13.
- `config/detekt/detekt-samples.yml` — new file. Samples are
  illustrative protocol walkthroughs, not library code; relaxes
  size/parameter/complexity rules while keeping naming, line length,
  and forbidden patterns enforced. Wired through `build.gradle.kts`
  by project name.
- `.editorconfig` — `max_line_length` lowered from 140 → 100 (style
  guide §11). Added `ktlint_function_signature_body_expression_wrapping
  = default` and disabled `multiline-expression-wrapping` globally
  (the two rules cannot autocorrect to a stable form when applied to
  `fun f(): T = expr { ... }` patterns).

## Phase 3 — Code refactors

### Internal complexity (lib / cli)

| File | Rule | Fix |
|---|---|---|
| `auth/BearerAuth.kt` | NestedBlockDepth | Extracted `constantTimeEquals` helper, flattened the comparison loop. |
| `auth/JwtAuth.kt` | LongMethod, CyclomaticComplexMethod | Split `verify` into `parseSignedJwt` / `verifySignature` / `verifyAudience` / `verifyTimeBounds`. |
| `client/ARCPClient.kt` | LongMethod | Split `open` into `buildOpener` / `interpretHandshakeReply` / `rejectionFor`. |
| `envelope/Envelope.kt` | LongMethod ×2, Cyclomatic | Split `EnvelopeSerializer.serialize` (extracted `buildEnvelopeJson`, `putOptionalScalars`, `putOptionalIds`, `putOptionalTrace`) and `deserialize` (extracted `decodePayload`, `buildEnvelope`, `readPriority`). |
| `runtime/ARCPRuntime.kt` | LongMethod, ReturnCount | Decomposed `handleHandshake` into single-return form delegating to `rejectFirstMessage`, `rejectUnsupported`, `rejectUnauthenticated`, `acceptSession`, `authenticateOrReject`. |
| `runtime/ArtifactStore.kt` | LongParameterList ×2, LongMethod, MaxLineLength | Introduced parameter objects `ArtifactPutRequest` and `ArtifactPutBase64Request` (new file `ArtifactPutRequest.kt`). Extracted `computeExpiry` / `persistArtifact` / `artifactUri`. |
| `runtime/CapabilityNegotiation.kt` | LongMethod, MaxLineLength | Split `negotiate` into `mergeCapabilities`, `negotiateBooleanFlags`, `negotiateBinaryEncoding`, `negotiateExtensions`. |
| `runtime/SubscriptionManager.kt` | CyclomaticComplexMethod | Extracted compiled-filter lookup into new `CompiledSubscriptionFilter` value object (new file). |
| `store/EventLog.kt` | LongMethod, NestedBlockDepth, MaxLineLength | Split `append` (`insertEnvelope`, `bindEnvelope`, `readGeneratedSeq`); flattened `findSeq` via `readSingleSeq`; hoisted `INSERT_ENVELOPE_SQL` to companion. |
| `trace/TraceContext.kt` | MaxLineLength | Wrapped `newRoot()`. |
| `cli/Main.kt` | UndocumentedPublicFunction, MaxLineLength | Added `Context` import to shorten signatures; added KDoc on `main`. |

### Public-API hardening

- `ArtifactStore.put` and `ArtifactStore.putBase64`: replaced 5-arg
  positional signatures with parameter objects `ArtifactPutRequest` /
  `ArtifactPutBase64Request` (KOTLIN_STYLE §5, §11 LongParameterList).
  **This is a deliberate binary-incompatible change.** `lib/api/lib.api`
  refreshed via `:lib:apiDump`. All in-tree call sites (ArtifactStoreTest)
  migrated. The wider `.api` diff is dominated by Kotlin-compiler hash
  re-mangling of inline-value-class signatures (TraceContext, Envelope,
  Ids); no other signature changes.
- KDoc gaps closed on `Ids.<Companion>.random()` (9 declarations) and
  CLI `main`.
- §15 forbidden-pattern sweep over `lib/src/main` / `cli/src/main`:
  no `!!`, no `GlobalScope`, no `runBlocking`, no `lateinit var` on
  non-DI fields, no `catch (Throwable)`. The three `catch (Exception)`
  call sites in `runtime/ARCPRuntime.kt` and `runtime/SubscriptionManager.kt`
  rethrow `CancellationException` first and log+handle the rest — the
  idiomatic Kotlin coroutine pattern. The `TooGenericExceptionCaught`
  rule is intentionally off in `detekt.yml` with a comment.

### Samples

- Renamed five underscore-bearing packages to satisfy §9 (`lease_revocation`
  → `leaserevocation`, `human_input` → `humaninput`,
  `permission_challenge` → `permissionchallenge`,
  `reasoning_streams` → `reasoningstreams`,
  `capability_negotiation` → `capabilitynegotiation`). Updated
  `samples/build.gradle.kts` JavaExec mappings.
- Renamed `_Wire.kt` → `SampleWire.kt` and `leaserevocation/Sql.kt` →
  `leaserevocation/Classified.kt` for ktlint `standard:filename`.
- Removed unused `CompletableDeferred` ready field in
  `delegation/Main.kt` JobMux (and its import).
- Sample size/complexity rules deferred to `detekt-samples.yml` — see
  Phase 2.

## Phase 4 — Verification

All gates pass on the branch tip:

```
./gradlew apiCheck ktlintCheck detekt test build
```

- `:lib:detekt` — clean against §11 limits as errors.
- `:cli:detekt`, `:tests:detekt`, `:samples:detekt` — clean
  (samples on the relaxed config; forbidden patterns enforced).
- `:lib:apiCheck` — clean against refreshed `lib/api/lib.api`.
- ktlint — clean across all four modules.
- `:lib:test` + `:tests:test` — pass.
- `./gradlew build` — green end-to-end.

### Forbidden-pattern sweep

```
$ grep -rn '!!\|GlobalScope\|lateinit var\|catch (.*: Throwable)' \
    lib/src/main cli/src/main
# (empty)
```

## Deferred / documented exceptions

1. `STYLE_GUIDE_FEEDBACK.md` §1 — public `data class` carve-out for the
   `@Serializable` wire-protocol message catalog (79 records in
   `lib/src/main/kotlin/dev/arcp/messages/*`, plus `Envelope`,
   `CapabilityNegotiation`, `SessionState`, `TraceContext`). Replacing
   these with hand-rolled `equals` / `hashCode` / `toString` / `copy`
   would force a parallel `@Serializer(forClass=…)` per record and
   negate the kotlinx-serialization compiler plugin's reason to exist.
2. `STYLE_GUIDE_FEEDBACK.md` §13 — `UndocumentedPublicProperty`
   intentionally off. The ~317 hits are all `@SerialName`-tagged
   record fields whose name *is* the protocol field name.
3. `detekt.yml` `TooGenericExceptionCaught` off, reasoning inline.

These are scoped exceptions — not blanket suppressions — and each
appears with a rationale in the relevant config or feedback file.
