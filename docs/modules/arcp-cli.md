# Module: arcp-cli (`dev.arcp:arcp-cli`)

The `:cli` Gradle module provides the `arcp` command-line binary, built on
top of the `:lib` protocol library.

**Maven coordinates**: `dev.arcp:arcp-cli:1.1.0`

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

Print SDK and protocol version information:

```
$ arcp version
ARCP protocol: 1.1
Kotlin SDK:    1.1.0
SDK kind:      kotlin
```

### `arcp serve` *(v0.2)*

Run an ARCP runtime over a named transport:

```
$ arcp serve --transport=websocket --port=8080
```

> Not functional in v0.1. Prints `"runtime serve mode is v0.2"`.

### `arcp send` *(v0.2)*

Submit a job to a running runtime:

```
$ arcp send --url=wss://runtime.example.com/arcp \
            --agent=summarise@1.0.0 \
            --token=my-bearer-token
```

> Not functional in v0.1.

### `arcp replay` *(v0.2)*

Replay a session log from a SQLite `EventLog` file:

```
$ arcp replay --db=session.db --session=sess_abcde
```

> Not functional in v0.1.

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
fun main(args: Array<String>) = ArcpCli().main(args)
```
