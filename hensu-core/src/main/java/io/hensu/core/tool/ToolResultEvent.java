package io.hensu.core.tool;

import java.util.Objects;

/// Audit record emitted after a tool invocation settles, whatever the outcome.
///
/// Tool output is unbounded in principle (a build log, a file dump), so the
/// record truncates it to {@link #MAX_OUTPUT_CHARS} characters: an audit trail
/// must stay cheap enough to always be on.
///
/// @param nodeId identifier of the node whose agent requested the tool, not null
/// @param agentId identifier of the requesting agent, not null
/// @param toolName the tool that was invoked, not null
/// @param success whether the invocation succeeded
/// @param durationMs wall-clock duration of the invocation in milliseconds
/// @param output tool output, truncated to {@link #MAX_OUTPUT_CHARS}, may be null
/// @param error error description on failure, may be null
/// @see ToolCallEvent for the matching request record
/// @see io.hensu.core.execution.ExecutionListener#onToolResult
public record ToolResultEvent(
        String nodeId,
        String agentId,
        String toolName,
        boolean success,
        long durationMs,
        String output,
        String error) {

    /// Maximum number of output characters retained; longer output is truncated.
    public static final int MAX_OUTPUT_CHARS = 4096;

    private static final String TRUNCATION_MARKER = "… [truncated]";

    /// Compact constructor truncating oversized output.
    public ToolResultEvent {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATION_MARKER;
        }
    }
}
