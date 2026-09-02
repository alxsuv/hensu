package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.adapter.langchain4j.LangChain4jProvider;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.HensuFactory;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolRouter;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.persistence.JdbcWorkflowRepository;
import io.hensu.server.persistence.JdbcWorkflowStateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/// CDI producer for the Hensu runtime environment and its dependencies.
///
/// Wires together the core execution components via {@link HensuFactory}:
/// workflow executor, agent registry, rubric engine, and action executor.
///
/// ### Credential Discovery
/// Credentials are loaded from (in priority order):
/// 1. **Application properties** under `hensu.credentials.*`
/// 2. **Environment variables** matching patterns: `*_API_KEY`, `*_KEY`, `*_SECRET`, `*_TOKEN`
///
/// ### Configuration Properties
/// | Property | Type | Default | Description |
/// |----------|------|---------|-------------|
/// | `hensu.credentials.ANTHROPIC_API_KEY` | String | - | Anthropic API key |
/// | `hensu.credentials.OPENAI_API_KEY` | String | - | OpenAI API key |
/// | `hensu.credentials.GOOGLE_API_KEY` | String | - | Google AI Gemini API key |
/// | `hensu.stub.enabled` | Boolean | `false` | Enable stub mode for testing |
///
/// @implNote Application-scoped singleton. Thread-safe after initialization.
/// The `quarkus-langchain4j-*` extensions are on the classpath solely for
/// GraalVM reflection metadata — Hensu creates models programmatically via
/// {@link LangChain4jProvider}, not via `@RegisterAiService`.
///
/// @see HensuEnvironment
/// @see HensuFactory
@ApplicationScoped
public class HensuEnvironmentProducer {

    private static final Logger LOG = Logger.getLogger(HensuEnvironmentProducer.class);

    private HensuEnvironment hensuEnvironment;

    @Inject Config config;

    @Inject Instance<GenericNodeHandler> genericNodeHandlers;

    @Inject Instance<ReviewHandler> reviewHandlerInstance;

    @Inject ActionExecutor actionExecutor;

    @Inject Instance<DataSource> dataSourceInstance;

    @Inject ObjectMapper objectMapper;

    @Inject ExecutionLeaseManager leaseManager;

    @Inject Instance<ToolProvider> toolProviders;

    /// Produces the Hensu runtime environment for CDI injection.
    ///
    /// Configures virtual threads, loads credentials from `hensu.credentials.*`
    /// properties and environment variables, and sets up the action executor.
    /// Registers all discovered generic node handlers.
    ///
    /// @return configured environment singleton, never null
    @Produces
    @ApplicationScoped
    public HensuEnvironment hensuEnvironment() {
        Properties properties = extractHensuProperties();

        HensuFactory.Builder factoryBuilder =
                HensuFactory.builder()
                        .loadCredentials(properties)
                        .agentProviders(List.of(new LangChain4jProvider()))
                        .actionExecutor(actionExecutor)
                        .toolRouter(new ToolRouter(toolProviders.stream().toList()));

        boolean dsActive =
                config.getOptionalValue("quarkus.datasource.active", Boolean.class).orElse(true);

        if (dsActive && dataSourceInstance.isResolvable()) {
            DataSource ds = dataSourceInstance.get();
            factoryBuilder
                    .workflowRepository(new JdbcWorkflowRepository(ds))
                    .workflowStateRepository(
                            new JdbcWorkflowStateRepository(
                                    ds, objectMapper, leaseManager.getServerNodeId()));
            LOG.info("Using JDBC persistence (PostgreSQL)");
        } else {
            LOG.info("Using in-memory persistence");
        }

        if (reviewHandlerInstance.isResolvable()) {
            factoryBuilder.reviewHandler(reviewHandlerInstance.get());
            LOG.info("Using CDI-provided ReviewHandler");
        }

        hensuEnvironment = factoryBuilder.build();

        LOG.info("Configured HensuEnvironment via HensuFactory");

        // Register all discovered generic node handlers
        registerGenericHandlers();

        return hensuEnvironment;
    }

    /// Register all CDI-discovered GenericNodeHandler implementations.
    private void registerGenericHandlers() {
        for (GenericNodeHandler handler : genericNodeHandlers) {
            hensuEnvironment
                    .getNodeExecutorRegistry()
                    .registerGenericHandler(handler.getType(), handler);
            LOG.infov("Registered generic handler: {0}", handler.getType());
        }
    }

    /// Extracts `hensu.credentials.*` and `hensu.stub.enabled` from Quarkus config.
    private Properties extractHensuProperties() {
        Properties properties = new Properties();
        String credentialsPrefix = "hensu.credentials.";
        String stubEnabledKey = "hensu.stub.enabled";

        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(credentialsPrefix)) {
                config.getOptionalValue(propertyName, String.class)
                        .ifPresent(value -> properties.setProperty(propertyName, value));
            }
        }

        config.getOptionalValue(stubEnabledKey, String.class)
                .ifPresent(value -> properties.setProperty(stubEnabledKey, value));

        return properties;
    }
}
