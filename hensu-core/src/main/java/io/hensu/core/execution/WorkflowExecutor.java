package io.hensu.core.execution;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.Node;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/// Graph-traversal engine for Hensu workflows.
///
/// Advances the workflow pointer from start node to end node, delegating
/// the per-node lifecycle (phase dispatch, pipeline orchestration, node
/// execution) to {@link NodeLifecycleCoordinator}. The traversal loop is
/// intentionally thin: look up the current node, delegate, check result.
///
/// ### Contracts
/// - **Precondition**: `workflow.getStartNode()` must exist in the workflow's node map
/// - **Postcondition**: Returns `Completed`, `Paused`, `Rejected`, or `Failure`, never null
/// - **Invariant**: State history is append-only during execution
///
/// @implNote **Conditionally thread-safe for concurrent `execute()` calls.**
/// The executor itself holds only `final` fields and creates a fresh {@link HensuState}
/// per call — it carries no mutable state between executions.
///
/// The one shared side effect is agent auto-registration in {@code registerWorkflowAgents()}:
/// before each execution, agents declared in the workflow are compared against the
/// shared {@link AgentRegistry} by both ID and full {@link io.hensu.core.agent.AgentConfig}
/// equality. Agents are only re-created when their configuration has changed (e.g. model
/// update via {@code hensu push}). Under concurrent calls with the same workflow, two
/// threads may race past the {@code hasAgent()} check and both invoke
/// {@code registerAgent()} for the same id. With {@link io.hensu.core.agent.DefaultAgentRegistry}
/// (backed by {@link java.util.concurrent.ConcurrentHashMap}) this race is **benign** —
/// both threads register an equivalent agent from the same config and the second call
/// overwrites the first with an identical value. No data corruption occurs.
///
/// If a custom {@link AgentRegistry} implementation is injected that is not thread-safe,
/// external synchronization around {@code execute()} is required.
///
/// @see NodeLifecycleCoordinator for per-node lifecycle logic
/// @see ProcessorPipeline for pre/post processor chains
/// @see NodeExecutorRegistry for node type dispatch
public class WorkflowExecutor {

    private static final Logger logger = Logger.getLogger(WorkflowExecutor.class.getName());

    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final AgentRegistry agentRegistry;
    private final RubricEngine rubricEngine;
    private final TemplateResolver templateResolver;
    private final ActionExecutor actionExecutor;
    private final WorkflowRepository workflowRepository;
    private final ToolRouter toolRouter;
    private final NodeLifecycleCoordinator lifecycleCoordinator;

    /// Creates a workflow executor with all dependencies.
    ///
    /// @param nodeExecutorRegistry  registry for node-type-specific executors, not null
    /// @param agentRegistry         registry of available AI agents, not null
    /// @param rubricEngine          engine for rubric-based quality evaluation, not null
    /// @param lifecycleCoordinator  per-node lifecycle processor, not null
    /// @param actionExecutor        executor for executable actions, may be null
    /// @param templateResolver      resolver for `{variable}` syntax in prompts, not null
    /// @param workflowRepository    repository for loading sub-workflow definitions, may be null
    /// @param toolRouter            router for tool discovery and invocation, may be null
    public WorkflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            RubricEngine rubricEngine,
            NodeLifecycleCoordinator lifecycleCoordinator,
            ActionExecutor actionExecutor,
            TemplateResolver templateResolver,
            WorkflowRepository workflowRepository,
            ToolRouter toolRouter) {
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.agentRegistry = agentRegistry;
        this.rubricEngine = rubricEngine;
        this.lifecycleCoordinator =
                Objects.requireNonNull(
                        lifecycleCoordinator, "lifecycleCoordinator must not be null");
        this.actionExecutor = actionExecutor;
        this.templateResolver = templateResolver;
        this.workflowRepository = workflowRepository;
        this.toolRouter = toolRouter;
    }

    /// Executes a workflow without observability listener.
    ///
    /// Convenience method that uses a no-op listener.
    ///
    /// @param workflow       the workflow definition to execute, not null
    /// @param initialContext initial context variables for template resolution, not null
    /// @return execution result indicating completion or rejection, never null
    /// @throws Exception if execution fails due to missing nodes, agents, or execution errors
    /// @throws IllegalStateException if start node not found or no valid transitions exist
    public ExecutionResult execute(Workflow workflow, Map<String, Object> initialContext)
            throws Exception {
        return execute(workflow, initialContext, ExecutionListener.NOOP);
    }

    /// Executes a workflow with an observability listener.
    ///
    /// Traverses the workflow graph from start node to end node, executing
    /// each node via type-specific executors. Post-execution processing
    /// (output extraction, history, review, rubric, transitions) is delegated
    /// to the {@link ProcessorPipeline}.
    ///
    /// @param workflow       the workflow definition to execute, not null
    /// @param initialContext initial context variables for template resolution, not null
    /// @param listener       listener for execution events (node start/complete, agent calls),
    /// not null
    /// @return execution result indicating completion or rejection, never null
    /// @throws Exception if execution fails due to missing nodes, agents, or execution errors
    /// @throws IllegalStateException if start node not found or no valid transitions exist
    public ExecutionResult execute(
            Workflow workflow, Map<String, Object> initialContext, ExecutionListener listener)
            throws Exception {

        registerWorkflowAgents(workflow);

        String currentNodeId = workflow.getStartNode();
        String executionId =
                initialContext.containsKey("_execution_id")
                        ? (String) initialContext.get("_execution_id")
                        : UUID.randomUUID().toString();
        HensuState state =
                new HensuState.Builder()
                        .executionId(executionId)
                        .workflowId(workflow.getId())
                        .currentNode(currentNodeId)
                        .context(initialContext)
                        .history(new ExecutionHistory())
                        .build();

        return executeLoop(state, workflow, listener, null);
    }

    /// Resumes execution of a workflow from a previously saved state.
    ///
    /// Used after a workflow was paused (returned {@link ExecutionResult.Paused})
    /// and needs to continue from the saved checkpoint.
    ///
    /// @param workflow   the workflow definition, not null
    /// @param savedState the state to resume from, not null
    /// @return execution result indicating completion, pause, or rejection, never null
    /// @throws Exception if execution fails
    public ExecutionResult executeFrom(Workflow workflow, HensuState savedState) throws Exception {
        return executeFrom(workflow, savedState, ExecutionListener.NOOP);
    }

    /// Resumes execution of a workflow from a previously saved state with a listener.
    ///
    /// @param workflow   the workflow definition, not null
    /// @param savedState the state to resume from, not null
    /// @param listener   listener for execution events, not null
    /// @return execution result indicating completion, pause, or rejection, never null
    /// @throws Exception if execution fails
    public ExecutionResult executeFrom(
            Workflow workflow, HensuState savedState, ExecutionListener listener) throws Exception {
        registerWorkflowAgents(workflow);
        return executeLoop(savedState, workflow, listener, null);
    }

    /// Executes a sub-flow that stops at a named boundary node.
    ///
    /// Used by {@code ForkNodeExecutor} to run forked sub-flows. The execution
    /// traverses the graph from {@code state.getCurrentNode()} through the full
    /// pipeline (pre- / post-processors) for each node, stopping when it reaches
    /// the boundary node. The boundary node itself is NOT executed – it serves
    /// as a graph marker read by the fork executor for merge configuration.
    ///
    /// @param boundaryNodeId the node ID where execution stops (typically a join node), not null
    /// @param state          branch-isolated state positioned at the sub-flow start node, not null
    /// @param workflow       the workflow definition containing all nodes, not null
    /// @param listener       execution event listener (should be thread-safe for
    ///                       concurrent sub-flows), not null
    /// @return {@link ExecutionResult.Completed} when the boundary is reached, with the
    /// branch state containing all variables written by sub-flow nodes via {@code writes()}
    /// @throws Exception if any sub-flow node fails
    public ExecutionResult executeUntil(
            String boundaryNodeId, HensuState state, Workflow workflow, ExecutionListener listener)
            throws Exception {
        return executeLoop(state, workflow, listener, boundaryNodeId);
    }

    /// Graph-traversal loop shared by {@link #execute}, {@link #executeFrom},
    /// and {@link #executeUntil}.
    ///
    /// Advances the node pointer and delegates per-node processing to
    /// {@link NodeLifecycleCoordinator#processNode}. Exits when the
    /// coordinator returns a terminal result or the boundary node is reached.
    ///
    /// @param state          current workflow state with position, not null
    /// @param workflow       workflow definition, not null
    /// @param listener       execution event listener, not null
    /// @param boundaryNodeId optional boundary node ID for sub-flow execution (null for main flow)
    /// @return execution result, never null
    /// @throws Exception if execution fails
    private ExecutionResult executeLoop(
            HensuState state, Workflow workflow, ExecutionListener listener, String boundaryNodeId)
            throws Exception {

        ExecutionContext context = createExecutionContext(state, workflow, listener);

        while (true) {
            String currentNodeId = state.getCurrentNode();

            if (currentNodeId.equals(boundaryNodeId)) {
                return new ExecutionResult.Completed(state, ExitStatus.SUCCESS);
            }

            Node node = workflow.getNodes().get(currentNodeId);
            if (node == null) {
                throw new IllegalStateException("Node not found: " + currentNodeId);
            }

            ExecutionResult result = lifecycleCoordinator.processNode(node, state, context);
            if (result != null) {
                logTerminalResult(result, currentNodeId);
                return result;
            }
        }
    }

    private ExecutionContext createExecutionContext(
            HensuState state, Workflow workflow, ExecutionListener listener) {
        return ExecutionContext.builder()
                .state(state)
                .workflow(workflow)
                .listener(listener)
                .agentRegistry(agentRegistry)
                .templateResolver(templateResolver)
                .nodeExecutorRegistry(nodeExecutorRegistry)
                .workflowExecutor(this)
                .actionExecutor(actionExecutor)
                .workflowRepository(workflowRepository)
                .rubricEngine(rubricEngine)
                .toolRegistry(toolRouter)
                .toolInvoker(toolRouter)
                .build();
    }

    /// Logs all terminal execution results centrally for observability.
    ///
    /// Covers every {@link ExecutionResult} variant so that completions,
    /// rejections, pauses, and failures all produce a uniform INFO-level log
    /// line from the same class.
    private void logTerminalResult(ExecutionResult result, String lastNodeId) {
        switch (result) {
            case ExecutionResult.Completed(_, var exitStatus) ->
                    logger.info("Reached end node: " + lastNodeId + " (" + exitStatus + ")");
            case ExecutionResult.Rejected(var reason, _) ->
                    logger.info(
                            "Execution rejected at node: " + lastNodeId + " – reason: " + reason);
            case ExecutionResult.Paused _ -> logger.info("Execution paused at node: " + lastNodeId);
            case ExecutionResult.Failure(_, var e) ->
                    logger.severe(
                            "Execution failed at node: " + lastNodeId + " – " + e.getMessage());
            case ExecutionResult.Success _ -> {
                // Intermediate result, no terminal log needed
            }
        }
    }

    /// Registers all agents defined in the workflow.
    ///
    /// Compares each agent's configuration against the registry; re-creates
    /// only when the config has changed (e.g. model update via {@code hensu push}).
    private void registerWorkflowAgents(Workflow workflow) {
        workflow.getAgents()
                .forEach(
                        (agentId, config) -> {
                            if (!agentRegistry.hasAgent(agentId, config)) {
                                logger.info("Auto-registering agent from workflow: " + agentId);
                                agentRegistry.registerAgent(agentId, config);
                            }
                        });
    }
}
