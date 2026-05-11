package com.arcp.samples.extensions

import com.arcp.samples.envelope
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import kotlinx.coroutines.runBlocking

/**
 * SDR domain via custom `arcpx.sdr.*.v1` extension messages.
 *
 * Tune to 145.500 MHz (2 m FM calling), capture 5 s of IQ at 2.048 MS/s,
 * NBFM-demodulate to 48 kHz PCM. Exercises §21 naming, capability
 * advertisement, and unknown-message handling.
 */

internal const val EXT_TUNE: String = "arcpx.sdr.tune.v1"
internal const val EXT_GAIN: String = "arcpx.sdr.gain.v1"
internal const val EXT_CAPTURE: String = "arcpx.sdr.capture.v1"
internal const val EXT_DEMODULATE: String = "arcpx.sdr.demodulate.v1"
private val ALL_EXTENSIONS = listOf(EXT_TUNE, EXT_GAIN, EXT_CAPTURE, EXT_DEMODULATE)

public fun main(): Unit = runBlocking {
    // capabilities.extensions = ALL_EXTENSIONS on the open call.
    val client: ARCPClient = TODO("transport, identity, auth elided")
    val accepted = client.open()

    // If the runtime didn't advertise our required extension set,
    // refuse the session — RFC §7 / §21.2.
    val advertised = accepted.capabilities.extensions.toSet()
    if (!advertised.containsAll(ALL_EXTENSIONS)) {
        throw ARCPException.Unimplemented(
            section = "21.2",
            detail = "runtime missing SDR extensions: $advertised",
        )
    }

    val handle = java.util.UUID.randomUUID().toString().take(8)

    client.request(
        envelope = client.envelope(
            type = EXT_TUNE,
            payload = mapOf(
                "center_freq_hz" to 145_500_000.0,
                "sample_rate_hz" to 2_048_000.0,
                "ppm_correction" to 1,
            ),
        ),
        timeoutMs = 10_000,
    )
    client.request(
        envelope = client.envelope(
            type = EXT_GAIN,
            payload = mapOf(
                "stages" to listOf(mapOf("name" to "TUNER", "value_db" to 28.0)),
            ),
        ),
        timeoutMs = 10_000,
    )

    // Capture returns an artifact.ref pointing at the IQ buffer.
    // The buffer never travels inline — demodulate references it.
    val cap = client.request(
        envelope = client.envelope(
            type = EXT_CAPTURE,
            payload = mapOf(
                "seconds" to 5.0,
                "capture_handle" to handle,
                "decimate" to 1,
            ),
        ),
        timeoutMs = 15_000,
    )
    val iqArtifact = cap.payloadMap()["artifact_id"].toString()
    println("captured IQ → $iqArtifact")

    val audio = client.request(
        envelope = client.envelope(
            type = EXT_DEMODULATE,
            payload = mapOf(
                "iq_artifact_id" to iqArtifact,
                "mode" to "NBFM",
                "audio_rate_hz" to 48_000,
            ),
        ),
        timeoutMs = 15_000,
    )
    println("demod  PCM → ${audio.payloadMap()["artifact_id"]}")

    // §21.3 demonstration: unadvertised extension marked optional.
    // Runtime SHOULD ack (silent drop) rather than nack.
    val optional = client.request(
        envelope = client.envelope(
            type = "arcpx.sdr.experimental_doppler.v1",
            extensions = mapOf("optional" to true),
            payload = mapOf("velocity_mps" to 7.4),
        ),
        timeoutMs = 5_000,
    )
    println("optional unknown → ${optional.type}")

    client.close()
}
