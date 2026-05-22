@file:Suppress("LongParameterList")

package dev.arcp.client

import dev.arcp.error.ARCPException
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.ResultChunkEncoding
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ResultChunkAssemblerTest :
    StringSpec({
        "concatenates utf8 chunks until more is false" {
            val assembler = ResultChunkAssembler()
            assembler.accept(chunk("res", 0, "hel", more = true)) shouldBe null
            assembler.accept(chunk("res", 1, "lo", more = false))!!.let {
                it.resultId shouldBe "res"
                it.bytes.decodeToString() shouldBe "hello"
                it.isText.shouldBeTrue()
            }
        }

        "decodes base64 chunks into bytes" {
            val assembler = ResultChunkAssembler()
            val bytes = byteArrayOf(1, 2, 3)
            val result =
                assembler.accept(
                    chunk(
                        resultId = "bin",
                        seq = 0,
                        data = Base64.Default.encode(bytes),
                        encoding = ResultChunkEncoding.BASE64,
                        more = false,
                    ),
                )!!
            result.bytes.toList() shouldBe bytes.toList()
            result.isText shouldBe false
        }

        "rejects mixed encodings within one result id" {
            val assembler = ResultChunkAssembler()
            assembler.accept(chunk("res", 0, "a", more = true))
            shouldThrow<ARCPException.FailedPrecondition> {
                assembler.accept(
                    chunk(
                        resultId = "res",
                        seq = 1,
                        data = Base64.Default.encode(byteArrayOf(1)),
                        encoding = ResultChunkEncoding.BASE64,
                        more = false,
                    ),
                )
            }
        }

        "rejects out of order chunk sequence" {
            val assembler = ResultChunkAssembler()
            assembler.accept(chunk("res", 0, "a", more = true))
            shouldThrow<ARCPException.OutOfRange> {
                assembler.accept(chunk("res", 2, "b", more = false))
            }
        }

        "rejects total size exceeding cap" {
            val assembler = ResultChunkAssembler(maxAssembledSize = 8)
            assembler.accept(chunk("res", 0, "12345", more = true))
            shouldThrow<ARCPException.Internal> {
                assembler.accept(chunk("res", 1, "67890", more = false))
            }
        }

        "allows interleaved result ids" {
            val assembler = ResultChunkAssembler()
            assembler.accept(chunk("a", 0, "a", more = true))
            assembler.accept(chunk("b", 0, "b", more = false))!!.bytes.decodeToString() shouldBe "b"
            assembler.accept(chunk("a", 1, "a", more = false))!!.bytes.decodeToString() shouldBe
                "aa"
        }
    })

private fun chunk(
    resultId: String,
    seq: Long,
    data: String,
    encoding: ResultChunkEncoding = ResultChunkEncoding.UTF8,
    more: Boolean,
): JobResultChunk = JobResultChunk(
    resultId = resultId,
    chunkSeq = seq,
    data = data,
    encoding = encoding,
    more = more,
)
