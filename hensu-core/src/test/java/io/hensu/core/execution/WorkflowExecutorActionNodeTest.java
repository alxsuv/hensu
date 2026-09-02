package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ActionNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowExecutorActionNodeTest extends WorkflowExecutorTestBase {

    private ActionExecutor mockActionExecutor;

    @BeforeEach
    void setUpActionExecutor() {
        mockActionExecutor = mock(ActionExecutor.class);
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        mockActionExecutor,
                        null,
                        null,
                        ToolRouter.empty());
    }

    @Test
    void shouldExecuteActionNodeAndContinue() throws Exception {
        when(mockActionExecutor.execute(any(), any()))
                .thenReturn(ActionExecutor.ActionResult.success("Action done"));

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "action",
                ActionNode.builder()
                        .id("action")
                        .actions(List.of(new Action.Execute("git-commit")))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow =
                Workflow.builder().id("action-test").nodes(nodes).startNode("action").build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldPropagateUncheckedExceptionFromActionExecutor() {
        // ActionNodeExecutor calls actionExecutor.execute() with no try-catch.
        // If the implementation throws (violating its own interface contract),
        // the exception must propagate — not be swallowed and silently reported as success.
        when(mockActionExecutor.execute(any(), any()))
                .thenThrow(new RuntimeException("executor internal fault"));

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "action",
                ActionNode.builder()
                        .id("action")
                        .actions(List.of(new Action.Execute("deploy")))
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition("failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder().id("action-exception").nodes(nodes).startNode("action").build();

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("executor internal fault");
    }

    @Test
    void shouldHandleActionFailure() throws Exception {
        when(mockActionExecutor.execute(any(), any()))
                .thenReturn(ActionExecutor.ActionResult.failure("Action failed"));

        var nodes = new HashMap<String, Node>();
        nodes.put(
                "action",
                ActionNode.builder()
                        .id("action")
                        .actions(List.of(new Action.Execute("deploy")))
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition("failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder().id("action-fail").nodes(nodes).startNode("action").build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }
}
