package dev.arcp.client

import dev.arcp.error.ARCPException
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.ResultChunkEncoding
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Client-side accumulator for `result_chunk` streams (RFC v1.1 §8.4).
 *
 * Not thread-safe: use one assembler per result stream and call [accept]
 * from a single coroutine. Sharing an instance across coroutines can
 * corrupt the per-result decode buffers.
 *
 * The assembler decodes each chunk exactly once on arrival and copies
 * decoded bytes into a single output buffer when the terminal chunk
 * arrives, avoiding the O(n) per-chunk re-decode that an earlier
 * implementation incurred near the 64 MiB ceiling.
 */
public class ResultChunkAssembler(
    private val maxAssembledSize: Long = DEFAULT_MAX_ASSEMBLED_SIZE,
) {
    private val buffers: MutableMap<String, ResultBuffer> = mutableMapOf()

    /** Accepts a chunk and returns the assembled result when the final chunk arrives. */
    public fun accept(chunk: JobResultChunk): AssembledResult? {
        val buffer = buffers.getOrPut(chunk.resultId) { ResultBuffer() }
        buffer.validateChunk(chunk)
        val decoded = decodedBytes(chunk)
        buffer.addDecoded(chunk, decoded)
        if (buffer.totalSize > maxAssembledSize) {
            throw ARCPException.Internal("assembled result exceeds $maxAssembledSize bytes")
        }
        if (chunk.more) return null
        buffers.remove(chunk.resultId)
        return AssembledResult(
            resultId = chunk.resultId,
            bytes = buffer.assemble(),
            isText = buffer.allEncoding == ResultChunkEncoding.UTF8,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodedBytes(chunk: JobResultChunk): ByteArray = when (chunk.encoding) {
        ResultChunkEncoding.UTF8 -> chunk.data.encodeToByteArray()
        ResultChunkEncoding.BASE64 -> Base64.Default.decode(chunk.data)
    }

    /**
     * Assembled bytes for one result stream.
     *
     * [equals] and [hashCode] use [ByteArray.contentEquals] /
     * [ByteArray.contentHashCode] so two results carrying the same bytes
     * compare equal regardless of array instance — Kotlin's default
     * data-class equality would otherwise compare arrays by reference.
     */
    public data class AssembledResult(
        public val resultId: String,
        public val bytes: ByteArray,
        public val isText: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssembledResult) return false
            return resultId == other.resultId &&
                isText == other.isText &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = resultId.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + isText.hashCode()
            return result
        }

        override fun toString(): String =
            "AssembledResult(resultId=$resultId, bytes=${bytes.size} bytes, isText=$isText)"
    }

    private class ResultBuffer {
        private val parts: ArrayList<ByteArray> = ArrayList()
        private var lastSeq: Long? = null
        private var firstEncoding: ResultChunkEncoding? = null
        var allEncoding: ResultChunkEncoding? = null
            private set
        var totalSize: Long = 0L
            private set

        fun validateChunk(chunk: JobResultChunk) {
            lastSeq?.let { previous ->
                if (chunk.chunkSeq != previous + 1L) {
                    throw ARCPException.OutOfRange(
                        "result_chunk sequence must be strictly monotonic",
                    )
                }
            }
            firstEncoding?.let { first ->
                if (first != chunk.encoding) {
                    throw ARCPException.FailedPrecondition(
                        "mixed result_chunk encodings are not allowed",
                    )
                }
            }
        }

        fun addDecoded(
            chunk: JobResultChunk,
            decoded: ByteArray,
        ) {
            parts += decoded
            lastSeq = chunk.chunkSeq
            if (firstEncoding == null) {
                firstEncoding = chunk.encoding
                allEncoding = chunk.encoding
            }
            totalSize += decoded.size.toLong()
        }

        fun assemble(): ByteArray {
            val out = ByteArrayOutputStream(totalSize.toInt())
            for (part in parts) out.write(part)
            return out.toByteArray()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_ASSEMBLED_SIZE: Long = 64L * 1024L * 1024L
    }
}
