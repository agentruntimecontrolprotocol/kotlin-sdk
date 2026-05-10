package dev.arcp.messages

import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.ToolName
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `tool.invoke` — direct tool call (RFC §6.2 Execution). */
@Serializable
@SerialName("tool.invoke")
public data class ToolInvoke(
    val tool: ToolName,
    val arguments: JsonObject = JsonObject(emptyMap()),
) : MessageType

/** `tool.result` — terminal success of a direct tool invocation (RFC §6.3). */
@Serializable
@SerialName("tool.result")
public data class ToolResult(
    val value: JsonElement? = null,
    @SerialName("result_ref")
    val resultRef: JsonElement? = null,
) : MessageType

/** `tool.error` — terminal failure of a direct tool invocation (RFC §18.1). */
@Serializable
@SerialName("tool.error")
public data class ToolError(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean? = null,
    val details: JsonElement? = null,
    val cause: JsonElement? = null,
) : MessageType

/** `job.accepted` — runtime accepted a job command (RFC §10.2). */
@Serializable
@SerialName("job.accepted")
public data class JobAccepted(
    @SerialName("job_id")
    val jobId: JobId,
) : MessageType

/** `job.started` — execution began (RFC §10.2). */
@Serializable
@SerialName("job.started")
public data class JobStarted(
    @SerialName("started_at")
    val startedAt: Instant,
) : MessageType

/** `job.progress` — non-terminal progress update (RFC §10.1). */
@Serializable
@SerialName("job.progress")
public data class JobProgress(
    val percent: Int? = null,
    val message: String? = null,
) : MessageType

/** Job state (RFC §10.2) carried in [JobHeartbeat]. */
@Serializable
public enum class JobLifecycleState {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("paused")
    PAUSED,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

/** `job.heartbeat` — periodic liveness signal (RFC §10.3). */
@Serializable
@SerialName("job.heartbeat")
public data class JobHeartbeat(
    val sequence: Long,
    @SerialName("deadline_ms")
    val deadlineMs: Long,
    val state: JobLifecycleState,
) : MessageType

/** `job.checkpoint` — durable checkpoint snapshot (RFC §10.1). */
@Serializable
@SerialName("job.checkpoint")
public data class JobCheckpoint(
    @SerialName("checkpoint_id")
    val checkpointId: String,
    val label: String? = null,
    val data: JsonElement? = null,
) : MessageType

/** `job.completed` — terminal success (RFC §10.2). */
@Serializable
@SerialName("job.completed")
public data class JobCompleted(
    val result: JsonElement? = null,
    @SerialName("result_ref")
    val resultRef: JsonElement? = null,
) : MessageType

/** `job.failed` — terminal error (RFC §18.1). */
@Serializable
@SerialName("job.failed")
public data class JobFailed(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean? = null,
    val details: JsonElement? = null,
) : MessageType

/** `job.cancelled` — terminal cancellation (RFC §10.4). */
@Serializable
@SerialName("job.cancelled")
public data class JobCancelled(
    val reason: String? = null,
    val code: ErrorCode = ErrorCode.CANCELLED,
) : MessageType

/** `job.schedule` — defer or recur (RFC §10.6). v0.1 NACKs with UNIMPLEMENTED. */
@Serializable
@SerialName("job.schedule")
public data class JobSchedule(
    val job: JsonObject,
    val `when`: JsonObject,
) : MessageType

/** `workflow.start` — workflow init (RFC §6.2). v0.1 deferred. */
@Serializable
@SerialName("workflow.start")
public data class WorkflowStart(
    val workflow: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
) : MessageType

/** `workflow.complete` — workflow termination (RFC §6.2). v0.1 deferred. */
@Serializable
@SerialName("workflow.complete")
public data class WorkflowComplete(
    val result: JsonElement? = null,
) : MessageType

/** `agent.delegate` — delegate work to a peer agent (RFC §14). v0.1 deferred. */
@Serializable
@SerialName("agent.delegate")
public data class AgentDelegate(
    val target: String,
    val task: String,
    val context: JsonObject = JsonObject(emptyMap()),
) : MessageType

/** `agent.handoff` — transfer ownership of a session/job (RFC §14). v0.1 deferred. */
@Serializable
@SerialName("agent.handoff")
public data class AgentHandoff(
    val target: String,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("job_id")
    val jobId: String? = null,
    @SerialName("receiving_runtime")
    val receivingRuntime: RuntimeIdentity,
    @SerialName("handoff_for")
    val handoffFor: MessageId? = null,
) : MessageType
