package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.ids.ArtifactId
import dev.arcp.ids.SessionId
import dev.arcp.messages.ArtifactRefSpec
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * SQLite-backed artifact store (RFC §16).
 *
 * Stores artifact bodies as BLOBs with a retention deadline. The store
 * exposes the canonical `put` / `fetch` / `release` operations as suspend
 * functions and runs a periodic retention sweep on a long-lived coroutine
 * scope. v0.1 supports inline base64 only; sidecar binary frames are v0.2.
 */
public class ArtifactStore private constructor(
    private val connection: Connection,
    /** Default retention window applied when [put] does not specify one. */
    public val defaultRetention: Duration,
    /** Maximum retention window enforced server-side. */
    public val maxRetention: Duration,
    private val sweepInterval: Duration,
    private val ownsConnection: Boolean,
) : AutoCloseable {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope =
        CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("arcp-artifacts"))

    init {
        scope.launch {
            while (isActive) {
                delay(sweepInterval)
                runCatching { sweepExpired() }
                    .onFailure { log.warn(it) { "artifact sweep failed" } }
            }
        }
    }

    /**
     * Persists [data] under [artifactId]. Returns an [ArtifactRefSpec] suitable
     * for embedding in any payload that would otherwise inline the bytes.
     *
     * Computes SHA-256 server-side. Honors [expiresAt] up to [maxRetention];
     * if absent, applies [defaultRetention].
     */
    public suspend fun put(
        sessionId: SessionId?,
        artifactId: ArtifactId,
        mediaType: String,
        data: ByteArray,
        expiresAt: Instant? = null,
    ): ArtifactRefSpec =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now()
            val ceiling = now.plus(maxRetention)
            val effectiveExpiry =
                (expiresAt ?: now.plus(defaultRetention)).coerceAtMost(ceiling)
            val sha256 = sha256Hex(data)

            val sql =
                """
                INSERT OR REPLACE INTO arcp_artifact
                    (artifact_id, session_id, media_type, size_bytes, sha256, expires_at_iso, body_blob)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, artifactId.value)
                ps.setString(2, sessionId?.value)
                ps.setString(3, mediaType)
                ps.setLong(4, data.size.toLong())
                ps.setString(5, sha256)
                ps.setString(6, effectiveExpiry.toString())
                ps.setBytes(7, data)
                ps.executeUpdate()
            }

            ArtifactRefSpec(
                artifactId = artifactId,
                uri = "arcp://session/${sessionId?.value ?: "_"}/artifact/${artifactId.value}",
                mediaType = mediaType,
                size = data.size.toLong(),
                sha256 = sha256,
                expiresAt = effectiveExpiry,
            )
        }

    /**
     * Convenience: stores [base64Body] (decoded once) and returns the same
     * shape as [put]. Mirrors the RFC §16.2 inline-base64 wire encoding.
     */
    public suspend fun putBase64(
        sessionId: SessionId?,
        artifactId: ArtifactId,
        mediaType: String,
        base64Body: String,
        expiresAt: Instant? = null,
    ): ArtifactRefSpec {
        val bytes =
            try {
                Base64.getDecoder().decode(base64Body)
            } catch (e: IllegalArgumentException) {
                throw ARCPException.InvalidArgument("artifact body is not valid base64", "data")
            }
        return put(sessionId, artifactId, mediaType, bytes, expiresAt)
    }

    /**
     * Returns the bytes for [artifactId], or throws [ARCPException.NotFound]
     * if the artifact is unknown or has expired.
     */
    public suspend fun fetch(artifactId: ArtifactId): ArtifactBody =
        withContext(Dispatchers.IO) {
            val sql =
                """
                SELECT media_type, size_bytes, sha256, expires_at_iso, body_blob
                FROM arcp_artifact
                WHERE artifact_id = ?
                """.trimIndent()
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, artifactId.value)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw ARCPException.NotFound("artifact ${artifactId.value} not found")
                    }
                    val expiresIso = rs.getString(4)
                    val expires = expiresIso?.let(Instant::parse)
                    if (expires != null && expires < Clock.System.now()) {
                        throw ARCPException.NotFound("artifact ${artifactId.value} expired")
                    }
                    ArtifactBody(
                        artifactId = artifactId,
                        mediaType = rs.getString(1),
                        size = rs.getLong(2),
                        sha256 = rs.getString(3),
                        expiresAt = expires,
                        bytes = rs.getBytes(5),
                    )
                }
            }
        }

    /** Deletes [artifactId]. Returns `true` if a row was removed. */
    public suspend fun release(artifactId: ArtifactId): Boolean =
        withContext(Dispatchers.IO) {
            connection.prepareStatement("DELETE FROM arcp_artifact WHERE artifact_id = ?").use { ps ->
                ps.setString(1, artifactId.value)
                ps.executeUpdate() > 0
            }
        }

    /** Removes every artifact whose [expiresAt] is in the past. Returns the count. */
    public suspend fun sweepExpired(): Int =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now().toString()
            connection
                .prepareStatement("DELETE FROM arcp_artifact WHERE expires_at_iso IS NOT NULL AND expires_at_iso < ?")
                .use { ps ->
                    ps.setString(1, now)
                    ps.executeUpdate()
                }
        }

    override fun close() {
        scope.cancel()
        if (ownsConnection) {
            runCatching { connection.close() }
        }
    }

    public companion object {
        /** Default retention applied when callers do not specify one (1 hour). */
        public val DEFAULT_RETENTION: Duration = kotlin.time.Duration.parse("1h")

        /** Maximum retention enforced server-side (24 hours). */
        public val MAX_RETENTION: Duration = kotlin.time.Duration.parse("24h")

        /** Default sweep interval. */
        public val DEFAULT_SWEEP_INTERVAL: Duration = 60.seconds

        /** Schema resource shared with [dev.arcp.store.EventLog]. */
        public const val SCHEMA_RESOURCE: String = "/arcp/store/schema.sql"

        /** Opens an in-memory artifact store; intended for tests and ephemeral runtimes. */
        public fun openInMemory(
            sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
            defaultRetention: Duration = DEFAULT_RETENTION,
            maxRetention: Duration = MAX_RETENTION,
        ): ArtifactStore = openOwning("jdbc:sqlite::memory:", sweepInterval, defaultRetention, maxRetention)

        /**
         * Adapts an existing JDBC [Connection] (typically the same one driving
         * an event log) into an [ArtifactStore]. The store does not close the
         * supplied connection.
         */
        public fun adopt(
            connection: Connection,
            sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
            defaultRetention: Duration = DEFAULT_RETENTION,
            maxRetention: Duration = MAX_RETENTION,
        ): ArtifactStore =
            ArtifactStore(
                connection = connection,
                defaultRetention = defaultRetention,
                maxRetention = maxRetention,
                sweepInterval = sweepInterval,
                ownsConnection = false,
            )

        private fun openOwning(
            jdbcUrl: String,
            sweepInterval: Duration,
            defaultRetention: Duration,
            maxRetention: Duration,
        ): ArtifactStore {
            Class.forName("org.sqlite.JDBC")
            val conn = java.sql.DriverManager.getConnection(jdbcUrl)
            applySchema(conn)
            return ArtifactStore(
                connection = conn,
                defaultRetention = defaultRetention,
                maxRetention = maxRetention,
                sweepInterval = sweepInterval,
                ownsConnection = true,
            )
        }

        private fun applySchema(conn: Connection) {
            val schema =
                ArtifactStore::class.java.getResource(SCHEMA_RESOURCE)?.readText()
                    ?: error("missing $SCHEMA_RESOURCE on the classpath")
            conn.createStatement().use { st ->
                schema
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { stmt -> st.execute(stmt) }
            }
        }

        private fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return buildString(digest.size * 2) {
                for (b in digest) {
                    val v = b.toInt() and 0xFF
                    if (v < 0x10) append('0')
                    append(v.toString(16))
                }
            }
        }
    }
}

/** Body returned by [ArtifactStore.fetch]. */
public data class ArtifactBody(
    val artifactId: ArtifactId,
    val mediaType: String,
    val size: Long,
    val sha256: String?,
    val expiresAt: Instant?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtifactBody) return false
        return artifactId == other.artifactId &&
            mediaType == other.mediaType &&
            size == other.size &&
            sha256 == other.sha256 &&
            expiresAt == other.expiresAt &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = artifactId.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (sha256?.hashCode() ?: 0)
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
