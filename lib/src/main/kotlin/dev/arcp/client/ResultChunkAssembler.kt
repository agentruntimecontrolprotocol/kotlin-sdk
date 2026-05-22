package dev.arcp.client

import dev.arcp.error.ARCPException
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.ResultChunkEncoding
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Client-side accumulator for `result_chunk` streams (RFC v1.1 §8.4). */
public class ResultChunkAssembler(
    private val maxAssembledSize: Long = DEFAULT_MAX_ASSEMBLED_SIZE,
) {
    private val buffers: MutableMap<String, MutableList<JobResultChunk>> = mutableMapOf()

    /** Accepts a chunk and returns the assembled result when the final chunk arrives. */
    public fun accept(chunk: JobResultChunk): AssembledResult? {
        val chunks = buffers.getOrPut(chunk.resultId) { mutableListOf() }
        validateChunk(chunks, chunk)
        chunks += chunk
        val size = chunks.sumOf { decodedBytes(it).size.toLong() }
        if (size > maxAssembledSize) {
            throw ARCPException.Internal("assembled result exceeds $maxAssembledSize bytes")
        }
        if (chunk.more) return null
        buffers.remove(chunk.resultId)
        val allBytes = chunks.flatMap { decodedBytes(it).asIterable() }.toByteArray()
        return AssembledResult(
            resultId = chunk.resultId,
            bytes = allBytes,
            isText = chunks.all { it.encoding == ResultChunkEncoding.UTF8 },
        )
    }

    private fun validateChunk(
        chunks: List<JobResultChunk>,
        chunk: JobResultChunk,
    ) {
        val previous = chunks.lastOrNull()
        if (previous != null && chunk.chunkSeq != previous.chunkSeq + 1) {
            throw ARCPException.OutOfRange("result_chunk sequence must be strictly monotonic")
        }
        val first = chunks.firstOrNull()
        if (first != null && first.encoding != chunk.encoding) {
            throw ARCPException.FailedPrecondition("mixed result_chunk encodings are not allowed")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodedBytes(chunk: JobResultChunk): ByteArray = when (chunk.encoding) {
        ResultChunkEncoding.UTF8 -> chunk.data.encodeToByteArray()
        ResultChunkEncoding.BASE64 -> Base64.Default.decode(chunk.data)
    }

    public data class AssembledResult(
        val resultId: String,
        val bytes: ByteArray,
        val isText: Boolean,
    )

    public companion object {
        public const val DEFAULT_MAX_ASSEMBLED_SIZE: Long = 64L * 1024L * 1024L
    }
}
