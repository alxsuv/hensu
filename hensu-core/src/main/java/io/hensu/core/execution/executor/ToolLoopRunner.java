package io.hensu.core.execution.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolCapable;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolInvoker;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.tool.ToolResultEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Stateless driver for the agent-native tool execution loop.
///
/// Dispatched from {@link AgentLifecycleRunner} when an agent declares tools
/// and implements {@link ToolCapable}. Resolves tools from the
/// {@link ToolRegistry}, opens a {@link ToolSession}, and iterates
/// tool-request/tool-result rounds until the agent emits a terminal response
/// or the tool call budget is exhausted. Every round is invoked through the
/// context's {@link ToolInvoker} and reported to the execution listener, so an
/// unattended run leaves a complete audit trail.
///
/// @implNote Package-private, stateless, no instances. Safe to call from any
/// thread including Virtual Threads.
final class ToolLoopRunner {

    private static final Logger logger = Logger.getLogger(ToolLoopRunner.class.getName());
    private static final int DEFAULT_MAX_TOOL_CALLS = 10;

    private ToolLoopRunner() {}

    /// Executes the full tool loop for an agent.
    ///
    /// @param eventSourceId  identifier for listener events
    /// @param agentId        identifier of the agent
    /// @param resolvedPrompt prompt with placeholders resolved and enriched
    /// @param agent          the resolved agent instance
    /// @param ctx            execution context carrying state and services
    /// @return node result, never null
    static NodeResult execute(
            String eventSourceId,
            String agentId,
            String resolvedPrompt,
            Agent agent,
            ExecutionContext ctx) {

        if (!(agent instanceof ToolCapable toolCapable)) {
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Agent '" + agentId + "' declares tools but does not implement ToolCapable",
                    Map.of());
        }

        ToolInvoker toolInvoker = ctx.getToolInvoker();
        if (toolInvoker == null) {
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Agent '" + agentId + "' declares tools but no ToolInvoker is configured",
                    Map.of());
        }

        List<ToolDefinition> catalog;
        try {
            catalog = catalog(ctx);
        } catch (IllegalStateException e) {
            // Colliding provider catalogs must fail the node, not escape the loop.
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Tool catalog is unusable for agent '" + agentId + "': " + e.getMessage(),
                    Map.of());
        }

        List<ToolDefinition> availableTools = resolveTools(agent, catalog);
        if (availableTools == null) {
            List<String> declared = agent.getConfig().getTools();
            List<String> available =
                    catalog != null
                            ? catalog.stream().map(ToolDefinition::name).toList()
                            : List.of();
            return new NodeResult(
                    ResultStatus.FAILURE,
                    "Unresolvable tools for agent '"
                            + agentId
                            + "': declared="
                            + declared
                            + ", available="
                            + available,
                    Map.of());
        }

        int maxToolCalls =
                agent.getConfig().getMaxToolCalls() != null
                        ? agent.getConfig().getMaxToolCalls()
                        : DEFAULT_MAX_TOOL_CALLS;

        ToolSession session =
                toolCapable.openToolSession(
                        resolvedPrompt, ctx.getState().getContext(), availableTools);

        try {
            AgentResponse response = session.start();
            int toolCallCount = 0;

            while (response instanceof AgentResponse.ToolRequest toolRequest) {
                // Budget check — count EXECUTED tool calls, not rounds
                if (toolCallCount >= maxToolCalls) {
                    session.compact();
                    ToolCallResult exhaustion =
                            ToolCallResult.failure(
                                    toolRequest.toolName(),
                                    "Tool call budget exhausted ("
                                            + toolCallCount
                                            + "/"
                                            + maxToolCalls
                                            + "). Provide your final answer based on the tool results received so far.");
                    fireCall(ctx, eventSourceId, agentId, toolRequest);
                    fireResult(ctx, eventSourceId, agentId, exhaustion, 0L);
                    response = session.submit(exhaustion);

                    if (response instanceof AgentResponse.ToolRequest) {
                        return new NodeResult(
                                ResultStatus.FAILURE,
                                "Agent continued requesting tools after budget exhaustion ("
                                        + maxToolCalls
                                        + "/"
                                        + maxToolCalls
                                        + ")",
                                Map.of());
                    }
                    break;
                }

                ToolCallResult result =
                        executeTool(toolRequest, availableTools, ctx, eventSourceId, agentId);
                toolCallCount++;
                response = session.submit(result);
            }

            return toNodeResult(response);

        } finally {
            session.close();
        }
    }

    /// Materializes the tool catalog, or null when no registry is configured.
    ///
    /// @throws IllegalStateException if the registry rejects its own catalog
    ///     (two providers exposing the same tool name)
    private static List<ToolDefinition> catalog(ExecutionContext ctx) {
        ToolRegistry registry = ctx.getToolRegistry();
        return registry != null ? registry.all() : null;
    }

    private static List<ToolDefinition> resolveTools(Agent agent, List<ToolDefinition> catalog) {
        if (catalog == null) {
            return null;
        }

        List<String> declaredNames = agent.getConfig().getTools();
        Map<String, ToolDefinition> byName =
                catalog.stream()
                        .collect(Collectors.toMap(ToolDefinition::name, t -> t, (a, _) -> a));

        List<ToolDefinition> resolved = declaredNames.stream().map(byName::get).toList();

        if (resolved.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        return resolved;
    }

    private static ToolCallResult executeTool(
            AgentResponse.ToolRequest toolRequest,
            List<ToolDefinition> availableTools,
            ExecutionContext ctx,
            String eventSourceId,
            String agentId) {

        String toolName = toolRequest.toolName();

        // Unknown tool (hallucination) — feed back, don't hard-fail
        boolean known = availableTools.stream().anyMatch(t -> t.name().equals(toolName));
        if (!known) {
            List<String> available = availableTools.stream().map(ToolDefinition::name).toList();
            logger.warning(
                    "Agent requested unknown tool '" + toolName + "', available: " + available);
            ToolCallResult unknown =
                    ToolCallResult.failure(
                            toolName, "Unknown tool '" + toolName + "', available: " + available);
            fireCall(ctx, eventSourceId, agentId, toolRequest);
            fireResult(ctx, eventSourceId, agentId, unknown, 0L);
            return unknown;
        }

        fireCall(ctx, eventSourceId, agentId, toolRequest);
        long startNanos = System.nanoTime();

        try {
            ToolCallResult result =
                    ctx.getToolInvoker()
                            .call(toolName, arguments(toolRequest), ctx.getState().getContext());
            fireResult(ctx, eventSourceId, agentId, result, elapsedMs(startNanos));
            return result;
        } catch (Exception e) {
            logger.warning("Tool execution failed for '" + toolName + "': " + e.getMessage());
            ToolCallResult failure =
                    ToolCallResult.failure(toolName, "Execution error: " + e.getMessage());
            fireResult(ctx, eventSourceId, agentId, failure, elapsedMs(startNanos));
            return failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(AgentResponse.ToolRequest toolRequest) {
        return (Map<String, Object>) (Map<?, ?>) toolRequest.arguments();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void fireCall(
            ExecutionContext ctx,
            String eventSourceId,
            String agentId,
            AgentResponse.ToolRequest toolRequest) {
        ExecutionListener listener = ctx.getListener();
        listener.onToolCall(
                new ToolCallEvent(
                        eventSourceId, agentId, toolRequest.toolName(), arguments(toolRequest)));
    }

    private static void fireResult(
            ExecutionContext ctx,
            String eventSourceId,
            String agentId,
            ToolCallResult result,
            long durationMs) {
        ExecutionListener listener = ctx.getListener();
        listener.onToolResult(
                new ToolResultEvent(
                        eventSourceId,
                        agentId,
                        result.toolName(),
                        result.success(),
                        durationMs,
                        result.output(),
                        result.error()));
    }

    private static NodeResult toNodeResult(AgentResponse response) {
        return switch (response) {
            case AgentResponse.TextResponse t ->
                    new NodeResult(ResultStatus.SUCCESS, t.content(), t.metadata());
            case AgentResponse.Error e ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            e.message(),
                            Map.of("errorType", e.errorType().name()));
            case AgentResponse.ToolRequest _ ->
                    new NodeResult(
                            ResultStatus.FAILURE,
                            "Unexpected ToolRequest after loop exit",
                            Map.of());
        };
    }
}
