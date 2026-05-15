package dev.arcp.trace

import dev.arcp.ids.SpanId
import dev.arcp.ids.TraceId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Distributed-trace coordinates carried through suspend calls (RFC §17.1).
 *
 * Trace context is modeled as a [CoroutineContext.Element] so it propagates
 * automatically across `suspend` boundaries — no `AsyncLocal` or
 * `ScopedValue` shim required. Children inherit the trace by default; nested
 * spans use [withSpan] to enter a child span while preserving [traceId].
 */
public data class TraceContext(
    val traceId: TraceId,
    val spanId: SpanId,
    val parentSpanId: SpanId? = null,
) : AbstractCoroutineContextElement(Key) {
    /** [CoroutineContext.Key] for [TraceContext] lookup. */
    public companion object Key : CoroutineContext.Key<TraceContext> {
        /** Synthesizes a fresh root trace + span. */
        public fun newRoot(): TraceContext =
            TraceContext(traceId = TraceId.random(), spanId = SpanId.random())
    }
}

/**
 * Returns the [TraceContext] in scope, or `null` if none has been installed.
 *
 * Suspend so it can be invoked anywhere on a coroutine; non-suspending lookup
 * is unsafe because there's no implicit "current context" off-coroutine.
 */
public suspend fun currentTrace(): TraceContext? = currentCoroutineContext()[TraceContext]

/**
 * Runs [block] under a fresh child span keyed to [name]. The child inherits
 * [TraceContext.traceId] from any ambient trace, sets `parentSpanId` to the
 * caller's `spanId`, and generates a new `spanId`. If no trace is installed,
 * one is synthesized with [TraceContext.newRoot].
 */
public suspend fun <T> withSpan(
    name: String,
    block: suspend (TraceContext) -> T,
): T {
    val parent = currentTrace()
    val child =
        if (parent == null) {
            TraceContext.newRoot()
        } else {
            TraceContext(
                traceId = parent.traceId,
                spanId = SpanId.random(),
                parentSpanId = parent.spanId,
            )
        }
    return withContext(child) {
        @Suppress("UNUSED_VARIABLE")
        val spanName = name
        block(child)
    }
}
