package io.hensu.cli.commands;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.NodeTargets;
import java.util.*;
import java.util.stream.Collectors;
import picocli.CommandLine;

/// CLI command for validating workflow syntax and structure.
///
/// Parses the workflow and performs static analysis to detect issues:
/// - Syntax errors in Kotlin DSL
/// - Unreachable nodes (not connected to start node)
/// - Missing node references in transitions
///
/// ### Usage
/// ```bash
/// hensu validate [-d <working-dir>] <workflow-name>
/// ```
///
/// @see WorkflowCommand
@CommandLine.Command(name = "validate", description = "Validate workflow syntax")
class WorkflowValidateCommand extends WorkflowCommand {

    @CommandLine.Parameters(
            index = "0",
            description = "Workflow name (from workflows/ directory)",
            arity = "0..1")
    private String workflowName;

    @CommandLine.Option(
            names = {"--with"},
            description =
                    """
                            Sub-workflow to load and validate alongside the root \
                            (repeatable, resolved from working-dir/workflows/, .kt optional)""")
    private List<String> withNames = List.of();

    @Override
    protected void execute() {
        try {
            Workflow workflow = getWorkflow(workflowName, withNames);

            System.out.println(" [OK] Workflow is valid!");
            System.out.println("   Name: " + workflow.getMetadata().getName());
            System.out.println("   Nodes: " + workflow.getNodes().size());
            System.out.println("   Agents: " + workflow.getAgents().size());

            for (Workflow subWorkflow : loadedSubWorkflows) {
                System.out.println("   Sub-workflow: " + subWorkflow.getMetadata().getName());
                System.out.println("   Nodes: " + subWorkflow.getNodes().size());
                System.out.println("   Agents: " + subWorkflow.getAgents().size());
            }

            List<String> unreachable = findUnreachableNodes(workflow);
            if (!unreachable.isEmpty()) {
                System.out.println(" [WARN] Unreachable nodes: " + String.join(", ", unreachable));
            }
        } catch (Exception e) {
            System.err.println(" [FAIL] Validation failed: " + rootCauseMessage(e));
        }
    }

    /// Walks the cause chain to the deepest exception carrying a message, so DSL
    /// build errors (e.g. overlapping condition arms) surface instead of generic
    /// script-engine wrappers like "Script execution error".
    private static String rootCauseMessage(Throwable e) {
        String message = e.getMessage();
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                message = t.getMessage();
            }
        }
        return message;
    }

    /// Finds nodes that are not reachable from the start node via any transition path.
    ///
    /// Performs breadth-first traversal from the start node following every node's successors
    /// (transition rules plus fork and loop-break targets). Nodes not visited are considered
    /// unreachable.
    ///
    /// @param workflow the workflow to analyze, not null
    /// @return list of unreachable node IDs (empty if all nodes are reachable)
    private List<String> findUnreachableNodes(Workflow workflow) {
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(workflow.getStartNode());

        while (!queue.isEmpty()) {
            String nodeId = queue.pollFirst();
            if (reachable.contains(nodeId)) continue;
            reachable.add(nodeId);

            Node node = workflow.getNodes().get(nodeId);
            if (node != null) {
                queue.addAll(NodeTargets.of(node));
            }
        }

        return workflow.getNodes().keySet().stream()
                .filter(id -> !reachable.contains(id))
                .collect(Collectors.toList());
    }
}
