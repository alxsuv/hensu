# Hensu Core

Pure Java workflow execution runtime with zero external dependencies.

## Overview

The `hensu-core` module is the execution engine at the heart of Hensu. It provides:

- **Workflow Execution** — Directed graph traversal with branching, looping, and parallel execution
- **Agent Abstraction** — Provider-agnostic AI agent interface with pluggable backends
- **Rubric Engine** — Quality evaluation with weighted criteria, score-based routing, and LLM-based assessment
- **Agent Tool Loop** — Native tool execution via `ToolCapable`/`ToolSession` with budget enforcement and sealed-hierarchy termination
- **Tool Seam** — Protocol-agnostic tool descriptors plus the provider/router seam that feeds agent tool loops
- **Human Review** — Optional or required review checkpoints at any workflow step
- **Pause / Resume** — Phase-aware suspend and resume for long-running executions with out-of-band review support
- **Action System** — Extensible action dispatch (send, execute) with pluggable executors
- **Template Resolution** — `{variable}` placeholder substitution in prompts
- **State Snapshots** — Serializable execution state for persistence and time-travel debugging
- **Generic Nodes** — Extensible node types for custom workflow operations
- **Agentic Output Validation** — Defense-in-depth safety checks applied to all LLM-generated node outputs before they enter workflow state (ASCII control chars, Unicode manipulation chars, payload size)
- **State Schema & Validation** — Optional typed schema declaration for domain-specific state variables, with load-time validation of `writes` declarations and prompt `{variable}` references

## Architecture

```mermaid
flowchart TD
    subgraph factory["HensuFactory.builder()"]
        direction TB
        subgraph top["Core"]
            direction LR
            we(["WorkflowExecutor\n(graph engine)"]) ~~~ ar(["AgentRegistry\n(agent lookup)"]) ~~~ re(["RubricEngine\n(quality gates)"])
        end
        subgraph nodes["Node Executors"]
            direction LR
            std(["Standard"]) ~~~ par(["Parallel"]) ~~~ fj(["Fork/Join"]) ~~~ act(["Action"]) ~~~ gen(["Generic"]) ~~~ sub(["SubWorkflow"])
        end
        subgraph mid["Infrastructure"]
            direction LR
            af(["AgentFactory\n(providers)"]) ~~~ ae(["ActionExecutor\n(send/execute)"])
        end
        subgraph support["Support"]
            direction LR
            tr(["ToolRouter"]) ~~~ tmpl(["TemplateResolver\n({var} subst)"]) ~~~ rh(["ReviewHandler\n(human-in-the-loop)"])
        end
        subgraph storage["Storage"]
            direction LR
            wr(["WorkflowRepository"]) ~~~ wsr(["WorkflowStateRepository\n(snapshots, pause/resume)"])
        end
        top --> nodes --> mid --> support --> storage
    end

    style factory fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style top fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style nodes fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style mid fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style support fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style storage fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px

    style we fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style ar fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style re fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style std fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style par fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style fj fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style act fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style gen fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style sub fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style af fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style ae fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style tr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style tmpl fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style rh fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style wr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style wsr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

## Key Design Principles

- **Zero external dependencies** — enforced by Gradle (`configurations.api` check fails the build on any dependency
  leak)
- **Provider-agnostic** — AI provider integration happens through `AgentProvider` interface, implemented in adapter
  modules
- **GraalVM native-image safe** — no reflection, no classpath scanning, explicit wiring only
- **Thread-safe after construction** — immutable components wired via `HensuFactory`
- **Storage interfaces in core** — `WorkflowRepository` and `WorkflowStateRepository` with in-memory defaults; server delegates via CDI

## Quick Start

```java
// Standalone (env vars for credentials, stub provider only)
var env = HensuFactory.createEnvironment();

// With explicit providers (recommended)
var env = HensuFactory.builder()
    .loadCredentials(properties)
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();

// Execute a workflow
ExecutionResult result = env.getWorkflowExecutor().execute(workflow, initialContext);
```

## Rubric Engine

Quality evaluation engine for rubric-based output assessment. Evaluates workflow node outputs against configurable
rubrics to determine quality scores and pass/fail status.

```mermaid
flowchart LR
    subgraph engine["RubricEngine"]
        direction LR
        subgraph eval["RubricEvaluator"]
            direction TB
            score(["ScoreExtractingEvaluator\n(reads score from ctx)"])
        end
        subgraph model["Rubric Model"]
            direction LR
            rubric(["Rubric"]) ~~~ criterion(["Criterion"]) ~~~ criterionEval(["CriterionEval"])
        end
    end

    style engine fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style eval fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style model fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style score fill:#2c2c2e, stroke:#0A84FF, color:#ebebf5, stroke-width:1px
    style rubric fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style criterion fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style criterionEval fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

**Key types:**

| Type               | Description                                                       |
|--------------------|-------------------------------------------------------------------|
| `RubricEngine`     | Orchestrates evaluation using evaluator                           |
| `RubricEvaluator`  | Evaluates output against criteria (self-eval or external LLM)     |
| `Rubric`           | Immutable rubric definition with pass threshold and criteria list |
| `Criterion`        | Single evaluation dimension with weight and minimum score         |
| `RubricEvaluation` | Complete evaluation result with per-criterion scores              |

Score-based routing: nodes can use `ScoreTransition` to route based on evaluation scores (e.g., score >= 80 goto "approve", else goto "revise"). Nodes that write a boolean `approved` variable can use `ApprovalTransition` (`onApproval` / `onRejection` in DSL) for binary decision routing. For routing on any other declared output variable — e.g. a self-reported `status` driving a bounded work loop — use `ConditionTransition` (`onCondition` in DSL).

## Tool Seam

Protocol-agnostic tool descriptors plus the seam each runtime plugs its tool sources into. A `ToolProvider` owns both a catalog and the invocation of the tools in it; a `ToolRouter` composes several providers into the single object the engine wires into an execution context.

```java
// Compose the tools a runtime offers
ToolRouter router = new ToolRouter(List.of(myCommandProvider, myMcpProvider));

// Discovery (ToolRegistry) and invocation (ToolInvoker) go through the same object
List<ToolDefinition> available = router.all();
ToolCallResult result = router.call("analyze", Map.of("input", "..."), context);
```

**Key types:**

| Type                  | Description                                                          |
|-----------------------|----------------------------------------------------------------------|
| `ToolDefinition`      | Tool descriptor with name, description, and parameters               |
| `ParameterDef`        | Parameter type with name, type, required flag, and default value     |
| `ToolProvider`        | A runtime's tool source: catalog plus invocation                     |
| `ToolInvoker`         | Invocation half of the seam, consumed by the tool loop               |
| `ToolRouter`          | Composes providers; implements both `ToolRegistry` and `ToolInvoker` |
| `ToolRegistry`        | Interface for tool discovery                                         |
| `DefaultToolRegistry` | Thread-safe ConcurrentHashMap implementation                         |

The server contributes a provider backed by MCP server connections; the CLI contributes providers for local commands and stdio MCP servers. When a node declares tools and its agent implements `ToolCapable`, `ToolLoopRunner` resolves declared tool names against the router's catalog, filters to the agent's declared subset, and passes full schemas into the tool session. Two providers exposing the same tool name is a configuration error and fails the node.

## Module Structure

```
hensu-core/src/main/java/io/hensu/core/
├── HensuFactory.java              # Entry point — builder for HensuEnvironment
├── HensuEnvironment.java          # Component container (executor, registry, etc.)
├── HensuConfig.java               # Configuration (storage backend)
├── agent/
│   ├── Agent.java                 # Core agent interface
│   ├── AgentConfig.java           # Agent configuration (model, temperature, etc.)
│   ├── AgentFactory.java          # Creates agents from explicit providers
│   ├── AgentProvider.java         # Provider interface for pluggable AI backends
│   ├── AgentRegistry.java         # Agent lookup interface
│   ├── DefaultAgentRegistry.java  # Thread-safe ConcurrentHashMap implementation
│   ├── ToolCapable.java           # Narrow interface for agents that support tool sessions
│   ├── ToolSession.java           # Call-scoped tool loop session (start/submit/compact/close)
│   └── stub/
│       ├── StubAgentProvider.java # Testing provider (priority 1000 when enabled)
│       ├── StubAgent.java         # Mock agent returning stub responses
│       ├── StubToolSession.java   # Scripted tool session for testing (---TURN--- syntax)
│       └── StubResponseRegistry.java
├── execution/
│   ├── WorkflowExecutor.java          # Main graph traversal engine (execute + executeFrom for resume)
│   ├── NodeLifecycleCoordinator.java  # Per-node lifecycle: phase dispatch + pipeline orchestration
│   ├── executor/
│   │   ├── NodeExecutor.java              # Interface for node type executors
│   │   ├── NodeExecutorRegistry.java      # Registry interface for node executor lookup
│   │   ├── DefaultNodeExecutorRegistry.java # Default implementation
│   │   ├── NodeResult.java                # Primary return type for all node executors
│   │   ├── ExecutionContext.java           # Per-execution context carrier (state + tenant)
│   │   ├── AgentLifecycleRunner.java      # Composition-based agent call: enrich → execute → extract
│   │   ├── ToolLoopRunner.java           # Drives agent-native tool loop (budget, feed-back, audit events, sealed termination)
│   │   ├── StandardNodeExecutor.java      # LLM prompt execution for StandardNode
│   │   ├── ParallelNodeExecutor.java      # Concurrent branch execution
│   │   ├── ForkNodeExecutor.java          # Fork into parallel paths
│   │   ├── JoinNodeExecutor.java          # Merge parallel results
│   │   ├── ActionNodeExecutor.java        # Action dispatch
│   │   ├── GenericNodeExecutor.java       # Custom node handlers
│   │   ├── SubWorkflowNodeExecutor.java   # Nested workflow execution
│   │   └── EndNodeExecutor.java           # Terminal nodes
│   ├── EngineVariables.java                   # SSOT for engine variable names (score, approved, recommendation)
│   ├── enricher/
│   │   ├── EngineVariableInjector.java        # Single-injector interface
│   │   ├── EngineVariablePromptEnricher.java  # Composite enricher — runs injector chain before each agent call
│   │   ├── FeedbackContextInjector.java       # Appends prior `recommendation` as a "### Previous Feedback" section (runs first)
│   │   ├── RubricPromptInjector.java          # Injects rubric criteria when node has a parsed Rubric
│   │   ├── ScoreVariableInjector.java         # Injects `score` requirement on ScoreTransition nodes or consensus branches
│   │   ├── ApprovalVariableInjector.java      # Injects `approved` requirement on ApprovalTransition nodes or consensus branches
│   │   ├── RecommendationVariableInjector.java # Injects `recommendation` on score/approval nodes or consensus branches
│   │   ├── WritesVariableInjector.java        # Injects field requirements for writes() variables with optional description hints
│   │   └── YieldsVariableInjector.java        # Injects field requirements for branch yields() variables
│   ├── pipeline/
│   │   ├── NodeExecutionProcessor.java          # Base processor interface
│   │   ├── PreNodeExecutionProcessor.java       # Pre-execution processor marker interface
│   │   ├── PostNodeExecutionProcessor.java      # Post-execution processor marker interface
│   │   ├── ProcessorContext.java                # Per-iteration context (node + result + state)
│   │   ├── ProcessorPipeline.java               # Orchestrates pre/post processor chains
│   │   ├── CheckpointPreProcessor.java          # Fires listener.onCheckpoint for crash-recovery persistence
│   │   ├── NodeStartPreProcessor.java           # Fires listener.onNodeStart for observability
│   │   ├── OutputExtractionPostProcessor.java   # Validates then stores node output in state context
│   │   ├── RubricPostProcessor.java             # Quality evaluation + auto-backtrack
│   │   ├── ReviewPostProcessor.java             # Human-in-the-loop review checkpoints (sync or async)
│   │   ├── NodeCompletePostProcessor.java       # Fires listener.onNodeComplete for observability
│   │   ├── HistoryPostProcessor.java            # Records execution steps for audit
│   │   ├── TransitionPostProcessor.java         # Evaluates transition rules, sets next node
│   │   └── ProcessorOutcome.java                # Sealed pipeline outcome: Continue, Terminal, SuspendForExternal
│   ├── action/
│   │   ├── Action.java            # Sealed interface: Send | Execute
│   │   ├── ActionExecutor.java    # Action dispatch interface
│   │   └── ActionHandler.java     # Per-action-type handler
│   ├── result/
│   │   ├── ExecutionResult.java   # Workflow execution outcome
│   │   ├── ExecutionHistory.java  # Step-by-step execution trace
│   │   └── ExecutionStep.java     # Single node execution record
│   └── parallel/
│       ├── Branch.java            # Parallel branch definition
│       ├── BranchResult.java      # Result of a single branch execution
│       ├── ConsensusStrategy.java # Multi-branch agreement strategy enum
│       ├── ConsensusConfig.java   # Consensus configuration (strategy, threshold)
│       ├── ConsensusEvaluator.java # Evaluates branch results against consensus rules
│       ├── ConsensusResult.java   # Outcome of consensus evaluation
│       ├── BranchExecutionConfig.java # Typed branch metadata on ExecutionContext (consensus, yields)
│       └── FailureMarker.java     # Sentinel for failed branches in fork/join
├── workflow/
│   ├── Workflow.java              # Workflow definition (agents + graph + optional state schema)
│   ├── WorkflowConfig.java        # Execution tuning (retry limits, timeouts, defaults)
│   ├── WorkflowMetadata.java      # Descriptive metadata (name, version, description)
│   ├── WorkflowRepository.java   # Tenant-scoped workflow storage interface
│   ├── InMemoryWorkflowRepository.java  # Default in-memory implementation
│   ├── node/
│   │   ├── Node.java              # Sealed base node interface
│   │   ├── NodeType.java          # Node type enum
│   │   ├── NodeTargets.java       # Outgoing-edge target set for a node
│   │   ├── StandardNode.java      # LLM prompt node with writes + transitions
│   │   ├── ParallelNode.java      # Concurrent branches with consensus
│   │   ├── ForkNode.java          # Splits into parallel paths (StructuredTaskScope)
│   │   ├── JoinNode.java          # Merges parallel results via writes/exports
│   │   ├── MergeStrategy.java     # Join merge strategy enum
│   │   ├── ActionNode.java        # Dispatches actions (send HTTP, execute command)
│   │   ├── GenericNode.java       # Custom handler for extensible operations
│   │   ├── SubWorkflowNode.java   # Delegates to another workflow
│   │   └── EndNode.java           # Terminal node
│   ├── transition/
│   │   ├── TransitionRule.java        # Sealed trigger interface; exposes requiredRoutingVars(), retryFeedback(), mismatchDiagnostic()
│   │   ├── TransitionTargets.java     # Primary + escalation target pair
│   │   ├── TransitionRuleChecks.java  # Shared rule-list validations (duplicate namespaces, arm ordering)
│   │   ├── SuccessTransition.java     # Routes on successful execution
│   │   ├── FailureTransition.java     # Routes on execution failure (pure trigger)
│   │   ├── NoConsensusTransition.java # Routes when a parallel node fails consensus
│   │   ├── ScoreTransition.java       # Routes on rubric evaluation score
│   │   ├── ApprovalTransition.java    # Routes on the human review verdict, else the approved engine variable
│   │   ├── ApprovalArms.java          # Reports which approval arms a rule list declares (SSOT for verdict routability)
│   │   ├── Condition.java             # Sealed value predicates (Equals, NotEquals, Compare) with coercion
│   │   ├── ConditionTransition.java   # Routes on a declared output variable (backs onCondition)
│   │   ├── BoundedTransition.java     # Decorates a trigger with retry budget + escalation (backs revise)
│   │   ├── AlwaysTransition.java      # Else-arm: routes every successful result to its target
│   │   └── RubricFailTransition.java  # Routes when rubric evaluation itself fails
│   ├── state/
│   │   ├── WorkflowStateSchema.java      # Typed state variable schema (optional per-workflow)
│   │   ├── StateVariableDeclaration.java # Variable declaration record (name, type, isInput)
│   │   └── VarType.java                  # Type enum: STRING, NUMBER, BOOLEAN, LIST_STRING
│   └── validation/
│       ├── SubWorkflowGraphValidator.java # Load-time cycle + dangling-ref detection
│       └── WorkflowValidator.java        # Load-time validator for transition targets, writes, prompt {variable} refs, and review arms
├── rubric/                        # Quality evaluation engine
│   ├── RubricEngine.java          # Evaluation orchestrator
│   ├── model/                     # Rubric, Criterion, ScoreCondition, etc.
│   └── evaluator/
│       ├── RubricEvaluator.java          # Evaluator interface
│       └── ScoreExtractingEvaluator.java # Reads score engine variable from context; accumulates recommendation feedback
├── tool/                          # Protocol-agnostic tool descriptors and the provider seam
│   ├── ToolDefinition.java        # Tool shape (name, params, return type)
│   ├── ToolCallResult.java        # Tool execution result (success/failure factories, asText())
│   ├── ToolProvider.java          # A runtime's tool source: catalog plus invocation
│   ├── ToolInvoker.java           # Invocation half of the seam, consumed by the tool loop
│   ├── ToolRouter.java            # Composes providers; ToolRegistry + ToolInvoker, rejects duplicate names
│   ├── ToolCallEvent.java         # Audit record for a dispatched tool request
│   ├── ToolResultEvent.java       # Audit record for a settled tool invocation (output truncated)
│   ├── ToolRegistry.java          # Tool discovery interface
│   └── DefaultToolRegistry.java   # Thread-safe ConcurrentHashMap implementation
├── review/                        # Human review support
│   ├── ReviewHandler.java         # Review callback interface
│   ├── ReviewOutcome.java         # Sealed: Decided(ReviewDecision) | Pending(correlationId)
│   ├── ReviewDecision.java        # Sealed: Approve | Backtrack | Reject
│   ├── ReviewVerdict.java         # A reviewer's decision and reason, read by ApprovalTransition
│   ├── ReviewConfig.java          # Per-node review configuration
│   └── ReviewMode.java            # DISABLED, OPTIONAL, REQUIRED
├── resume/
│   └── ResumeInput.java           # Sealed: ApplyReview | ApplyContextEdits | None
├── template/                      # {variable} placeholder resolution
├── util/
│   ├── AgentOutputValidator.java  # LLM output safety checks (control chars, Unicode tricks, size)
│   ├── JsonUtil.java              # Dependency-free JSON extraction utilities
│   ├── LogSanitizer.java         # Strips CR/LF from logged values to prevent log injection
│   └── ShellEscaper.java         # Escapes strings for safe shell interpolation in command templates
└── state/                         # Execution state and persistence
    ├── HensuState.java            # Mutable runtime state during execution
    ├── HensuSnapshot.java         # Immutable checkpoint record for persistence
    ├── ExecutionPhase.java        # Sealed: Initial | Awaiting(nodeId, processorId, ...) | Terminal
    ├── WorkflowStateRepository.java     # Tenant-scoped state storage interface
    └── InMemoryWorkflowStateRepository.java  # Default in-memory implementation
```

## Node Types

| Node              | Description                                                                                                        |
|-------------------|--------------------------------------------------------------------------------------------------------------------|
| `StandardNode`    | Executes an LLM prompt, optionally writes structured state variables via `writes`, and transitions based on result |
| `ParallelNode`    | Runs multiple branches concurrently; branches declare domain output via `yields()` which merge into workflow state |
| `ForkNode`        | Splits execution into parallel paths via `StructuredTaskScope`                                                     |
| `JoinNode`        | Merges parallel results via `writes()` with configurable strategy; `exports()` filters boundary variables          |
| `ActionNode`      | Dispatches actions (send HTTP, execute command)                                                                    |
| `GenericNode`     | Custom handler for extensible operations                                                                           |
| `SubWorkflowNode` | Delegates to another workflow                                                                                      |
| `EndNode`         | Terminal node                                                                                                      |

**Sub-workflow constraints.** Nested execution is capped at depth 16 (`SubWorkflowNodeExecutor.MAX_DEPTH`) to prevent unbounded recursion. The executor propagates `_tenant_id` into the child context so multi-tenant isolation holds across the boundary. Reference graphs are validated at load time (CLI) and push time (server) by `SubWorkflowGraphValidator` – both cycles and dangling references are rejected before execution starts.

## Transition Rules

| Rule                    | Description                                                                                                                                      |
|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `SuccessTransition`     | Routes on successful execution                                                                                                                   |
| `FailureTransition`     | Routes on execution failure (pure trigger; no retry budget)                                                                                      |
| `NoConsensusTransition` | Routes when a parallel node fails to reach consensus                                                                                             |
| `ScoreTransition`       | Routes based on rubric evaluation score                                                                                                          |
| `ApprovalTransition`    | Routes on the human reviewer's verdict when one exists, else on the `approved` boolean engine variable (fall-through if absent)                  |
| `ConditionTransition`   | Routes on a declared output variable via a typed `Condition` predicate; type mismatches emit a loud transition warning (backs DSL `onCondition`) |
| `BoundedTransition`     | Decorates a trigger with a per-node retry budget + escalation target (the `revise` / `onFailure retry` mechanism)                                |
| `AlwaysTransition`      | Else-arm of `onScore` / `onCondition`: routes every **successful** result to its target; failures fall through to `FailureTransition`            |
| `RubricFailTransition`  | Routes when rubric evaluation itself fails                                                                                                       |

## Agent Provider Interface

Implement `AgentProvider` to add support for new AI backends:

```java
public class MyProvider implements AgentProvider {
    public String getName() { return "my-provider"; }
    public boolean supportsModel(String model) { return model.startsWith("my-"); }
    public Agent createAgent(String id, AgentConfig config, Map<String, String> credentials) {
        return new MyAgent(id, config, credentials.get("MY_API_KEY"));
    }
}
```

Wire providers explicitly via `HensuFactory.builder().agentProviders(...)`. The built-in `StubAgentProvider` is always
included automatically for testing support.

## Stub Mode

Enable stub mode for testing without API calls:

```bash
export HENSU_STUB_ENABLED=true
```

Or via properties: `hensu.stub.enabled=true`

When enabled, `StubAgentProvider` (priority 1000) intercepts all model requests, returning mock responses.

## Documentation

| Document                                           | Description                                                |
|----------------------------------------------------|------------------------------------------------------------|
| [Developer Guide](../docs/developer-guide-core.md) | Architecture deep-dive, extension points, testing patterns |

## Dependencies

- **None** — zero external dependencies enforced at build time
- Test dependencies: JUnit 5, Mockito, AssertJ
