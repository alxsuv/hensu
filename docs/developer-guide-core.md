# Hensu Core Developer Guide

This guide covers API usage, adapter development, extension points, and testing strategies for developers working with or extending `hensu-core`.

## Table of Contents

- [API Usage](#api-usage)
  - [Quick Start](#quick-start)
  - [Using HensuFactory.Builder](#using-hensufactorybuilder)
  - [Executing Workflows](#executing-workflows)
- [Execution Pipeline](#execution-pipeline)
  - [Pre-Execution Pipeline](#pre-execution-pipeline)
  - [Post-Execution Pipeline](#post-execution-pipeline)
  - [Parallel Branch Concurrency](#parallel-branch-concurrency)
  - [Agentic Output Validation](#agentic-output-validation)
- [Pause / Resume Lifecycle](#pause--resume-lifecycle)
- [Creating Custom Adapters](#creating-custom-adapters)
- [Generic Nodes](#generic-nodes)
- [Sub-Workflows](#sub-workflows)
- [Action Handlers](#action-handlers)
- [Rubric Engine](#rubric-engine)
  - [Score-Based Routing](#score-based-routing)
  - [Approval Routing](#approval-routing)
  - [Condition-Based Routing](#condition-based-routing)
  - [Bounded Revise](#bounded-revise)
- [State Schema](#state-schema)
- [Engine Variable Injection](#engine-variable-injection)
- [Agent Tool Loop](#agent-tool-loop)
- [Tool Registry](#tool-registry)
- [Template Resolution](#template-resolution)
- [Human Review](#human-review)
- [Testing](#testing)
  - [Stub Agent System](#stub-agent-system)
- [GraalVM Native Image Constraints](#graalvm-native-image-constraints)
- [Credentials Management](#credentials-management)
- [Key Classes Reference](#key-files-reference)

## API Usage

### Quick Start

**Standalone with environment variables (stub provider only):**
```java
var env = HensuFactory.createEnvironment();
```

**With explicit providers (recommended):**
```java
var env = HensuFactory.builder()
    .loadCredentials(properties)
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
```

### Using HensuFactory.Builder

The builder provides fine-grained control over all components:

```java
HensuEnvironment env = HensuFactory.builder()
    // Credentials (multiple loading strategies)
    .loadCredentials(properties)              // From Properties + env vars
    .loadCredentialsFromEnvironment()         // From env vars only
    .anthropicApiKey("sk-ant-...")            // Individual key
    .credential("CUSTOM_KEY", "value")        // Custom key

    // Agent providers (explicit wiring, GraalVM-safe)
    .agentProviders(List.of(new LangChain4jProvider()))

    // Optional components
    .stubMode(false)                          // Enable for testing
    .evaluatorAgent("evaluator")              // LLM-based rubric evaluation
    .reviewHandler(myReviewHandler)           // Human review support
    .actionExecutor(myActionExecutor)         // Action execution

    // Repositories (defaults to in-memory implementations)
    .workflowRepository(myWorkflowRepo)      // Custom workflow storage
    .workflowStateRepository(myStateRepo)    // Custom state persistence

    .build();
```

> **Note**: When `workflowRepository` or `workflowStateRepository` are not set, `HensuFactory` defaults to
> `InMemoryWorkflowRepository` and `InMemoryWorkflowStateRepository` respectively.

> **Note**: `StubAgentProvider` is always included automatically by `build()`. Do not add it explicitly.

### Executing Workflows

```java
// Get executor from environment
HensuEnvironment env = HensuFactory.builder()
    .loadCredentialsFromEnvironment()
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
WorkflowExecutor executor = env.getWorkflowExecutor();

// Register workflow agents
env.getAgentRegistry().registerAgents(workflow.getAgents());

// Execute with initial context
Map<String, Object> initialContext = Map.of("topic", "AI workflows");
ExecutionResult result = executor.execute(workflow, initialContext);

// Handle result
if (result instanceof ExecutionResult.Completed completed) {
    System.out.println("Success! Exit status: " + completed.getExitStatus());
    System.out.println("Final output: " + completed.getFinalOutput());
} else if (result instanceof ExecutionResult.Paused(HensuState pausedState)) {
    System.out.println("Paused at node: " + pausedState.getCurrentNode());
    // Save snapshot for later resume
    HensuSnapshot snapshot = pausedState.snapshot("paused");
    // ... persist snapshot, then later:
    // executor.executeFrom(workflow, snapshot.toState());
} else if (result instanceof ExecutionResult.Rejected rejected) {
    System.out.println("Rejected: " + rejected.getReason());
}
```

## Execution Pipeline

The `WorkflowExecutor` processes each node through a standardized, three-phase lifecycle orchestrated by processor
pipelines. This model decouples the core node logic (e.g., agent calls) from cross-cutting concerns like state
management, history, and quality evaluation.

The lifecycle for every node is:

1. **Pre-Execution Pipeline** — processors that run *before* the node's main logic
2. **Node Execution** — the appropriate `NodeExecutor` is invoked
3. **Post-Execution Pipeline** — processors that run *after* the node's main logic to process its result

Every processor returns a `ProcessorOutcome` — a sealed type with three variants:

| Variant              | Meaning                                                                                                                        |
|----------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `Continue`           | Proceed to the next processor (or next loop iteration if this was the last one)                                                |
| `Terminal(result)`   | Short-circuit the executor with the given `ExecutionResult` (e.g., `Rejected`, `Failure`)                                      |
| `SuspendForExternal` | Pause the post-pipeline at this processor for an out-of-band resume (see [Pause / Resume Lifecycle](#pause--resume-lifecycle)) |

`SuspendForExternal` is only valid in the post-pipeline. If a pre-pipeline processor returns it, the pipeline throws `IllegalStateException` — no node result exists to cache yet.

```mermaid
flowchart LR
    subgraph loop["WorkflowExecutor.executeLoop()"]
        direction LR
        pre(["Pre-Execution\n(placeholder)"]) --> node(["Node Executor\n(agent call)"]) --> post(["Post-Execution\n(6 processors)"])
    end

    style loop fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style pre fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style node fill:#2c2c2e, stroke:#0A84FF, color:#ebebf5, stroke-width:1px
    style post fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

### Pre-Execution Pipeline

Runs before the node's primary logic. All processors receive a `ProcessorContext` with a `null` result.

| Order | Processor                  | Responsibility                                                      |
|-------|----------------------------|---------------------------------------------------------------------|
| 1     | `CheckpointPreProcessor`   | Fires `listener.onCheckpoint(state)` for crash-recovery persistence |
| 2     | `NodeStartPreProcessor`    | Fires `listener.onNodeStart(node)` for observability                |

### Post-Execution Pipeline

This is where the majority of the workflow's state management and decision-making occurs. The processors run in a
**fixed, critical order** — changing the order breaks invariants downstream processors depend on:

| Order | Processor                       | Responsibility                                                        |
|-------|---------------------------------|-----------------------------------------------------------------------|
| 1     | `OutputExtractionPostProcessor` | Validates (control chars, Unicode tricks, size) then stores output    |
| 2     | `RubricPostProcessor`           | Automated quality evaluation and self-correction                      |
| 3     | `ReviewPostProcessor`           | Human-in-the-loop checkpoints (may suspend for async review)          |
| 4     | `NodeCompletePostProcessor`     | Fires `listener.onNodeComplete(node, result)` for observability       |
| 5     | `HistoryPostProcessor`          | Records execution step for audit and backtracking                     |
| 6     | `TransitionPostProcessor`       | Determines next node via `TransitionRule` evaluation                  |

**`OutputExtractionPostProcessor`** — First validates the raw output using `AgentOutputValidator` (see
[Agentic Output Validation](#agentic-output-validation)). On any violation it returns `ExecutionResult.Failure`
immediately, leaving the context map unmodified. If validation passes, it puts the raw output string into the context
map keyed by node ID. For `StandardNode`s with `writes` declared, it routes the output differently:
- **Single write**: attempts to parse the response as JSON and extract the declared key; falls back to the full raw text if the key is absent or the output is not valid JSON.
- **Multiple writes**: the response is parsed as JSON and each declared key is extracted into context.

**`RubricPostProcessor`** — If a node has a `Rubric`, this processor evaluates the output against the rubric's
criteria using the `RubricEngine`. It stores the evaluation result in the state for use by `ScoreTransition` rules. If
the evaluation fails and no explicit transition handles the low score, it can trigger an **auto-backtrack** to a prior
step, enabling self-correcting loops. On auto-backtrack, sets `state.nodeRedirected = true` — downstream processors
check this flag to skip redundant work (e.g., `ReviewPostProcessor` skips review when a rubric already redirected).

**`ReviewPostProcessor`** — If a node has a `reviewConfig`, this processor invokes the registered `ReviewHandler`.
The handler returns a `ReviewOutcome`: either `Decided(ReviewDecision)` for synchronous review or
`Pending(correlationId)` for asynchronous out-of-band review. On `Pending`, the processor emits
`ProcessorOutcome.SuspendForExternal`, pausing the execution until a resume call delivers the decision.
Based on the decision, the processor can allow the workflow to continue (`Approve`), reject it terminating execution
(`Reject`), or backtrack to a previous node (`Backtrack`). Skipped when `state.isNodeRedirected()` is true (prior
rubric backtrack).

**`NodeCompletePostProcessor`** — Fires `listener.onNodeComplete(node, result)` for observability. Runs after
Rubric/Review so rejected or redirected nodes are never marked complete.

**`HistoryPostProcessor`** — Appends an immutable `ExecutionStep` (containing a state snapshot, the node result, and a
timestamp) to the `ExecutionHistory`. Runs after Rubric/Review so the snapshot includes scores and review edits. This
is the foundation for time-travel debugging and backtracking.

**`TransitionPostProcessor`** — The final step. Evaluates the current node's `TransitionRule` list in order. The first
rule that returns a valid target node ID wins, and the state is updated to point to that next node. If
`state.isNodeRedirected()` is true (set by Rubric or Review backtrack), the transition evaluator is skipped — the
current node was already updated by the backtracking processor. The flag is then reset. Throws
`IllegalStateException` if no rule matches, preventing the workflow from silently getting stuck.

Two further responsibilities live here:

- **Mismatch diagnostics.** When a rule yields no match but reports a type-mismatch via
  `TransitionRule.mismatchDiagnostic(state)` (e.g. a `ConditionTransition` whose routed variable is absent or
  uncoercible), the processor emits `ExecutionListener.onTransitionWarning(nodeId, message)` — a mismatch is never a
  silent `false`. Diagnostics are suppressed for failed node results, where the routed variable legitimately never
  existed.
- **Engine variable lifecycle.** The routing clear-set is derived from the node's rules'
  `TransitionRule.requiredRoutingVars()` (plus the built-in `score`/`approved` pair, minus `recommendation`, which has
  its own feedback lifecycle). Declared condition variables are therefore cleared on every transition, preventing a
  stale value from a previous iteration from firing an exit arm prematurely. No other component clears engine
  variables from the state context.

### Parallel Branch Concurrency

Both `ParallelNodeExecutor` and `ForkNodeExecutor` run branches on named virtual threads
via `StructuredTaskScope` (preview, Java 25). Two concurrency guarantees apply to all
parallel execution paths:

**1. ScopedValue isolation** – each branch receives an isolated context snapshot bound via
`ScopedValue.where(BRANCH_CONTEXT, snapshot)`. `ParallelNodeExecutor.BRANCH_CONTEXT` is a
`ScopedValue<Map<String, Object>>` that carries branch-scoped metadata (like `BranchExecutionConfig` –
consensus strategy, yield declarations) without polluting the state context map. Branch mutations
never leak into sibling branches or the parent state. This is the same mechanism used for tenant
isolation (`TenantContext`).

> **Extension rule:** Never use `ThreadLocal` in branch-aware code. `ScopedValue` is the only
> safe context propagation mechanism for virtual threads. See `10-java-standards.md` for details.

**2. SynchronizedListenerDecorator** – the parent `ExecutionListener` is wrapped in a
`SynchronizedListenerDecorator` before branch submission. Every callback is `synchronized` so that
multi-line output (e.g., box-drawing in verbose CLI, SSE event frames) completes atomically without
interleaving across concurrent branches.

Custom `ExecutionListener` implementations do **not** need their own synchronization when used with
parallel nodes – the decorator handles it. Only the parallel execution path pays the synchronization
cost; sequential nodes use the raw listener.

> **JEP 491 (Java 24+):** Virtual thread pinning on `synchronized` blocks was eliminated. Monitor
> contention on I/O-bound listener callbacks is negligible for typical branch counts (3–10).

### Agentic Output Validation

LLM-generated outputs are non-deterministic and treated as **untrusted data**. Unlike REST input (short, user-typed,
identity-validated), agentic output is machine-generated and carries threats that simple control-character filtering
does not cover.

`AgentOutputValidator` (`io.hensu.core.util`) is the single authority for output validation in the core engine. It is
called by `OutputExtractionPostProcessor` before any output is written to workflow state.

#### Checks Applied

| Check                      | Method                     | Threat                                                                                |
|----------------------------|----------------------------|---------------------------------------------------------------------------------------|
| ASCII control characters   | `containsDangerousChars()` | Null bytes, non-printable chars that degrade downstream processing                    |
| Unicode manipulation chars | `containsUnicodeTricks()`  | RTL overrides, zero-width chars, BOM — used to hide content or carry prompt injection |
| Payload size               | `exceedsSizeLimit()`       | Runaway generation exhausting memory or storage in downstream stages                  |

The **size limit is 4 MB** (`MAX_LLM_OUTPUT_BYTES`) — higher than the 1 MB REST cap to accommodate large agentic
outputs (documents, generated code). The limits serve different threat models and are intentionally separate constants.

#### Unicode Manipulation Detail

The `containsUnicodeTricks()` check targets characters outside the ASCII control range that LLMs can produce:

| Category                      | Codepoints    | Example Attack                              |
|-------------------------------|---------------|---------------------------------------------|
| RTL/LTR directional overrides | U+202A–U+202E | Hide malicious text behind visual reversal  |
| Unicode isolates              | U+2066–U+2069 | Isolate bidi runs to redirect rendering     |
| Zero-width characters         | U+200B–U+200D | Invisible payload embedding, steganography  |
| Byte-order mark               | U+FEFF        | Unexpected BOM in content signals injection |

Legitimate RTL scripts (Arabic, Hebrew) use **natural bidi properties** of their characters and do not require
directional override characters. The check produces no false positives on standard multilingual content.

#### Separation from Server-Side Validation

`AgentOutputValidator` is intentionally separate from `InputValidator` in `hensu-server`. The two classes serve
distinct trust boundaries and must not be merged:

| Concern            | `InputValidator` (server)           | `AgentOutputValidator` (core)        |
|--------------------|-------------------------------------|--------------------------------------|
| Input source       | Human-typed REST requests           | LLM-generated node outputs           |
| Size limit         | 1 MB (`MAX_JSON_MESSAGE_BYTES`)     | 4 MB (`MAX_LLM_OUTPUT_BYTES`)        |
| Unicode tricks     | Not checked (low risk for humans)   | Checked (LLMs can produce overrides) |
| Safe-ID validation | Yes — for user-supplied identifiers | Not applicable — no IDs in output    |
| Framework          | Jakarta Bean Validation             | Pure utility, zero dependencies      |

## Pause / Resume Lifecycle

When a post-processor cannot complete synchronously (e.g., human review via a web UI), the execution suspends and resumes later without re-running the node's agent call. Three sealed types collaborate to make this work.

### ExecutionPhase

`ExecutionPhase` is a sealed interface on `HensuState` that tracks where inside a node's lifecycle the execution is:

| Variant                                                                   | Meaning                                                                                         |
|---------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `Initial`                                                                 | Top of the loop — run pre-pipeline, execute node, post-pipeline normally                        |
| `Awaiting(nodeId, processorId, cachedResult, correlationId, requestedAt)` | Paused inside the post-pipeline; resume re-enters at the named processor with the cached result |
| `Terminal`                                                                | Execution finished — further calls fail fast                                                    |

`NodeLifecycleCoordinator` dispatches on the phase:
- **`Initial`** — full lifecycle: reset per-node state, pre-pipeline, execute node, post-pipeline.
- **`Awaiting`** — resume path: skip pre-pipeline and node execution entirely; re-enter the post-pipeline at `processorId` via `ProcessorPipeline.executePostFrom(processorId, context)`.
- **`Terminal`** — throws `IllegalStateException`.

### ReviewOutcome

`ReviewHandler.requestReview()` returns `ReviewOutcome` — a sealed type that replaces the previous synchronous `ReviewDecision` return:

| Variant                  | Meaning                                                                |
|--------------------------|------------------------------------------------------------------------|
| `Decided(decision)`      | Handler answered synchronously — apply the `ReviewDecision` now        |
| `Pending(correlationId)` | Handler accepted the request; answer arrives out-of-band via resume    |

On `Pending`, `ReviewPostProcessor` emits `ProcessorOutcome.SuspendForExternal("ReviewPostProcessor", cachedResult, correlationId)`. The pipeline records an `ExecutionPhase.Awaiting` on state and returns `ExecutionResult.Paused`.

### ResumeInput

When the execution resumes, the caller supplies a `ResumeInput` — a sealed type set transiently on `HensuState` before `executeFrom()`:

| Variant                                | Meaning                                                       |
|----------------------------------------|---------------------------------------------------------------|
| `ApplyReview(correlationId, decision)` | Deliver a `ReviewDecision` produced out-of-band by a reviewer |
| `ApplyContextEdits(edits)`             | Merge free-form context edits without going through review    |
| `None`                                 | Pure continuation (e.g., server restart recovery)             |

`ResumeInput` is **transient** — it is never persisted in snapshots. It is set by the resume caller, consumed by post-processors, and cleared after the post-pipeline completes.

### Resume flow

1. Caller sets `state.setResumeInput(new ApplyReview(correlationId, decision))`.
2. `NodeLifecycleCoordinator` sees `ExecutionPhase.Awaiting` → calls `postPipeline.executePostFrom("ReviewPostProcessor", ctx)`.
3. `ReviewPostProcessor.process()` checks `state.getResumeInput()` for `ApplyReview` **before** calling `requestReview()` — this prevents an infinite `Pending` loop on resume.
4. Validates that `correlationId` matches the `Awaiting` phase via `ExecutionPhase.validateCorrelation()`.
5. Applies the decision (Approve/Backtrack/Reject), records a `ReviewVerdict` on the state, and clears `resumeInput`.
6. Remaining post-processors run normally (NodeComplete → History → Transition). `TransitionPostProcessor` routes on the verdict, promotes its reason to `recommendation` where the transition carries feedback, and clears it.

The phase and the resume input must agree in both directions. `ExecutionPhase.validateCorrelation()` rejects an `ApplyReview` against a non-`Awaiting` phase, and equally rejects a `None` or `ApplyContextEdits` against an `Awaiting` one — an execution paused at a review gate has nothing to resume *with* except a decision, and accepting anything else re-entered the post-pipeline and silently requested the review again. On the server this check runs before the execution record is touched, so a bad request is a 400 that leaves the execution paused and resumable rather than marking it failed.

### The review verdict channel

A reviewer's decision does not travel as an engine variable. `ReviewPostProcessor` records a `ReviewVerdict(approved, reason)` on `HensuState`, and `ApprovalTransition` reads it in preference to the agent-written `approved` when routing. This keeps `approved` to two writers with one meaning — the agent's self-assessment via output extraction, and consensus — and makes "the reviewer decides" a property of the rule that reads the values rather than of the order in which processors write them.

A rejection is routed through the graph whenever the node declares an `onRejection` arm, so `onRejection goto "declined"` is reachable; only a node with no such arm terminates on rejection.

The verdict is transient like `resumeInput`: never persisted, never carried into a fork branch, and cleared by `TransitionPostProcessor` on every exit path.

### Recovery granularity

Two at-least-once boundaries follow from what a checkpoint can record.

- **Forks re-run whole.** `CheckpointPreProcessor` suppresses checkpoints for branch states (`HensuState.isBranchState()`), because a checkpoint holds one cursor and a fork has several. The parent's last checkpoint before the fork still names the fork node, so recovery re-runs every sub-flow — including ones that had already completed, whose agent calls are billed again. Branches record into private `ExecutionHistory` copies; `ForkNodeExecutor` folds them back into the parent in target-declaration order after the join, so the history is complete and reproducible.
- **Reviews can be re-requested.** A crash between applying a review decision and the next checkpoint loses the verdict, and recovery re-prompts the review. Persisting the verdict would not close that window, only move it earlier.

### Backtrack prompt override

When a reviewer or rubric backtrack redirects execution to a previous node, `StandardNodeExecutor` checks for a `_prompt_override` key in the state context. If present, it is consumed (removed) and used instead of the node's static prompt. This allows reviewers to inject corrective instructions without modifying the workflow definition.

## Creating Custom Adapters

### 1. Create New Module

**build.gradle.kts**
```kotlin
dependencies {
    implementation(project(":hensu-core"))
    implementation("com.myai:myai-sdk:1.0.0")
}
```

### 2. Implement AgentProvider

```java
package io.hensu.adapter.myai;

import io.hensu.core.agent.AgentProvider;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import java.util.Map;

public class MyAiProvider implements AgentProvider {

    @Override
    public String getName() {
        return "myai";
    }

    @Override
    public boolean supportsModel(String modelName) {
        return modelName.startsWith("myai-");
    }

    @Override
    public Agent createAgent(String agentId, AgentConfig config,
                            Map<String, String> credentials) {
        String apiKey = credentials.get("MYAI_API_KEY");
        return new MyAiAgent(agentId, config, apiKey);
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
```

### 3. Implement Agent

```java
package io.hensu.adapter.myai;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import java.util.Map;

public class MyAiAgent implements Agent {
    private final String id;
    private final AgentConfig config;
    private final MyAiClient client;

    public MyAiAgent(String id, AgentConfig config, String apiKey) {
        this.id = id;
        this.config = config;
        this.client = new MyAiClient(apiKey);
    }

    @Override
    public AgentResponse execute(String prompt, Map<String, Object> context) {
        try {
            MyAiResponse response = client.complete(prompt);
            return AgentResponse.success(response.getText(), response.getMetadata());
        } catch (Exception e) {
            return AgentResponse.failure(e);
        }
    }

    @Override
    public String getId() { return id; }

    @Override
    public AgentConfig getConfig() { return config; }
}
```

### 4. Wire Explicitly

Wire your provider via `HensuFactory.builder().agentProviders(...)`:

```java
var env = HensuFactory.builder()
    .agentProviders(List.of(
        new LangChain4jProvider(),
        new MyAiProvider()
    ))
    .loadCredentialsFromEnvironment()
    .build();
```

Or add a single provider:

```java
var env = HensuFactory.builder()
    .agentProvider(new LangChain4jProvider())
    .agentProvider(new MyAiProvider())
    .build();
```

### Provider Priority

When multiple providers support the same model, higher priority wins:

```java
public class LangChain4jProvider implements AgentProvider {
    public int getPriority() { return 100; }  // Preferred
}

public class MyAiProvider implements AgentProvider {
    public int getPriority() { return 50; }   // Fallback
}

// StubAgentProvider has priority 1000 when stub mode is enabled
```

## Generic Nodes

Generic nodes allow custom execution logic without involving an AI agent. They're useful for data validation, transformation, external service integration, and conditional branching.

### How Generic Nodes Work

1. Define a `GenericNode` in your workflow with an `executorType`
2. Create a `GenericNodeHandler` implementation with matching type
3. Register the handler with `NodeExecutorRegistry`
4. At runtime, `GenericNodeExecutor` looks up your handler and invokes it

### Creating a Handler

Implement the `GenericNodeHandler` interface:

```java
package com.example.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import java.util.Map;

public class ValidatorHandler implements GenericNodeHandler {

    public static final String TYPE = "validator";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) throws Exception {
        Map<String, Object> config = node.getConfig();
        String fieldName = (String) config.getOrDefault("field", "input");
        Object fieldValue = context.getState().getContext().get(fieldName);

        boolean isValid = fieldValue != null && !fieldValue.toString().isBlank();

        if (isValid) {
            return NodeResult.success("Validation passed",
                Map.of("validated_field", fieldName));
        } else {
            return NodeResult.failure(fieldName + " is required");
        }
    }
}
```

### Registering Handlers

#### Manual Registration (Pure Java)

```java
HensuEnvironment env = HensuFactory.builder()
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();

NodeExecutorRegistry registry = env.getNodeExecutorRegistry();
registry.registerGenericHandler("validator", new ValidatorHandler());
registry.registerGenericHandler("data-transformer", new DataTransformerHandler());
```

#### CDI Auto-Discovery (Quarkus/CDI)

With CDI, handlers annotated with `@ApplicationScoped` are automatically discovered and registered:

```java
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ValidatorHandler implements GenericNodeHandler {
    // ... same implementation
}
```

Both CLI and server register all CDI-discovered handlers via injection:

```java
@Inject Instance<GenericNodeHandler> genericNodeHandlers;

private void registerGenericHandlers() {
    for (GenericNodeHandler handler : genericNodeHandlers) {
        hensuEnvironment.getNodeExecutorRegistry()
            .registerGenericHandler(handler.getType(), handler);
    }
}
```

### Handler Best Practices

1. **Use descriptive type names**: `"user-validator"` is better than `"v1"`
2. **Access config safely**: Use `getOrDefault()` for optional parameters
3. **Store outputs in context**: Put results in `context.getState().getContext()` for subsequent nodes
4. **Return meaningful metadata**: Include relevant info in `NodeResult` metadata map
5. **Handle errors gracefully**: Return `NodeResult.failure()` with clear error messages

## Sub-Workflows

`SubWorkflowNode` delegates execution to a nested workflow. The parent pauses on the boundary, the child runs to completion, and control returns to the parent with selected outputs mapped back into its state.

### Context propagation and depth limit

- `_tenant_id` is copied from parent into child context, preserving multi-tenant isolation across the boundary.
- Nested invocation is capped at depth 16 (`SubWorkflowNodeExecutor.MAX_DEPTH`) via `_sub_workflow_depth`. The executor throws before invoking a child beyond the cap – this is a hard guard against runaway recursion, not a tunable.

### Input and output mappings

Data crosses the boundary only through explicit mappings declared on `SubWorkflowNode`:

| Mapping         | Direction              | Semantics                                                                                                                    |
|-----------------|------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `inputMapping`  | `childKey → parentKey` | Before the child starts, the executor reads `parentKey` from parent state and writes it as `childKey` in child state.        |
| `outputMapping` | `parentKey → childKey` | On successful child completion, the executor reads `childKey` from child state and writes it as `parentKey` in parent state. |

Anything not covered by these mappings stays on its side of the boundary. There is no implicit state leakage between parent and child.

### Reference graph validation

`SubWorkflowGraphValidator` rejects cycles and dangling references at graph-load time, before any node executes. See [SubWorkflowGraphValidator checks](#subworkflowgraphvalidator-checks) under State Schema.

## Action Handlers

Action handlers send data from workflow actions to external systems. Handlers can implement any integration: HTTP calls, messaging (Slack, email), event publishing (Kafka, RabbitMQ), database operations, or custom logic.

### How Action Handlers Work

1. Implement the `ActionHandler` interface with your execution logic
2. Register the handler with `ActionExecutor`
3. Reference the handler by ID in workflow DSL using `send()`
4. At runtime, the executor looks up your handler and invokes it

### Creating a Handler

```java
package com.example.handlers;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class SlackHandler implements ActionHandler {

    private final String webhookUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public SlackHandler(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String getHandlerId() {
        return "slack";
    }

    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        try {
            String message = payload.getOrDefault("message", "Workflow event").toString();
            String body = String.format("{\"text\": \"%s\"}", message);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ActionResult.success("Slack notification sent");
            } else {
                return ActionResult.failure("Slack API error: " + response.statusCode());
            }
        } catch (Exception e) {
            return ActionResult.failure("Slack call failed: " + e.getMessage(), e);
        }
    }
}
```

### Registering Handlers

#### Manual Registration

```java
CLIActionExecutor executor = new CLIActionExecutor();

String slackUrl = System.getenv("SLACK_WEBHOOK_URL");
executor.registerHandler(new SlackHandler(slackUrl));

HensuEnvironment env = HensuFactory.builder()
    .actionExecutor(executor)
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
```

#### CDI Auto-Discovery

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SlackHandler implements ActionHandler {

    @ConfigProperty(name = "slack.webhook.url")
    String webhookUrl;

    @Override
    public String getHandlerId() {
        return "slack";
    }

    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        // Implementation using injected webhookUrl
    }
}
```

### Handler Best Practices

1. **Use descriptive handler IDs**: `"slack"`, `"github-dispatch"` are better than `"handler1"`
2. **Load credentials securely**: Use environment variables, config files, or secret managers
3. **Handle errors gracefully**: Return `ActionResult.failure()` with clear error messages
4. **Make handlers thread-safe**: They may be called concurrently

## Rubric Engine

The rubric engine evaluates output quality against defined criteria with score-based routing.

### Components

| Component                  | Description                                                                                                                                  |
|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `RubricEngine`             | Orchestrates evaluation using evaluator                                                                                                      |
| `RubricEvaluator`          | Evaluates output against criteria                                                                                                            |
| `ScoreExtractingEvaluator` | Reads the `score` engine variable from context; accumulates `recommendation` feedback for failing criteria into `_rubric_criterion_feedback` |
| `Rubric`                   | Immutable definition with pass threshold and weighted criteria                                                                               |
| `Criterion`                | Single evaluation dimension with weight and minimum score                                                                                    |

### How Evaluation Works

Rubrics are parsed at build time and stored directly on the node as typed `Rubric` objects.

`ScoreExtractingEvaluator` reads the `score` engine variable directly from the execution context. The score is extracted automatically by `OutputExtractionPostProcessor` whenever the node has a `ScoreTransition` — no JSON parsing is needed in the evaluator itself.

If the score falls below a criterion's minimum and a `recommendation` engine variable is present in context, the text is appended to `_rubric_criterion_feedback`. `RubricPostProcessor` uses that list to assemble a combined backtrack context update for self-correcting loops.

### Score-Based Routing

Nodes using rubrics can route based on evaluation scores via `ScoreTransition`:

```kotlin
onScore {
    whenScore greaterThanOrEqual 80.0 goto "approve"
    whenScore lessThan 80.0 goto "revise"
}
```

### Approval Routing

`ApprovalTransition` routes based on the boolean `approved` engine variable — useful when a node
acts as a classifier or reviewer and produces a binary decision.

```kotlin
node("review") {
    agent = "reviewer"
    onApproval goto "finalize"
    onRejection goto "improve"
}
```

Rules:
- Falls through (no match) if `approved` is absent or cannot be parsed as a boolean.
- Accepts `true`/`false` as Java `Boolean` or case-insensitive strings `"true"`/`"false"`.
- `approved` is an **engine variable** — injected automatically when `onApproval`/`onRejection` routing is present. Never declare it in `writes()` or the state schema.

### Condition-Based Routing

`ConditionTransition` routes on an arbitrary **declared output variable** (a `writes()` name, not an engine
variable) via a typed `Condition` predicate — the general loop-exit primitive behind the DSL's
`onCondition("status") { }` block.

```kotlin
onCondition("status") {
    whenValue equalTo "complete" goto "done"
    whenValue equalTo "blocked" goto "escalate"
    otherwise revise "work" retry 5 otherwise "escalate"
}
```

Key types and semantics:

- `Condition` is a sealed hierarchy: `Equals` / `NotEquals` (canonical-string compare — `85`, `85.0`, and `"85"`
  are equal; booleans compare via `"true"`/`"false"`) and `Compare` (GT/GTE/LT/LTE with numeric coercion,
  accepting numbers and numeric strings). `Condition.test()` returns `MATCH`, `NO_MATCH`, or `TYPE_MISMATCH`.
- A `TYPE_MISMATCH` (absent variable, non-numeric string under a numeric operator, nested JSON object) never
  matches **and** surfaces through `mismatchDiagnostic()` → `ExecutionListener.onTransitionWarning` — see
  `TransitionPostProcessor` above.
- `ConditionTransition.requiredRoutingVars()` returns the routed variable plus `recommendation`, which drives both
  prompt injection (the agent is instructed to emit the variable) and the transition clear-set.
- The DSL validates at build time: the routed variable must be declared in `writes()`, arms must be disjoint
  (`ArmIntervals` in `hensu-dsl`), and no arm may follow the `otherwise` else-arm.
- `AlwaysTransition` is the compiled form of the `otherwise` else-arm — it carries a target and fires on every
  **successful** result no earlier arm matched. Like `ConditionTransition`, it never routes a failed result:
  failures fall through to a `FailureTransition` (or abort with `IllegalStateException` if none is declared).

See the [DSL Reference](dsl-reference.md#condition-based-transitions-oncondition) for author-facing syntax.

### Bounded Revise

`BoundedTransition` decorates a trigger rule (`ApprovalTransition`, `NoConsensusTransition`, a single-condition `ScoreTransition`, a `ConditionTransition` arm, or an `AlwaysTransition` else-arm) with a per-node retry budget and an escalation target. It backs the DSL `revise "producer" retry N otherwise "escalate"` form on `onRejection`, `onNoConsensus`, score arms, and condition arms. Counters are namespaced per node and trigger kind (`approval`, `consensus`, `score`, `condition`) in `HensuState`; `TransitionPostProcessor` increments on a backtrack and resets on any forward move. The decorator is transparent to engine-variable wiring — injectors and output extraction consume `TransitionRule.requiredRoutingVars()`, and feedback injection consumes `TransitionRule.retryFeedback()` (`NONE` for failure triggers, `CONSENSUS` for no-consensus, `RECOMMENDATION` otherwise) rather than `instanceof`, so a revise-only node still gets its `approved`/`score`/`recommendation` instructions and decorators keep their semantics. See the [DSL Reference](dsl-reference.md#bounded-revise-revise) for author-facing syntax.

## State Schema

`WorkflowStateSchema` is an optional typed declaration on a `Workflow` that lists all domain-specific
state variables — inputs expected from the caller and outputs produced by nodes.

### Why declare a schema?

Without a schema, workflows operate in **legacy mode**: node outputs are stored in context keyed by
node ID. Schema mode enables:
- **Load-time validation** — `WorkflowBuilder.build()` throws `IllegalStateException` listing every
  `writes` name or `{variable}` prompt reference that is not declared.
- **Structured output** — the engine can generate a JSON schema for nodes that declare `writes`,
  guiding the LLM to produce structured output.

### Engine variables

The following variables are always implicitly valid — never declare them in the schema or in `writes()`:

| Variable           | Type      | Injected when                                                              |
|--------------------|-----------|----------------------------------------------------------------------------|
| `score`            | `NUMBER`  | Node has `onScore` routing (rubric-evaluated or self-scoring)              |
| `approved`         | `BOOLEAN` | Node has `onApproval` / `onRejection` routing                              |
| `recommendation`   | `STRING`  | Node has `onScore`, `onCondition`, or `onApproval` / `onRejection` routing |

Variables routed by `onCondition` are **not** engine variables — they are ordinary declared outputs and must appear
in both the schema and the node's `writes()`.

### DSL declaration

```kotlin
workflow("ContentPipeline") {
    state {
        input("topic", VarType.STRING)                                              // required in initial context
        variable("article",    VarType.STRING, "the full written article text")     // with LLM hint
        variable("confidence", VarType.NUMBER, "reviewer confidence score 0-100")  // with LLM hint
    }

    agents { ... }

    graph {
        node("write") {
            writes("article")
            prompt = "Write about {topic}"
            onSuccess goto "review"
        }
        node("review") {
            writes("confidence")
            prompt = "Review: {article}. Output JSON with confidence (0-100) and approved (true/false)."
            onApproval goto "end_ok"
            onRejection goto "write"
        }
        end("end_ok")
    }
}
```

### Java API

```java
var schema = new WorkflowStateSchema(List.of(
    new StateVariableDeclaration("topic",      VarType.STRING,  true),
    new StateVariableDeclaration("article",    VarType.STRING,  false, "the full written article text"),
    new StateVariableDeclaration("confidence", VarType.NUMBER,  false, "reviewer confidence score 0-100")
));

var workflow = Workflow.builder()
    .id("content-pipeline")
    .stateSchema(schema)
    // ... nodes, agents, etc.
    .build();

WorkflowValidator.validate(workflow); // throws IllegalStateException on violations
```

### `WorkflowValidator` checks

| Check                           | Error example                                                                   |
|---------------------------------|---------------------------------------------------------------------------------|
| Transition target doesn't exist | `Node 'write' has transition to 'revieww' which does not exist in the workflow` |
| `writes` name not in schema     | `Node 'write' writes 'draft' which is not declared in state schema`             |
| Prompt `{var}` not in schema    | `Node 'write' prompt references '{tone}' which is not declared in state schema` |
| Review node missing an approval arm | `Node 'assess' is under human review and declares an approval arm but no 'onRejection' transition, so a rejection from the reviewer has nowhere to go` |

Validation is a no-op when no schema is declared. Legacy workflows always pass through unchanged.

### `SubWorkflowGraphValidator` checks

`SubWorkflowGraphValidator` runs at graph-load time over the sub-workflow reference graph. It rejects cycles and – on the server push path – unresolved references, so neither can surface mid-execution. Two overloads serve the two entry points:

| Overload                         | Caller           | Detects                               | Unknown targets                                                                                |
|----------------------------------|------------------|---------------------------------------|------------------------------------------------------------------------------------------------|
| `validate(Collection<Workflow>)` | CLI batch loader | Cycles only                           | Silently skipped – loader reports missing `--with` declarations separately with richer context |
| `validate(Workflow, Function)`   | Server push path | Cycles **and** unknown referenced ids | Aggregated into a single `IllegalStateException` alongside any cycles, in a single DFS pass    |

The `(Workflow, Function)` overload shadows the incoming workflow for its own id so re-push/update sees the post-push graph without an intermediate write. The resolver is queried lazily and only for ids forward-reachable from the root – bounded by a `globallyVisited` set so each id costs at most one repository lookup.

## Engine Variable Injection

Before each agent call, `AgentLifecycleRunner` runs `EngineVariablePromptEnricher` to append
format requirements to the resolved prompt. The enricher is a dumb iterator over an ordered chain
of `EngineVariableInjector`s — each one self-contained, deciding independently whether it applies.

Engine variable names (`score`, `approved`, `recommendation`) are defined in the `EngineVariables`
class — the single source of truth for all engine-managed variable keys.

### Injection Pipeline

`FeedbackContextInjector` runs first and is the odd one out: it surfaces *prior* feedback rather
than instructing the agent to produce a new variable. It appends a `### Previous Feedback` section
whenever the context carries a non-blank `recommendation` value (preserved across a backtrack
`revise` or a forward `withFeedback` transition — see `TransitionPostProcessor` for the lifecycle).

Each remaining injector fires when **either** of two conditions is met:
1. A transition rule on the node declares the injector's variable via
   `TransitionRule.requiredRoutingVars()` (shared activation logic in the `TransitionVariableInjector` base class —
   no `instanceof` on rule types, so `BoundedTransition` decorators and `ConditionTransition` arms activate the
   same injectors as their bare counterparts)
2. The execution context carries a `BranchExecutionConfig` where `needsSelfScoring()` returns true
   (consensus branch with a non-JUDGE_DECIDES strategy)

```mermaid
flowchart LR
    fc(["FeedbackContextInjector\n· recommendation present"]) --> r(["RubricPromptInjector\n· rubric != null"])
    r --> s(["ScoreVariableInjector\n· rule requires score or\nconsensus branch"])
    s --> a(["ApprovalVariableInjector\n· rule requires approved or\nconsensus branch"])
    a --> rec(["RecommendationVariable\nInjector\n· rule requires recommendation\nor consensus branch"])
    rec --> w(["WritesVariableInjector\n· has writes()"])
    w --> y(["YieldsVariableInjector\n· has yields()"])

    style fc fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style r fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style s fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style a fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style rec fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style w fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style y fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

Each injector runs unconditionally in order. The condition listed under each name is when that
injector **appends** its instruction – otherwise it passes the prompt through unchanged.

### Description Hints in WritesVariableInjector

When a variable is declared with a description in the state schema, `WritesVariableInjector` includes it in the appended instruction:

```
Engine output requirement: your JSON response MUST include:
  "article"    — the full written article text
  "confidence" — reviewer confidence score 0-100
```

Without a description, only the field name is emitted. The hint removes any reliance on the LLM inferring expected content from the variable name alone.

### Extension

Construct a custom enricher with additional injectors:

```java
EngineVariablePromptEnricher enricher = new EngineVariablePromptEnricher(
    List.of(
        new FeedbackContextInjector(),
        new RubricPromptInjector(),
        new ScoreVariableInjector(),
        new ApprovalVariableInjector(),
        new RecommendationVariableInjector(),
        new WritesVariableInjector(),
        new YieldsVariableInjector(),
        new MyCustomInjector()   // appended after built-ins
    ));
```

Inject it via `HensuFactory` or override the server CDI producer.

## Agent Tool Loop

Agents that implement `ToolCapable` can natively request and receive tool results during execution.
The loop is driven by `ToolLoopRunner`, dispatched from `AgentLifecycleRunner` when a node declares
tools.

### Architecture

```mermaid
sequenceDiagram
    participant SNE as StandardNodeExecutor
    participant ALR as AgentLifecycleRunner
    participant TLR as ToolLoopRunner
    participant TS as ToolSession
    participant TR as ToolRouter

    SNE->>ALR: execute(nodeId, agentId, prompt, node, ctx)
    ALR->>ALR: EngineVariablePromptEnricher.enrich()
    ALR->>ALR: AgentRegistry.getAgent()

    alt Agent declares tools
        ALR->>TLR: execute(nodeId, agentId, prompt, agent, ctx)
        TLR->>TR: all() — catalog union, duplicate names rejected
        TLR->>TLR: filter to the agent's declared tools
        TLR->>TS: openToolSession(prompt, context, tools)
        TLR->>TS: start()
        TS-->>TLR: AgentResponse

        loop while ToolRequest && budget > 0
            TLR->>TLR: listener.onToolCall(ToolCallEvent)
            alt Known tool
                TLR->>TR: call(name, arguments, context)
                TR-->>TLR: ToolCallResult
                TLR->>TLR: toolCallCount++
            else Unknown tool (hallucination)
                TLR->>TLR: ToolCallResult.failure("Unknown tool")
            end
            TLR->>TLR: listener.onToolResult(ToolResultEvent)
            TLR->>TS: submit(ToolCallResult)
            TS-->>TLR: AgentResponse
        end

        alt Budget exhausted
            TLR->>TS: compact()
            TLR->>TS: submit(failure("budget exhausted"))
            TS-->>TLR: TextResponse → SUCCESS / ToolRequest → FAILURE
        end

        TLR->>TS: close()
        TLR-->>ALR: NodeResult

    else No tools declared
        ALR->>ALR: agent.execute(prompt, context)
        ALR->>ALR: toNodeResult(AgentResponse)
    end

    ALR-->>SNE: NodeResult
```

### Protocol

Three types form the tool session protocol (all in `hensu-core`, zero external deps):

| Type             | Description                                                                                         |
|------------------|-----------------------------------------------------------------------------------------------------|
| `ToolCapable`    | Narrow interface on agents: `openToolSession(prompt, context, tools)` returns a `ToolSession`       |
| `ToolSession`    | Call-scoped session: `start()`, `submit(ToolCallResult)`, `compact()`, `close()`                    |
| `ToolCallResult` | Record `(toolName, success, output, error)` with `success()`/`failure()` factories and `asText()`   |

Sessions are call-scoped, not stateful on the agent – `ParallelNodeExecutor` fan-out shares one agent
instance across branches, so loop state must not live on the agent.

### Termination Semantics

The sealed `AgentResponse` hierarchy IS the termination signal – no explicit "done" message:

| Response       | Meaning                                      |
|----------------|----------------------------------------------|
| `TextResponse` | Loop DONE – agent produced final answer      |
| `ToolRequest`  | Loop continues – another tool call round     |
| `Error`        | Loop DONE – agent errored                    |

### Engagement

`ToolLoopRunner` engages when both conditions hold:
1. `agent.getConfig().getTools()` is non-empty
2. `agent instanceof ToolCapable`

Tools declared but agent not capable → `NodeResult.failure()` (routable via `onFailure`).

### Tool Resolution

Tools are resolved through the router's provider catalogs:
1. `ctx.getToolRegistry().all()` returns the union of every provider's live catalog (in server context: `TenantToolProvider` → `McpToolDiscovery`)
2. Filter to the agent's declared `tools` names
3. Unresolvable name → `NodeResult.failure()` with diagnostic listing available tools
4. Two providers exposing the same tool name → `NodeResult.failure()` naming both providers; the collision is caught before any tool runs, never thrown out of the loop
5. Filtered `List<ToolDefinition>` (with full schemas) passed into `openToolSession()`

### Budget Enforcement

`AgentConfig.maxToolCalls` (default 10) caps EXECUTED tool calls – each invocation counts, so one
round with N parallel calls from the model counts N (not 1).

On cap exhaustion:
1. Feed back `ToolCallResult.failure(lastToolName, "Tool call budget exhausted (N/N)...")` via `session.submit()`
2. Model responds with `TextResponse` → **SUCCESS** (accumulated context preserved)
3. Model responds with `ToolRequest` → **hard FAILURE** ("agent continued requesting tools after budget exhaustion")

No retry, no counter reset. If an agent genuinely needs more budget, raise `maxToolCalls` in the DSL.

### Safety: Raw Payloads

Agent-originated tool arguments are dispatched as `Action.Send(..., rawPayload=true)`. The `rawPayload`
flag prevents template resolution of LLM-generated content – without it, an argument containing `{secret_key}`
would be expanded from workflow context (template exfiltration). DSL-authored sends use `rawPayload=false`
and continue to resolve templates normally.

### Unknown Tools

When an agent requests a tool name that doesn't exist (hallucination), the runner feeds back
`ToolCallResult.failure(toolName, "Unknown tool 'X', available: [...]")` rather than hard-failing.
The agent can retry or answer – budget bounds the loop.

### Session Lifecycle

`ToolLoopRunner` wraps the loop in try/finally:
- `session.compact()` before the cap-exhaustion final call (maximize context for summarization)
- `session.close()` in the finally block (release resources, flush final pair to shared history)

### Audit Events

Every tool request is reported to the `ExecutionListener`, so an unattended run leaves a complete
trail of what the agent asked to run:

| Callback                          | Fires                                                                |
|-----------------------------------|----------------------------------------------------------------------|
| `onToolCall(ToolCallEvent)`       | Immediately before dispatch, carrying the agent's arguments          |
| `onToolResult(ToolResultEvent)`   | Once the invocation settles, carrying success, duration, and output  |

The pair fires for every outcome, not only for tools that ran: a hallucinated name, a provider
failure, an exception during invocation, and a request the budget rejected are all audited.
`ToolResultEvent` truncates output at `MAX_OUTPUT_CHARS` (4096) so the trail stays cheap enough to
always be on. Both callbacks are serialized by `SynchronizedListenerDecorator` during parallel
branch execution.

### Adapter Implementations

| Adapter        | Class                    | Notes                                                                        |
|----------------|--------------------------|------------------------------------------------------------------------------|
| LangChain4j    | `LangChain4jToolSession` | Multi-tool queue draining, `ReentrantLock`-guarded history, system-msg merge |
| Stub (testing) | `StubToolSession`        | Scripted turns via `---TURN---` separator, `[TOOL_CALL]` prefix for requests |

## Tool Seam

Protocol-agnostic tool descriptors plus the seam each runtime plugs its tool sources into. A `ToolProvider` owns both a catalog and the invocation of the tools in it; a `ToolRouter` composes several providers into the single object the engine wires into an execution context, implementing `ToolRegistry` for discovery and `ToolInvoker` for invocation.

### Contributing Tools

```java
// Each runtime supplies its own providers
ToolProvider commands = new CommandToolProvider(catalog, runner);
ToolProvider mcp = new McpToolProvider(discovery, pool);

ToolRouter router = new ToolRouter(List.of(commands, mcp));

HensuEnvironment env = HensuFactory.builder()
    .toolRouter(router)
    .build();
```

A provider's catalog may be dynamic – tenant-scoped on the server, lazily started on the CLI – so
the router delegates `all()` and `call()` live rather than snapshotting at construction. Duplicate
tool names across providers are a configuration error: the constructor rejects what it can see, and
because dynamic catalogs are still empty at that point, the check runs again on every catalog
materialization.

### MCP Integration

The server contributes a provider backed by MCP server connections. Tools discovered via MCP become
`ToolDefinition` instances available for agent tool loops.

```
MCP Server ──► ToolDefinition ──► ToolProvider ──► ToolRouter ──► ToolLoopRunner ──► Agent Tool Session
```

## Template Resolution

The `TemplateResolver` substitutes `{variable}` placeholders in prompts from workflow context:

- `{topic}` — resolved from initial context or previous node output
- `{nodeId}` — resolved from the output of node with that ID (legacy mode — no schema declared)
- `{varName}` — resolved from named state variables written via `writes` (schema mode)

Template resolution applies only to DSL-authored payloads (`Action.Send` with `rawPayload=false`).
Agent-originated tool arguments (`rawPayload=true`) bypass resolution entirely to prevent template
exfiltration – an LLM-generated argument containing `{secret}` must not expand from workflow context.

## Human Review

Configure human review checkpoints via `ReviewHandler`:

```java
var env = HensuFactory.builder()
    .reviewHandler(new CLIReviewManager())
    .build();
```

Review modes per node:
- `DISABLED` — no human review (default)
- `OPTIONAL` — review only on failure
- `REQUIRED` — always require human review

## Testing

### Stub Agent System

The stub agent system lets you run full workflow executions without consuming API tokens. It intercepts all agent calls and returns configurable mock responses, making it the foundation for both unit and integration testing.

#### Architecture

Three classes collaborate to provide the stub infrastructure:

```
StubAgentProvider (priority 1000, intercepts all models when enabled)
  └── creates StubAgent per agent ID
        └── resolves response via StubResponseRegistry (singleton)
```

| Class                  | Role                                                       |
|------------------------|------------------------------------------------------------|
| `StubAgentProvider`    | Intercepts all model requests when enabled (priority 1000) |
| `StubAgent`            | Executes by resolving responses from the registry          |
| `StubResponseRegistry` | Singleton that stores and resolves stub responses          |

#### Enabling Stub Mode

Stub mode can be enabled through any of these (checked in order):

```java
// 1. Credentials map (per-execution override)
credentials.put("HENSU_STUB_ENABLED", "true");

// 2. System property
// -Dhensu.stub.enabled=true

// 3. Environment variable
// export HENSU_STUB_ENABLED=true

// 4. Application property (Quarkus)
// hensu.stub.enabled=true
```

When enabled, `StubAgentProvider` returns priority 1000, outranking all real providers. When disabled, it returns priority -1 and is never selected.

#### Response Resolution Order

When `StubAgent.execute()` is called, it reads the current node ID from `context.get("current_node")` (set by `StandardNodeExecutor` before each agent call) and delegates to `StubResponseRegistry.getResponse(nodeId, agentId, context, prompt)`.

The registry searches in this order:

1. **Programmatic response** for the active scenario, matched by **node ID**
2. **Programmatic response** for the active scenario, matched by **agent ID**
3. **Classpath resource** at `/stubs/{scenario}/{nodeId}.txt`
4. **Classpath resource** at `/stubs/{scenario}/{agentId}.txt`
5. **Filesystem stub** at `{stubsDir}/{scenario}/{nodeId}.txt`
6. **Filesystem stub** at `{stubsDir}/{scenario}/{agentId}.txt`
7. **Default scenario classpath** at `/stubs/default/{nodeId}.txt` (if scenario is not `"default"`)
8. **Default scenario classpath** at `/stubs/default/{agentId}.txt`
9. **Default scenario filesystem** at `{stubsDir}/default/{nodeId}.txt`
10. **Default scenario filesystem** at `{stubsDir}/default/{agentId}.txt`
11. **Auto-generated fallback** — parses the prompt for JSON structure hints or returns a labelled stub response

The first match wins. This means you can register stubs by **node ID** (most common in tests) or by **agent ID** (useful when multiple nodes share the same agent).

#### Node ID Propagation

`StandardNodeExecutor` sets `current_node` in the execution context before each `agent.execute()` call:

```java
state.getContext().put("current_node", node.getId());
```

This allows `StubAgent` to resolve stubs by the workflow node ID rather than the agent ID. Since a workflow JSON typically assigns different node IDs and agent IDs (e.g., node `"process"` with agent `"writer"`), node-ID-based registration is the natural choice for tests.

#### Programmatic Registration

Register responses directly via `StubResponseRegistry`:

```java
StubResponseRegistry registry = StubResponseRegistry.getInstance();

// Register by node ID (most common — matches resolution priority 1)
registry.registerResponse("draft", "Article about AI covering key concepts.");

// Register by agent ID (matches resolution priority 2)
registry.registerResponse("writer", "Fallback content for any node using 'writer' agent");

// Register for a specific scenario
registry.registerResponse("low_score", "evaluate",
    "{\"score\": 0.3, \"content\": \"Poor quality output\"}");

// Clear all responses between tests
registry.clearResponses();
```

#### Resource-Based Stubs

Place text files on the classpath for reusable, declarative stubs:

```
src/test/resources/
  stubs/
    default/           # Default scenario
      writer.txt       # Matched by agent ID "writer"
      reviewer.txt     # Matched by agent ID "reviewer"
    high_score/        # Named scenario
      evaluate.txt     # Matched by node/agent ID "evaluate"
    low_score/
      evaluate.txt
```

Each `.txt` file contains the raw response text the stub agent returns.

#### Filesystem-Based Stubs

For CLI usage where classpath resources are sealed inside the JAR, place stub files in the working directory's `stubs/` folder. The CLI automatically detects this directory and configures the registry:

```
working-dir/
  workflows/
    my-workflow.kt
  stubs/
    default/
      writer.txt
      reviewer.txt
    low_score/
      evaluate.txt
```

```bash
hensu run -d working-dir georgia-discovery.kt
```

Filesystem stubs follow the same `{scenario}/{key}.txt` convention as classpath resources. Classpath resources take priority over filesystem stubs, so integration tests with classpath stubs are unaffected.

You can also set the stubs directory programmatically:

```java
StubResponseRegistry.getInstance().setStubsDirectory(Path.of("./working-dir/stubs"));
```

#### Scenario Selection

Scenarios partition stub responses for different test paths (e.g., high-score vs low-score flows). The active scenario is determined by:

1. **Context variable**: `context.put("stub_scenario", "low_score")`
2. **System property**: `-Dhensu.stub.scenario=backtrack`
3. **Default**: `"default"`

#### Template Variables

Response templates support `{{key}}` substitution from the execution context:

```text
# stubs/default/writer.txt
A detailed article about {{topic}} covering key aspects of the subject.
```

When the execution context contains `"topic" -> "quantum computing"`, the placeholder is replaced at resolution time.

### Unit Testing with Mock Providers

For lightweight unit tests that don't need the full stub system, create an inline mock provider:

```java
@Test
void testWorkflowLogic() {
    AgentProvider mockProvider = new AgentProvider() {
        public String getName() { return "mock"; }
        public boolean supportsModel(String model) { return true; }
        public Agent createAgent(String id, AgentConfig config, Map<String, String> creds) {
            return new MockAgent(id, config);
        }
        public int getPriority() { return 100; }
    };

    var env = HensuFactory.builder()
        .agentProviders(List.of(mockProvider))
        .build();

    // Test workflow logic without real API calls
}
```

### Test Commands

```bash
./gradlew hensu-core:test                              # Core unit tests
./gradlew hensu-server:test                            # Server + integration tests
./gradlew hensu-core:test --tests "RubricEngineTest"   # Single test class
./gradlew test                                         # All modules
```

> **See also**: [Server Developer Guide — Integration Testing](developer-guide-server.md#integration-testing) for the full `@QuarkusTest` integration test framework built on this stub system.

## GraalVM Native Image Constraints

`hensu-server` is deployed as a GraalVM native image. All code in `hensu-core` (and adapters) must be native-image safe. GraalVM performs static analysis at build time — anything not visible to the compiler at build time will not work at runtime.

### Rules

**No reflection.** GraalVM cannot discover classes, methods, or fields at runtime unless they are registered in advance. This drives several design decisions:

```java
// WRONG — reflection-based lookup fails in native image
Class<?> clazz = Class.forName("com.example.MyProvider");
Object instance = clazz.getDeclaredConstructor().newInstance();

// CORRECT — explicit construction
AgentProvider provider = new MyProvider();
```

**No classpath scanning.** Runtime scanning for classes (e.g., `ServiceLoader`, annotation scanning) doesn't work without build-time metadata. This is why providers are wired explicitly:

```java
// WRONG — ServiceLoader discovers via META-INF/services at runtime
ServiceLoader<AgentProvider> providers = ServiceLoader.load(AgentProvider.class);

// CORRECT — explicit provider list
HensuFactory.builder()
    .agentProviders(List.of(new LangChain4jProvider(), new MyProvider()))
    .build();
```

> **Note**: Quarkus *does* support `ServiceLoader` by processing `META-INF/services` at build time. But `hensu-core` avoids it entirely to remain framework-agnostic.

**No dynamic proxies without registration.** JDK dynamic proxies (`Proxy.newProxyInstance(...)`) and CGLIB require upfront registration. Prefer concrete classes or manual delegation over dynamic proxies.

**No runtime bytecode generation.** Libraries that generate classes at runtime (e.g., some serialization frameworks) fail silently. Use explicit, static implementations.

### Jackson Serialization Pattern

**`hensu-core` must contain zero Jackson annotations.** No `@JsonDeserialize`, `@JsonProperty`, `@JsonTypeInfo`, or any Jackson import belongs here. All serialization metadata lives in `hensu-serialization`; all native-image reflection registrations live in `hensu-server`. This keeps the core framework-agnostic.

The `hensu-serialization` module uses explicit `SimpleModule` registrations instead of Jackson's reflective annotation processing:

```java
// In HensuJacksonModule — explicit serializer/deserializer registration
SimpleModule module = new SimpleModule("HensuModule");
module.addSerializer(Node.class, new NodeSerializer());
module.addDeserializer(Node.class, new NodeDeserializer());
module.addSerializer(TransitionRule.class, new TransitionRuleSerializer());
// ... each type explicitly registered
```

When adding new serializable types:
1. Create explicit `JsonSerializer<T>` and `JsonDeserializer<T>` classes
2. Register them in `HensuJacksonModule`
3. Do **not** rely on `@JsonTypeInfo` with class names — GraalVM cannot resolve them at runtime
4. Use a `"type"` discriminator field with an explicit `switch` in the deserializer

> **See also**: [hensu-serialization Developer Guide](developer-guide-serialization.md) for the full Jackson contract — mixin/builder pattern, `treeToValue` rules, and how to add new types safely.

### Writing Native-Image-Safe Adapters

When implementing `AgentProvider` for a new AI backend:

1. **Avoid reflection in model builders.** If the upstream SDK uses `.builder()` patterns with reflection internally, the SDK's Quarkus extension (if available) registers the needed metadata. If no extension exists, you must provide `reflect-config.json`.

2. **Use `SimpleModule` for custom Jackson types.** Any new model or DTO must be explicitly serializable without `@JsonAutoDetect` or field-level reflection.

3. **Test with native image.** Run `./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native` to verify. Failures manifest as `ClassNotFoundException` or `NoSuchMethodException` at runtime.

### Quick Reference

| Pattern                              | Safe         | Unsafe                  |
|--------------------------------------|--------------|-------------------------|
| `new MyClass()`                      | Yes          | —                       |
| `Class.forName(...)`                 | —            | Yes                     |
| `field.setAccessible(true)`          | —            | Yes (unless registered) |
| `ServiceLoader.load(...)`            | Quarkus only | Yes (standalone)        |
| Jackson `SimpleModule`               | Yes          | —                       |
| Jackson `@JsonTypeInfo(use = CLASS)` | —            | Yes                     |
| `Proxy.newProxyInstance(...)`        | —            | Yes (unless registered) |
| Sealed interface `switch`            | Yes          | —                       |
| Builder pattern                      | Yes          | —                       |

## Credentials Management

Credentials are loaded via `HensuFactory` and passed to providers as a `Map<String, String>`.

### Loading Strategies

**From environment variables:**
```java
HensuFactory.builder()
    .loadCredentialsFromEnvironment()
    .build();
```

**From properties (`hensu.credentials.*` prefix, stripped automatically):**
```java
// application.properties:
// hensu.credentials.ANTHROPIC_API_KEY=sk-ant-...
// hensu.credentials.OPENAI_API_KEY=sk-...
// hensu.stub.enabled=true

HensuFactory.builder()
    .loadCredentials(properties)  // Loads from env + properties (properties win)
    .build();
```

**Individual keys:**
```java
HensuFactory.builder()
    .anthropicApiKey("sk-ant-...")
    .openAiApiKey("sk-...")
    .build();
```

### Supported Environment Variables

| Variable             | Provider         |
|----------------------|------------------|
| `ANTHROPIC_API_KEY`  | Anthropic Claude |
| `OPENAI_API_KEY`     | OpenAI GPT       |
| `GOOGLE_API_KEY`     | Google Gemini    |
| `DEEPSEEK_API_KEY`   | DeepSeek         |

Environment variables matching `*_API_KEY`, `*_KEY`, `*_SECRET`, or `*_TOKEN` patterns are auto-discovered.

## Key Files Reference

| File                                                    | Description                                                                                       |
|---------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `HensuFactory.java`                                     | Bootstrap and environment creation                                                                |
| `HensuEnvironment.java`                                 | Container for all core components                                                                 |
| `HensuConfig.java`                                      | Configuration (storage backend)                                                                   |
| `agent/AgentFactory.java`                               | Creates agents from explicit providers                                                            |
| `agent/AgentProvider.java`                              | Provider interface for pluggable AI backends                                                      |
| `agent/AgentRegistry.java`                              | Agent lookup interface                                                                            |
| `agent/DefaultAgentRegistry.java`                       | Thread-safe agent registry                                                                        |
| `agent/ToolCapable.java`                                | Narrow interface for agents that support tool sessions                                            |
| `agent/ToolSession.java`                                | Call-scoped tool loop session (start/submit/compact/close)                                        |
| `agent/stub/StubAgentProvider.java`                     | Testing provider (priority 1000 when enabled)                                                     |
| `agent/stub/StubToolSession.java`                       | Scripted tool session for testing (`---TURN---` syntax)                                           |
| `execution/WorkflowExecutor.java`                       | Main execution engine                                                                             |
| `execution/NodeLifecycleCoordinator.java`               | Per-node lifecycle: phase dispatch, pipeline orchestration                                        |
| `execution/executor/GenericNodeHandler.java`            | Generic node handler interface                                                                    |
| `execution/action/ActionHandler.java`                   | Action handler interface                                                                          |
| `execution/action/ActionExecutor.java`                  | Action dispatch interface                                                                         |
| `execution/result/ExecutionResult.java`                 | Workflow execution outcome (Completed, Paused, Rejected, Failure)                                 |
| `workflow/Workflow.java`                                | Core data model                                                                                   |
| `workflow/WorkflowRepository.java`                      | Workflow definition persistence interface                                                         |
| `workflow/InMemoryWorkflowRepository.java`              | In-memory workflow repository (default)                                                           |
| `state/HensuState.java`                                 | Mutable workflow execution state; `branch(node)` creates isolated copies for concurrent branches  |
| `state/HensuSnapshot.java`                              | Immutable state snapshot for persistence                                                          |
| `state/ExecutionPhase.java`                             | Sealed: `Initial`, `Awaiting`, `Terminal` — tracks position within a node's lifecycle             |
| `state/WorkflowStateRepository.java`                    | Execution state persistence interface                                                             |
| `state/InMemoryWorkflowStateRepository.java`            | In-memory state repository (default)                                                              |
| `workflow/state/WorkflowStateSchema.java`               | Typed state variable schema (optional per-workflow declaration)                                   |
| `workflow/state/StateVariableDeclaration.java`          | Single variable declaration record (name, type, isInput)                                          |
| `workflow/state/VarType.java`                           | Variable type enum: STRING, NUMBER, BOOLEAN, LIST_STRING                                          |
| `workflow/transition/ApprovalTransition.java`           | Routes on the human reviewer's verdict when one exists, else on the `approved` engine variable    |
| `workflow/transition/ApprovalArms.java`                 | Reports which approval arms a rule list declares — SSOT for "can a verdict be routed here?"       |
| `workflow/transition/NoConsensusTransition.java`        | Routes when a parallel node fails to reach consensus                                              |
| `workflow/transition/BoundedTransition.java`            | Decorates a trigger with a per-node retry budget + escalation target (backs DSL `revise`)         |
| `workflow/transition/Condition.java`                    | Sealed value predicates (`Equals`, `NotEquals`, `Compare`) with coercion + `TYPE_MISMATCH`        |
| `workflow/transition/ConditionTransition.java`          | Routes on a declared output variable via a `Condition` predicate (backs DSL `onCondition`)        |
| `workflow/transition/AlwaysTransition.java`             | Else-arm of `onScore`/`onCondition`: routes every successful result to its target                 |
| `workflow/transition/TransitionRuleChecks.java`         | Shared rule-list validations (duplicate bounded namespaces, bounded-before-exit warning)          |
| `workflow/validation/SubWorkflowGraphValidator.java`    | Load-time cycle + dangling-reference detector for sub-workflow graphs                             |
| `workflow/validation/WorkflowValidator.java`            | Load-time validator for transition targets, `writes`, prompt `{variable}` references, review arms |
| `rubric/RubricEngine.java`                              | Quality evaluation engine                                                                         |
| `rubric/model/Rubric.java`                              | Rubric definition model                                                                           |
| `tool/ToolDefinition.java`                              | Protocol-agnostic tool descriptor                                                                 |
| `tool/ToolCallResult.java`                              | Tool execution result record (success/failure factories, `asText()`)                              |
| `tool/ToolProvider.java`                                | A runtime's tool source: catalog plus invocation                                                  |
| `tool/ToolInvoker.java`                                 | Invocation half of the seam, consumed by the tool loop                                            |
| `tool/ToolRouter.java`                                  | Composes providers; `ToolRegistry` + `ToolInvoker`, rejects duplicate tool names                  |
| `tool/ToolCallEvent.java`                               | Audit record for a dispatched tool request                                                        |
| `tool/ToolResultEvent.java`                             | Audit record for a settled tool invocation (output truncated)                                     |
| `tool/ToolRegistry.java`                                | Tool discovery interface                                                                          |
| `execution/EngineVariables.java`                        | SSOT for engine variable names (`score`, `approved`, `recommendation`)                            |
| `execution/SynchronizedListenerDecorator.java`          | Thread-safe listener wrapper for parallel branch execution                                        |
| `execution/executor/AgentLifecycleRunner.java`          | Composition-based agent call: prompt enrichment → agent execution → output extraction             |
| `execution/executor/ToolLoopRunner.java`                | Drives agent-native tool loop (budget enforcement, feed-back, audit events, sealed termination)   |
| `execution/parallel/BranchExecutionConfig.java`         | Typed branch metadata on `ExecutionContext` (consensus strategy, yields list)                     |
| `template/SimpleTemplateResolver.java`                  | `{variable}` substitution                                                                         |
| `review/ReviewHandler.java`                             | Human review interface                                                                            |
| `review/ReviewOutcome.java`                             | Sealed: `Decided(ReviewDecision)`, `Pending(correlationId)` — sync vs async review                |
| `review/ReviewVerdict.java`                             | A reviewer's decision and reason, recorded on state and read by `ApprovalTransition`              |
| `resume/ResumeInput.java`                               | Sealed: `ApplyReview`, `ApplyContextEdits`, `None` — caller-supplied resume input                 |
| `execution/pipeline/ProcessorPipeline.java`             | Orchestrates pre/post processor chains                                                            |
| `execution/pipeline/ProcessorContext.java`              | Per-iteration context carrier (node + result + execution context)                                 |
| `execution/pipeline/PreNodeExecutionProcessor.java`     | Pre-execution processor interface                                                                 |
| `execution/pipeline/PostNodeExecutionProcessor.java`    | Post-execution processor interface                                                                |
| `execution/pipeline/NodeExecutionProcessor.java`        | Base processor interface                                                                          |
| `execution/pipeline/OutputExtractionPostProcessor.java` | Extracts node output into state context                                                           |
| `execution/pipeline/HistoryPostProcessor.java`          | Records execution steps for audit/backtracking                                                    |
| `execution/pipeline/ReviewPostProcessor.java`           | Human-in-the-loop review checkpoints                                                              |
| `execution/pipeline/RubricPostProcessor.java`           | Quality evaluation and auto-backtrack                                                             |
| `execution/pipeline/ProcessorOutcome.java`              | Sealed: `Continue`, `Terminal`, `SuspendForExternal` — pipeline flow control                      |
| `execution/pipeline/TransitionPostProcessor.java`       | Evaluates transition rules, sets next node                                                        |
