package io.hensu.core.tool;

import java.util.List;
import java.util.Map;

/// Source of callable tools contributed by one runtime integration.
///
/// A provider owns both halves of a tool's lifecycle: it publishes the
/// descriptors an agent may choose from ({@link #tools()}) and performs the
/// actual invocation ({@link #call}). Runtimes plug their own sources into the
/// engine by contributing providers – MCP servers on the server, local commands
/// and stdio MCP servers on the CLI – and the engine composes them through a
/// {@link ToolRouter} without knowing which runtime it is running in.
///
/// ### Dynamic catalogs
/// A provider's catalog may change over time, for example when it is scoped to
/// the tenant currently bound to the calling thread. Implementations must return
/// the catalog that is valid *right now* rather than a snapshot captured at
/// construction, and must return an empty list rather than throwing when no
/// catalog is currently available.
///
/// ### Thread Safety
/// @implNote Implementations must be safe for concurrent access: parallel
/// workflow branches invoke tools from multiple Virtual Threads at once.
///
/// @see ToolRouter for composition of several providers
/// @see ToolInvoker for the invocation half of this contract
public interface ToolProvider {

    /// Returns the tools this provider currently exposes.
    ///
    /// @return tool descriptors, never null (may be empty)
    List<ToolDefinition> tools();

    /// Returns whether this provider can invoke the named tool.
    ///
    /// @param toolName the tool identifier to check, not null
    /// @return true if {@link #call} would route this name
    boolean provides(String toolName);

    /// Invokes a tool and returns its result.
    ///
    /// Implementations report failures as {@link ToolCallResult#failure} so the
    /// agent can react to them; throwing is reserved for programming errors.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null (may be empty)
    /// @return the invocation result, never null
    ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context);
}
