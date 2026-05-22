# Kotlin Public SDK Style Guide

Authoritative rules for writing and refactoring this codebase. Rules are
prescriptive, not suggestive. When a rule says "must" or "never," there is
no judgment call.

---

## 0. Core Principles

- Optimize for the **caller**, not the author. This is a public SDK.
- **Smaller is better**: smaller files, smaller functions, smaller surface.
- **Immutable by default**: mutation is opt-in, never opt-out.
- **Explicit over implicit**: visibility, nullability, types, dispatchers.
- **Binary compatibility is a contract**. Breaking it requires a major bump.
- If a rule and "clever" conflict, the rule wins.

---

## 1. API Visibility & Binary Stability

- Enable `explicitApi()` strict mode in every module. No exceptions.
- Every public symbol carries an explicit `public` modifier. Never implicit.
- Default new code to `internal`. Promote to `public` only with intent.
- `@PublishedApi internal` for symbols referenced from `inline` functions.
- Never expose `data class` in the public API. Use a regular class with
  explicit `equals`/`hashCode`/`toString` and a `copy` method if needed.
  Adding a property to a public `data class` breaks `componentN()`.
- Never expose `MutableList`, `MutableMap`, `MutableSet` publicly. Return
  `List`/`Map`/`Set`. Accept the read-only type at parameter positions.
- Never expose implementation types (`okhttp3.*`, `kotlinx.serialization`
  internals, framework types). Wrap them.
- Prefer `interface` over `abstract class` for extension points.
- Mark classes `final` (the default). Add `open` only with a stated reason
  in KDoc.
- Sealed hierarchies are closed: adding a subtype is a breaking change.
  Document this on the sealed declaration.
- Use `@RequiresOptIn` for experimental APIs. Never ship an unmarked one.
- Run the Kotlin Binary Compatibility Validator (`kotlinx-binary-compatibility-validator`)
  in CI. A diff to `.api` files fails the build.

### §1 amendment — `@Serializable` wire-protocol types

`lib/src/main/kotlin/dev/arcp/messages/*.kt` and adjacent envelope/runtime
value types are `@Serializable` data classes pinned to the ARCP RFC field
names via `@SerialName`. The §1 prohibition on public `data class` does not
apply because:

1. The catalog is versioned by the ARCP RFC; field additions are intentional
   protocol changes that bump the spec version, not free-form evolution.
2. Destructuring is not part of the SDK's published consumer surface.
3. Replacing `data class` with hand-rolled `equals`/`hashCode`/`toString`/
   `copy` per record would lose kotlinx-serialization compiler-generated
   serializers or require parallel `@Serializer(forClass=...)` plumbing.

**Rule:** `@Serializable` value types pinned to an external schema (wire
protocol, IPC catalog) are exempt from the `data class` prohibition.
Every such class must carry KDoc referencing the RFC section, and every
field must carry `@SerialName`.

---

## 2. Java Interop (when SDK targets JVM consumers)

- `@JvmStatic` on every public companion function consumers may call.
- `@JvmOverloads` on every public function with default parameters.
- `@JvmName` to disambiguate when JVM signatures collide.
- `@JvmField` only for true public constants exposed to Java.
- `@Throws(IOException::class, ...)` on suspend or regular functions that
  cross the JVM boundary and throw checked exceptions Java cares about.

---

## 3. Null Safety

- `!!` is **forbidden** in production code. The single allowed exception is
  a line preceded by a `// !!: ` comment proving impossibility,
  and even then prefer `requireNotNull` / `checkNotNull` with a message.
- `lateinit` only for DI-injected or framework-initialized fields. Never
  for lazy logic — use `by lazy`.
- Public API never returns platform types. Annotate Java boundaries with
  `@Nullable` / `@NotNull` (or wrap them) before exposing.
- Prefer the Elvis operator with a meaningful default or `error(...)` over
  nested null checks.
- `requireNotNull(x) { "x must be set before calling foo()" }` over
  `x ?: throw IllegalArgumentException(...)`.

---

## 4. Immutability

- `val` is the default. Reach for `var` only with a comment justifying it.
- Public class properties are `val` unless mutation is the documented contract.
- Prefer `copy()` returning a new instance over in-place mutation.
- Collections in public API are read-only types (`List`, `Set`, `Map`).
- Internal collections may be mutable but never leak. Defensive `.toList()`
  at the boundary if needed.
- `@Immutable` / `@Stable` annotations (where the consumer framework
  defines them) are part of the contract and must be honored.

---

## 5. Functions

- Hard cap: **30 lines** per function body (excluding signature, braces).
  If over, decompose. No exceptions for "it reads fine."
- Hard cap: **5 parameters**. At 6, introduce a parameter object.
- Hard cap: **3 levels of nesting**. Use early returns, guard clauses,
  `when` extraction, or helper functions to flatten.
- Prefer top-level functions for stateless helpers over `object` wrappers.
- Prefer extension functions for utility that reads as a method on a
  type the SDK does not own.
- Default parameters over overload chains.
- Single-expression functions (`fun foo() = bar()`) when the body fits
  one expression cleanly. Do not force it past readability.
- Pure functions where possible. Side effects are named and contained.
- Boolean parameters are a smell. Prefer two functions or an enum.

---

## 6. Classes & Inheritance

- Composition over inheritance. Always.
- `data class` for internal value types only. Never in public API (see §1).
- `object` for singletons. Never a class with a private constructor and
  a `companion object getInstance()`.
- `sealed interface` over `sealed class` unless state is shared.
- Constructor-inject all dependencies. No service locators, no globals.
- `companion object` only for factory functions, constants tied to the type,
  or JVM static interop. Not a dumping ground.
- A class doing two things is two classes. Name them both.
- Hard cap: **300 lines** per class. Over that, the class has multiple
  responsibilities — split it.

---

## 7. Coroutines & Concurrency

- `suspend` functions are the default async primitive. Never callback APIs
  in the public surface.
- `GlobalScope` is **forbidden**. Inject a `CoroutineScope` or use
  `coroutineScope { }` for structured concurrency.
- `Dispatchers` are injected, never hard-referenced in business logic.
  Provide a `CoroutineDispatcher` parameter or a `DispatcherProvider`
  interface. Hardcoded `Dispatchers.IO` is a refactor target.
- Wrap blocking IO with `withContext(io)` at the lowest level that owns
  the blocking call. Never bubble blocking up.
- `Flow` for streams of values. `StateFlow` for state with a current
  value. `SharedFlow` for events without conflation. Never `Channel` in
  public API.
- Cold flows are the default. Document explicitly if you ship a hot flow.
- Cancellation is cooperative: never catch `CancellationException` without
  rethrowing. Use `currentCoroutineContext().ensureActive()` in long loops.
- No `runBlocking` outside of `main`, tests, or JVM-only sync bridges.

---

## 8. Error Handling

- Expected, recoverable outcomes: sealed `Result`-style type the SDK owns.
  Do **not** expose `kotlin.Result` in public API (it is restricted).
- Programmer errors: `require(...)`, `check(...)`, `error(...)` with a
  message that names the failing precondition.
- Unexpected, unrecoverable: throw a typed, SDK-owned exception that
  extends a single public root (e.g. `ARCPException`).
- Every public throwing function lists exceptions in KDoc with `@throws`.
- Never `catch (e: Exception)` without rethrowing, logging *and* handling,
  or narrowing the type. Bare swallows fail review.
- Never `catch (e: Throwable)`. Period.

---

## 9. Naming

- Packages: all lowercase, no underscores, no camelCase. Reverse-domain.
- Classes/Interfaces/Objects: `PascalCase`, noun phrases.
- Functions: `camelCase`, verb phrases. `fetchUser`, not `userFetch`.
- Properties: `camelCase`, noun phrases.
- Constants (`const val`, top-level immutable): `SCREAMING_SNAKE_CASE`.
- Booleans: `is`/`has`/`should`/`can` prefix. `isActive`, `hasPayload`.
- No Hungarian, no type suffixes (`UserManager` is usually a smell —
  what does it *do*?).
- Test functions: `` `does X when Y given Z`() `` backticked sentences.
- No abbreviations unless they are domain-standard (`url`, `id`, `uuid`).
  `mgr`, `svc`, `repo` are forbidden; `Manager`, `Service`, `Repository`
  are at least honest (but see the smell note above).

---

## 10. Idioms & Scope Functions

Use the right scope function. One purpose each:

- `let`: null-safe transform. `value?.let { transform(it) }`.
- `also`: side effect, return the same receiver. Logging, debugging.
- `apply`: configure the receiver, return it. Builders.
- `run`: compute a result from the receiver. Replaces `with` on nullable.
- `with`: compute a result from a non-null argument.

Rules:

- Never nest scope functions. One level only.
- Never use a scope function purely for `it` aliasing. Name the variable.
- Prefer `when` over `if/else if/else` chains of 3+ branches.
- Prefer `when` over a sequence of `is` checks in `if`.
- Always cover `when` exhaustively on sealed types. No `else` branch on
  a sealed `when` — let the compiler enforce.
- Destructuring on `data class` and `Pair`/`Triple` is fine internally,
  forbidden across module boundaries.
- String templates (`"$x"`, `"${obj.field}"`) over concatenation. Always.
- Ranges and sequences over manual indexed loops.
- `buildList { }` / `buildMap { }` over manual `mutableListOf().also { }`.

---

## 11. Complexity Limits (hard, enforced by Detekt)

| Metric                       | Limit |
|------------------------------|-------|
| Cyclomatic complexity / fn   | 10    |
| Cognitive complexity / fn    | 15    |
| Function length (lines)      | 30    |
| Class length (lines)         | 300   |
| File length (lines)          | 500   |
| Function parameter count     | 5     |
| Constructor parameter count  | 7     |
| Nesting depth                | 3     |
| Number of returns / fn       | 3     |
| Line length (chars)          | 100 hard, **80 target** |

Refactor strategies when over limit:

- **Over function length**: extract helpers, replace inline lambdas with
  named functions, collapse `when` into table-driven lookup.
- **Over class length**: extract collaborators, split by responsibility,
  move helpers to top-level or extensions.
- **Over file length**: split by type or feature. One public type per file
  is preferred; never more than three.
- **Over param count**: introduce a parameter object (often a `data class`
  internally) or a builder.
- **Over nesting**: invert conditions, early-return guard clauses, extract.
- **Over cyclomatic**: replace conditional logic with polymorphism, use
  `when` over chained `if`, table-driven dispatch.

---

## 12. File & Function Size Targets

- One public top-level declaration per file is the default.
- Co-locate small, tightly coupled internal helpers in the same file.
- Filename matches the primary public declaration in PascalCase.kt.
- Aim for files under **200 lines**. The 500-line cap is the maximum,
  not a goal.
- Aim for functions under **15 lines**. The 30-line cap is the maximum.
- Line length aspires to **80 characters**. The 100 cap is a forcing
  function for line breaks, not a license.

---

## 13. Documentation (KDoc)

- Every public symbol has KDoc. No exceptions for "obvious" ones.
- First sentence is a single-line summary, ending in a period.
- Document `@param`, `@return`, `@throws`, `@sample`, `@since` as relevant.
- Document **thread safety / coroutine context** assumptions explicitly.
- Document nullability semantics in prose when the type signature is
  ambiguous about meaning (e.g. "null means use default").
- `@sample` to runnable code in a `*-samples` source set when the API is
  non-trivial.
- Mark deprecations with `@Deprecated(message, ReplaceWith(...), level)`
  and a `@since` for when removal is planned.

### §13 amendment — `@SerialName` wire-protocol properties

When a property carries `@SerialName` and the enclosing class KDoc
references the spec section that defines the field, a separate
property-level KDoc is not required. Detekt's `UndocumentedPublicProperty`
remains off for this codebase; `UndocumentedPublicClass` and
`UndocumentedPublicFunction` are enforced.

---

## 14. Build & Tooling (non-negotiable)

- Kotlin compiler: `-Xexplicit-api=strict`, `-Werror`,
  `-Xjvm-default=all` (JVM modules), `-opt-in` per experimental need only.
- ktlint or Spotless with ktlint, default ruleset + project overrides.
  CI fails on lint violation.
- Detekt with the limits in §11 codified in `detekt.yml`. CI fails on
  violation. No baseline suppressions added without a TODO ticket linked.
- Kotlin Binary Compatibility Validator. `.api` files committed.
- Test coverage: line coverage gate on public modules (project-defined
  threshold, not less than 80% on changed code).
- No `// TODO` without an issue link. No `// FIXME` in merged code.

---

## 15. Forbidden Patterns (refactor on sight)

- `!!` (see §3).
- `GlobalScope` (see §7).
- `runBlocking` outside permitted locations (see §7).
- `lateinit var` for non-DI fields (see §3).
- Bare `catch (e: Exception)` / `catch (e: Throwable)` (see §8).
- Public `data class` (see §1 — exemption in §1 amendment).
- Public `MutableList`/`MutableMap`/`MutableSet` (see §1).
- `companion object` used as a static utility dumping ground (see §6).
- `Object.getInstance()` singletons — use `object` (see §6).
- `Boolean` parameters where two functions or an enum would clarify intent.
- `if (x != null) x.foo() else default` — use `x?.foo() ?: default`.
- `for (i in 0 until list.size)` — use `forEachIndexed` or `withIndex()`.
- String concatenation with `+` across more than two operands.
- `getX()`/`setX()` style accessors — use properties.
- Nested ternary-style `if` expressions across more than 3 lines.
- Returning `null` from a function that conceptually returns a collection
  — return an empty collection.
- Magic numbers and strings — extract to named `const val` or enum.

---

## 16. Testing the API (not just the impl)

- Public API has contract tests pinning behavior, not just unit tests
  on internals.
- Test through the public surface. If you cannot, the public surface
  is wrong, not the test.
- Inject `TestDispatcher` for coroutines. Never `delay()` in tests
  without `runTest` virtual time.
- One assertion concept per test. Multiple `assert*` calls fine if they
  describe one behavior.
- Test names describe behavior, not implementation
  (`` `returns empty list when source is empty`() ``).
