package io.hensu.core.execution;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/// Abstract base for {@link WorkflowExecutor} tests.
///
/// Provides shared mock fields, a pre-configured default executor, lifecycle
/// management, and common node/agent builder helpers. All executor test classes
/// extend this base to eliminate boilerplate and ensure consistent setup.
///
/// Subclasses with specialised executor needs (e.g., injecting a custom
/// {@link io.hensu.core.review.ReviewHandler} or
/// {@link io.hensu.core.execution.action.ActionExecutor}) should declare their
/// own {@code @BeforeEach} — JUnit 5 guarantees the base {@link #setUpBase()}
/// runs first, so the subclass can safely override the {@code executor} field.
@ExtendWith(MockitoExtension.class)
abstract class WorkflowExecutorTestBase {

    @Mock protected AgentRegistry agentRegistry;
    @Mock protected RubricEngine rubricEngine;
    @Mock protected Agent mockAgent;

    protected WorkflowExecutor executor;

    @BeforeEach
    void setUpBase() {
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        null,
                        new SimpleTemplateResolver(),
                        null,
                        ToolRouter.empty());
    }

    protected static NodeLifecycleCoordinator createCoordinator(
            NodeExecutorRegistry registry, ReviewHandler reviewHandler, RubricEngine rubricEngine) {
        return new NodeLifecycleCoordinator(
                registry,
                ProcessorPipeline.preExecution(),
                ProcessorPipeline.postExecution(reviewHandler, rubricEngine));
    }

    /// Creates an {@link AgentConfig} for the default {@code "test-agent"}.
    protected static AgentConfig agentCfg() {
        return AgentConfig.builder().id("test-agent").role("Test").model("test").build();
    }

    /// Creates a {@link StandardNode} that always succeeds and transitions to {@code next}.
    protected static Node step(String id, String next) {
        return StandardNode.builder()
                .id(id)
                .agentId("test-agent")
                .prompt("Do work")
                .transitionRules(List.of(new SuccessTransition(next)))
                .build();
    }

    /// Creates an {@link EndNode} with {@link ExitStatus#SUCCESS}.
    protected static Node end(String id) {
        return EndNode.builder().id(id).status(ExitStatus.SUCCESS).build();
    }

    /// Creates an {@link EndNode} with {@link ExitStatus#FAILURE}.
    protected static Node failEnd(String id) {
        return EndNode.builder().id(id).status(ExitStatus.FAILURE).build();
    }
}
