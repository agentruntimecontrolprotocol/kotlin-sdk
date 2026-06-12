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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

    /**
     * Serializes all access to [connection]. JDBC connections are not
     * safe to use concurrently from multiple coroutines, and the
     * background sweep job races against caller [put]/[fetch]/[release]
     * traffic.
     *
     * `adopt(connection)` callers must coordinate access to the shared
     * connection with whatever other code uses it — this lock only
     * covers calls made through *this* store.
     */
    private val connectionLock: Mutex = Mutex()
    private val sweepJob: Job =
        scope.launch {
            while (isActive) {
                delay(sweepInterval)
                runCatching { sweepExpired() }
                    .onFailure { log.warn(it) { "artifact sweep failed" } }
            }
        }

    /**
     * Persists [request] under its artifact id. Returns an [ArtifactRefSpec]
     * suitable for embedding in any payload that would otherwise inline the
     * bytes.
     *
     * Computes SHA-256 server-side. Honors `request.expiresAt` up to
     * [maxRetention]; if absent, applies [defaultRetention].
     */
    public suspend fun put(request: ArtifactPutRequest): ArtifactRefSpec =
        withContext(Dispatchers.IO) {
            val effectiveExpiry = computeExpiry(request.expiresAt)
            val sha256 = sha256Hex(request.data)
            connectionLock.withLock { persistArtifact(request, effectiveExpiry, sha256) }
            ArtifactRefSpec(
                artifactId = request.artifactId,
                uri = artifactUri(request.sessionId, request.artifactId),
                mediaType = request.mediaType,
                size = request.data.size.toLong(),
                sha256 = sha256,
                expiresAt = effectiveExpiry,
            )
        }

    /**
     * Convenience: stores `request.base64Body` (decoded once) and returns the
     * same shape as [put]. Mirrors the RFC §16.2 inline-base64 wire encoding.
     */
    public suspend fun putBase64(request: ArtifactPutBase64Request): ArtifactRefSpec {
        val bytes =
            try {
                Base64.getDecoder().decode(request.base64Body)
            } catch (e: IllegalArgumentException) {
                throw ARCPException.InvalidArgument(
                    "artifact body is not valid base64",
                    "data",
                )
            }
        return put(
            ArtifactPutRequest(
                sessionId = request.sessionId,
                artifactId = request.artifactId,
                mediaType = request.mediaType,
                data = bytes,
                expiresAt = request.expiresAt,
            ),
        )
    }

    private fun computeExpiry(requested: Instant?): Instant {
        val now = Clock.System.now()
        val ceiling = now.plus(maxRetention)
        return (requested ?: now.plus(defaultRetention)).coerceAtMost(ceiling)
    }

    private fun persistArtifact(
        request: ArtifactPutRequest,
        effectiveExpiry: Instant,
        sha256: String,
    ) {
        val sql =
            """
            INSERT OR REPLACE INTO arcp_artifact
                (artifact_id, session_id, media_type, size_bytes, sha256,
                 expires_at_iso, body_blob)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, request.artifactId.value)
            ps.setString(2, request.sessionId?.value)
            ps.setString(3, request.mediaType)
            ps.setLong(4, request.data.size.toLong())
            ps.setString(5, sha256)
            ps.setString(6, effectiveExpiry.toString())
            ps.setBytes(7, request.data)
            ps.executeUpdate()
        }
    }

    private fun artifactUri(
        sessionId: SessionId?,
        artifactId: ArtifactId,
    ): String = "arcp://session/${sessionId?.value ?: "_"}/artifact/${artifactId.value}"

    /**
     * Returns the bytes for [artifactId], or throws [ARCPException.NotFound]
     * if the artifact is unknown or has expired.
     */
    public suspend fun fetch(artifactId: ArtifactId): ArtifactBody = withContext(Dispatchers.IO) {
        connectionLock.withLock { fetchLocked(artifactId) }
    }

    private fun fetchLocked(artifactId: ArtifactId): ArtifactBody {
        val sql =
            """
            SELECT media_type, size_bytes, sha256, expires_at_iso, body_blob
            FROM arcp_artifact
            WHERE artifact_id = ?
            """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, artifactId.value)
            return ps.executeQuery().use { rs -> readArtifactBody(rs, artifactId) }
        }
    }

    private fun readArtifactBody(
        rs: java.sql.ResultSet,
        artifactId: ArtifactId,
    ): ArtifactBody {
        if (!rs.next()) {
            throw ARCPException.NotFound("artifact ${artifactId.value} not found")
        }
        val expires = rs.getString(4)?.let(Instant::parse)
        if (expires != null && expires < Clock.System.now()) {
            throw ARCPException.NotFound("artifact ${artifactId.value} expired")
        }
        return ArtifactBody(
            artifactId = artifactId,
            mediaType = rs.getString(1),
            size = rs.getLong(2),
            sha256 = rs.getString(3),
            expiresAt = expires,
            bytes = rs.getBytes(5),
        )
    }

    /** Deletes [artifactId]. Returns `true` if a row was removed. */
    public suspend fun release(artifactId: ArtifactId): Boolean = withContext(Dispatchers.IO) {
        val sql = "DELETE FROM arcp_artifact WHERE artifact_id = ?"
        connectionLock.withLock {
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, artifactId.value)
                ps.executeUpdate() > 0
            }
        }
    }

    /**
     * Removes every artifact whose `expiresAt` is in the past. Returns the
     * deleted row count.
     */
    public suspend fun sweepExpired(): Int = withContext(Dispatchers.IO) {
        val now = Clock.System.now().toString()
        val sql =
            "DELETE FROM arcp_artifact " +
                "WHERE expires_at_iso IS NOT NULL AND expires_at_iso < ?"
        connectionLock.withLock {
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, now)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Cancels the background sweep and (when this store owns its
     * connection) closes the JDBC connection. Waits for an in-flight
     * sweep iteration to unwind before closing so it never runs against
     * a closed connection.
     */
    override fun close() {
        runBlocking { sweepJob.cancelAndJoin() }
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

        /**
         * Opens an in-memory artifact store; intended for tests and
         * ephemeral runtimes.
         */
        public fun openInMemory(
            sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
            defaultRetention: Duration = DEFAULT_RETENTION,
            maxRetention: Duration = MAX_RETENTION,
        ): ArtifactStore = openOwning(
            "jdbc:sqlite::memory:",
            sweepInterval,
            defaultRetention,
            maxRetention,
        )

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
        ): ArtifactStore = ArtifactStore(
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
