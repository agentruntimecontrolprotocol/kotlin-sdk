package dev.arcp.client

import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.ResultChunkEncoding
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * [ResultChunkAssembler.AssembledResult] uses [ByteArray.contentEquals]/
 * [ByteArray.contentHashCode] so two results with the same bytes compare
 * equal even when the array instances differ (#53).
 */
class ResultChunkAssemblerEqualityTest :
    StringSpec({
        "two AssembledResults with equal contents are equal (#53)" {
            val a = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x01, 0x02, 0x03),
                isText = false,
            )
            val b = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x01, 0x02, 0x03),
                isText = false,
            )
            a shouldBe b
            a.hashCode() shouldBe b.hashCode()
        }

        "different bytes compare unequal" {
            val a = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x01),
                isText = false,
            )
            val b = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x02),
                isText = false,
            )
            a shouldNotBe b
        }

        "assembled result works as a map key" {
            val a = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x01, 0x02),
                isText = false,
            )
            val b = ResultChunkAssembler.AssembledResult(
                resultId = "r1",
                bytes = byteArrayOf(0x01, 0x02),
                isText = false,
            )
            val map = mutableMapOf<ResultChunkAssembler.AssembledResult, Int>()
            map[a] = 1
            map[b] shouldBe 1
        }

        "decoded chunks are reused, not re-decoded (#54)" {
            // Smoke test: assembling a multi-chunk stream produces the same
            // bytes as the concatenated chunk payloads, which means the
            // single-decode pass agreed with the old multi-decode pass.
            val assembler = ResultChunkAssembler()
            val first =
                assembler.accept(
                    JobResultChunk(
                        resultId = "r1",
                        chunkSeq = 0,
                        data = "Hello",
                        encoding = ResultChunkEncoding.UTF8,
                        more = true,
                    ),
                )
            first shouldBe null
            val final =
                assembler.accept(
                    JobResultChunk(
                        resultId = "r1",
                        chunkSeq = 1,
                        data = " world",
                        encoding = ResultChunkEncoding.UTF8,
                        more = false,
                    ),
                )
            final?.bytes?.decodeToString() shouldBe "Hello world"
            final?.isText shouldBe true
        }
    })
