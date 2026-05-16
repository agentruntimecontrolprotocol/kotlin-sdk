package com.arcp.samples.subscriptions

import dev.arcp.envelope.Envelope

/** structlog-style summarizer. Real version: kotlin-logging structured fields. */
internal class StdoutSink {
    suspend fun handle(env: Envelope): Unit = TODO("stdout sink: pretty-print")
}

/** SQLite-backed event log. Real version: dev.arcp.store.EventLog. */
internal class SqliteSink(
    private val path: String,
) : AutoCloseable {
    suspend fun handle(env: Envelope): Unit = TODO("sqlite sink: insert into eventlog")

    override fun close(): Unit = TODO("close sqlite handle")
}

/** OTLP exporter for `metric` and `trace.span` envelopes. */
internal class OtlpSink(
    private val endpoint: String,
) {
    suspend fun handle(env: Envelope): Unit = TODO("otlp sink: post to $endpoint")
}
