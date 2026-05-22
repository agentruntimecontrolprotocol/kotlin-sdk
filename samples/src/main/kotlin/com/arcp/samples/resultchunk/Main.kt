package com.arcp.samples.resultchunk

import dev.arcp.client.ResultChunkAssembler
import dev.arcp.messages.JobResult
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.ResultChunkEncoding

public fun main() {
    val assembler = ResultChunkAssembler()
    val chunks =
        listOf(
            JobResultChunk("report", 0, "quarterly ", ResultChunkEncoding.UTF8, more = true),
            JobResultChunk("report", 1, "summary", ResultChunkEncoding.UTF8, more = false),
        )
    val assembled = chunks.mapNotNull(assembler::accept).single()
    val result =
        JobResult(
            finalStatus = "success",
            resultId = assembled.resultId,
            resultSize = assembled.bytes.size.toLong(),
        )
    println("assembled ${assembled.bytes.decodeToString()} (${result.resultSize} bytes)")
}
