package com.arcp.recipes.emailvendorleases

import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.runBlocking

fun main(): Unit =
    runBlocking {
        val (clientTransport, serverTransport) = MemoryTransport.pair()
        val runtime = runServer(serverTransport)
        runClient(clientTransport)
        runtime.close()
    }
