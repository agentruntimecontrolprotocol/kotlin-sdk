# CLI — `arcp`

The `arcp` binary is a thin JVM command-line tool built on the SDK library.
It is distributed as the `:cli` Gradle module.

> **Status**: v0.1 ships the `version` subcommand and a `serve` stub. The
> protocol-driving subcommands (`tail`, `send`, `replay`) plus a real
> `serve` implementation are scheduled for v0.2, when the WebSocket and
> stdio transports land. The `:cli` module is not currently published to
> Maven Central — build it from source.

---

## Building the binary

```bash
./gradlew :cli:installDist
# Binary is placed at:
./cli/build/install/arcp/bin/arcp
```

Or run directly through Gradle:

```bash
./gradlew :cli:run --args="version"
```

---

## Commands

### `arcp version`

Print SDK and protocol versions. The `Kotlin SDK` line reflects
`dev.arcp.Version.SDK_VERSION` (currently `0.1.0` while the CLI matures
ahead of the library v1.1 release):

```
$ arcp version
ARCP protocol: 1.1
Kotlin SDK:    0.1.0
SDK kind:      arcp-kotlin-sdk
```

### `arcp serve`

Stub command — accepts `--transport=<name>` (default `memory`) and prints a
v0.2 placeholder message. Real runtime hosting lands once the WebSocket and
stdio transports ship:

```
$ arcp serve --transport=memory
transport=memory — runtime serve mode is v0.2
```

### `arcp send` *(v0.2)*

> Not implemented in v0.1 — `arcp send …` will exit with "no such
> subcommand". The intended shape is:

```
$ arcp send --url=wss://runtime.example.com/arcp \
            --agent=summarise@1.0.0 \
            --token=my-bearer-token
```

### `arcp replay` *(v0.2)*

> Not implemented in v0.1. The intended shape is:

```
$ arcp replay --db=session.db --session=sess_abcde
```

---

## Shell completion *(v0.2)*

Completion scripts for bash, zsh, and fish will be generated automatically
by the Clikt framework when the `--generate-completion` option lands.
