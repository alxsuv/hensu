package io.hensu.core.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.workflow.node.Node;

/// Thread-safe decorator that serialises {@link ExecutionListener} callbacks.
///
/// Parallel branch execution fires listener events from multiple Virtual Threads
/// concurrently. Without synchronization, multi-line output (e.g. box-drawing in
/// {@code VerboseExecutionListener}) interleaves across branches, producing
/// corrupted terminal output.
///
/// This decorator wraps every callback in a {@code synchronized} block so that
/// each event's full output completes atomically. Only the parallel execution
/// path pays the synchronization cost – sequential nodes use the raw listener.
///
/// ### Virtual Thread safety
/// JEP 491 (Java 24+) eliminated Virtual Thread pinning on {@code synchronized}
/// blocks. Monitor contention on I/O-bound listener callbacks (stdout writes) is
/// negligible for typical branch counts (3–10).
///
/// @see ExecutionListener
/// @see io.hensu.core.execution.executor.ParallelNodeExecutor
public final class SynchronizedListenerDecorator implements ExecutionListener {

    private final ExecutionListener delegate;

    public SynchronizedListenerDecorator(ExecutionListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized void onAgentStart(String nodeId, String agentId, String prompt) {
        delegate.onAgentStart(nodeId, agentId, prompt);
    }

    @Override
    public synchronized void onAgentComplete(
            String nodeId, String agentId, AgentResponse response) {
        delegate.onAgentComplete(nodeId, agentId, response);
    }

    @Override
    public synchronized void onNodeStart(Node node) {
        delegate.onNodeStart(node);
    }

    @Override
    public synchronized void onNodeComplete(Node node, NodeResult result) {
        delegate.onNodeComplete(node, result);
    }

    @Override
    public synchronized void onTransitionWarning(String nodeId, String message) {
        delegate.onTransitionWarning(nodeId, message);
    }

    @Override
    public synchronized void onCheckpoint(HensuState state) {
        delegate.onCheckpoint(state);
    }

    @Override
    public synchronized void onToolCall(ToolCallEvent event) {
        delegate.onToolCall(event);
    }

    @Override
    public synchronized void onToolResult(ToolResultEvent event) {
        delegate.onToolResult(event);
    }
}
