package dev.arcp.error

import dev.arcp.json.arcpJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ErrorCodeTest :
    StringSpec({
        "every code round-trips via wire string" {
            for (code in ErrorCode.entries) {
                val s = arcpJson.encodeToString(ErrorCode.serializer(), code)
                arcpJson.decodeFromString(ErrorCode.serializer(), s) shouldBe code
            }
        }

        "RATE_LIMITED decodes as RESOURCE_EXHAUSTED but does not encode that way" {
            val decoded = arcpJson.decodeFromString(ErrorCode.serializer(), "\"RATE_LIMITED\"")
            decoded shouldBe ErrorCode.RESOURCE_EXHAUSTED
            Json.Default.encodeToString(ErrorCode.serializer(), decoded) shouldBe "\"RESOURCE_EXHAUSTED\""
        }

        "unknown wire string is rejected" {
            shouldThrow<IllegalArgumentException> {
                arcpJson.decodeFromString(ErrorCode.serializer(), "\"NOT_A_REAL_CODE\"")
            }
        }

        "retryability defaults match RFC §18.3" {
            ErrorCode.RESOURCE_EXHAUSTED.retryableByDefault shouldBe true
            ErrorCode.UNAVAILABLE.retryableByDefault shouldBe true
            ErrorCode.DEADLINE_EXCEEDED.retryableByDefault shouldBe true
            ErrorCode.INTERNAL.retryableByDefault shouldBe true
            ErrorCode.ABORTED.retryableByDefault shouldBe true

            ErrorCode.INVALID_ARGUMENT.retryableByDefault shouldBe false
            ErrorCode.NOT_FOUND.retryableByDefault shouldBe false
            ErrorCode.ALREADY_EXISTS.retryableByDefault shouldBe false
            ErrorCode.PERMISSION_DENIED.retryableByDefault shouldBe false
            ErrorCode.FAILED_PRECONDITION.retryableByDefault shouldBe false
            ErrorCode.UNIMPLEMENTED.retryableByDefault shouldBe false
            ErrorCode.UNAUTHENTICATED.retryableByDefault shouldBe false
            ErrorCode.DATA_LOSS.retryableByDefault shouldBe false
        }
    })
