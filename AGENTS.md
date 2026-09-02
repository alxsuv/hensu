# AGENTS.md: Hensu™ Project Operational Manual

Single Source of Truth for all AI coding agents. You MUST read this before proposing changes or executing commands.

Guides live in [docs/](docs/) — read on demand (core/server developer guides, DSL reference, architecture, javadoc standards).

## Build

Standard Gradle wrapper (`./gradlew build`, `./gradlew test`, `./gradlew <module>:test --tests "FooTest"`, `./gradlew hensu-server:quarkusDev`).

Modules: `hensu-core`, `hensu-dsl`, `hensu-serialization`, `hensu-cli`, `hensu-server`, `hensu-langchain4j-adapter`.
Dependency flow: `cli → dsl → core`, `cli → serialization → core`, `server → serialization → core`, `langchain4j-adapter → core`.

## Architecture

Hensu is a modular AI workflow engine on Java 25 + Kotlin DSL. Core design principle: **zero external dependencies in `hensu-core`** — all AI provider integrations go through the `AgentProvider` interface, wired explicitly via `HensuFactory.builder().agentProviders(...)`. No classpath scanning, GraalVM-native-safe.

## Patterns & Conventions

1. **Builder pattern** for all domain models: `Workflow.builder().id(...).build()`
2. **Constructor injection** — no `@Autowired`, explicit dependency wiring
3. **Sealed interfaces** for results: `ExecutionResult` → `Completed | Paused | Rejected | Failure | Success`
4. **Template resolution**: `{variable}` syntax in prompts, resolved via `SimpleTemplateResolver`
5. **`@DslMarker`** on Kotlin builders to prevent scope leakage
6. **Engine variables** (`score`, `approved`, `recommendation`): engine-managed — `writes()` rejects them at build time and again at load time, and declaring one in `state{}` is redundant. Surfaced to the next node by the `EngineVariablePromptEnricher` injectors (`FeedbackContextInjector` appends prior feedback as a `### Previous Feedback` section). A backtracking `revise` arm preserves them; a plain forward `goto` clears them unless marked `withFeedback`.
7. **Agent tool loop**: agents implementing `ToolCapable` open a call-scoped `ToolSession` and execute tools natively via `ToolLoopRunner` — the sealed `AgentResponse` hierarchy (`TextResponse`/`ToolRequest`/`Error`) controls flow. Budget enforced by `AgentConfig.maxToolCalls` (default 10, counting executed calls). Tools reach the loop through the `ToolProvider`/`ToolRouter` seam, never through `ActionExecutor`, and are invoked via the context's `ToolInvoker`; every request and outcome is reported to the `ExecutionListener` (`onToolCall`/`onToolResult`), including hallucinated names and budget rejections.

## Key Architectural Rules

1. **HensuFactory pattern**: ALWAYS use `HensuFactory.builder()` — never construct core components directly.
2. **Client-side compilation**: CLI compiles Kotlin DSL → JSON; server receives pre-compiled JSON (no Kotlin compiler in native image).
3. **Build-then-push**: `hensu build` compiles to `{working-dir}/build/`; `hensu push` reads compiled JSON (no recompilation).
4. **Shared serialization**: CLI and server both use `hensu-serialization`; `WorkflowSerializer.createMapper()` is the single `ObjectMapper` factory.
5. **Server MCP-only**: server never executes bash locally, only MCP requests to external tools.
6. **Storage in core**: repository interfaces and in-memory defaults live in `hensu-core`. JDBC impls live in `hensu-server/persistence/` as plain classes (not CDI beans). `HensuEnvironmentProducer` conditionally wires JDBC vs in-memory. Server exposes core components via `@Produces @Singleton` — never instantiates directly.
7. **API separation**: `/api/v1/workflows` (definitions) and `/api/v1/executions` (runtime) are distinct resources.
8. **JWT authentication**: SmallRye JWT bearer auth. Tenant identity extracted from `tenant_id` claim via `RequestTenantResolver`. CLI sends `Authorization: Bearer <token>` via `--token` or `hensu.server.token` config. JWT is required in every profile **except `inmem`** (integration tests), which disables auth and uses `hensu.tenant.default`. RSA keys live outside the repo (e.g. `~/.hensu/`).
9. **Nodes do work; transitions route.** Control-flow capabilities (iteration, exit conditions, budgets, feedback) are expressed in the `TransitionRule` sealed hierarchy — never as node types, node flags, or executor forks on node configuration. A request shaped like "this node needs to loop/branch/retry/converge" is answered with a new or extended transition rule wired through `requiredRoutingVars()`. `LoopNode` and the plan subsystem were removed for violating this. Do not reintroduce the pattern.
10. **A human verdict is not an engine variable.** Engine variables record what the *agent* said about its own output; a reviewer's decision is a different claim and travels on its own channel — a `ReviewVerdict` set on `HensuState` by `ReviewPostProcessor`, which writes no engine variable at all. `ApprovalTransition` gives the verdict precedence over `approved` when routing. This keeps `approved` to two writers with one meaning (output extraction, consensus) and makes "the reviewer decides" a property of the rule that reads the values rather than of the order in which processors run. Do not resolve a precedence question by having one processor overwrite another's variable.

## CLI

Verbs: `run | validate | visualize | build | push | pull | delete | list`. Use `-h` for args. `build` compiles `.kt` → JSON in `{working-dir}/build/`; `push` reads compiled JSON by workflow ID (not the `.kt`).

Example workflows: `working-dir/workflows/*.kt`.

## Environment Variables

Provider credentials loaded via `HensuFactory.loadCredentialsFromEnvironment()`: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `AZURE_OPENAI_KEY`.

## Testing

- JUnit 5 + AssertJ + Mockito. Stub mode: `HENSU_STUB_ENABLED=true`. Core testable in isolation (no AI deps).
- **Integration tests** (`hensu-server`): extend `IntegrationTestBase`, run under `@QuarkusTest` with `@TestProfile(InMemoryTestProfile.class)`. The `inmem` profile disables PostgreSQL (no Docker). Base class provides `loadWorkflow`, `registerStub`, `pushAndExecute`, `pushAndExecuteWithMcp`, `resolveRubricPath`. Per-test cleanup clears `StubResponseRegistry` and deletes tenant data (execution states first, FK constraint).
- **Test handlers** (auto-discovered `@ApplicationScoped`): `TestActionHandler`, `TestReviewHandler`, `TestPauseHandler`, `TestValidatorHandler`.
- **Stub resolution order**: programmatic → `/stubs/{scenario}/{nodeId}.txt` → `/stubs/default/{nodeId}.txt` → echo fallback.
- **Repository tests** (`io.hensu.server.persistence`): plain JUnit 5 + Testcontainers PostgreSQL (no Quarkus). `JdbcRepositoryTestBase` starts container, runs Flyway, provides `DataSource`.
