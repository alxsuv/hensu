package io.hensu.core.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Audit record emitted immediately before a tool is invoked.
///
/// Paired with a {@link ToolResultEvent} for every outcome, including tools the
/// agent hallucinated and invocations that threw: an unattended run must leave a
/// complete trail of what the agent asked to run.
///
/// @param nodeId identifier of the node whose agent requested the tool, not null
/// @param agentId identifier of the requesting agent, not null
/// @param toolName the tool the agent asked for, not null
/// @param arguments arguments the agent supplied, not null (may be empty, values may be null)
/// @see ToolResultEvent for the matching outcome record
/// @see io.hensu.core.execution.ExecutionListener#onToolCall
public record ToolCallEvent(
        String nodeId, String agentId, String toolName, Map<String, Object> arguments) {

    /// Compact constructor defensively copying the arguments.
    ///
    /// @implNote The copy tolerates null values – agent arguments originate from
    /// model-produced JSON, where an explicit null is legal – so `Map.copyOf` is
    /// not usable here.
    public ToolCallEvent {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        arguments =
                arguments != null
                        ? Collections.unmodifiableMap(new HashMap<>(arguments))
                        : Map.of();
    }
}
