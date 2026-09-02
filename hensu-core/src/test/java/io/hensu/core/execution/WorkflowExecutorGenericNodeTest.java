package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowExecutorGenericNodeTest extends WorkflowExecutorTestBase {

    private DefaultNodeExecutorRegistry genericRegistry;

    @BeforeEach
    void setUpGenericExecutor() {
        genericRegistry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        genericRegistry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(
                                genericRegistry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        null,
                        null,
                        null,
                        ToolRouter.empty());
    }

    @Test
    void shouldExecuteGenericNodeViaHandler() throws Exception {
        // Handler reads config["target"] and returns it in the output.
        genericRegistry.registerGenericHandler(
                "validator",
                new GenericNodeHandler() {
                    @Override
                    public String getType() {
                        return "validator";
                    }

                    @Override
                    public NodeResult handle(GenericNode node, ExecutionContext context) {
                        return NodeResult.success(
                                "Validated: " + node.getConfig().get("target"), Map.of());
                    }
                });

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "validate",
                GenericNode.builder()
                        .id("validate")
                        .executorType("validator")
                        .config(Map.of("target", "user-input"))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow =
                Workflow.builder().id("generic-test").nodes(nodes).startNode("validate").build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(completed.getFinalState().getContext().get("validate").toString())
                .contains("Validated");
    }

    @Test
    void shouldRouteGenericNodeOnHandlerFailure() throws Exception {
        // Handler returns failure → FailureTransition fires → failure-end.
        genericRegistry.registerGenericHandler(
                "validator",
                new GenericNodeHandler() {
                    @Override
                    public String getType() {
                        return "validator";
                    }

                    @Override
                    public NodeResult handle(GenericNode node, ExecutionContext context) {
                        return NodeResult.failure("Validation failed");
                    }
                });

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "validate",
                GenericNode.builder()
                        .id("validate")
                        .executorType("validator")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition("failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder().id("generic-fail").nodes(nodes).startNode("validate").build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldPauseWhenGenericNodeReturnsPending() throws Exception {
        // PENDING result from a handler must suspend execution and return Paused —
        // the node must NOT be advanced and the current node pointer must remain on
        // the paused node so resumeFrom can re-enter it.
        genericRegistry.registerGenericHandler(
                "gate",
                new GenericNodeHandler() {
                    @Override
                    public String getType() {
                        return "gate";
                    }

                    @Override
                    public NodeResult handle(GenericNode node, ExecutionContext context) {
                        return NodeResult.builder()
                                .status(io.hensu.core.execution.result.ResultStatus.PENDING)
                                .build();
                    }
                });

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "gate",
                GenericNode.builder()
                        .id("gate")
                        .executorType("gate")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow = Workflow.builder().id("pending-test").nodes(nodes).startNode("gate").build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Paused.class);
        assertThat(((ExecutionResult.Paused) result).state().getCurrentNode()).isEqualTo("gate");
    }
}
