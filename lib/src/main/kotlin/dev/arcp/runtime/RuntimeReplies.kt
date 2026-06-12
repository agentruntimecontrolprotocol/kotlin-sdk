package dev.arcp.runtime

import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.MessageId
import dev.arcp.ids.PermissionName
import dev.arcp.messages.MessageType
import dev.arcp.messages.Nack
import dev.arcp.messages.Ping
import dev.arcp.messages.Pong

/** Builds a correlated reply envelope carrying [payload]. */
internal fun reply(
    env: Envelope,
    payload: MessageType,
): Envelope = Envelope(
    id = MessageId.random(),
    sessionId = env.sessionId,
    correlationId = env.id,
    payload = payload,
)

/** Builds a correlated `Nack` carrying the canonical code for [error]. */
internal fun nack(
    env: Envelope,
    error: ARCPException,
): Envelope = reply(
    env,
    Nack(
        nackFor = env.id,
        code = error.code,
        message = error.message ?: error.code.wire,
        retryable = error.retryable,
    ),
)

/** A PERMISSION_DENIED error for an unauthorized job-scoped [action] (§14). */
internal fun permissionDenied(
    action: String,
    resource: String,
): ARCPException = ARCPException.PermissionDenied(
    permission = PermissionName(action),
    resource = resource,
    message = "principal is not authorized for $action on $resource",
)

/** Builds the correlated `Pong` reply for [ping]. */
internal fun pong(
    env: Envelope,
    ping: Ping,
): Envelope = reply(env, Pong(nonce = ping.nonce))

/** Nacks a message type that v0.1 does not implement. */
internal fun unimplemented(env: Envelope): Envelope = reply(
    env,
    Nack(
        nackFor = env.id,
        code = ErrorCode.UNIMPLEMENTED,
        message = "message type ${env.type} not implemented in v0.1",
    ),
)

/** Nacks the deferred challenge/authenticate handshake flow (RFC §8.2). */
internal fun deferredHandshakeNack(env: Envelope): Envelope = reply(
    env,
    Nack(
        nackFor = env.id,
        code = ErrorCode.UNIMPLEMENTED,
        message =
            "session challenge/authenticate flow is deferred; " +
                "use direct-credential session.open (RFC §8.2)",
    ),
)
