package io.hensu.core.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.workflow.node.Node;

/// Listener for workflow execution lifecycle events.
///
/// Provides hooks for observability, crash-recovery checkpointing, and debugging
/// during workflow execution. All methods have default no-op implementations,
/// allowing listeners to override only the events they care about.
///
/// ### Callback Lifecycle
/// Each non-end node in the execution loop triggers callbacks in this order:
///
/// ```
/// onCheckpoint(state)             — state is consistent, safe to persist
/// onNodeStart(node)               — about to execute node
/// onAgentStart(nodeId, ...)       — about to call LLM
/// onAgentComplete(nodeId, ...)    — LLM returned result
/// onNodeComplete(node, result)    — node execution finished (state NOT yet updated)
/// [state mutations: output → history → review → rubric → transitions]
/// ```
///
/// ### Usage
/// Implement this interface to receive callbacks during workflow execution:
/// - Checkpoint state for crash recovery ({@link #onCheckpoint})
/// - Track agent invocations and responses
/// - Log node execution progress
/// - Collect metrics for monitoring
///
/// @implNote All methods must be thread-safe if used with parallel node execution.
/// The listener may receive callbacks from multiple threads simultaneously.
///
/// @see WorkflowExecutor#execute(io.hensu.core.workflow.Workflow, java.util.Map, ExecutionListener)
public interface ExecutionListener {

    /// Called before an agent executes a prompt.
    ///
    /// @param nodeId identifier of the node being executed, not null
    /// @param agentId identifier of the agent handling execution, not null
    /// @param prompt the resolved prompt sent to the agent, not null
    default void onAgentStart(String nodeId, String agentId, String prompt) {}

    /// Called after an agent completes execution.
    ///
    /// @param nodeId identifier of the node that was executed, not null
    /// @param agentId identifier of the agent that handled execution, not null
    /// @param response the agent's response containing output and metadata, not null
    default void onAgentComplete(String nodeId, String agentId, AgentResponse response) {}

    /// Called when a node execution starts.
    ///
    /// @param node the node being executed, not null
    default void onNodeStart(Node node) {}

    /// Called when a node execution completes.
    ///
    /// @param node the node that completed execution, not null
    /// @param result the execution result containing status and output, not null
    default void onNodeComplete(Node node, NodeResult result) {}

    /// Called when a transition rule detects a type mismatch while routing.
    ///
    /// Fires when a routing value cannot be coerced to a rule's expected form (absent
    /// variable, non-numeric string under a numeric operator, structured JSON object).
    /// The rule reports no match, but the mismatch is never silent – surface this
    /// warning so loop budgets are not burned invisibly.
    ///
    /// @param nodeId identifier of the node whose transition rule mismatched, not null
    /// @param message diagnostic naming variable, expected form, and actual value, not null
    default void onTransitionWarning(String nodeId, String message) {}

    /// Called when workflow state is fully consistent and safe to persist.
    ///
    /// Fires once per loop iteration, after all state mutations from the previous
    /// node (output, history, review, rubric, transitions) are applied, and before
    /// the next node begins execution. Intended for crash-recovery checkpointing.
    ///
    /// @param state the current workflow state with consistent position and context, not null
    default void onCheckpoint(HensuState state) {}

    /// Called immediately before an agent's tool request is invoked.
    ///
    /// Fires for every request the agent makes, including tools it hallucinated:
    /// the audit trail records what was asked for, not only what ran.
    ///
    /// @param event the tool request being dispatched, not null
    /// @see #onToolResult for the matching outcome callback
    default void onToolCall(ToolCallEvent event) {}

    /// Called after a tool invocation settles, whatever the outcome.
    ///
    /// Fires exactly once for every {@link #onToolCall}, reporting success,
    /// provider failure, unknown-tool rejection, or an exception during
    /// invocation.
    ///
    /// @param event the invocation outcome, not null
    default void onToolResult(ToolResultEvent event) {}

    /// No-op listener instance that ignores all events.
    ///
    /// Use this when no observability is needed.
    ExecutionListener NOOP = new ExecutionListener() {};
}
