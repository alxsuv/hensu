# Hensu CLI

Local workflow engine for Hensu — executes, validates, and manages AI workflows on your machine,
then pushes them to a remote `hensu-server` when ready.

Acts like a local Docker Engine: a background **daemon** keeps the JVM and Kotlin compiler warm so
successive `hensu run` calls start in milliseconds. Detach from a running workflow with `Ctrl+C`
and re-attach at any time without interrupting execution.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)
- [Local Workflow Commands](#local-workflow-commands)
  - [`hensu run`](#hensu-run)
  - [`hensu validate`](#hensu-validate)
  - [`hensu visualize`](#hensu-visualize)
  - [`hensu build`](#hensu-build)
  - [Sub-Workflows](#sub-workflows)
- [Daemon Commands](#daemon-commands)
  - [`hensu daemon`](#hensu-daemon)
  - [`hensu ps`](#hensu-ps)
  - [`hensu attach`](#hensu-attach)
  - [`hensu cancel`](#hensu-cancel)
  - [Detach (Ctrl+C)](#detach-ctrlc)
- [Credentials Commands](#credentials-commands)
  - [`hensu credentials set`](#hensu-credentials-set)
  - [`hensu credentials list`](#hensu-credentials-list)
  - [`hensu credentials unset`](#hensu-credentials-unset)
- [Server Commands](#server-commands)
  - [`hensu push`](#hensu-push)
  - [`hensu pull`](#hensu-pull)
  - [`hensu delete`](#hensu-delete)
  - [`hensu list`](#hensu-list)
- [Typical Workflows](#typical-workflows)
- [Project Layout](#project-layout)
- [Configuration](#configuration)
- [Development](#development)
- [Testing — Stub Agent System](#testing--stub-agent-system)
- [Mid-Workflow Actions](#mid-workflow-actions)
- [Exit Codes](#exit-codes)
- [Dependencies](#dependencies)

## Installation

**Requires Java 25+ with preview features.** The install scripts pass `--enable-preview`
automatically. If you launch manually via `java -jar`, you must include `--enable-preview`
(required for `StructuredTaskScope`).

```bash
# Install latest release (recommended)
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/install.sh | bash

# Custom prefix
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/install.sh | bash -s -- --prefix /usr/local

# Skip service installation (systemd on Linux, launchd on macOS)
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/install.sh | bash -s -- --no-service

# Update to latest release
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/update.sh | bash

# Pin to a specific version
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/update.sh | bash -s -- --version cli/v0.9.0-beta.1

# Uninstall
bash <(curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/remove.sh)
bash <(curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/remove.sh) --purge   # also removes ~/.hensu/ data
```

All releases and assets: [github.com/hensu-project/hensu/releases](https://github.com/hensu-project/hensu/releases)

> **From source:** clone the repo and run `bash hensu-cli/scripts/install.sh`. See `--help` for all options.

## Usage

```
usage: hensu <command> [<args>]

Local workflow commands
   run        Execute a workflow (daemon-aware; inline fallback)
   validate   Validate workflow syntax and detect unreachable nodes
   visualize  Render workflow graph as text or Mermaid diagram
   build      Compile Kotlin DSL to JSON  →  {working-dir}/build/

Daemon commands  (local execution engine, analogous to Docker Engine)
   daemon     Manage the background daemon  (start | stop | status)
   ps         List workflow executions tracked by the daemon
   attach     Stream output from a running or completed execution
   cancel     Cancel a running execution

Credentials commands
   credentials  set, list, or unset API keys in ~/.hensu/credentials

Server commands  (remote hensu-server)
   push       Push compiled workflow JSON to the server
   pull       Pull a workflow definition from the server
   delete     Delete a workflow from the server
   list       List all workflows on the server
```

---

## Local Workflow Commands

All local workflow commands accept:

```
-d, --working-dir <path>   Working directory containing workflows/, prompts/, and rubrics/
                           (default: . | override: hensu.working.dir in application.properties)
```

### `hensu run`

Execute a workflow. Automatically delegates to the daemon when running; falls back to inline
execution when the daemon is not available.

```
hensu run [<workflow-name>] [-d <working-dir>]
          [-v] [-i] [--no-color] [--no-daemon] [-c <context>]
          [--with <sub-workflow>]...

options:
  <workflow-name>          Workflow name from workflows/ (default: hensu.workflow.file property)
  -d, --working-dir        Working directory
  -c, --context <value>    Context as a JSON string  '{"key":"value"}'  or path to a JSON/YAML file
  -v, --verbose            Show agent inputs/outputs and fork/join execution structure
  -i, --interactive        Enable interactive human review mode with manual backtracking
      --with <name>        Sub-workflow to load alongside the root (repeatable); see Sub-Workflows below
      --no-color           Disable ANSI colored output
      --no-daemon          Force inline execution even if the daemon is running
```

**Ctrl+C behavior:** pressing Ctrl+C *detaches* the client – execution keeps running in the daemon.
A JVM shutdown hook sends a `detach` frame, then prints the execution ID and re-attach instructions.

**Capacity limit:** if the daemon is already running the maximum number of concurrent
executions, `run` receives a `daemon_full` frame and exits with a warning. Retry shortly or
increase the limit.

**Context seeding:** `-c` context is merged with an automatic `_tenant_id` (set to
`SubWorkflowLoader.CLI_TENANT`) so sub-workflow nodes resolve children from the same
repository slot the loader populated.

**Interactive review (`-i`):** When a workflow node declares a review gate — `review(ReviewMode.REQUIRED)`
or a `review { mode = ReviewMode.REQUIRED }` block — the daemon pauses
execution and sends a `review_request` frame to the attached client. The terminal displays the
node output and prompts for a decision:

- **Approve** — accept the output and continue to the next node.
- **Reject** — reject the output. The decision is routed like any other: on a node declaring
  `onRejection`, the workflow continues to that target; on a node without one, the execution ends.
- **Backtrack** — jump back to a previous node. You can optionally edit the prompt context in
  `$EDITOR` (via `ContextEditor`) before the node re-executes.

If no client is attached when a review is requested, the execution enters `AWAITING_REVIEW` and
blocks (cheaply, on a virtual thread) until a client runs `hensu attach <exec-id>` and submits
the decision. A 30-minute fallback timeout applies.

### `hensu validate`

Parse the workflow and perform static analysis: syntax errors, missing node references,
unreachable nodes, and – when `--with` supplies sub-workflows – cycles in the sub-workflow
reference graph.

```
hensu validate [<workflow-name>] [-d <working-dir>] [--with <sub-workflow>]...
```

### `hensu visualize`

Render the workflow graph to the terminal.

```
hensu visualize [<workflow-name>] [-d <working-dir>] [--format <fmt>] [--with <sub-workflow>]...

options:
  --format <text|mermaid>  Output format  (default: text)
  --with <name>            Sub-workflow to include in the rendered graph (repeatable)
```

### `hensu build`

Compile the Kotlin DSL to JSON. The Kotlin compiler runs client-side – the server cannot
execute it – so every workflow must pass through `hensu build` before `hensu push`.
Output is written to `{working-dir}/build/{workflow-id}.json`.

```
hensu build [<workflow-name>] [-d <working-dir>]
```

`build` does not accept `--with`. Sub-workflows are compiled and pushed independently;
the server resolves references at runtime via its own registry and rejects dangling refs
at push time. If a root workflow contains `subWorkflow` nodes, each referenced child must
be built and pushed separately or the server will reject the push due to dangling references.

---

## Sub-Workflows

A workflow can delegate to another workflow via a `subWorkflow` node. For commands that
execute or analyze the graph locally – `run`, `validate`, `visualize` – referenced children
must be supplied explicitly with `--with`. The flag is repeatable.

```bash
hensu run parent --with child-a --with child-b -d ./my-project
```

Under the hood:

- Each `--with <name>` resolves to `workflows/<name>.kt` in the working directory.
- The root plus all `--with` workflows are registered under the CLI tenant
  (`SubWorkflowLoader.CLI_TENANT`) so `SubWorkflowNodeExecutor` can resolve children at runtime.
- When the daemon handles the execution, the full set is serialized and shipped across the
  wire – the daemon JVM does not share your working directory.
- `validate` additionally runs `SubWorkflowGraphValidator` over the supplied set, rejecting
  cycles before execution.

`build` and `push` do not use `--with` – each workflow is compiled and pushed independently,
and the server validates the full reference graph (cycles + dangling refs) at push time.

---

## Daemon Commands

The daemon is an optional background process that eliminates JVM cold-start on every `hensu run`.
It mirrors the role `hensu-server` plays for remote deployments.

### `hensu daemon`

```
hensu daemon <subcommand>

subcommands:
  start    Start the daemon in the background; waits up to 10 s for the socket (WatchService + poll)
  stop     Graceful shutdown (finishes in-flight executions, then exits)
  status   Show socket path, running execution count, and total tracked executions
```

`start` delegates to the platform service manager when installed (systemd on Linux, launchd
on macOS), falling back to a detached background child process. After launching, a
`WatchService` + `DaemonClient.isAlive()` poll loop waits up to 10 seconds for the Unix
socket to appear before returning.

### `hensu ps`

List all executions tracked by the daemon (running + recently completed).

```
hensu ps
```

Example output:

```
         ID                                    WORKFLOW                  STATUS        NODE              ELAPSED
  ——————————————————————————————————————————————————————————————————————————————————————————————————————————————
  ●  3f2a1c9e-…-4b1a                          my-workflow               RUNNING           summarize         12s
  ●  8c3f2b1a-…-99dd                          review-workflow           AWAITING_REVIEW   human-gate        2m 10s
  ●  7b9d4efa-…-22cc                          other-workflow            COMPLETED         —                 1m 5s
```

### `hensu attach`

Replay buffered output from the ring buffer, then stream live output until the execution
completes or you press Ctrl+C (which detaches without stopping the execution).

If the execution is in `AWAITING_REVIEW`, attaching immediately prompts you with the
pending review decision (Approve / Reject / Backtrack). The execution resumes once you
respond — no separate `resume` command is needed.

```
hensu attach <exec-id>

arguments:
  <exec-id>   Execution ID from `hensu ps`
```

### `hensu cancel`

Send a cancellation signal to a running execution in the daemon.

```
hensu cancel <exec-id>

arguments:
  <exec-id>   Execution ID to cancel  (from `hensu ps`)
```

### Detach (Ctrl+C)

There is no `hensu detach` command. Detach is triggered by pressing **Ctrl+C** inside
`hensu run` or `hensu attach`. Both commands install a JVM shutdown hook that sends a
`detach` frame to the daemon – the execution keeps running; only the client terminal
disconnects.

```
Ctrl+C during `hensu run`     →  shutdown hook fires → detach frame → prints exec ID and re-attach hint
Ctrl+C during `hensu attach`  →  same
```

The daemon itself does not handle SIGINT – the detach logic lives entirely in the client.

---

## Credentials Commands

API keys are stored in `~/.hensu/credentials` — one `KEY=VALUE` per line, `#` for
comments. The file is read at startup by both the daemon and direct CLI runs, so it
works regardless of how the process was launched. The installer creates it with
commented-out examples on first install.

```
# ~/.hensu/credentials
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=AIza...
OPENAI_API_KEY=sk-...
```

You can also export the equivalent environment variables (`ANTHROPIC_API_KEY`, etc.)
instead of using the file.

The commands below are the recommended way to manage the file: values are read via a
masked prompt so they never enter shell history, and all writes enforce `0600`
permissions. When the daemon is running at the time of a write, a restart hint is
printed — credentials are loaded once at daemon startup.

### `hensu credentials set`

Set or update a single credential. The value is read via a masked prompt so it never
appears in shell history. Use `--stdin` in non-interactive environments.

```
hensu credentials set <KEY> [--stdin]

arguments:
  <KEY>      Credential key name, e.g. ANTHROPIC_API_KEY

options:
  --stdin    Read value from stdin instead of an interactive prompt
```

Example:

```
$ hensu credentials set ANTHROPIC_API_KEY
Enter value for ANTHROPIC_API_KEY: *******
✓ ANTHROPIC_API_KEY saved to ~/.hensu/credentials

# CI / scripting
$ echo "$MY_API_KEY" | hensu credentials set ANTHROPIC_API_KEY --stdin
```

### `hensu credentials list`

Show the names of all configured credentials. Values are always masked.

```
hensu credentials list
```

Example output:

```
Configured credentials  (~/.hensu/credentials)
  ANTHROPIC_API_KEY   ***
  GOOGLE_API_KEY      ***
```

### `hensu credentials unset`

Remove a single credential from the file. Comment lines and all other entries are
preserved.

```
hensu credentials unset <KEY>

arguments:
  <KEY>   Credential key to remove
```

---

## Server Commands

All server commands accept:

```
--server <url>    Server base URL  (default: http://localhost:8080 | override: hensu.server.url)
--token  <jwt>    JWT bearer token  (override: hensu.server.token)
```

### `hensu push`

Push a compiled workflow JSON to the server. Run `hensu build` first.

```
hensu push <workflow-id> [-d <working-dir>] [--server <url>] [--token <jwt>]

arguments:
  <workflow-id>            Workflow ID — must match a file in {working-dir}/build/
  -d, --working-dir        Directory that contains the build/ output folder
```

### `hensu pull`

Fetch a workflow definition from the server and print it to stdout.

```
hensu pull <workflow-id> [--server <url>] [--token <jwt>]
```

### `hensu delete`

Delete a workflow from the server.

```
hensu delete <workflow-id> [--server <url>] [--token <jwt>]
```

### `hensu list`

List all workflows registered on the server for the current tenant.

```
hensu list [--server <url>] [--token <jwt>]
```

Output:

```
ID                              VERSION
---------------------------------------------
my-workflow                     1.0.0
other-workflow                  1.2.0
```

---

## Typical Workflows

### Run once (no daemon)

```bash
hensu run my-workflow -d ./my-project
```

### Run with daemon (fast cold-start)

```bash
hensu daemon start
hensu run my-workflow -d ./my-project          # delegates to daemon automatically
hensu ps                                       # see running executions
hensu attach <exec-id>                         # reconnect after Ctrl+C
```

### Build and push to server

```bash
# 1. Compile Kotlin DSL to JSON (server cannot run the Kotlin compiler)
hensu build my-workflow -d ./my-project

# 2. Push compiled JSON to server
hensu push my-workflow -d ./my-project --server http://localhost:8080
```

### Auto-start daemon on login

**Linux (systemd):**

```bash
# Enable socket activation — daemon starts on-demand when first hensu run is called
systemctl --user enable --now hensu-daemon.socket
```

**macOS (launchd):**

```bash
# Load the agent and mark it to start automatically on login
launchctl load -w ~/Library/LaunchAgents/io.hensu.daemon.plist
```

---

## Project Layout

    A typical Hensu working directory (`-d <working-dir>`):
    
    ```
    working-dir/
    +— workflows/                          # Kotlin DSL workflow definitions
    +— stubs/                              # Agent stub responses for local testing
    +— prompts/                            # Input prompt files
    +— rubrics/                            # Evaluation rubric definitions
    │   +— templates/
    +— build/                              # Output of `hensu build` (JSON artifacts)
    +— commands.yaml                       # CLI command shortcuts
    ```

## Configuration

### API Credentials

See [Credentials Commands](#credentials-commands) above.

### Application Properties

The shipped `hensu-cli/src/main/resources/application.properties` sets `hensu.server.url`
and `hensu.workflow.file`. The following properties are all honored via MicroProfile config
(system property or environment variable) even when absent from the file:

```properties
# Default server URL for push / pull / delete / list
hensu.server.url=http://localhost:8080

# Optional JWT token for server commands
hensu.server.token=

# Default working directory (overridden by -d on any command)
hensu.working.dir=

# Default workflow file (overridden by positional argument on run/validate/etc.)
hensu.workflow.file=
```

All properties can be overridden at runtime with the corresponding CLI option.

## Development

```bash
# Build
./gradlew hensu-cli:build

# Run in Quarkus dev mode (hot reload)
./gradlew hensu-cli:quarkusDev
```

## Testing — Stub Agent System

The stub agent system runs full workflow executions without consuming API tokens. When enabled,
`StubAgentProvider` (priority 1000) intercepts all agent calls and returns responses from
`StubResponseRegistry`. Place `.txt` files under `stubs/default/{nodeId}.txt` in your working
directory to supply per-node mock responses.

**Enable stub mode** (four equivalent ways):

```bash
# 1. Environment variable (recommended for CLI use)
export HENSU_STUB_ENABLED=true
hensu run my-workflow -d ./my-project

# 2. System property
java -Dhensu.stub.enabled=true -jar ~/.hensu/lib/hensu.jar run my-workflow

# 3. Application property (src/main/resources/application.properties)
# hensu.stub.enabled=true

# 4. Credentials map (per-execution override in Java code)
# credentials.put("HENSU_STUB_ENABLED", "true");
```

> See [Stub Agent System](../docs/developer-guide-core.md#stub-agent-system) in the Core
> Developer Guide for response resolution order, programmatic registration, and scenario-based
> stubs.

## Mid-Workflow Actions

The CLI supports two action types that nodes can trigger during execution, implemented by
`CLIActionExecutor`:

- **Send** – delegates to a registered `ActionHandler`. Each handler has a unique ID
  (e.g. `"slack"`, `"github-dispatch"`). Register handlers programmatically:

  ```java
  CLIActionExecutor executor = new CLIActionExecutor();
  executor.registerHandler(new SlackHandler(webhookUrl));
  executor.registerHandler(new GitHubDispatchHandler(token));
  ```

- **Execute** – runs shell commands defined in `commands.yaml` (loaded from the working
  directory). Commands are looked up by ID from a `CommandRegistry` – the DSL never
  specifies raw shell strings, keeping credentials out of workflow files.

DSL-authored action parameters support `{variable}` template syntax, resolved from the
current workflow context at execution time.

---

## Exit Codes

| Code | Meaning                                                                                                     |
|------|-------------------------------------------------------------------------------------------------------------|
| `0`  | Command ran — including workflows ending in `FAILURE` status and reported errors (e.g. validation failures) |
| `1`  | Unhandled internal error                                                                                    |
| `2`  | Invalid command-line arguments (picocli usage error)                                                        |

Commands report execution and validation errors on stderr but still exit `0` — scripts
must parse the output (`[OK]` / `[FAIL]`, the `status` line) rather than rely on the
exit code to detect workflow-level failure.

---

## Dependencies

| Module / Library             | Role                                      |
|------------------------------|-------------------------------------------|
| `hensu-core`                 | Workflow engine, execution, agent API     |
| `hensu-serialization`        | Jackson-based JSON for workflows          |
| `hensu-langchain4j-adapter`  | LLM provider integration (Anthropic, etc) |
| `quarkus-picocli`            | Command-line framework                    |
| `quarkus-arc`                | CDI container                             |
| `kotlin-compiler-embeddable` | Client-side Kotlin DSL compilation        |
