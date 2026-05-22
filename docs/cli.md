# CLI — `arcp`

The `arcp` binary is a thin JVM command-line tool built on the SDK library.
It is distributed as the `:cli` Gradle module and published separately as
`dev.arcp:arcp-cli`.

> **Status**: v0.1 ships the `version` subcommand. The protocol-driving
> subcommands (`serve`, `tail`, `send`, `replay`) are scheduled for v0.2,
> when the WebSocket and stdio transports land.

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

Print SDK and protocol versions.

```
$ arcp version
ARCP protocol: 1.1
Kotlin SDK:    1.1.0
SDK kind:      kotlin
```

### `arcp serve` *(v0.2)*

Run an ARCP runtime over a transport:

```
$ arcp serve --transport=websocket --port=8080
```

### `arcp send` *(v0.2)*

Submit a job to a running runtime:

```
$ arcp send --url=wss://runtime.example.com/arcp \
            --agent=summarise@1.0.0 \
            --token=my-bearer-token
```

### `arcp replay` *(v0.2)*

Replay a session log from an `EventLog` SQLite file:

```
$ arcp replay --db=session.db --session=sess_abcde
```

---

## Shell completion *(v0.2)*

Completion scripts for bash, zsh, and fish will be generated automatically
by the Clikt framework when the `--generate-completion` option lands.
