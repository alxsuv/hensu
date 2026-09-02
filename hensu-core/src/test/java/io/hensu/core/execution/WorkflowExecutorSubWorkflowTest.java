package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.SubWorkflowNodeExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.InMemoryWorkflowRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests for {@link io.hensu.core.execution.executor.SubWorkflowNodeExecutor}.
///
/// Covers parent↔child data flow semantics:
/// inputMapping (childKey ← parentKey), outputMapping (parentKey ← childKey),
/// boundary filtering, multi-tenant lookup, and error surfaces. Scope-fenced:
/// does NOT duplicate DSL builder, validator, loader, or registry test coverage.
class WorkflowExecutorSubWorkflowTest extends WorkflowExecutorTestBase {

    private InMemoryWorkflowRepository repository;

    @BeforeEach
    void setUpSubWorkflowRepository() {
        repository = new InMemoryWorkflowRepository();
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        null,
                        new SimpleTemplateResolver(),
                        repository,
                        ToolRouter.empty());
    }

    @Test
    void shouldMapInputsToChildAndReadOnlyMappedOutputsBack() throws Exception {
        // Child writes both "summary" and "scratch"; parent maps only "summary" → "result".
        // Asserts the boundary filter: unmapped child writes must NOT leak to parent.
        var childAgent = mock(Agent.class);
        when(agentRegistry.getAgent("child-agent")).thenReturn(Optional.of(childAgent));
        when(childAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                "{\"summary\":\"distilled\",\"scratch\":\"draft\"}"));
        repository.save(
                "default",
                buildChildWriting(
                        "child-summarizer", "child-agent", List.of("summary", "scratch")));

        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("child-summarizer")
                        .inputMapping(Map.of("query", "topic"))
                        .outputMapping(Map.of("result", "summary"))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "AI workflows");

        var result = executor.execute(buildParent(sub), ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var finalCtx = ((ExecutionResult.Completed) result).getFinalState().getContext();
        assertThat(finalCtx)
                .containsEntry("result", "distilled")
                .containsEntry("topic", "AI workflows");
        assertThat(finalCtx)
                .doesNotContainKey("scratch")
                .doesNotContainKey("summary")
                .doesNotContainKey("query");
    }

    @Test
    void shouldThrowWhenInputMappingSourceKeyMissingInParent() {
        repository.save("default", buildEmptyChild("child-x"));
        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("child-x")
                        .inputMapping(Map.of("query", "topic"))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        assertThatThrownBy(() -> executor.execute(buildParent(sub), new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing input")
                .hasMessageContaining("topic");
    }

    @Test
    void shouldResolveSubWorkflowUnderParentTenant() throws Exception {
        // Parent state carries _tenant_id="acme"; child saved ONLY under "acme".
        // Successful execution proves tenant routing in loadSubWorkflow.
        repository.save("acme", buildEmptyChild("tenant-child"));
        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("tenant-child")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        var ctx = new HashMap<String, Object>();
        ctx.put("_tenant_id", "acme");

        var result = executor.execute(buildParent(sub), ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldNotLeakGrandchildOutputThroughChildWithEmptyOutputMapping() throws Exception {
        // Grandchild writes "deep_var"="leak-me". Child wraps grandchild with EMPTY
        // outputMapping (filters). Parent asks for "deep_var" via outputMapping →
        // resolves to null, NOT "leak-me". Proves the boundary filter applies at every
        // hop, not just the root.
        var grandchildAgent = mock(Agent.class);
        when(agentRegistry.getAgent("gc-agent")).thenReturn(Optional.of(grandchildAgent));
        when(grandchildAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"deep_var\":\"leak-me\"}"));

        repository.save(
                "default", buildChildWriting("grandchild", "gc-agent", List.of("deep_var")));

        var childToGrandchild =
                SubWorkflowNode.builder()
                        .id("sub-gc")
                        .workflowId("grandchild")
                        .transitionRules(List.of(new SuccessTransition("child-end")))
                        .build();
        var nodes = new HashMap<String, Node>();
        nodes.put("sub-gc", childToGrandchild);
        nodes.put("child-end", end("child-end"));
        var childWf =
                Workflow.builder()
                        .id("child")
                        .agents(Map.of())
                        .nodes(nodes)
                        .startNode("sub-gc")
                        .build();
        repository.save("default", childWf);

        var parentSub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("child")
                        .outputMapping(Map.of("echo", "deep_var"))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        var result = executor.execute(buildParent(parentSub), new HashMap<>());

        var finalCtx = ((ExecutionResult.Completed) result).getFinalState().getContext();
        // Key is present (outputMapping always puts) but value is null —
        // grandchild's "leak-me" did not pierce the child boundary.
        assertThat(finalCtx).containsKey("echo");
        assertThat(finalCtx.get("echo")).isNull();
    }

    @Test
    void shouldOverwriteParentContextOnOutputKeyCollision() throws Exception {
        var childAgent = mock(Agent.class);
        when(agentRegistry.getAgent("c-agent")).thenReturn(Optional.of(childAgent));
        when(childAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"summary\":\"ChildValue\"}"));
        repository.save("default", buildChildWriting("collide", "c-agent", List.of("summary")));

        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("collide")
                        .outputMapping(Map.of("topic", "summary"))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "ParentValue");

        var result = executor.execute(buildParent(sub), ctx);

        assertThat(((ExecutionResult.Completed) result).getFinalState().getContext())
                .containsEntry("topic", "ChildValue");
    }

    @Test
    void shouldThrowWhenSubWorkflowNotInRepository() {
        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("ghost-workflow")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        assertThatThrownBy(() -> executor.execute(buildParent(sub), new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sub-workflow not found")
                .hasMessageContaining("ghost-workflow");
    }

    @Test
    void shouldPropagateTenantThroughDepthTwoSubWorkflows() throws Exception {
        // grandchild + intermediate child are both stored ONLY under tenant "acme".
        // If _tenant_id were not propagated into the depth-1 subContext, the
        // depth-2 lookup would fall through to "default" and throw "not found".
        // Successful Completed proves the engine var rides the recursion.
        var gcAgent = mock(Agent.class);
        when(agentRegistry.getAgent("gc-agent")).thenReturn(Optional.of(gcAgent));
        when(gcAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"deep_var\":\"v\"}"));
        repository.save("acme", buildChildWriting("grandchild", "gc-agent", List.of("deep_var")));

        var middleSub =
                SubWorkflowNode.builder()
                        .id("sub-gc")
                        .workflowId("grandchild")
                        .transitionRules(List.of(new SuccessTransition("child-end")))
                        .build();
        var middleNodes = new HashMap<String, Node>();
        middleNodes.put("sub-gc", middleSub);
        middleNodes.put("child-end", end("child-end"));
        repository.save(
                "acme",
                Workflow.builder()
                        .id("middle")
                        .agents(Map.of())
                        .nodes(middleNodes)
                        .startNode("sub-gc")
                        .build());

        var topSub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("middle")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var ctx = new HashMap<String, Object>();
        ctx.put("_tenant_id", "acme");

        var result = executor.execute(buildParent(topSub), ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldThrowWhenRecursionDepthExceedsCap() {
        // Pre-seed _sub_workflow_depth at the cap so the very first sub-workflow
        // entry trips the guard. Proves the static stack-depth fence catches
        // any cycle that bypasses the load-time validator.
        repository.save("default", buildEmptyChild("any-child"));
        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("any-child")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var ctx = new HashMap<String, Object>();
        ctx.put(SubWorkflowNodeExecutor.DEPTH_KEY, SubWorkflowNodeExecutor.MAX_DEPTH);

        assertThatThrownBy(() -> executor.execute(buildParent(sub), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recursion depth")
                .hasMessageContaining("any-child");
    }

    @Test
    void shouldThrowWhenChildExecutionPauses() throws Exception {
        // Drives SubWorkflowNodeExecutor directly with a mocked WorkflowExecutor
        // so the child returns Paused without standing up the review pipeline.
        // Asserts we surface the unsupported combination loudly instead of
        // masking it as a parent FAILURE (the original silent-swallow bug).
        repository.save("default", buildEmptyChild("paused-child"));

        var pausedChildState =
                new HensuState.Builder()
                        .executionId("child-exec")
                        .workflowId("paused-child")
                        .currentNode("end")
                        .context(new HashMap<>())
                        .build();
        var pausingExecutor = mock(WorkflowExecutor.class);
        when(pausingExecutor.execute(any(Workflow.class), any(Map.class), any()))
                .thenReturn(new ExecutionResult.Paused(pausedChildState));

        var parentState =
                new HensuState.Builder()
                        .executionId("parent-exec")
                        .workflowId("parent")
                        .currentNode("sub")
                        .context(new HashMap<>())
                        .build();
        var sub = SubWorkflowNode.builder().id("sub").workflowId("paused-child").build();

        var ctx =
                ExecutionContext.builder()
                        .state(parentState)
                        .workflow(buildParent(sub))
                        .workflowExecutor(pausingExecutor)
                        .workflowRepository(repository)
                        .build();

        assertThatThrownBy(() -> new SubWorkflowNodeExecutor().execute(sub, ctx))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("paused-child")
                .hasMessageContaining("paused");
    }

    @Test
    void shouldThrowWhenWorkflowRepositoryIsNull() {
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        null,
                        null,
                        null,
                        ToolRouter.empty());

        var sub =
                SubWorkflowNode.builder()
                        .id("sub")
                        .workflowId("any-child")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        assertThatThrownBy(() -> executor.execute(buildParent(sub), new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No WorkflowRepository configured");
    }

    // — Helpers ————————————————————————————————————————————————————————————

    private Workflow buildParent(SubWorkflowNode sub) {
        var nodes = new HashMap<String, Node>();
        nodes.put("sub", sub);
        nodes.put("end", end("end"));
        return Workflow.builder()
                .id("parent-" + sub.getWorkflowId())
                .agents(Map.of())
                .nodes(nodes)
                .startNode("sub")
                .build();
    }

    private Workflow buildChildWriting(String id, String agentId, List<String> writes) {
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "step",
                StandardNode.builder()
                        .id("step")
                        .agentId(agentId)
                        .prompt("Process")
                        .writes(writes)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        return Workflow.builder()
                .id(id)
                .agents(
                        Map.of(
                                agentId,
                                AgentConfig.builder()
                                        .id(agentId)
                                        .role("Worker")
                                        .model("test")
                                        .build()))
                .nodes(nodes)
                .startNode("step")
                .build();
    }

    private Workflow buildEmptyChild(String id) {
        return Workflow.builder()
                .id(id)
                .agents(Map.of())
                .nodes(Map.of("end", end("end")))
                .startNode("end")
                .build();
    }
}
