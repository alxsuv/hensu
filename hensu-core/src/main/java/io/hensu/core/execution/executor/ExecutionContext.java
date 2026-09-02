package io.hensu.core.execution.executor;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.parallel.BranchExecutionConfig;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.tool.ToolInvoker;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;

/// Encapsulates all dependencies and state needed for node execution.
///
/// This context object bundles execution-related services and state, allowing
/// each NodeExecutor to access only what it needs without requiring a bloated
/// method signature. Context is immutable but provides `with*` methods for
/// creating modified copies for sub-workflow execution.
///
/// ### Required Fields
/// - `state` - Current workflow execution state
/// - `workflow` - Workflow definition being executed
///
/// ### Optional Services
/// - `agentRegistry` - For resolving agent instances
/// - `templateResolver` - For prompt variable substitution
/// - `nodeExecutorRegistry` - For node type dispatch
/// - `workflowExecutor` - For sub-workflow invocation
/// - `actionExecutor` - For command/action execution
/// - `rubricEngine` - For rubric-based quality evaluation
/// - `workflowRepository` - For loading sub-workflow definitions
/// - `toolRegistry` - For discovering the tools an agent may request
/// - `toolInvoker` - For running the tools an agent requested
///
/// @implNote Immutable after construction. Thread-safe for read access.
/// Modified copies can be created via {@link #withState}, {@link #withListener},
/// and {@link #withBranchConfig}.
///
/// @see NodeExecutor for node execution logic
/// @see WorkflowExecutor for main execution loop
public final class ExecutionContext {

    // Required - always needed
    private final HensuState state;
    private final Workflow workflow;
    private final ExecutionListener listener;

    // Branch execution metadata - null when not executing a parallel branch
    private final BranchExecutionConfig branchConfig;

    // Services - provided by registry, executors pull what they need
    private final AgentRegistry agentRegistry;
    private final TemplateResolver templateResolver;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final WorkflowExecutor workflowExecutor;
    private final ActionExecutor actionExecutor;
    private final RubricEngine rubricEngine;
    private final WorkflowRepository workflowRepository;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;

    private ExecutionContext(Builder builder) {
        this.state = builder.state;
        this.workflow = builder.workflow;
        this.listener = builder.listener;
        this.branchConfig = builder.branchConfig;
        this.agentRegistry = builder.agentRegistry;
        this.templateResolver = builder.templateResolver;
        this.nodeExecutorRegistry = builder.nodeExecutorRegistry;
        this.workflowExecutor = builder.workflowExecutor;
        this.actionExecutor = builder.actionExecutor;
        this.rubricEngine = builder.rubricEngine;
        this.workflowRepository = builder.workflowRepository;
        this.toolRegistry = builder.toolRegistry;
        this.toolInvoker = builder.toolInvoker;
    }

    /// Returns the current workflow execution state.
    ///
    /// @return current state, never null
    public HensuState getState() {
        return state;
    }

    /// Returns the workflow definition being executed.
    ///
    /// @return workflow definition, never null
    public Workflow getWorkflow() {
        return workflow;
    }

    /// Returns the execution event listener.
    ///
    /// @return listener (defaults to NOOP), never null
    public ExecutionListener getListener() {
        return listener;
    }

    /// Returns the agent registry for resolving agents.
    ///
    /// @return agent registry, or null if not configured
    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    /// Returns the template resolver for prompt variables.
    ///
    /// @return template resolver, or null if not configured
    public TemplateResolver getTemplateResolver() {
        return templateResolver;
    }

    /// Returns the node executor registry for type dispatch.
    ///
    /// @return node executor registry, or null if not configured
    public NodeExecutorRegistry getNodeExecutorRegistry() {
        return nodeExecutorRegistry;
    }

    /// Returns the workflow executor for sub-workflow invocation.
    ///
    /// @return workflow executor, or null if not configured
    public WorkflowExecutor getWorkflowExecutor() {
        return workflowExecutor;
    }

    /// Returns the action executor for command execution.
    ///
    /// @return action executor, or null if not configured
    public ActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    /// Returns the rubric engine for quality evaluation of node and branch outputs.
    ///
    /// @return rubric engine, or null if not configured
    /// @see RubricEngine for evaluation logic
    public RubricEngine getRubricEngine() {
        return rubricEngine;
    }

    /// Returns the workflow repository for tenant-scoped sub-workflow loading.
    ///
    /// Used by {@link io.hensu.core.execution.executor.SubWorkflowNodeExecutor}
    /// to resolve nested workflow definitions at runtime.
    ///
    /// @return workflow repository, or null if not configured
    public WorkflowRepository getWorkflowRepository() {
        return workflowRepository;
    }

    /// Returns the tool registry for resolving available tools.
    ///
    /// @return tool registry, or null if not configured
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /// Returns the tool invoker for running the tools an agent requested.
    ///
    /// Distinct from {@link #getToolRegistry()} on purpose: the tool loop
    /// discovers through the registry and executes through the invoker, so a
    /// runtime can expose a catalog it cannot itself run.
    ///
    /// @return tool invoker, or null if not configured
    public ToolInvoker getToolInvoker() {
        return toolInvoker;
    }

    /// Returns the branch execution configuration, if executing inside a parallel branch.
    ///
    /// Non-null only during branch execution within {@code ParallelNodeExecutor}.
    /// Enrichers use this to detect consensus branches and read yield declarations
    /// without polluting the user-visible state context map.
    ///
    /// @return branch config, or null if not executing a branch
    /// @see BranchExecutionConfig
    public BranchExecutionConfig getBranchConfig() {
        return branchConfig;
    }

    /// Creates a new context builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Creates a child context with a different state.
    ///
    /// Used for sub-workflow execution or branch contexts where only
    /// the state differs from the parent context.
    ///
    /// @param newState the new execution state, not null
    /// @return new context with updated state, never null
    public ExecutionContext withState(HensuState newState) {
        return builder()
                .state(newState)
                .workflow(this.workflow)
                .listener(this.listener)
                .branchConfig(this.branchConfig)
                .agentRegistry(this.agentRegistry)
                .templateResolver(this.templateResolver)
                .nodeExecutorRegistry(this.nodeExecutorRegistry)
                .workflowExecutor(this.workflowExecutor)
                .actionExecutor(this.actionExecutor)
                .rubricEngine(this.rubricEngine)
                .workflowRepository(this.workflowRepository)
                .toolRegistry(this.toolRegistry)
                .toolInvoker(this.toolInvoker)
                .build();
    }

    /// Creates a child context with a different listener.
    ///
    /// Used by {@code ParallelNodeExecutor} to wrap the listener in a
    /// thread-safe decorator for concurrent branch execution.
    ///
    /// @param newListener the new execution listener, not null
    /// @return new context with updated listener, never null
    public ExecutionContext withListener(ExecutionListener newListener) {
        return builder()
                .state(this.state)
                .workflow(this.workflow)
                .listener(newListener)
                .branchConfig(this.branchConfig)
                .agentRegistry(this.agentRegistry)
                .templateResolver(this.templateResolver)
                .nodeExecutorRegistry(this.nodeExecutorRegistry)
                .workflowExecutor(this.workflowExecutor)
                .actionExecutor(this.actionExecutor)
                .rubricEngine(this.rubricEngine)
                .workflowRepository(this.workflowRepository)
                .toolRegistry(this.toolRegistry)
                .toolInvoker(this.toolInvoker)
                .build();
    }

    /// Creates a child context with branch execution metadata.
    ///
    /// Used by {@code ParallelNodeExecutor} to attach branch-specific config
    /// (consensus flag, yield declarations) without polluting the state context map.
    ///
    /// @param config branch execution configuration, may be null to clear
    /// @return new context with branch config set, never null
    public ExecutionContext withBranchConfig(BranchExecutionConfig config) {
        return builder()
                .state(this.state)
                .workflow(this.workflow)
                .listener(this.listener)
                .branchConfig(config)
                .agentRegistry(this.agentRegistry)
                .templateResolver(this.templateResolver)
                .nodeExecutorRegistry(this.nodeExecutorRegistry)
                .workflowExecutor(this.workflowExecutor)
                .actionExecutor(this.actionExecutor)
                .rubricEngine(this.rubricEngine)
                .workflowRepository(this.workflowRepository)
                .toolRegistry(this.toolRegistry)
                .toolInvoker(this.toolInvoker)
                .build();
    }

    /// Builder for constructing ExecutionContext instances.
    ///
    /// Required fields: `state`, `workflow`
    ///
    /// @see #build() for validation rules
    public static final class Builder {
        private HensuState state;
        private Workflow workflow;
        private ExecutionListener listener = ExecutionListener.NOOP;
        private BranchExecutionConfig branchConfig;
        private AgentRegistry agentRegistry;
        private TemplateResolver templateResolver;
        private NodeExecutorRegistry nodeExecutorRegistry;
        private WorkflowExecutor workflowExecutor;
        private ActionExecutor actionExecutor;
        private RubricEngine rubricEngine;
        private WorkflowRepository workflowRepository;
        private ToolRegistry toolRegistry;
        private ToolInvoker toolInvoker;

        private Builder() {}

        public Builder state(HensuState state) {
            this.state = state;
            return this;
        }

        public Builder workflow(Workflow workflow) {
            this.workflow = workflow;
            return this;
        }

        public Builder listener(ExecutionListener listener) {
            this.listener = listener != null ? listener : ExecutionListener.NOOP;
            return this;
        }

        public Builder branchConfig(BranchExecutionConfig branchConfig) {
            this.branchConfig = branchConfig;
            return this;
        }

        public Builder agentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistry = agentRegistry;
            return this;
        }

        public Builder templateResolver(TemplateResolver templateResolver) {
            this.templateResolver = templateResolver;
            return this;
        }

        public Builder nodeExecutorRegistry(NodeExecutorRegistry nodeExecutorRegistry) {
            this.nodeExecutorRegistry = nodeExecutorRegistry;
            return this;
        }

        public Builder workflowExecutor(WorkflowExecutor workflowExecutor) {
            this.workflowExecutor = workflowExecutor;
            return this;
        }

        public Builder actionExecutor(ActionExecutor actionExecutor) {
            this.actionExecutor = actionExecutor;
            return this;
        }

        public Builder rubricEngine(RubricEngine rubricEngine) {
            this.rubricEngine = rubricEngine;
            return this;
        }

        public Builder workflowRepository(WorkflowRepository workflowRepository) {
            this.workflowRepository = workflowRepository;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder toolInvoker(ToolInvoker toolInvoker) {
            this.toolInvoker = toolInvoker;
            return this;
        }

        public ExecutionContext build() {
            if (state == null) {
                throw new IllegalStateException("state is required");
            }
            if (workflow == null) {
                throw new IllegalStateException("workflow is required");
            }
            return new ExecutionContext(this);
        }
    }
}
