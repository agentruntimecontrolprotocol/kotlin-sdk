package dev.arcp.trace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class TraceContextTest :
    StringSpec({
        "no ambient trace returns null" {
            runTest { currentTrace() shouldBe null }
        }

        "withSpan installs a fresh root when no parent is in scope" {
            runTest {
                withSpan("root") { ctx ->
                    val seen = currentTrace()
                    seen.shouldNotBeNull()
                    seen.traceId shouldBe ctx.traceId
                    seen.parentSpanId shouldBe null
                }
            }
        }

        "child span preserves traceId and points parentSpanId at caller" {
            runTest {
                withSpan("outer") { outer ->
                    withSpan("inner") { inner ->
                        inner.traceId shouldBe outer.traceId
                        inner.parentSpanId shouldBe outer.spanId
                        inner.spanId shouldNotBe outer.spanId
                    }
                }
            }
        }

        "trace propagates across coroutineScope boundaries" {
            runTest {
                withSpan("root") { ctx ->
                    coroutineScope {
                        val deferred = async { currentTrace()?.traceId }
                        deferred.await() shouldBe ctx.traceId
                    }
                }
            }
        }

        "withContext can install a TraceContext directly" {
            runTest {
                val tc = TraceContext.newRoot()
                withContext(tc) {
                    currentTrace() shouldBe tc
                }
            }
        }
    })
