package com.arcp.recipes.streamresume

import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main(): Unit =
    runBlocking {
        val (clientTransport, serverTransport) = MemoryTransport.pair()
        val serverJob = launch { runServer(serverTransport) }
        runClient(clientTransport)
        serverJob.cancel()
    }
