package io.hensu.core;

import io.hensu.core.agent.AgentFactory;
import io.hensu.core.agent.AgentProvider;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.DefaultAgentRegistry;
import io.hensu.core.agent.stub.StubAgentProvider;
import io.hensu.core.execution.NodeLifecycleCoordinator;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.*;
import io.hensu.core.rubric.InMemoryRubricRepository;
import io.hensu.core.rubric.RubricRepository;
import io.hensu.core.rubric.evaluator.ScoreExtractingEvaluator;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.InMemoryWorkflowRepository;
import io.hensu.core.workflow.WorkflowRepository;
import java.util.*;

/// Factory for creating and wiring Hensu workflow execution environments.
///
/// Provides static factory methods and a fluent {@link Builder} for constructing
/// fully-configured {@link HensuEnvironment} instances. Handles dependency wiring,
/// credential loading, and component initialization with zero external dependencies.
///
/// ### Usage Patterns
///
/// **Builder with explicit providers** (recommended for Quarkus apps):
/// {@snippet :
/// var env = HensuFactory.builder()
///     .loadCredentials(properties)
///     .agentProviders(List.of(new LangChain4jProvider()))
///     .build();
/// }
///
/// **Quick start with environment variables** (standalone):
/// {@snippet :
/// var env = HensuFactory.createEnvironment();
/// }
///
/// @implNote This is a utility class with only static methods. All dependencies
/// are wired explicitly via constructor injection in created components.
///
/// @see HensuEnvironment
/// @see HensuConfig
/// @see Builder
public final class HensuFactory {

    private HensuFactory() {
        // Utility class - prevent instantiation
    }

    /// Creates a complete Hensu environment using default configuration and
    /// credentials discovered from environment variables.
    ///
    /// @apiNote **Side effects**:
    /// - Reads environment variables matching API key patterns
    ///
    /// @return a fully-configured environment, never null
    /// @see #loadCredentialsFromEnvironment()
    public static HensuEnvironment createEnvironment() {
        Map<String, String> credentials = loadCredentialsFromEnvironment();
        return createEnvironment(new HensuConfig(), credentials);
    }

    /// Creates a Hensu environment with custom configuration and credentials
    /// discovered from environment variables.
    ///
    /// @param config configuration options for threading and storage, not null
    /// @return a fully-configured environment, never null
    public static HensuEnvironment createEnvironment(HensuConfig config) {
        Map<String, String> credentials = loadCredentialsFromEnvironment();
        return createEnvironment(config, credentials);
    }

    /// Creates a Hensu environment with custom configuration and explicit credentials.
    ///
    /// This is the primary factory method that other overloads delegate to.
    ///
    /// @apiNote **Side effects**:
    /// - Sets `hensu.stub.enabled` system property if stub mode is enabled in credentials
    ///
    /// @param config configuration options for threading and storage, not null
    /// @param credentials map of API keys and settings, not null (may be empty)
    /// @return a fully-configured environment, never null
    public static HensuEnvironment createEnvironment(
            HensuConfig config, Map<String, String> credentials) {
        // Apply stub mode setting as system property if present in credentials
        applyStubModeSetting(credentials);

        // Create AgentFactory with StubAgentProvider (the only provider in hensu-core).
        // For real model providers, use HensuFactory.builder().agentProviders(...).
        AgentFactory agentFactory = new AgentFactory(credentials, List.of(new StubAgentProvider()));

        // Create AgentRegistry using the factory
        AgentRegistry agentRegistry = new DefaultAgentRegistry(agentFactory);

        // Create NodeExecutorRegistry (stateless executors get services from ExecutionContext)
        NodeExecutorRegistry nodeExecutorRegistry = new DefaultNodeExecutorRegistry();

        return createEnvironment(config, nodeExecutorRegistry, agentRegistry);
    }

    /// Sets the `hensu.stub.enabled` system property if enabled in credentials.
    ///
    /// @apiNote **Side effects**: Modifies JVM system properties when stub mode is enabled.
    ///
    /// @param credentials map potentially containing `hensu.stub.enabled` key, not null
    private static void applyStubModeSetting(Map<String, String> credentials) {
        String stubEnabled = credentials.get("hensu.stub.enabled");
        if ("true".equalsIgnoreCase(stubEnabled)) {
            System.setProperty("hensu.stub.enabled", "true");
        }
    }

    /// Creates a Hensu environment with pre-configured registries.
    ///
    /// Useful for testing with mock registries or custom implementations.
    ///
    /// @param config configuration options, not null
    /// @param nodeExecutorRegistry registry for node type executors, not null
    /// @param agentRegistry registry for AI agents, not null
    /// @return a fully-configured environment, never null
    public static HensuEnvironment createEnvironment(
            HensuConfig config,
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry) {
        return createEnvironment(
                config,
                nodeExecutorRegistry,
                agentRegistry,
                ReviewHandler.AUTO_APPROVE,
                null,
                new InMemoryWorkflowRepository(),
                new InMemoryWorkflowStateRepository());
    }

    /// Creates a Hensu environment with pre-configured registries and review support.
    ///
    /// @param config configuration options, not null
    /// @param nodeExecutorRegistry registry for node type executors, not null
    /// @param agentRegistry registry for AI agents, not null
    /// @param reviewHandler handler for human review checkpoints, may be null for auto-approve
    /// @return a fully-configured environment, never null
    public static HensuEnvironment createEnvironment(
            HensuConfig config,
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ReviewHandler reviewHandler) {
        return createEnvironment(
                config,
                nodeExecutorRegistry,
                agentRegistry,
                reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE,
                null,
                new InMemoryWorkflowRepository(),
                new InMemoryWorkflowStateRepository());
    }

    /// Creates a Hensu environment with all configuration options.
    ///
    /// This is the most complete factory method, allowing full control over
    /// all components including review handler, action executor, and repositories.
    ///
    /// @param config configuration options, not null
    /// @param nodeExecutorRegistry registry for node type executors, not null
    /// @param agentRegistry registry for AI agents, not null
    /// @param reviewHandler handler for human review checkpoints, may be null for auto-approve
    /// @param actionExecutor executor for executable actions, may be null for logging-only mode
    /// @param workflowRepository tenant-scoped storage for workflow definitions, not null
    /// @param workflowStateRepository tenant-scoped storage for execution state snapshots, not null
    /// @return a fully-configured environment, never null
    public static HensuEnvironment createEnvironment(
            HensuConfig config,
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ReviewHandler reviewHandler,
            ActionExecutor actionExecutor,
            WorkflowRepository workflowRepository,
            WorkflowStateRepository workflowStateRepository) {
        return buildCoreComponents(
                config,
                nodeExecutorRegistry,
                agentRegistry,
                reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE,
                actionExecutor,
                workflowRepository,
                workflowStateRepository,
                null);
    }

    /// Internal factory shared by the public static overloads and {@link Builder#build()}.
    ///
    /// Constructs all runtime components (rubric engine, template resolver, workflow executor)
    /// and assembles the final {@link HensuEnvironment}.
    private static HensuEnvironment buildCoreComponents(
            HensuConfig config,
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ReviewHandler reviewHandler,
            ActionExecutor actionExecutor,
            WorkflowRepository workflowRepository,
            WorkflowStateRepository workflowStateRepository,
            ToolRouter toolRouter) {
        RubricRepository rubricRepository = createRubricRepository(config);
        RubricEngine rubricEngine =
                new RubricEngine(rubricRepository, new ScoreExtractingEvaluator());
        TemplateResolver templateResolver = createTemplateResolver();

        NodeLifecycleCoordinator lifecycleCoordinator =
                new NodeLifecycleCoordinator(
                        nodeExecutorRegistry,
                        ProcessorPipeline.preExecution(),
                        ProcessorPipeline.postExecution(reviewHandler, rubricEngine));

        WorkflowExecutor workflowExecutor =
                new WorkflowExecutor(
                        nodeExecutorRegistry,
                        agentRegistry,
                        rubricEngine,
                        lifecycleCoordinator,
                        actionExecutor,
                        templateResolver,
                        workflowRepository,
                        toolRouter != null ? toolRouter : ToolRouter.empty());

        return new HensuEnvironment(
                workflowExecutor,
                nodeExecutorRegistry,
                agentRegistry,
                rubricRepository,
                actionExecutor,
                workflowRepository,
                workflowStateRepository);
    }

    /// Discovers and loads API credentials from environment variables.
    ///
    /// Automatically discovers API keys matching common naming patterns:
    /// - `*_API_KEY` (e.g., `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`)
    /// - `*_KEY` (e.g., `AZURE_OPENAI_KEY`)
    /// - `*_SECRET` (e.g., `CLIENT_SECRET`)
    /// - `*_TOKEN` (e.g., `ACCESS_TOKEN`)
    ///
    /// @return map of discovered credentials, never null (may be empty)
    public static Map<String, String> loadCredentialsFromEnvironment() {
        Map<String, String> credentials = new HashMap<>();

        // Auto-discover all environment variables matching API key patterns
        System.getenv()
                .forEach(
                        (key, value) -> {
                            if (value != null && !value.isEmpty() && isApiKeyPattern(key)) {
                                credentials.put(key, value);
                            }
                        });

        return credentials;
    }

    /// Checks if an environment variable name matches common API key patterns.
    ///
    /// @param key the environment variable name, not null
    /// @return `true` if the key matches an API key pattern
    private static boolean isApiKeyPattern(String key) {
        String upperKey = key.toUpperCase();
        return upperKey.endsWith("_API_KEY")
                || upperKey.endsWith("_KEY")
                || upperKey.endsWith("_SECRET")
                || upperKey.endsWith("_TOKEN");
    }

    /// Loads credentials from a Properties object.
    ///
    /// Supports multiple patterns:
    /// - Prefixed keys (e.g., `hensu.credentials.ANTHROPIC_API_KEY=sk-...`) — prefix is stripped
    /// - Direct API key names (e.g., `ANTHROPIC_API_KEY=sk-...`)
    /// - Stub mode setting: `hensu.stub.enabled=true`
    ///
    /// @param properties the properties to extract credentials from, not null
    /// @return map of credential keys to their values, never null (may be empty)
    public static Map<String, String> loadCredentialsFromProperties(Properties properties) {
        Map<String, String> credentials = new HashMap<>();
        String prefix = "hensu.credentials.";
        String stubEnabledKey = "hensu.stub.enabled";

        properties.forEach(
                (key, value) -> {
                    String keyStr = key.toString();
                    String valueStr = value.toString();

                    if (valueStr != null && !valueStr.isEmpty()) {
                        // Check for prefixed keys (hensu.credentials.ANTHROPIC_API_KEY)
                        if (keyStr.startsWith(prefix)) {
                            String credentialKey = keyStr.substring(prefix.length());
                            credentials.put(credentialKey, valueStr);
                        }
                        // Check for stub mode setting
                        else if (keyStr.equals(stubEnabledKey)) {
                            credentials.put(stubEnabledKey, valueStr);
                        }
                        // Also check for direct API key patterns (ANTHROPIC_API_KEY)
                        else if (isApiKeyPattern(keyStr)) {
                            credentials.put(keyStr, valueStr);
                        }
                    }
                });

        return credentials;
    }

    /// Loads credentials from both environment variables and properties.
    ///
    /// Properties take precedence over environment variables when the same
    /// key exists in both sources.
    ///
    /// @param properties the properties to merge with environment credentials, not null
    /// @return merged map of credentials (properties override env vars), never null
    public static Map<String, String> loadCredentials(Properties properties) {
        Map<String, String> credentials = loadCredentialsFromEnvironment();
        credentials.putAll(loadCredentialsFromProperties(properties));
        return credentials;
    }

    /// Creates a rubric repository based on configuration.
    ///
    /// @param config configuration specifying storage type, not null
    /// @return the configured repository, never null
    private static RubricRepository createRubricRepository(HensuConfig config) {
        // Could be file-based, database, etc. based on config
        return new InMemoryRubricRepository();
    }

    /// Creates the default template resolver for prompt variable substitution.
    ///
    /// @return a new template resolver, never null
    private static TemplateResolver createTemplateResolver() {
        return new SimpleTemplateResolver();
    }

    /// Fluent builder for constructing {@link HensuEnvironment} instances with fine-grained
    /// control.
    ///
    /// Provides methods for configuring credentials, agent providers, registries,
    /// handlers, and threading options. Call {@link #build()} to create the environment.
    ///
    /// ### Example
    /// {@snippet :
    /// var env = HensuFactory.builder()
    ///     .loadCredentials(properties)
    ///     .agentProviders(List.of(new LangChain4jProvider()))
    ///     .actionExecutor(actionExecutor)
    ///     .build();
    /// }
    ///
    /// @implNote **Not thread-safe**. Intended for single-threaded configuration
    /// before calling {@link #build()}.
    public static class Builder {
        private HensuConfig config = new HensuConfig();
        private Map<String, String> credentials = new HashMap<>();
        private List<AgentProvider> agentProviders = new ArrayList<>();
        private NodeExecutorRegistry nodeExecutorRegistry;
        private AgentRegistry agentRegistry;
        private ReviewHandler reviewHandler = null;
        private ActionExecutor actionExecutor = null;
        private WorkflowRepository workflowRepository;
        private WorkflowStateRepository workflowStateRepository;
        private ToolRouter toolRouter;

        /// Sets the configuration options.
        ///
        /// @param config the configuration, not null
        /// @return this builder for chaining, never null
        public Builder config(HensuConfig config) {
            this.config = config;
            return this;
        }

        /// Adds a single credential key-value pair.
        ///
        /// @param key the credential key (e.g., `ANTHROPIC_API_KEY`), not null
        /// @param value the credential value, not null
        /// @return this builder for chaining, never null
        public Builder credential(String key, String value) {
            this.credentials.put(key, value);
            return this;
        }

        /// Adds multiple credentials from a map.
        ///
        /// @param credentials map of credential keys to values, not null
        /// @return this builder for chaining, never null
        public Builder credentials(Map<String, String> credentials) {
            this.credentials.putAll(credentials);
            return this;
        }

        /// Sets the Anthropic API key for Claude models.
        ///
        /// @param apiKey the API key starting with `sk-`, not null
        /// @return this builder for chaining, never null
        public Builder anthropicApiKey(String apiKey) {
            this.credentials.put("ANTHROPIC_API_KEY", apiKey);
            return this;
        }

        /// Sets the OpenAI API key for GPT models.
        ///
        /// @param apiKey the API key, not null
        /// @return this builder for chaining, never null
        public Builder openAiApiKey(String apiKey) {
            this.credentials.put("OPENAI_API_KEY", apiKey);
            return this;
        }

        /// Sets the Google API key for Gemini models.
        ///
        /// @param apiKey the API key, not null
        /// @return this builder for chaining, never null
        public Builder googleApiKey(String apiKey) {
            this.credentials.put("GOOGLE_API_KEY", apiKey);
            return this;
        }

        /// Sets the DeepSeek API key.
        ///
        /// @param apiKey the API key, not null
        /// @return this builder for chaining, never null
        public Builder deepSeekApiKey(String apiKey) {
            this.credentials.put("DEEPSEEK_API_KEY", apiKey);
            return this;
        }

        /// Sets the agent providers for model creation.
        ///
        /// Providers are selected by priority when creating agents. The built-in
        /// {@link StubAgentProvider} is always included automatically — do not add
        /// it explicitly.
        ///
        /// @param providers list of agent providers, not null
        /// @return this builder for chaining, never null
        /// @see AgentProvider for implementing custom providers
        public Builder agentProviders(List<AgentProvider> providers) {
            this.agentProviders = new ArrayList<>(providers);
            return this;
        }

        /// Adds a single agent provider.
        ///
        /// @param provider the agent provider, not null
        /// @return this builder for chaining, never null
        public Builder agentProvider(AgentProvider provider) {
            this.agentProviders.add(provider);
            return this;
        }

        /// Enables or disables stub mode for testing without API calls.
        ///
        /// When enabled, stub agents return mock responses, allowing workflow
        /// testing without consuming API tokens.
        ///
        /// @param enabled `true` to enable stub mode
        /// @return this builder for chaining, never null
        public Builder stubMode(boolean enabled) {
            this.credentials.put("hensu.stub.enabled", String.valueOf(enabled));
            return this;
        }

        /// Configures a handler for human review checkpoints.
        ///
        /// When set, workflow nodes with review configuration invoke this handler
        /// to request human approval, backtracking, or rejection.
        ///
        /// @param reviewHandler the review handler, may be null for auto-approve behavior
        /// @return this builder for chaining, never null
        public Builder reviewHandler(ReviewHandler reviewHandler) {
            this.reviewHandler = reviewHandler;
            return this;
        }

        /// Configures an executor for action node.
        ///
        /// When set, actions (send, execute) are processed
        /// by this executor. If not set, actions are logged but not executed.
        ///
        /// @param actionExecutor the action executor, may be null for logging-only mode
        /// @return this builder for chaining, never null
        public Builder actionExecutor(ActionExecutor actionExecutor) {
            this.actionExecutor = actionExecutor;
            return this;
        }

        /// Sets a custom workflow repository for tenant-scoped workflow storage.
        ///
        /// When not set, defaults to {@link InMemoryWorkflowRepository}.
        ///
        /// @param workflowRepository the repository, may be null for default in-memory storage
        /// @return this builder for chaining, never null
        public Builder workflowRepository(WorkflowRepository workflowRepository) {
            this.workflowRepository = workflowRepository;
            return this;
        }

        /// Sets a custom workflow state repository for persisting execution snapshots.
        ///
        /// When not set, defaults to {@link InMemoryWorkflowStateRepository}.
        ///
        /// @param workflowStateRepository the repository, may be null for default in-memory storage
        /// @return this builder for chaining, never null
        public Builder workflowStateRepository(WorkflowStateRepository workflowStateRepository) {
            this.workflowStateRepository = workflowStateRepository;
            return this;
        }

        /// Sets a custom node executor registry.
        ///
        /// @param nodeExecutorRegistry the registry, may be null for default
        /// @return this builder for chaining, never null
        public Builder nodeExecutorRegistry(NodeExecutorRegistry nodeExecutorRegistry) {
            this.nodeExecutorRegistry = nodeExecutorRegistry;
            return this;
        }

        /// Sets a custom agent registry.
        ///
        /// @param agentRegistry the registry, may be null for default
        /// @return this builder for chaining, never null
        public Builder agentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistry = agentRegistry;
            return this;
        }

        /// Loads credentials from environment variables matching API key patterns.
        ///
        /// @return this builder for chaining, never null
        /// @see HensuFactory#loadCredentialsFromEnvironment()
        public Builder loadCredentialsFromEnvironment() {
            this.credentials.putAll(HensuFactory.loadCredentialsFromEnvironment());
            return this;
        }

        /// Loads credentials from a Properties object.
        ///
        /// Supports `hensu.credentials.*` prefixed keys (prefix is stripped),
        /// direct API key patterns, and `hensu.stub.enabled`.
        ///
        /// @param properties the properties to extract credentials from, not null
        /// @return this builder for chaining, never null
        public Builder loadCredentialsFromProperties(Properties properties) {
            this.credentials.putAll(HensuFactory.loadCredentialsFromProperties(properties));
            return this;
        }

        /// Loads credentials from environment variables and properties.
        ///
        /// Properties take precedence over environment variables.
        ///
        /// @param properties the properties to merge with environment credentials, not null
        /// @return this builder for chaining, never null
        public Builder loadCredentials(Properties properties) {
            this.credentials.putAll(HensuFactory.loadCredentials(properties));
            return this;
        }

        /// Configures the tool router backing agent tool discovery and invocation.
        ///
        /// Defaults to {@link ToolRouter#empty()} when not set, which exposes no
        /// tools; a runtime enables the tool loop by composing its own
        /// {@link io.hensu.core.tool.ToolProvider} instances into a router.
        ///
        /// @param toolRouter the tool router, not null
        /// @return this builder for chaining, never null
        public Builder toolRouter(ToolRouter toolRouter) {
            this.toolRouter = toolRouter;
            return this;
        }

        /// Builds and returns the configured {@link HensuEnvironment}.
        ///
        /// ### Contracts
        /// - **Postcondition**: Returns a fully-initialized environment
        ///
        /// @apiNote **Side effects**:
        /// - Auto-loads environment credentials if none were explicitly provided
        /// - Sets `hensu.stub.enabled` system property if stub mode is enabled
        ///
        /// @return the configured environment, never null
        public HensuEnvironment build() {
            // Autoload from environment if no explicit credentials provided
            if (credentials.isEmpty()) {
                credentials = HensuFactory.loadCredentialsFromEnvironment();
            }
            // Apply stub mode setting
            applyStubModeSetting(credentials);

            if (agentRegistry == null) {
                // Always include StubAgentProvider alongside explicit providers
                List<AgentProvider> allProviders = new ArrayList<>(agentProviders);
                allProviders.add(new StubAgentProvider());
                AgentFactory agentFactory = new AgentFactory(credentials, allProviders);
                agentRegistry = new DefaultAgentRegistry(agentFactory);
            }
            if (nodeExecutorRegistry == null) {
                nodeExecutorRegistry = new DefaultNodeExecutorRegistry();
            }
            if (workflowRepository == null) {
                workflowRepository = new InMemoryWorkflowRepository();
            }
            if (workflowStateRepository == null) {
                workflowStateRepository = new InMemoryWorkflowStateRepository();
            }

            ReviewHandler effectiveReviewHandler =
                    reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE;
            return buildCoreComponents(
                    config,
                    nodeExecutorRegistry,
                    agentRegistry,
                    effectiveReviewHandler,
                    actionExecutor,
                    workflowRepository,
                    workflowStateRepository,
                    toolRouter);
        }
    }

    /// Creates a new builder for fluent environment configuration.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }
}
