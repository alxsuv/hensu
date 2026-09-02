package io.hensu.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.*;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.SynchronizedListenerDecorator;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.StubToolProvider;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolLoopRunnerTest {

    private static final ToolDefinition SEARCH_TOOL =
            ToolDefinition.of(
                    "search",
                    "Search for information",
                    List.of(
                            ToolDefinition.ParameterDef.required(
                                    "query", "string", "Search query")));

    private StubToolProvider toolProvider;
    private ToolRouter toolRouter;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        toolProvider = StubToolProvider.alwaysSucceeding("search results here", SEARCH_TOOL);
        toolRouter = new ToolRouter(List.of(toolProvider));
        listener = new RecordingListener();
    }

    @Nested
    class HappyPath {

        @Test
        void shouldExecuteToolAndReturnFinalResponse() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    "[TOOL_CALL] search query=test\n---TURN---\nFinal answer based on search");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Find info", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Final answer based on search");
            assertThat(toolProvider.invocations())
                    .singleElement()
                    .satisfies(
                            invocation -> {
                                assertThat(invocation.toolName()).isEqualTo("search");
                                assertThat(invocation.arguments()).containsEntry("query", "test");
                            });
        }

        @Test
        void shouldAuditEveryToolCallAndResult() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "[TOOL_CALL] search query=test\n---TURN---\nDone");

            ToolLoopRunner.execute(
                    "node1", "test-agent", "Find info", agent, buildContext(config, agent));

            assertThat(listener.calls)
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.nodeId()).isEqualTo("node1");
                                assertThat(event.agentId()).isEqualTo("test-agent");
                                assertThat(event.toolName()).isEqualTo("search");
                                assertThat(event.arguments()).containsEntry("query", "test");
                            });
            assertThat(listener.results)
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.success()).isTrue();
                                assertThat(event.output()).isEqualTo("search results here");
                                assertThat(event.error()).isNull();
                            });
        }

        @Test
        void shouldHandleMultipleToolRounds() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=first
                            ---TURN---
                            [TOOL_CALL] search query=second
                            ---TURN---
                            Combined answer""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Multi search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Combined answer");
            assertThat(toolProvider.invocations()).hasSize(2);
            assertThat(listener.results).hasSize(2);
        }
    }

    @Nested
    class CapExhaustion {

        @Test
        void shouldSucceedWhenModelCooperatesAfterCapExhaustion() {
            AgentConfig config = cappedConfig();
            StubAgent agent = new StubAgent("test-agent", config);
            // First turn: tool call (counts as 1, hitting cap)
            // After cap exhaustion feedback, stub returns final text
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Final answer after budget warning""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString())
                    .isEqualTo("Final answer after budget warning");
        }

        @Test
        void shouldFailWhenModelIgnoresBudgetWarning() {
            AgentConfig config = cappedConfig();
            StubAgent agent = new StubAgent("test-agent", config);
            // First: tool call (hits cap after execution), then after cap-exhaustion
            // feedback the stub still tries another tool call instead of answering
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            [TOOL_CALL] search query=another
                            ---TURN---
                            [TOOL_CALL] search query=yet-another""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("budget exhaustion");
        }

        @Test
        void shouldAuditTheRequestRejectedByTheBudget() {
            AgentConfig config = cappedConfig();
            StubAgent agent = new StubAgent("test-agent", config);
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            [TOOL_CALL] search query=over-budget
                            ---TURN---
                            Fine, done""");

            ToolLoopRunner.execute(
                    "node1", "test-agent", "Search", agent, buildContext(config, agent));

            // The over-budget request never reached the provider but is still audited.
            assertThat(toolProvider.invocations()).hasSize(1);
            assertThat(listener.calls).hasSize(2);
            assertThat(listener.results)
                    .last()
                    .satisfies(
                            event -> {
                                assertThat(event.success()).isFalse();
                                assertThat(event.error()).contains("budget exhausted");
                            });
        }
    }

    @Nested
    class UnknownTool {

        @Test
        void shouldFeedBackUnknownToolAndEventuallySucceed() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            // First turn: hallucinated tool, second turn: real tool, third: final
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] nonexistent query=test
                            ---TURN---
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Got it""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            // Only the real tool call reaches a provider
            assertThat(toolProvider.invocations())
                    .singleElement()
                    .satisfies(invocation -> assertThat(invocation.toolName()).isEqualTo("search"));
            // ...but both requests are audited
            assertThat(listener.calls)
                    .extracting(ToolCallEvent::toolName)
                    .containsExactly("nonexistent", "search");
            assertThat(listener.results)
                    .extracting(ToolResultEvent::success)
                    .containsExactly(false, true);
        }
    }

    @Nested
    class ToolFailure {

        @Test
        void shouldFeedBackProviderFailure() {
            toolProvider =
                    new StubToolProvider(
                            List.of(SEARCH_TOOL),
                            (name, _) -> ToolCallResult.failure(name, "Tool crashed"));
            toolRouter = new ToolRouter(List.of(toolProvider));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Handled the failure gracefully""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Handled the failure gracefully");
            assertThat(listener.results)
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.success()).isFalse();
                                assertThat(event.error()).isEqualTo("Tool crashed");
                            });
        }
    }

    @Nested
    class EngagementCheck {

        @Test
        void shouldFailWhenAgentNotToolCapable() {
            Agent nonToolAgent = mock(Agent.class);
            when(nonToolAgent.getConfig()).thenReturn(agentConfig(List.of("search")));

            ExecutionContext ctx = buildContext(agentConfig(List.of("search")), nonToolAgent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Prompt", nonToolAgent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("does not implement ToolCapable");
        }

        @Test
        void shouldFailWhenToolNotInCatalog() {
            AgentConfig config = agentConfig(List.of("nonexistent-tool"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "Should not reach here");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Prompt", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("Unresolvable tools");
        }

        @Test
        void shouldFailWhenNoToolInvokerConfigured() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "whatever");

            // Catalog present, but nothing can run the tools it advertises
            ExecutionContext ctx =
                    ExecutionContext.builder()
                            .state(buildState())
                            .workflow(buildWorkflow())
                            .listener(listener)
                            .agentRegistry(buildAgentRegistry(config, agent))
                            .toolRegistry(toolRouter)
                            .build();

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Prompt", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("no ToolInvoker is configured");
        }

        @Test
        void shouldFailTheNodeWhenTwoProvidersClaimTheSameTool() {
            // Both catalogs are empty at construction – like a tenant-scoped or
            // lazily-started source – so only the loop's own catalog read collides.
            AtomicBoolean armed = new AtomicBoolean(false);
            ToolRouter colliding =
                    new ToolRouter(
                            List.of(
                                    new LazyProvider(toolProvider, armed),
                                    new LazyProvider(
                                            StubToolProvider.alwaysSucceeding("other", SEARCH_TOOL),
                                            armed)));
            armed.set(true);

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);
            registerStubResponse(agent, "[TOOL_CALL] search query=test\n---TURN---\nDone");

            ExecutionContext ctx =
                    ExecutionContext.builder()
                            .state(buildState())
                            .workflow(buildWorkflow())
                            .listener(listener)
                            .agentRegistry(buildAgentRegistry(config, agent))
                            .toolRegistry(colliding)
                            .toolInvoker(colliding)
                            .build();

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Prompt", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString())
                    .contains("Tool catalog is unusable")
                    .contains("Duplicate tool 'search'");
            assertThat(toolProvider.invocations()).isEmpty();
        }
    }

    @Nested
    class ParallelBranches {

        @Test
        void shouldKeepEveryBranchesToolEventsIntact() throws InterruptedException {
            RecordingListener recording = new RecordingListener();
            ExecutionListener shared = new SynchronizedListenerDecorator(recording);

            AgentConfig configA = agentConfig("agent-a", List.of("search"));
            AgentConfig configB = agentConfig("agent-b", List.of("search"));
            StubAgent agentA = new StubAgent("agent-a", configA);
            StubAgent agentB = new StubAgent("agent-b", configB);

            String script =
                    """
                            [TOOL_CALL] search query=one
                            ---TURN---
                            [TOOL_CALL] search query=two
                            ---TURN---
                            Done""";
            io.hensu.core.agent.stub.StubResponseRegistry.getInstance()
                    .registerResponse("agent-a", script);
            io.hensu.core.agent.stub.StubResponseRegistry.getInstance()
                    .registerResponse("agent-b", script);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            Runnable branchA = branch(start, done, "branch-a", "agent-a", agentA, configA, shared);
            Runnable branchB = branch(start, done, "branch-b", "agent-b", agentB, configB, shared);

            Thread.ofVirtual().start(branchA);
            Thread.ofVirtual().start(branchB);
            start.countDown();

            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(recording.calls).hasSize(4);
            assertThat(recording.results).hasSize(4);
            assertThat(recording.calls.stream().filter(e -> e.nodeId().equals("branch-a")).count())
                    .isEqualTo(2);
            assertThat(recording.calls.stream().filter(e -> e.nodeId().equals("branch-b")).count())
                    .isEqualTo(2);
            assertThat(recording.results).allSatisfy(event -> assertThat(event.success()).isTrue());
        }

        private Runnable branch(
                CountDownLatch start,
                CountDownLatch done,
                String nodeId,
                String agentId,
                Agent agent,
                AgentConfig config,
                ExecutionListener shared) {
            return () -> {
                try {
                    start.await();
                    ExecutionContext ctx =
                            ExecutionContext.builder()
                                    .state(buildState())
                                    .workflow(buildWorkflow())
                                    .listener(shared)
                                    .agentRegistry(buildAgentRegistry(config, agent))
                                    .toolRegistry(toolRouter)
                                    .toolInvoker(toolRouter)
                                    .build();
                    ToolLoopRunner.execute(nodeId, agentId, "Search", agent, ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            };
        }
    }

    // ——— Helpers ———————————————————————————————————————————————————————

    /// Provider reporting an empty catalog until armed, mimicking a source whose
    /// tools only appear after construction.
    private static final class LazyProvider implements ToolProvider {

        private final ToolProvider delegate;
        private final AtomicBoolean armed;

        LazyProvider(ToolProvider delegate, AtomicBoolean armed) {
            this.delegate = delegate;
            this.armed = armed;
        }

        @Override
        public List<ToolDefinition> tools() {
            return armed.get() ? delegate.tools() : List.of();
        }

        @Override
        public boolean provides(String toolName) {
            return armed.get() && delegate.provides(toolName);
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            return delegate.call(toolName, arguments, context);
        }
    }

    /// Listener capturing tool audit events for assertions.
    private static final class RecordingListener implements ExecutionListener {

        private final List<ToolCallEvent> calls = new ArrayList<>();
        private final List<ToolResultEvent> results = new ArrayList<>();

        @Override
        public void onToolCall(ToolCallEvent event) {
            calls.add(event);
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            results.add(event);
        }
    }

    private AgentConfig agentConfig(List<String> tools) {
        return agentConfig("test-agent", tools);
    }

    private AgentConfig agentConfig(String agentId, List<String> tools) {
        return AgentConfig.builder().id(agentId).role("tester").model("stub").tools(tools).build();
    }

    private AgentConfig cappedConfig() {
        return AgentConfig.builder()
                .id("test-agent")
                .role("tester")
                .model("stub")
                .tools(List.of("search"))
                .maxToolCalls(1)
                .build();
    }

    private void registerStubResponse(StubAgent agent, String response) {
        io.hensu.core.agent.stub.StubResponseRegistry.getInstance()
                .registerResponse(agent.getId(), response);
    }

    private ExecutionContext buildContext(AgentConfig config, Agent agent) {
        return ExecutionContext.builder()
                .state(buildState())
                .workflow(buildWorkflow())
                .listener(listener)
                .agentRegistry(buildAgentRegistry(config, agent))
                .toolRegistry(toolRouter)
                .toolInvoker(toolRouter)
                .build();
    }

    private AgentRegistry buildAgentRegistry(AgentConfig config, Agent agent) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getAgent(config.getId())).thenReturn(java.util.Optional.of(agent));
        return registry;
    }

    private HensuState buildState() {
        return new HensuState.Builder()
                .executionId("test-exec")
                .workflowId("test-wf")
                .currentNode("node1")
                .context(new HashMap<>(Map.of("current_node", "node1")))
                .history(new ExecutionHistory())
                .build();
    }

    private Workflow buildWorkflow() {
        StandardNode node1 =
                StandardNode.builder()
                        .id("node1")
                        .agentId("test-agent")
                        .prompt("test")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        EndNode end = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        return Workflow.builder()
                .id("test-wf")
                .startNode("node1")
                .nodes(Map.of("node1", node1, "end", end))
                .build();
    }
}
