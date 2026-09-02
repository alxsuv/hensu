# Hensu Server

Quarkus-based HTTP server for multi-tenant AI workflow execution with MCP (Model Context Protocol) integration.

## Overview

The `hensu-server` module extends `hensu-core` with:

- **REST API** for workflow definition management and execution
- **Multi-Tenant Isolation** using Java 25 ScopedValues
- **MCP Gateway** for external tool integration (server never executes locally)
- **Human-in-the-Loop** support with review workflows
- **SSE Streaming** for real-time execution monitoring

## Architecture

The server is a **GraalVM native image** that receives pre-compiled workflow JSON from the CLI.
It initializes core infrastructure via `HensuFactory.builder()` and delegates all tool execution
to external MCP servers.

```mermaid
flowchart TD
    subgraph server["hensu-server"]
        direction TB
        subgraph api["Interface Layer"]
            direction LR
            rest(["REST API\n(Workflows + Executions)"]) ~~~ mcpgw(["MCP Gateway\n(JSON-RPC)"])
        end
        subgraph runtime["Server Runtime"]
            direction LR
            sae(["ServerAction\nExecutor (MCP)"]) ~~~ tc(["TenantContext\n(ScopedValue)"])
        end
        subgraph core["hensu-core (HensuEnvironment)"]
            direction LR
            we(["WorkflowExecutor"]) ~~~ ar(["AgentRegistry"]) ~~~ re(["RubricEngine"])
            wr(["WorkflowRepository"]) ~~~ sr(["StateRepository"]) ~~~ tr(["ToolRouter"])
        end
        api --> runtime --> core
    end

    style server fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style api fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style runtime fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style core fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style rest fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style mcpgw fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style sae fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style tc fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style we fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style ar fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style re fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style wr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style sr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style tr fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

## Local Development

JWT authentication is required in all profiles — including dev mode. Each developer generates
their own RSA keypair; keys are gitignored and never committed.

See **[Local Development](../docs/developer-guide-server.md#local-development)** in the Server
Developer Guide for the full setup (keypair generation, docker-compose, token generation).

Quick reference:

```bash
# 1. Configure environment
cp .env.example .env  # fill in HENSU_DB_PASSWORD

# 2. Generate keypair
mkdir -p dev/keys
openssl genrsa -out dev/keys/privateKey.pem 2048
openssl rsa -in dev/keys/privateKey.pem -pubout -out dev/keys/publicKey.pem
# Then set HENSU_JWT_PUBLIC_KEY=file:/absolute/path/to/repo/dev/keys/publicKey.pem in .env

# 3. Start database
docker-compose up -d

# 4. Run server (Flyway migrations run automatically)
./gradlew :hensu-server:quarkusDev

# 5. Get a token (valid 1 hour)
TOKEN=$(bash dev/gen-jwt.sh)
```

---

## Quick Start

> Requires local development setup above. Use `$TOKEN` from step 5.

### Running the Server

```bash
# Development mode with hot reload
./gradlew :hensu-server:quarkusDev

# Production build
./gradlew :hensu-server:build
java -jar hensu-server/build/quarkus-app/quarkus-run.jar
```

### Push a Workflow (CLI → Server)

```bash
# Build (compile DSL to JSON) then push to server
hensu build workflow.kt -d working-dir
hensu push my-workflow

# Or directly via curl
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d @workflow.json

# Response (201 Created)
{"id": "order-processing", "version": "1.0.0", "created": true}
```

### Start an Execution

```bash
curl -X POST http://localhost:8080/api/v1/executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d '{"workflowId": "order-processing", "context": {"orderId": "ORD-456"}}'

# Response (202 Accepted)
{"executionId": "exec-abc-123", "workflowId": "order-processing"}
```

## REST API

All API and MCP endpoints require a valid JWT bearer token (`Authorization: Bearer <jwt>`).
Tenant identity is extracted from the `tenant_id` claim. JWT is required in every profile
except `inmem` (the integration-test profile), where authentication is disabled and a default
tenant is used (see [Local Development](#local-development)).

### Workflow Definition Management

Terraform/kubectl-style operations for managing workflow definitions (CLI integration).

| Method   | Path                             | Description                                                   |
|----------|----------------------------------|---------------------------------------------------------------|
| `POST`   | `/api/v1/workflows`              | Push workflow (create or update; validates sub-workflow refs) |
| `GET`    | `/api/v1/workflows`              | List all workflows for tenant                                 |
| `GET`    | `/api/v1/workflows/{workflowId}` | Pull workflow definition                                      |
| `DELETE` | `/api/v1/workflows/{workflowId}` | Delete workflow                                               |

Push is serialized cluster-wide by `WorkflowPushLock` (pg_advisory_xact_lock with JVM
fallback) and runs `SubWorkflowGraphValidator` over the forward-reachable sub-workflow
graph — rejecting cycles and dangling `target` references before any row is written.
See [Sub-Workflows](#sub-workflows) below.

### Execution Operations

Runtime operations for starting and managing workflow executions (client integration).

| Method | Path                                        | Description                                        |
|--------|---------------------------------------------|----------------------------------------------------|
| `POST` | `/api/v1/executions`                        | Start workflow execution                           |
| `GET`  | `/api/v1/executions/{executionId}`          | Get execution status                               |
| `GET`  | `/api/v1/executions/{executionId}/events`   | Subscribe to execution events (SSE stream)         |
| `POST` | `/api/v1/executions/{executionId}/resume`   | Resume paused execution                            |
| `GET`  | `/api/v1/executions/{executionId}/result`   | Get final output (public context, `_`-keys hidden) |
| `GET`  | `/api/v1/executions/paused`                 | List paused executions                             |

### MCP Gateway (SSE Split-Pipe Transport)

Implements MCP (Model Context Protocol) over SSE using a "split-pipe" architecture:

- **Downstream (SSE)**: the Hensu server pushes JSON-RPC tool call requests to connected tenant clients
- **Upstream (HTTP POST)**: tenant clients relay each request to their own MCP servers and post the JSON-RPC response back (requests time out after 60 s if no response is received — see `McpSessionManager.DEFAULT_TIMEOUT`)

```mermaid
flowchart LR
    subgraph server["Hensu Server"]
        direction TB
        send(["sendRequest()"])
        handle(["handleResponse\n(Future.done)"])
    end

    subgraph client["Tenant Client"]
        direction TB
        es(["EventSource"])
        post(["POST /message"])
    end

    send -->|"SSE (tools/call)"| es
    post -->|"POST (result/error)"| handle

    style server fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style client fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style send fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style handle fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style es fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style post fill:#2c2c2e, stroke:#0A84FF, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

| Method | Path                             | Description                                          |
|--------|----------------------------------|------------------------------------------------------|
| `GET`  | `/mcp/connect?clientId=...`      | SSE stream for receiving tool call requests          |
| `POST` | `/mcp/message`                   | Submit JSON-RPC responses                            |
| `GET`  | `/mcp/status`                    | Gateway status (connected clients, pending requests) |
| `GET`  | `/mcp/clients/{clientId}/status` | Status of a specific MCP client connection           |

### Event Streaming (SSE)

| Method | Path                                      | Description                      |
|--------|-------------------------------------------|----------------------------------|
| `GET`  | `/api/v1/executions/{executionId}/events` | SSE stream for execution events  |
| `GET`  | `/api/v1/executions/events`               | SSE stream for all tenant events |

### Input Validation & Error Responses

All identifiers in path and query parameters (`workflowId`, `executionId`, `clientId`) are
validated with the `@ValidId` constraint: alphanumeric start, followed by alphanumeric characters,
dots, hyphens, or underscores (1–255 chars). Workflow request bodies are deep-validated by
`@ValidWorkflow`, which walks the entire object graph to ensure all embedded identifiers are
well-formed and free-text fields (prompts, instructions, rubric content) are free of dangerous
control characters. `LogSanitizer` provides defense-in-depth by stripping CR/LF from user-derived
values at log call sites.

Invalid input returns `400 Bad Request` with a JSON error body:

```json
{"error": "workflowId: must be a valid identifier (alphanumeric, dots, hyphens, underscores; 1-255 chars)", "status": 400}
```

See the [Server Developer Guide — Input Validation](../docs/developer-guide-server.md#input-validation) for details.

### Execution Event Types

- `execution.started` - Execution began
- `execution.paused` - Awaiting human review
- `execution.completed` - Workflow finished
- `execution.error` - Error occurred

## Key Components

### Server Initialization

The server initializes core infrastructure via CDI:

1. `HensuEnvironmentProducer` creates `HensuEnvironment` via `HensuFactory.builder()`
2. `ServerConfiguration` delegates core components for CDI injection
3. `ServerActionExecutor` routes `Action.Send` to registered handlers (MCP and others) and rejects `Action.Execute` (local command execution). Agent tool calls no longer take this path — they go through the `ToolRouter`

See [Server Developer Guide](../docs/developer-guide-server.md) for implementation details.

### Tenant Context

Thread-safe tenant isolation using Java 25 ScopedValues:

```java
TenantInfo tenant = TenantInfo.withMcp("tenant-123", "http://mcp.local:8080");
TenantContext.runAs(tenant, () -> {
    // All code in this scope has tenant context
    TenantInfo current = TenantContext.current();
    // MCP calls, DB queries, etc. are tenant-scoped
});
```

### Persistence

Repository interfaces and in-memory defaults live in `hensu-core`. The server provides PostgreSQL
implementations via plain JDBC + Flyway. `HensuEnvironmentProducer` conditionally wires JDBC repos
when a DataSource is available, otherwise falls back to in-memory. Repositories are delegated from
`HensuEnvironment` via `@Produces @Singleton` — never created directly in CDI producers.

- `WorkflowRepository` (`io.hensu.core.workflow`) — Workflow definition storage
- `WorkflowStateRepository` (`io.hensu.core.state`) — Execution state snapshots (checkpoint/pause/resume)
- `JdbcWorkflowRepository` / `JdbcWorkflowStateRepository` — PostgreSQL implementations (JSONB columns)
- **Checkpoint hook**: `WorkflowExecutor` calls `listener.onCheckpoint(state)` before each node execution,
  enabling inter-node state persistence for failover recovery
- **Distributed recovery**: `ExecutionLeaseManager` maintains a `server_node_id` / `last_heartbeat_at`
  lease per active execution; `ExecutionHeartbeatJob` renews leases every 30 s (configurable);
  `WorkflowRecoveryJob` sweeps for stale leases and resumes orphaned executions on a surviving node —
  see [Server Developer Guide — Distributed Recovery](../docs/developer-guide-server.md#distributed-recovery-leasing)
- **`inmem` profile**: `quarkus.datasource.active=false` disables PostgreSQL; `quarkus.scheduler.enabled=false`
  disables all lease jobs for in-memory-only operation

### Sub-Workflows

`SubWorkflowNode` lets a parent workflow delegate to another workflow by id. The server
adds three guarantees on top of core execution:

- **Push-time validation** (`WorkflowRegistryService` → `SubWorkflowGraphValidator`): a single
  DFS walks the forward-reachable sub-workflow graph from the incoming workflow, rejecting
  cycles and dangling `target` references. Unresolved ids are looked up lazily via
  `WorkflowRepository.findById(tenant, id)`, so validation always reflects the post-push
  graph without an intermediate write.
- **Cluster-wide serialization** (`WorkflowPushLock`): push acquires `pg_advisory_xact_lock`
  (JVM-lock fallback when the datasource is inactive) so two concurrent nodes cannot each
  observe a clean graph and together introduce a cycle.
- **Runtime isolation**: `_tenant_id` is propagated into the child context and child lookup
  uses the tenant-scoped `WorkflowRepository`, so a workflow can never resolve a child from
  a different tenant. Recursion depth is bounded by `SubWorkflowNodeExecutor.MAX_DEPTH = 16`
  (tracked via `_sub_workflow_depth`).

See [Sub-Workflow Validation on Push](../docs/developer-guide-server.md#sub-workflow-validation-on-push)
in the Server Developer Guide for the push pipeline.

## Configuration

### application.properties

```properties
# HTTP Server
quarkus.http.port=8080
quarkus.http.host=0.0.0.0
# MCP Configuration
hensu.mcp.connection-timeout=30s
hensu.mcp.read-timeout=60s
hensu.mcp.pool-size=10
# PostgreSQL
# Dev: docker-compose (set HENSU_DB_USER, HENSU_DB_PASSWORD, HENSU_DB_NAME in .env)
# Prod: set HENSU_DB_URL, HENSU_DB_USER, HENSU_DB_PASSWORD as environment variables
quarkus.datasource.db-kind=postgresql
%dev.quarkus.datasource.devservices.enabled=false
%dev.quarkus.datasource.username=${HENSU_DB_USER}
%dev.quarkus.datasource.password=${HENSU_DB_PASSWORD}
%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/${HENSU_DB_NAME:hensu}
%prod.quarkus.datasource.username=${HENSU_DB_USER}
%prod.quarkus.datasource.password=${HENSU_DB_PASSWORD}
%prod.quarkus.datasource.jdbc.url=${HENSU_DB_URL}
# Flyway — migrates runtime schema on startup
quarkus.flyway.migrate-at-start=true
quarkus.flyway.schemas=runtime
# In-memory profile: for integration tests only (no PostgreSQL, no leasing, auth disabled)
%inmem.quarkus.datasource.active=false
%inmem.quarkus.datasource.devservices.enabled=false
%inmem.quarkus.flyway.migrate-at-start=false
%inmem.quarkus.scheduler.enabled=false
# Credentials — loaded by HensuEnvironmentProducer from hensu.credentials.* prefix
# hensu.credentials.ANTHROPIC_API_KEY=sk-ant-...
# hensu.credentials.GOOGLE_API_KEY=AIza...
# Default tenant (used when JWT tenant_id claim is absent)
# hensu.tenant.default=default
# Verbose execution logging (enables LoggingExecutionListener)
hensu.verbose.enabled=false
# Distributed recovery leasing
hensu.lease.heartbeat-interval=30s
hensu.lease.recovery-interval=60s
hensu.lease.stale-threshold=90s
```

## Module Structure

```
hensu-server/
├── src/main/java/io/hensu/server/
│   ├── action/                            # Server-specific action execution
│   │   └── ServerActionExecutor.java      # Send-action dispatcher (rejects local execution)
│   ├── api/                               # REST and SSE endpoints
│   │   ├── WorkflowResource.java          # Workflow definition management
│   │   ├── ExecutionResource.java         # Execution runtime operations
│   │   ├── ExecutionEventResource.java    # SSE endpoint for execution events
│   │   ├── McpGatewayResource.java        # MCP SSE/POST endpoints
│   │   ├── ExecutionStartRequest.java     # Request DTO for POST /executions
│   │   ├── ResumeRequest.java             # Request DTO for POST /executions/{id}/resume
│   │   ├── ResumeResponse.java            # Response DTO for POST /executions/{id}/resume
│   │   ├── PushWorkflowResponse.java      # Response DTO for POST /workflows
│   │   ├── WorkflowSummary.java           # Response DTO for GET /workflows list entries
│   │   ├── GatewayStatusResponse.java     # Response DTO for GET /mcp/status
│   │   └── ClientStatusResponse.java      # Response DTO for GET /mcp/clients/{id}/status
│   ├── validation/                        # Input validation (Bean Validation)
│   │   ├── InputValidator                  # Shared validation predicates (safe-ID, dangerous chars, size)
│   │   ├── ValidId.java                    # Custom identifier constraint
│   │   ├── ValidIdValidator.java           # Regex-based validator
│   │   ├── ValidMessage                    # Custom constraint for raw message body strings
│   │   ├── ValidMessageValidator     # Size-limit + control-character validator
│   │   ├── ValidWorkflow.java              # Custom constraint for Workflow bodies
│   │   ├── ValidWorkflowValidator.java     # Deep-validates workflow object graph
│   │   ├── IllegalArgumentExceptionMapper.java      # Maps IllegalArgumentException → 400
│   │   └── ConstraintViolationExceptionMapper.java  # Global 400 error mapper
│   ├── config/                            # CDI configuration
│   │   ├── HensuEnvironmentProducer.java          # HensuFactory → HensuEnvironment
│   │   ├── CoreModelNativeConfig.java                 # @RegisterForReflection — Hensu domain model (mixin/builder, treeToValue, records)
│   │   ├── LangChain4jNativeConfig.java           # @RegisterForReflection — JDK HTTP transport (ServiceLoader)
│   │   ├── LangChain4jAnthropicNativeConfig.java  # @RegisterForReflection — Anthropic API request/response DTOs
│   │   ├── LangChain4jGeminiNativeConfig.java     # @RegisterForReflection — Google AI Gemini API request/response DTOs
│   │   ├── ExecutionEventNativeConfig.java        # @RegisterForReflection — SSE event sealed subtypes
│   │   ├── ServerBootstrap.java                   # Startup registrations
│   │   └── ServerConfiguration.java               # CDI delegation + server beans
│   ├── execution/                         # Server-side execution listeners
│   │   ├── LoggingExecutionListener.java  # Structured log output for node lifecycle events + transition warnings
│   │   └── CompositeExecutionListener.java # Combines multiple ExecutionListeners
│   ├── dev/                               # Dev-only handlers (excluded from prod image)
│   │   └── SleepHandler.java              # Simulates long-running node for crash-recovery tests
│   ├── mcp/                               # MCP integration (SSE split-pipe transport)
│   │   ├── JsonRpc.java
│   │   ├── McpConnection.java
│   │   ├── McpConnectionFactory.java
│   │   ├── McpConnectionPool.java
│   │   ├── McpException.java
│   │   ├── McpSessionManager.java
│   │   ├── McpSidecar.java                # ActionHandler dispatching to MCP tools
│   │   ├── McpToolDiscovery.java          # Runtime tool schema discovery + cache
│   │   ├── SseMcpConnection.java
│   │   ├── TenantToolProvider.java        # Temporary bridge exposing the tenant registry as a ToolProvider
│   │   └── TenantToolRegistry.java        # Merges base + tenant MCP tools (MCP precedence)
│   ├── security/                          # JWT + tenant resolution + error mapping
│   │   ├── GlobalExceptionMapper.java     # Global @Provider — normalizes errors to JSON
│   │   └── RequestTenantResolver.java     # Extracts tenant_id claim from JWT
│   ├── persistence/                       # PostgreSQL persistence (plain JDBC)
│   │   ├── JdbcWorkflowRepository.java        # Workflow definitions (JSONB)
│   │   ├── JdbcWorkflowStateRepository.java   # Execution state snapshots (JSONB + lease columns)
│   │   ├── ExecutionLeaseManager.java         # Distributed lease management (@ApplicationScoped)
│   │   ├── WorkflowPushLock.java              # Cluster-wide push mutex (pg_advisory_xact_lock + JVM fallback)
│   │   ├── JdbcSupport.java                   # JDBC helper (queryList, update)
│   │   └── PersistenceException.java          # Unchecked wrapper for SQLException
│   ├── workflow/              # Business logic
│   │   ├── WorkflowService.java                 # Facade over registry + execution + query services
│   │   ├── WorkflowRegistryService.java         # Push pipeline: WorkflowPushLock + SubWorkflowGraphValidator
│   │   ├── WorkflowExecutionService.java        # Start/resume orchestration
│   │   ├── ExecutionQueryService.java           # Read-side: status, plan, output, paused list
│   │   ├── ExecutionStateService.java           # Snapshot load/save with split-brain guard
│   │   ├── ExecutionResultHandler.java          # Shared ExecutionResult → snapshot + SSE dispatch
│   │   ├── WorkflowContextUtil.java             # Filters internal (_-prefixed) keys from context
│   │   ├── ExecutionHeartbeatJob.java           # Periodic heartbeat emission (@Scheduled)
│   │   ├── WorkflowRecoveryJob.java             # Orphaned execution sweeper (@Scheduled)
│   │   ├── ExecutionStartResult.java            # DTO
│   │   ├── ExecutionOutput.java                 # DTO
│   │   ├── ExecutionStatus.java                 # DTO for execution status (with correlationId)
│   │   ├── ExecutionSummary.java                # DTO for paused-list (with correlationId)
│   │   ├── ExecutionNotFoundException.java
│   │   ├── WorkflowNotFoundException.java
│   │   └── WorkflowExecutionException.java
│   ├── review/                # Server-side review handling
│   │   └── InteractiveReviewHandler.java  # @ApplicationScoped default ReviewHandler for workflow reviews
│   ├── streaming/             # SSE event streaming
│   │   ├── ExecutionEvent.java
│   │   └── ExecutionEventBroadcaster.java
│   └── tenant/                # Multi-tenancy
│       └── TenantContext.java                   # ScopedValue-based tenant context
└── src/test/java/
    └── io/hensu/server/
        ├── action/
        ├── api/
        ├── integration/       # Full-stack tests via IntegrationTestBase (inmem profile)
        ├── mcp/
        ├── persistence/       # JDBC repo tests via Testcontainers PostgreSQL
        ├── streaming/
        ├── validation/
        └── workflow/
```

## Testing

```bash
# Run all tests
./gradlew :hensu-server:test

# Run specific test class
./gradlew :hensu-server:test --tests "*.WorkflowExecutionServiceTest"

# Run integration tests (inmem profile, no Docker required)
./gradlew :hensu-server:test --tests "*.integration.*"

# Run JDBC repo tests (requires Docker for Testcontainers PostgreSQL)
./gradlew :hensu-server:test --tests "*.persistence.*"
```

## Native Image

`hensu-server` ships as a GraalVM native image built by Quarkus. Because `hensu-core` carries no
serialization metadata, every reflective access Jackson needs is registered explicitly in the
`config/*NativeConfig` classes — never in the core or DSL modules.

```bash
# Native build (requires GraalVM JDK 25+ with native-image; takes several minutes)
./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native

# Run the binary (inmem profile needs no PostgreSQL)
QUARKUS_PROFILE=inmem ./hensu-server/build/hensu-server-*-runner
```

For day-to-day work use JVM mode (`./gradlew :hensu-server:quarkusDev`) and reserve native builds
for release verification. See the [Server Developer Guide — GraalVM Native Image](../docs/developer-guide-server.md#graalvm-native-image)
for the reflection-registration patterns, the `CoreModelNativeConfig` decision ladder, and resource
bundling rules.

## Dependencies

- **hensu-core**: Core workflow engine
- **hensu-serialization**: Jackson-based JSON serialization (provides `ObjectMapper` via
  `WorkflowSerializer.createMapper()`)
- **hensu-langchain4j-adapter**: LLM provider integration
- **Quarkus REST**: JAX-RS implementation
- **Quarkus Arc**: CDI container
- **Quarkus Scheduler**: Background tasks
- **Quarkus JDBC PostgreSQL**: PostgreSQL connection pooling (Agroal)
- **Quarkus Flyway**: Schema migration management

## See Also

- [hensu-core README](../hensu-core/README.md) - Core engine documentation
- [Unified Architecture](../docs/unified-architecture.md) - Architecture decisions and vision
- [DSL Reference](../docs/dsl-reference.md) - Workflow DSL syntax
- [Developer Guide](../docs/developer-guide-server.md) - Server development patterns
- [Spring Reference Client](../integrations/spring-reference-client/README.md) - Example Spring Boot integration
