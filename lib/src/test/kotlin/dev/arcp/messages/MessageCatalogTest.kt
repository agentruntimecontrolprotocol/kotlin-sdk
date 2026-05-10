package dev.arcp.messages

import dev.arcp.envelope.Envelope
import dev.arcp.envelope.Priority
import dev.arcp.error.ErrorCode
import dev.arcp.ids.ArtifactId
import dev.arcp.ids.JobId
import dev.arcp.ids.LeaseId
import dev.arcp.ids.MessageId
import dev.arcp.ids.PermissionName
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import dev.arcp.ids.SubscriptionId
import dev.arcp.ids.ToolName
import dev.arcp.json.arcpJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class MessageCatalogTest :
    StringSpec({
        val ts = Instant.parse("2026-05-09T13:00:00Z")

        fun envelope(payload: MessageType) = Envelope(id = MessageId.random(), timestamp = ts, payload = payload)

        val cases: List<MessageType> =
            listOf(
                // Session
                SessionOpen(
                    auth = Auth(scheme = AuthScheme.BEARER, token = "tok"),
                    client = ClientInfo(kind = "test", version = "0.0.1"),
                    capabilities = Capabilities(streaming = true),
                ),
                SessionChallenge(scheme = AuthScheme.SIGNED_JWT, nonce = "n"),
                SessionAuthenticate(token = "tok"),
                SessionAccepted(
                    sessionId = SessionId("sess_x"),
                    runtime = RuntimeIdentity(kind = "rt", version = "1"),
                    capabilities = Capabilities(),
                ),
                SessionUnauthenticated(message = "bad"),
                SessionRejected(code = ErrorCode.UNIMPLEMENTED, message = "no"),
                SessionRefresh(scheme = AuthScheme.BEARER, nonce = "x", expiresAt = ts),
                SessionEvicted(code = ErrorCode.CANCELLED, reason = "policy"),
                SessionClose(reason = "done"),
                // Control
                Ping(nonce = "p"),
                Pong(nonce = "p"),
                Ack(ackFor = MessageId("msg_x")),
                Nack(nackFor = MessageId("msg_x"), code = ErrorCode.INVALID_ARGUMENT, message = "bad"),
                Cancel(target = CancelTarget.JOB, targetId = "job_x"),
                CancelAccepted(targetId = "job_x"),
                CancelRefused(targetId = "job_x", reason = "terminal"),
                Interrupt(target = CancelTarget.JOB, targetId = "job_x", prompt = "stop"),
                Resume(sessionId = SessionId("sess_x"), afterMessageId = MessageId("msg_x")),
                Backpressure(streamId = StreamId("str_x"), desiredRatePerSecond = 20),
                CheckpointCreate(jobId = JobId("job_x")),
                CheckpointRestore(jobId = JobId("job_x"), checkpointId = "ck_1"),
                // Execution
                ToolInvoke(tool = ToolName("filesystem.search"), arguments = buildJsonObject { put("q", JsonPrimitive("x")) }),
                ToolResult(value = JsonPrimitive(42)),
                ToolError(code = ErrorCode.RESOURCE_EXHAUSTED, message = "quota"),
                JobAccepted(jobId = JobId("job_x")),
                JobStarted(startedAt = ts),
                JobProgress(percent = 42, message = "embedding"),
                JobHeartbeat(sequence = 1, deadlineMs = 60000, state = JobLifecycleState.RUNNING),
                JobCheckpoint(checkpointId = "ck_1"),
                JobCompleted(result = JsonPrimitive("ok")),
                JobFailed(code = ErrorCode.INTERNAL, message = "boom"),
                JobCancelled(reason = "user", code = ErrorCode.CANCELLED),
                // Streaming
                StreamOpen(kind = StreamKind.TEXT, contentType = "text/plain"),
                StreamChunk(sequence = 1, content = "hello"),
                StreamClose(totalChunks = 1),
                StreamError(code = ErrorCode.CANCELLED, message = "x"),
                // Human
                HumanInputRequest(prompt = "?", expiresAt = ts),
                HumanInputResponse(value = JsonPrimitive("yes")),
                HumanChoiceRequest(
                    prompt = "?",
                    options = listOf(HumanChoiceOption("a", "A")),
                    expiresAt = ts,
                ),
                HumanChoiceResponse(choiceId = "a"),
                HumanInputCancelled(reason = "expired"),
                // Permissions
                PermissionRequest(
                    permission = PermissionName("filesystem.read"),
                    resource = "/tmp",
                ),
                PermissionGrant(permission = PermissionName("filesystem.read"), resource = "/tmp"),
                PermissionDeny(permission = PermissionName("filesystem.read"), resource = "/tmp"),
                LeaseGranted(
                    leaseId = LeaseId("lse_x"),
                    permission = PermissionName("filesystem.read"),
                    resource = "/tmp",
                    expiresAt = ts,
                ),
                LeaseRefresh(leaseId = LeaseId("lse_x")),
                LeaseExtended(leaseId = LeaseId("lse_x"), expiresAt = ts),
                LeaseRevoked(leaseId = LeaseId("lse_x"), reason = "policy"),
                // Subscriptions
                Subscribe(filter = SubscriptionFilter(types = listOf("job.progress"))),
                SubscribeAccepted(subscriptionId = SubscriptionId("sub_x")),
                SubscribeEvent(event = JsonObject(emptyMap())),
                Unsubscribe(subscriptionId = SubscriptionId("sub_x")),
                SubscribeClosed(
                    subscriptionId = SubscriptionId("sub_x"),
                    code = ErrorCode.BACKPRESSURE_OVERFLOW,
                    reason = "overflow",
                ),
                // Artifacts
                ArtifactPut(
                    artifactId = ArtifactId("art_x"),
                    mediaType = "application/json",
                    size = 4,
                    data = "AAAA",
                ),
                ArtifactFetch(artifactId = ArtifactId("art_x")),
                ArtifactRefMessage(
                    ref =
                        ArtifactRefSpec(
                            artifactId = ArtifactId("art_x"),
                            uri = "arcp://x",
                            mediaType = "application/json",
                            size = 0,
                        ),
                ),
                ArtifactRelease(artifactId = ArtifactId("art_x")),
                // Telemetry
                EventEmit(eventType = "subscription.backfill_complete"),
                Log(level = LogLevel.INFO, message = "hi"),
                Metric(name = "tokens.used", value = JsonPrimitive(123), unit = "tokens"),
                TraceSpan(name = "tool.invoke", startedAt = ts),
                // Workflow / Agent (deferred but data classes round-trip)
                WorkflowStart(workflow = "ingest", arguments = JsonObject(emptyMap())),
                WorkflowComplete(),
                AgentDelegate(target = "research", task = "summarize"),
                AgentHandoff(
                    target = "peer",
                    receivingRuntime = RuntimeIdentity(kind = "rt", version = "1"),
                ),
                JobSchedule(job = JsonObject(emptyMap()), `when` = JsonObject(emptyMap())),
            )

        cases.forEach { msg ->
            "${msg::class.simpleName} envelope round-trips" {
                val original = envelope(msg).copy(priority = Priority.NORMAL)
                val json = arcpJson.encodeToString(Envelope.serializer(), original)
                val decoded = arcpJson.decodeFromString(Envelope.serializer(), json)
                decoded shouldBe original
            }
        }
    })
