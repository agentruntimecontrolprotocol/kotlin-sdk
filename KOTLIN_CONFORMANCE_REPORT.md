# Kotlin Conformance Pass — Report

**Branch**: `chore/kotlin-conformance-pass`
**Base**: `main` @ `b980179` (initial import — repo had no prior git history)
**Date**: 2026-05-10
**Scope**: `:lib`, `:cli`, `:samples`, `:tests` (Kotlin 2.0.21, JDK 21)

## Top-line

The `kotlin-sdk` was already in **strong** idiomatic shape going in. The pass found no `!!` in production source, no `GlobalScope`, no `runCatching` in production, no wildcard imports, no `@OptIn` cruft, no scope-function misuse, and no obvious anti-pattern collection chains. The `explicitApi()` strict mode passed on first try with one trivial omission. So this report is largely about **locking in what already holds** with tooling, plus two surfaced findings that are out-of-scope for a conformance pass and need separate, deliberate work.

**Test parity**: 111 tests across 8 test suites passed before the pass and pass after (`:lib:test` + `:tests:test`, zero skipped, zero failures, zero errors).

**Build**: `./gradlew clean build :lib:apiCheck` is green at branch HEAD.

## Phase 0 — discovery

| Aspect | Before | After |
|---|---|---|
| Git history | none | branch `chore/kotlin-conformance-pass`, 4 commits (excluding baseline import) |
| `.editorconfig` | absent | present, codifies de facto style |
| binary-compatibility-validator | not wired | wired on `:lib`, `lib/api/lib.api` checked in (3,317 lines) |
| `explicitApi()` mode | implicit (manual) | strict (compiler-enforced) on `:lib` |
| Kotlin version, JVM target | 2.0.21 / JDK 21 | unchanged |
| `allWarningsAsErrors` | every module | unchanged (Phase 0 first-pass table was wrong; corrected in `.claude/kotlin-conformance-discovery.md`) |
| `-Xjsr305=strict`, `-Xjvm-default=all` | `:lib` only | unchanged (consumer modules don't need them) |
| detekt rules | many off | `WildcardImport`, `ForbiddenComment` re-enabled |

## Phase 1 — tooling commits

| Commit | Subsection | Files | Diff size | Risk | Notes |
|---|---|---|---|---|---|
| `677c9bd` | 1a — `.editorconfig` | 1 added | +25 | none | Codifies indent, line endings, trailing commas, max_line_length=140 (p99 of current source is 98 chars). |
| `db75eaa` | 1b — binary-compatibility-validator | 2 + `lib/api/lib.api` | +3,322 | none | Plugin 0.16.3. The 3,317-line API dump is now a reviewable diff signal. Surfaced one ABI hazard inherent to the existing code: value-class parameters generate mangled function names (e.g. `ARCPClient.send-WmJQgEs`). The validator now protects against silent changes to these. |
| `a0aca9c` | 1c — `explicitApi()` strict on `:lib` | 2 | +3 / -1 | low | One real fix: `ARCPClient.receive()` was missing its explicit return type. The inferred `Flow<Envelope>` was already correct — `apiCheck` confirmed no ABI change. |
| `1168815` | 1e — detekt `WildcardImport` + `ForbiddenComment` | 1 | +18 / -2 | none | Both rules currently produce zero violations; this locks in conventions that already hold. `MagicNumber` and `MatchingDeclarationName` deliberately left off — see "Rules not enforced" below. |

Phase 1d (extend `allWarningsAsErrors` to `:samples` and `:tests`) was a **no-op** — initial Phase 0 reading missed that those modules already had it. Discovery file corrected.

## Phase 2 — code audits

All four audits found no edits worth making.

| Subsection | What I checked | Result |
|---|---|---|
| 2.5 / 2.9 — cancellation safety | All `runCatching` and `catch (e: Exception/Throwable)` sites | One `runCatching` (test-only, synchronous, safe). Two `catch (e: Exception)` sites in coroutine code — **flagged below as out-of-scope findings.** |
| 2.6 — scope functions | All `let`/`run`/`with`/`apply`/`also` usages | 22 uses, all `nullable?.let { … }` or `?.let(::ValueClass)`. Zero `apply`/`run`/`with`/`also`. No nested pyramids. Idiomatic. |
| 2.8 — collection idioms | `filterNotNull`, `groupBy.mapValues`, `filter.firstOrNull`, mutable collection construction, manual loops | Single `mutableListOf` is a properly-scoped accumulator returning an immutable view. Two `for` loops are ULID bit-encoding state machines that don't reduce to higher-order ops. |
| 2.16 — opt-ins | `@OptIn`, `@Experimental`, `-opt-in=` flags | Zero usage. Codebase is built on stable APIs only. |

## Surfaced findings — out of scope for this pass

These were noted during the audits and **deliberately not fixed**, per the prompt's "no drive-by edits" and "no behavioral drift" rules. Each warrants its own deliberate change.

### 1. Two cancellation-swallowing catches in `ARCPRuntime`

**Files**: [`lib/src/main/kotlin/dev/fizzpop/arcp/runtime/ARCPRuntime.kt:79`](lib/src/main/kotlin/dev/fizzpop/arcp/runtime/ARCPRuntime.kt#L79) and [`:99`](lib/src/main/kotlin/dev/fizzpop/arcp/runtime/ARCPRuntime.kt#L99)

```kotlin
// line 75–82
public fun accept(transport: Transport): Job =
    scope.launch {
        val opener =
            try {
                transport.receive().first()
            } catch (e: Exception) {              // ← catches CancellationException
                log.warn(e) { "transport closed before session.open" }
                return@launch
            }

// line 94–102
private suspend fun runDispatchLoop(transport: Transport) {
    try {
        transport.receive().collect { env -> handleEnvelope(env, transport) }
    } catch (e: Exception) {                       // ← catches CancellationException
        log.info(e) { "session ended" }
    }
}
```

`CancellationException extends Exception`, so when the runtime's `SupervisorJob` is cancelled (e.g. from `runtime.close()`), the cancel signal is intercepted and logged as "transport closed before session.open" or "session ended" rather than propagating. This violates Kotlin structured-concurrency contracts and would mislead operators reading the logs during a clean shutdown.

**Suggested fix** (do not bundle into this pass; behavior change):
```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.warn(e) { "transport closed before session.open" }
    return@launch
}
```

The same pattern applies to line 99. A single PR with both fixes plus a regression test (verify that cancelling `runtime.scope` propagates a `CancellationException` rather than being swallowed) is the right shape.

### 2. CONFORMANCE.md mentions `// TODO(v0.2):` markers that don't exist in code

`CONFORMANCE.md` currently states *"core present, edges deferred (v0.2 candidate). `// TODO(v0.2):` markers remain in code"* in its status legend. A repo-wide grep of all `.kt`/`.kts` files for `TODO|FIXME|STOPSHIP|XXX` returns **zero hits**. Either the markers were a future-tense aspiration that wasn't implemented, or they were stripped. The new `ForbiddenComment` detekt rule will now reject any future addition — so if the `// TODO(v0.2):` convention is desired, it'll need to be unblocked (e.g., by allowing `TODO(v0.2):` specifically), or `CONFORMANCE.md` should drop the claim. **Decision needed from the team.**

## Rules deliberately not enforced

| Rule | Why not |
|---|---|
| detekt `MagicNumber` | The only candidates in the codebase are JDBC positional `ps.setString(N, ...)` parameter indices in `EventLog.kt`. That's not what `MagicNumber` is meant to catch. The minimum `ignoreNumbers` configuration to silence the false positives would be so broad it would reduce the rule to noise. Re-evaluate when business logic with literal constants appears. |
| detekt `MatchingDeclarationName` | The `messages/` package deliberately groups related sealed-interface implementations into topical files (`Session.kt` has 17 declarations, `Execution.kt` has 17, `Control.kt` has 13). This is exactly the pattern endorsed by the prompt's §2.17 ("Multiple small related sealed subclasses, value classes, or extension files are fine in one file"). Enabling this rule would force splitting into ~80 single-class files, which is anti-idiomatic. |
| detekt `MaxLineLength` | `.editorconfig` already declares `max_line_length = 140` and ktlint enforces it on format. No need for a second enforcer. |
| detekt complexity rules (`LongMethod`, `LongParameterList`, `CyclomaticComplexMethod`, `LargeClass`, `TooManyFunctions`) | Left in their current "off" state from the existing config. These are judgment calls that the team explicitly tuned out; this pass is not the place to re-litigate. |
| `-Xexplicit-api=strict` on `:cli`/`:samples`/`:tests` | These are application/test modules with no public API surface. `explicitApi()` only matters for libraries. |
| `-Xjsr305=strict` on `:cli`/`:samples`/`:tests` | Useful at Kotlin/Java boundaries with JSR-305-annotated Java types. None of these modules consume such APIs. |
| `-progressive` | Has trade-offs (can break source compatibility within a major version). Not adding it without explicit team buy-in on the trade-off. |
| Phase 2.13 — Java interop annotations (`@JvmStatic`, `@JvmOverloads`, etc.) | Per user input, `:lib` is Kotlin-only consumption. None of these are needed. |

## Files changed

```
.editorconfig                                                | +25
config/detekt/detekt.yml                                     | +18 -2
gradle/libs.versions.toml                                    | +2
lib/api/lib.api                                              | +3,317  (generated)
lib/build.gradle.kts                                         | +2
lib/src/main/kotlin/dev/fizzpop/arcp/client/ARCPClient.kt    | +2 -1
```

Six files, four conformance commits, zero changes to behavior, zero changes to ABI (verified by `:lib:apiCheck`).

## Open questions for the reviewer

1. ~~**Cancellation-swallowing fix**~~ — resolved on branch `fix/runtime-cancellation-swallow` (commit `b1d408e`). Fix is the documented `catch (e: CancellationException) { throw e }` pattern at both sites. A clean unit-level regression test would require log-capture infrastructure or a non-supervisor scope wiring change; both are out of scope for the fix itself.
2. ~~**TODO/FIXME convention**~~ — resolved on this branch by dropping the false claim from `CONFORMANCE.md`'s status legend. Per-row Notes already enumerate each deferral; the inline-marker convention was never implemented and is not worth introducing now (the new `ForbiddenComment` detekt rule would block it anyway).
3. **API dump review** — `lib/api/lib.api` (3,317 lines) is now committed. Worth a one-pass human review before merging this branch — anything in there that's exposed by accident is now a backwards-compatibility commitment. The mangled-name signature `ARCPClient.send-WmJQgEs` (value-class param hash) in particular: if you ever unwrap that value class, the ABI break will be invisible without this validator.
4. **CLAUDE.md** — there is no project-conventions doc. This pass would have been faster with one. Optional follow-up: write a short `CLAUDE.md` codifying the conventions that are now machine-enforced (`explicitApi()`, no wildcard imports, no TODOs in main, etc.) so future humans and future agents share the same rule set.

## Compliance with operating prompt

- ✅ Phase 0 discovery written before any edits (`.claude/kotlin-conformance-discovery.md`).
- ✅ One conformance category per commit; conventional-style commit messages with rule + scope.
- ✅ No public API signatures changed (`apiCheck` green at HEAD).
- ✅ All edits behavior-preserving (only `explicitApi` fix touched code; verified ABI unchanged).
- ✅ Test parity preserved (111/111 tests pass before and after).
- ✅ No dependency upgrades, no architecture refactors, no async-paradigm rewrites, no test rewrites.
- ✅ Two findings logged-not-fixed; one config gap (TODO markers) flagged for the team.
- ✅ Reporting structure as specified by the prompt.
