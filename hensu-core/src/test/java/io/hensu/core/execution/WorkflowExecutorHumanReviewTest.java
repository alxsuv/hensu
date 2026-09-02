package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowExecutorHumanReviewTest extends WorkflowExecutorTestBase {

    private ReviewHandler mockReviewHandler;

    @BeforeEach
    void setUpReviewExecutor() {
        mockReviewHandler = mock(ReviewHandler.class);
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, mockReviewHandler, rubricEngine),
                        null,
                        new SimpleTemplateResolver(),
                        null,
                        ToolRouter.empty());
    }

    // — Review mode routing ———————————————————————————————————————————————

    @Test
    void shouldAutoApproveWhenReviewDisabled() throws Exception {
        var workflow = workflowWithReview("review-disabled", ReviewMode.DISABLED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler, never()).requestReview(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAutoApproveSuccessWhenReviewOptional() throws Exception {
        // OPTIONAL + agent SUCCESS → reviewHandler never called.
        var workflow = workflowWithReview("review-optional-success", ReviewMode.OPTIONAL);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler, never()).requestReview(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAlwaysRequestReviewWhenRequired() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewOutcome.decided(new ReviewDecision.Approve(null)));
        var workflow = workflowWithReview("review-required", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler).requestReview(any(), any(), any(), any(), any(), any());
    }

    // — Review decisions ——————————————————————————————————————————————————

    @Test
    void shouldRejectWorkflowOnReviewRejection() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        ReviewOutcome.decided(new ReviewDecision.Reject("Quality insufficient")));
        var workflow = workflowWithReview("review-reject", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Rejected.class);
        assertThat(((ExecutionResult.Rejected) result).getReason())
                .isEqualTo("Quality insufficient");
    }

    @Test
    void shouldBacktrackOnReviewBacktrack() throws Exception {
        // First review backtracks to step1; second approves. step1 executes twice → 4+ history
        // entries.
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        ReviewOutcome.decided(new ReviewDecision.Backtrack("step1", "Redo step 1")))
                .thenReturn(ReviewOutcome.decided(new ReviewDecision.Approve(null)));

        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("review-backtrack")
                        .agent(agentCfg())
                        .startNode(step("step1", "step2"))
                        .node(
                                StandardNode.builder()
                                        .id("step2")
                                        .agentId("test-agent")
                                        .prompt("Step 2")
                                        .reviewConfig(
                                                new ReviewConfig(ReviewMode.REQUIRED, true, true))
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
        // step1 executed twice + step2 executed twice → at least 4 history entries.
        assertThat(
                        ((ExecutionResult.Completed) result)
                                .getFinalState()
                                .getHistory()
                                .getSteps()
                                .size())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldThrowWhenBacktrackTargetsNodeNotInWorkflow() {
        // handleBacktrack() sets currentNode = "ghost-node", then the loop calls
        // workflow.getNodes().get("ghost-node") == null → IllegalStateException.
        // If someone adds silent null-handling instead of throwing, this test fails.
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        ReviewOutcome.decided(
                                new ReviewDecision.Backtrack("ghost-node", "go back")));

        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("backtrack-invalid")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("step")
                                        .agentId("test-agent")
                                        .prompt("Work")
                                        .reviewConfig(
                                                new ReviewConfig(ReviewMode.REQUIRED, true, true))
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost-node");
    }

    // — Pending review (async) ——————————————————————————————————————————

    @Test
    void shouldPauseOnPendingReview() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewOutcome.pending("corr-1"));
        var workflow = workflowWithReview("review-pending", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Paused.class);
        var paused = (ExecutionResult.Paused) result;
        assertThat(paused.state().getPhase()).isInstanceOf(ExecutionPhase.Awaiting.class);
        var phase = (ExecutionPhase.Awaiting) paused.state().getPhase();
        assertThat(phase.processorId()).isEqualTo("ReviewPostProcessor");
        assertThat(phase.correlationId()).isEqualTo("corr-1");
        assertThat(phase.nodeId()).isEqualTo("step");
    }

    @Test
    void shouldResumeAfterPendingReviewAndComplete() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewOutcome.pending("corr-1"))
                .thenReturn(ReviewOutcome.decided(new ReviewDecision.Approve(null)));
        var workflow = workflowWithReview("review-resume", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var pausedResult = executor.execute(workflow, new HashMap<>());
        assertThat(pausedResult).isInstanceOf(ExecutionResult.Paused.class);
        var pausedState = ((ExecutionResult.Paused) pausedResult).state();

        var resumed = executor.executeFrom(workflow, pausedState);

        assertThat(resumed).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) resumed).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
        verify(mockAgent).execute(any(), any());
    }

    // — Constructor contract ——————————————————————————————————————————————

    @Test
    void shouldRejectNullLifecycleCoordinator() {
        assertThatThrownBy(
                        () ->
                                new WorkflowExecutor(
                                        new DefaultNodeExecutorRegistry(),
                                        agentRegistry,
                                        rubricEngine,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lifecycleCoordinator");
    }

    // — Helpers ———————————————————————————————————————————————————————————

    // — Verdict routing over a real graph —————————————————————————————————

    /// The reviewer's approval must reach the `onApproval` arm even though the agent rejected
    /// its own output. Before the verdict channel existed, routing read only the agent's
    /// `approved` and this workflow ended at the rejection node with the operator's decision
    /// discarded.
    @Test
    void humanApprovalOutranksAgentSelfRejection() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewOutcome.decided(new ReviewDecision.Approve(null)));
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"approved\": false}"));

        var result = executor.execute(workflowWithApprovalArms("verdict-approve"), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).finalState().getCurrentNode())
                .isEqualTo("approved-end");
    }

    /// The mirror case: a human rejection routes through the graph to the rejection node
    /// rather than aborting at the review node, so `onRejection goto` is reachable.
    @Test
    void humanRejectionRoutesThroughTheGraph() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewOutcome.decided(new ReviewDecision.Reject("limit too high")));
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"approved\": true}"));

        var result = executor.execute(workflowWithApprovalArms("verdict-reject"), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).finalState().getCurrentNode())
                .isEqualTo("rejected-end");
    }

    private io.hensu.core.workflow.Workflow workflowWithApprovalArms(String id) {
        return WorkflowTest.TestWorkflowBuilder.create(id)
                .agent(agentCfg())
                .startNode(
                        StandardNode.builder()
                                .id("step")
                                .agentId("test-agent")
                                .prompt("Work")
                                .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, false, false))
                                .transitionRules(
                                        List.of(
                                                new ApprovalTransition(true, "approved-end"),
                                                new ApprovalTransition(false, "rejected-end")))
                                .build())
                .node(end("approved-end"))
                .node(end("rejected-end"))
                .build();
    }

    private io.hensu.core.workflow.Workflow workflowWithReview(String id, ReviewMode mode) {
        return WorkflowTest.TestWorkflowBuilder.create(id)
                .agent(agentCfg())
                .startNode(
                        StandardNode.builder()
                                .id("step")
                                .agentId("test-agent")
                                .prompt("Work")
                                .reviewConfig(new ReviewConfig(mode, false, false))
                                .transitionRules(List.of(new SuccessTransition("end")))
                                .build())
                .node(end("end"))
                .build();
    }
}
