package dev.arcp.runtime

/** Reserved event kinds from the ARCP event taxonomy. */
public object ReservedEventKinds {
    public val ALL: Set<String> =
        setOf(
            "log",
            "thought",
            "tool_call",
            "tool_result",
            "status",
            "metric",
            "artifact_ref",
            "delegate",
            "progress",
            "result_chunk",
        )
}
