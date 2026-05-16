package com.arcp.samples.humaninput

/** ntfy / email / slack adapters. Stubbed. */

internal typealias Channel =
    suspend (prompt: String, schema: Map<String, Any?>) -> Map<String, Any?>

internal val REGISTRY: Map<String, Channel> =
    mapOf(
        "ntfy:phone" to ::ntfy,
        "email:oncall" to ::email,
        "slack:ops" to ::slack,
    )

private suspend fun ntfy(
    prompt: String,
    schema: Map<String, Any?>,
): Map<String, Any?> = TODO("ntfy push + reply collection")

private suspend fun email(
    prompt: String,
    schema: Map<String, Any?>,
): Map<String, Any?> = TODO("smtp send + IMAP poll")

private suspend fun slack(
    prompt: String,
    schema: Map<String, Any?>,
): Map<String, Any?> = TODO("slack chat.postMessage + interactive callback")
