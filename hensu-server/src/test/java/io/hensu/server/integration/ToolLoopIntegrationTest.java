package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.server.workflow.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for the agent-native tool loop.
///
/// Verifies end-to-end tool execution through the full Quarkus stack:
/// stub agent → ToolLoopRunner → ToolRouter → TestToolProvider → TestActionHandler
/// → stub feed-back → final answer.
@QuarkusTest
@TestProfile(InMemoryTestProfile.class)
class ToolLoopIntegrationTest extends IntegrationTestBase {

    @Inject TestActionHandler testActionHandler;
    @Inject ActionExecutor actionExecutor;

    @BeforeEach
    void setUpTools() {
        testActionHandler.reset();
        actionExecutor.registerHandler(testActionHandler);
    }

    @Test
    void shouldExecuteToolLoopEndToEnd() {
        var workflow = loadWorkflow("tool-loop.json");

        // Stub: first turn requests tool, second turn returns final text
        registerStub(
                "tool-agent",
                "[TOOL_CALL] test-tool input=hello\n---TURN---\nFinal answer from tool results");

        Map<String, Object> context = new HashMap<>();
        context.put("query", "test topic");
        ExecutionStartResult result = pushAndExecute(workflow, context);

        // Verify execution completed
        Optional<HensuSnapshot> snapshot =
                workflowStateRepository.findByExecutionId(TEST_TENANT, result.executionId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().checkpointReason()).isEqualTo("completed");

        // Verify the tool handler received the call
        assertThat(testActionHandler.getReceivedPayloads()).hasSize(1);
        assertThat(testActionHandler.getReceivedPayloads().getFirst())
                .containsEntry("input", "hello");
    }

    @Test
    void shouldHandleToolLoopWithoutToolCalls() {
        var workflow = loadWorkflow("tool-loop.json");

        // Stub: agent responds immediately without tool calls
        registerStub("tool-agent", "Direct answer, no tools needed");

        Map<String, Object> context = new HashMap<>();
        context.put("query", "simple question");
        ExecutionStartResult result = pushAndExecute(workflow, context);

        Optional<HensuSnapshot> snapshot =
                workflowStateRepository.findByExecutionId(TEST_TENANT, result.executionId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().checkpointReason()).isEqualTo("completed");

        // No tool calls should have been made
        assertThat(testActionHandler.getReceivedPayloads()).isEmpty();
    }
}
