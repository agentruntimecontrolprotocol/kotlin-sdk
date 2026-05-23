# Contributing to dev.arcp:arcp

Thanks for your interest in improving the Kotlin SDK for ARCP. This
document covers how to report issues, propose changes, and get a change merged.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Where changes belong

ARCP is two things in two places, and a change belongs to exactly one of them:

- **The protocol** — the wire format, message semantics, lease rules, error
  taxonomy, feature flags. These live in the
  [specification repository](https://github.com/agentruntimecontrolprotocol/spec).
  If your idea changes what goes *on the wire* or what a conformant runtime must
  do, it is a spec change — open it there, not here. This SDK implements the
  spec; it does not define it.
- **This SDK** — how the protocol is expressed idiomatically in Kotlin:
  bugs, ergonomics, performance, missing-but-specified features, docs, tests.
  Those belong here.

When in doubt, open an issue here and we'll redirect if it's really a protocol
question.

## The golden rule: conform, don't extend

A change to this SDK must keep it a faithful client of
[ARCP v1.1 (draft)](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
Concretely:

- **Don't invent wire behavior.** No envelope fields, event kinds, error codes,
  or feature flags that the spec doesn't define. If you need one, it's a spec
  proposal first.
- **Negotiate honestly.** Only advertise a feature flag in `session.hello` once
  the SDK actually implements it. The feature matrix in the README must match
  what the code negotiates — a row marked `Supported` is a promise.
- **Respect the semantics.** Sequence numbers stay gap-free and monotonic;
  `LEASE_EXPIRED` and `BUDGET_EXHAUSTED` stay non-retryable; the effective
  feature set is the intersection of client and runtime advertisements. Tests
  must not paper over a semantic the spec requires.
- **Stay layered.** This SDK controls runtimes. It does not expose tools (that's
  MCP) or export telemetry (that's OpenTelemetry). PRs that blur those layers
  will be asked to move the logic out.

## Reporting bugs

Open an issue with: the SDK version and Kotlin version, the runtime you
connected to, a minimal reproduction (the smallest program that triggers it),
what you expected, and what happened. A failing test is the best possible bug
report. Wire-level traces (the envelopes exchanged) help enormously for protocol
behavior — redact any `auth.token` or provisioned-credential `value` first.

## Proposing a change

For anything beyond a small fix, open an issue describing the problem before
writing code, so we can agree on the approach. Small, focused PRs review faster
than large ones; if a change is big, say so early and we'll help break it down.

## Development setup

This is a Gradle multi-module build (`:lib`, `:cli`, `:samples`, `:tests`,
`:recipes`) targeting Kotlin 2.3.21 on the JVM. You need JDK 21 or newer; the
Gradle wrapper is checked in, so no separate Gradle install is required (the
foojay resolver will auto-provision a matching toolchain on first run).

```sh
git clone https://github.com/agentruntimecontrolprotocol/kotlin-sdk.git
cd kotlin-sdk
./gradlew assemble
```

To install the library into your local Maven repository for use from another
project:

```sh
./gradlew :lib:publishToMavenLocal
```

## Tests and conformance

Two layers must pass before a PR merges:

- **Unit tests** — this SDK's own suite:

  ```sh
  ./gradlew test
  ```

- **Conformance** — the SDK's behavior against the reference runtime. New
  protocol-facing code (session negotiation, event sequencing, lease handling,
  error mapping) needs a test that exercises the real exchange, not a mock that
  assumes the answer. The `:tests` module wires `ARCPClient` and `ARCPRuntime`
  together over `MemoryTransport` to exercise full end-to-end flows; run it
  with `./gradlew :tests:test`. See [CONFORMANCE.md](CONFORMANCE.md) for the
  section-by-section coverage matrix.

CI runs both on every PR. A PR that changes which feature flags the SDK
negotiates must also update the README feature matrix in the same change.

## Coding standards

This repo enforces formatting with ktlint, static analysis with detekt, and
public-API stability with the Kotlin binary-compatibility validator. The
library module is compiled with `explicitApi()` and `allWarningsAsErrors`, so
every public declaration needs an explicit visibility modifier and KDoc.

```sh
./gradlew :lib:ktlintCheck :cli:ktlintCheck :tests:ktlintCheck
./gradlew :lib:detekt :cli:detekt :tests:detekt
./gradlew :lib:apiCheck
```

Auto-fix formatting with `./gradlew ktlintFormat`. See `KOTLIN_STYLE.md` for
the project's idiomatic-Kotlin conventions.

Match the surrounding code. Public API changes need doc comments and an entry in
the changelog. Prefer clarity over cleverness in a library others build on.

## Commit and pull-request conventions

- Write focused commits with present-tense, imperative subjects
  (`add result_chunk reassembly`, not `added` / `adds`).
- Reference the issue a PR closes (`Closes #123`).
- Keep the PR description honest about scope and any spec sections touched.
- Rebase on the default branch and ensure CI is green before requesting review.
- Sign off your commits to certify the [Developer Certificate of Origin](https://developercertificate.org/):

  ```sh
  git commit -s -m "your message"
  ```

## Releases

Releases are cut by maintainers. Pushing a `vX.Y.Z` tag triggers the `publish`
GitHub Actions workflow, which builds, signs, and uploads the `dev.arcp:arcp`
artifact bundle to the Sonatype Central Portal (Maven Central) via the
`publishAggregationToCentralPortal` task. The SDK is versioned with
semantic versioning independently of the protocol version it speaks; a protocol
version bump is noted in the changelog when the negotiated ARCP version changes.

## License

By contributing, you agree that your contributions are licensed under the
project's [Apache-2.0](LICENSE) license.
