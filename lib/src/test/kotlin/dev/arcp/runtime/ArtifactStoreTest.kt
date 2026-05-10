package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.ids.ArtifactId
import dev.arcp.ids.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ArtifactStoreTest :
    StringSpec({
        "put-fetch round-trips bytes and computes sha256" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    val data = "hello world".toByteArray()
                    val ref =
                        store.put(
                            sessionId = SessionId("sess_a"),
                            artifactId = ArtifactId("art_a"),
                            mediaType = "text/plain",
                            data = data,
                        )
                    ref.size shouldBe data.size.toLong()
                    ref.sha256!!.shouldStartWith("b94d27") // sha256("hello world")
                    val body = store.fetch(ArtifactId("art_a"))
                    body.bytes.toString(Charsets.UTF_8) shouldBe "hello world"
                    body.mediaType shouldBe "text/plain"
                }
            }
        }

        "putBase64 decodes and stores" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    val ref =
                        store.putBase64(
                            sessionId = null,
                            artifactId = ArtifactId("art_b"),
                            mediaType = "application/octet-stream",
                            base64Body = "SGVsbG8=", // "Hello"
                        )
                    ref.size shouldBe 5
                    val body = store.fetch(ArtifactId("art_b"))
                    body.bytes.toString(Charsets.UTF_8) shouldBe "Hello"
                }
            }
        }

        "putBase64 rejects malformed input" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    shouldThrow<ARCPException.InvalidArgument> {
                        store.putBase64(null, ArtifactId("art_x"), "text/plain", "not_base64!!!")
                    }
                }
            }
        }

        "fetch on unknown id raises NotFound" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    shouldThrow<ARCPException.NotFound> { store.fetch(ArtifactId("art_missing")) }
                }
            }
        }

        "release evicts the artifact" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    store.put(null, ArtifactId("art_c"), "text/plain", "x".toByteArray())
                    store.release(ArtifactId("art_c")) shouldBe true
                    shouldThrow<ARCPException.NotFound> { store.fetch(ArtifactId("art_c")) }
                }
            }
        }

        "fetch past expiry raises NotFound" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    store.put(
                        null,
                        ArtifactId("art_d"),
                        "text/plain",
                        "x".toByteArray(),
                        expiresAt = kotlinx.datetime.Instant.parse("2000-01-01T00:00:00Z"),
                    )
                    shouldThrow<ARCPException.NotFound> { store.fetch(ArtifactId("art_d")) }
                }
            }
        }

        "sweepExpired removes past-deadline artifacts and leaves live ones" {
            runTest {
                ArtifactStore.openInMemory().use { store ->
                    store.put(
                        null,
                        ArtifactId("art_dead"),
                        "text/plain",
                        "x".toByteArray(),
                        expiresAt = kotlinx.datetime.Instant.parse("2000-01-01T00:00:00Z"),
                    )
                    store.put(
                        null,
                        ArtifactId("art_alive"),
                        "text/plain",
                        "y".toByteArray(),
                        expiresAt = Clock.System.now().plus(1.hours),
                    )
                    store.sweepExpired() shouldBe 1
                    // alive survives
                    store.fetch(ArtifactId("art_alive")).bytes.toString(Charsets.UTF_8) shouldBe "y"
                }
            }
        }

        "expiresAt is clamped to maxRetention" {
            runTest {
                ArtifactStore.openInMemory(maxRetention = 1.minutes).use { store ->
                    val far = Clock.System.now().plus(48.hours)
                    val ref = store.put(null, ArtifactId("art_e"), "text/plain", "x".toByteArray(), expiresAt = far)
                    val expiry = ref.expiresAt!!
                    val now = Clock.System.now()
                    // expiry should be roughly now + 1 minute
                    val deltaSeconds = (expiry - now).inWholeSeconds
                    (deltaSeconds in 30..120) shouldBe true
                }
            }
        }
    })
