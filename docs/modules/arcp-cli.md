# Module: arcp-cli

The `:cli` Gradle module provides the `arcp` command-line binary, built on
top of the `:lib` protocol library.

> The `:cli` module is currently **not published to Maven Central** — only
> the `:lib` artifact (`dev.arcp:arcp`) is in the root project's
> `nmcpAggregation` set. Build the CLI from source via `./gradlew
> :cli:installDist`.

---

## Building

```bash
./gradlew :cli:installDist
# Binary placed at:
./cli/build/install/arcp/bin/arcp
```

Or run directly via Gradle:

```bash
./gradlew :cli:run --args="version"
```

---

## Commands

### `arcp version`

Print SDK and protocol version information. `Kotlin SDK` reads from
`dev.arcp.Version.SDK_VERSION` (currently `0.1.0` — the CLI version trails
the library while the protocol-driving subcommands are under construction):

```
$ arcp version
ARCP protocol: 1.1
Kotlin SDK:    0.1.0
SDK kind:      arcp-kotlin-sdk
```

### `arcp serve`

v0.1 stub. Accepts `--transport=<name>` (default `memory`) and prints a
placeholder message — real runtime hosting lands in v0.2 with the WebSocket
and stdio transports.

```
$ arcp serve --transport=memory
transport=memory — runtime serve mode is v0.2
```

### `arcp send` *(v0.2 — not yet registered)*

Intended to submit a job to a running runtime. Not present as a subcommand
in v0.1; only mentioned in the CLI class docstring as a roadmap item.

### `arcp replay` *(v0.2 — not yet registered)*

Intended to replay a session log from a SQLite `EventLog` file. Not present
as a subcommand in v0.1.

---

## Shell completion *(v0.2)*

Bash, zsh, and fish completion scripts will be generated automatically by
the Clikt framework when `--generate-completion` lands in v0.2.

---

## Entry point

`dev.arcp.cli.main` — the JVM `main` function. The binary is assembled by
the `application` plugin and distributed as a zip/tar via
`:cli:distZip` / `:cli:distTar`.

```kotlin
// cli/src/main/kotlin/dev/arcp/cli/Main.kt
public fun main(args: Array<String>) {
    ArcpCli().subcommands(VersionCommand(), ServeCommand()).parse(args)
}
```
