package dev.arcp.store

import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.json.arcpJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Append-only SQLite event log (RFC §6.4, §19).
 *
 * The log is globally ordered by an autoincrement sequence. Per-message
 * idempotency is enforced by a `UNIQUE (session_id, message_id)` constraint
 * — duplicate appends are detected and rejected as
 * [ARCPException.AlreadyExists]. The log also stores logical idempotency
 * outcomes (RFC §6.4) and binary artifacts (RFC §16).
 *
 * All public APIs are `suspend` and dispatch JDBC work onto [Dispatchers.IO].
 */
public class EventLog private constructor(
    private val connection: Connection,
) : AutoCloseable {
    /**
     * Appends [envelope] to the log. Returns the assigned monotonic sequence
     * number. Re-appending an envelope with the same `(session_id, message_id)`
     * raises [ARCPException.AlreadyExists].
     */
    public suspend fun append(envelope: Envelope): Long =
        withIo {
            val sql =
                """
                INSERT INTO arcp_envelope (
                    session_id, message_id, type, timestamp_iso,
                    job_id, stream_id, subscription_id, trace_id,
                    correlation_id, causation_id, priority, body_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            try {
                connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                    ps.setString(1, envelope.sessionId?.value)
                    ps.setString(2, envelope.id.value)
                    ps.setString(3, envelope.type)
                    ps.setString(4, envelope.timestamp.toString())
                    ps.setString(5, envelope.jobId?.value)
                    ps.setString(6, envelope.streamId?.value)
                    ps.setString(7, envelope.subscriptionId?.value)
                    ps.setString(8, envelope.traceId?.value)
                    ps.setString(9, envelope.correlationId?.value)
                    ps.setString(10, envelope.causationId?.value)
                    ps.setString(11, envelope.priority.name.lowercase())
                    ps.setString(12, arcpJson.encodeToString(Envelope.serializer(), envelope))
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs ->
                        check(rs.next()) { "no generated key returned for envelope insert" }
                        rs.getLong(1)
                    }
                }
            } catch (e: SQLException) {
                if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                    throw ARCPException.AlreadyExists(
                        "duplicate message id ${envelope.id.value} for session ${envelope.sessionId?.value}",
                    )
                }
                throw ARCPException.Internal("event-log append failed: ${e.message}", e)
            }
        }

    /**
     * Streams envelopes belonging to [sessionId] in append order, optionally
     * starting strictly after [afterMessageId] (RFC §19).
     */
    public fun replay(
        sessionId: SessionId,
        afterMessageId: MessageId? = null,
    ): Flow<Envelope> =
        flow {
            val cursorSeq =
                if (afterMessageId == null) {
                    -1L
                } else {
                    findSeq(sessionId, afterMessageId)
                        ?: throw ARCPException.DataLoss(
                            "no message ${afterMessageId.value} for session ${sessionId.value}",
                        )
                }

            val sql =
                """
                SELECT body_json FROM arcp_envelope
                WHERE session_id = ? AND seq > ?
                ORDER BY seq ASC
                """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, sessionId.value)
                ps.setLong(2, cursorSeq)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val body = rs.getString(1)
                        emit(arcpJson.decodeFromString(Envelope.serializer(), body))
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Returns the previously-recorded outcome for [principal]+[idempotencyKey],
     * or `null` if none exists or the outcome has expired (RFC §6.4).
     */
    public suspend fun lookupIdempotent(
        principal: String,
        idempotencyKey: String,
    ): JsonElement? =
        withIo {
            val sql =
                """
                SELECT outcome_json, expires_at_iso FROM arcp_idempotency
                WHERE principal = ? AND idempotency_key = ?
                """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, principal)
                ps.setString(2, idempotencyKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val expires = kotlinx.datetime.Instant.parse(rs.getString(2))
                    if (expires <
                        kotlinx.datetime.Clock.System
                            .now()
                    ) {
                        return@use null
                    }
                    arcpJson.parseToJsonElement(rs.getString(1))
                }
            }
        }

    /**
     * Records [outcome] under [principal]+[idempotencyKey] until [expiresAt]
     * (RFC §6.4). Subsequent calls return the prior outcome rather than
     * re-executing.
     */
    public suspend fun recordIdempotent(
        principal: String,
        idempotencyKey: String,
        outcome: JsonElement,
        expiresAt: kotlinx.datetime.Instant,
    ) {
        withIo {
            val sql =
                """
                INSERT OR REPLACE INTO arcp_idempotency
                    (principal, idempotency_key, outcome_json, created_at_iso, expires_at_iso)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, principal)
                ps.setString(2, idempotencyKey)
                ps.setString(3, outcome.toString())
                ps.setString(
                    4,
                    kotlinx.datetime.Clock.System
                        .now()
                        .toString(),
                )
                ps.setString(5, expiresAt.toString())
                ps.executeUpdate()
            }
        }
    }

    /** Returns the highest assigned sequence number in the log. */
    public suspend fun lastSeq(): Long =
        withIo {
            connection.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM arcp_envelope").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun findSeq(
        sessionId: SessionId,
        messageId: MessageId,
    ): Long? {
        val sql = "SELECT seq FROM arcp_envelope WHERE session_id = ? AND message_id = ?"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId.value)
            ps.setString(2, messageId.value)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong(1) else null
            }
        }
    }

    private suspend inline fun <T> withIo(crossinline block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    override fun close() {
        try {
            connection.close()
        } catch (e: SQLException) {
            log.warn(e) { "EventLog close failed" }
        }
    }

    public companion object {
        /** Resource path of the embedded schema file. */
        public const val SCHEMA_RESOURCE: String = "/arcp/store/schema.sql"

        /** Opens an event log backed by an in-memory SQLite database. */
        public fun openInMemory(): EventLog = open("jdbc:sqlite::memory:")

        /** Opens an event log backed by a file. */
        public fun openFile(path: Path): EventLog = open("jdbc:sqlite:${path.toAbsolutePath()}")

        private val driverLoaded = AtomicReference(false)

        private fun open(jdbcUrl: String): EventLog {
            ensureDriver()
            val conn = DriverManager.getConnection(jdbcUrl)
            applySchema(conn)
            return EventLog(conn)
        }

        private fun ensureDriver() {
            if (driverLoaded.compareAndSet(false, true)) {
                Class.forName("org.sqlite.JDBC")
            }
        }

        private fun applySchema(conn: Connection) {
            val schema =
                EventLog::class.java.getResource(SCHEMA_RESOURCE)?.readText()
                    ?: error("missing $SCHEMA_RESOURCE on the classpath")
            conn.createStatement().use { st ->
                schema
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { stmt -> st.execute(stmt) }
            }
        }

        @Suppress("UnusedPrivateMember", "unused")
        private fun ResultSet.first(): Boolean = next()
    }
}
