package dev.fizzpop.arcp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dev.fizzpop.arcp.Version

/**
 * `arcp` — command-line tool for working with ARCP runtimes.
 *
 * v0.1 ships the `version` subcommand; the protocol-driving subcommands
 * (`serve`, `tail`, `send`, `replay`) come online as the corresponding
 * runtime/transport surfaces ship in v0.2.
 */
public class ArcpCli : CliktCommand(name = "arcp") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = "ARCP Kotlin SDK command-line tool"

    override fun run(): Unit = Unit
}

private class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Print SDK and protocol version"

    override fun run() {
        echo("ARCP protocol: ${Version.PROTOCOL_VERSION}")
        echo("Kotlin SDK:    ${Version.SDK_VERSION}")
        echo("SDK kind:      ${Version.SDK_KIND}")
    }
}

private class ServeCommand : CliktCommand(name = "serve") {
    private val transport by option().default("memory")

    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Run an ARCP runtime (transport implementations are v0.2)"

    override fun run() {
        echo("transport=$transport — runtime serve mode is v0.2")
    }
}

public fun main(args: Array<String>) {
    ArcpCli().subcommands(VersionCommand(), ServeCommand()).parse(args)
}
