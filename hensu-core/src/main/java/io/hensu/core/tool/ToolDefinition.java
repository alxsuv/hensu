package io.hensu.core.tool;

import java.util.List;
import java.util.Objects;

/// Describes a callable tool without implementation details.
///
/// Tool definitions are protocol-agnostic descriptors used by:
/// - Agents, to decide which tool to request
/// - The tool loop, to validate the tool an agent asked for
/// - MCP integration at the server layer
///
/// The core module only defines the tool shape; actual invocation happens
/// through {@link ToolProvider} implementations contributed by each runtime.
///
/// ### Contracts
/// - **Precondition**: `name` must not be null or blank
/// - **Postcondition**: All fields immutable after construction
///
/// ### Usage
/// {@snippet :
/// ToolDefinition searchTool = new ToolDefinition(
///     "search",
///     "Search for information",
///     List.of(
///         new ParameterDef("query", "string", "Search query", true, null)
///     ),
///     new ParameterDef("results", "array", "Search results", true, null)
/// );
/// }
///
/// @param name unique tool identifier, not null
/// @param description human-readable description for LLM context, not null
/// @param parameters input parameters accepted by the tool, not null (may be empty)
/// @param returnType description of the tool's output, may be null
/// @see ToolRegistry for tool discovery
/// @see ToolProvider for tool invocation
public record ToolDefinition(
        String name, String description, List<ParameterDef> parameters, ParameterDef returnType) {

    /// Compact constructor with validation.
    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
    }

    /// Creates a tool definition without explicit return type.
    ///
    /// @param name unique tool identifier, not null
    /// @param description human-readable description, not null
    /// @param parameters input parameters, not null
    /// @return new tool definition, never null
    public static ToolDefinition of(
            String name, String description, List<ParameterDef> parameters) {
        return new ToolDefinition(name, description, parameters, null);
    }

    /// Creates a simple tool definition with no parameters.
    ///
    /// @param name unique tool identifier, not null
    /// @param description human-readable description, not null
    /// @return new tool definition, never null
    public static ToolDefinition simple(String name, String description) {
        return new ToolDefinition(name, description, List.of(), null);
    }

    /// Returns whether this tool has any required parameters.
    ///
    /// @return true if any parameter is required
    public boolean hasRequiredParameters() {
        return parameters.stream().anyMatch(ParameterDef::required);
    }

    /// Returns the list of required parameter names.
    ///
    /// @return list of required parameter names, never null
    public List<String> requiredParameterNames() {
        return parameters.stream().filter(ParameterDef::required).map(ParameterDef::name).toList();
    }

    /// Describes a tool parameter or return type.
    ///
    /// @param name parameter identifier, not null
    /// @param type parameter type (string, number, boolean, object, array), not null
    /// @param description human-readable description, not null
    /// @param required whether the parameter must be provided
    /// @param defaultValue default value if not provided, may be null
    public record ParameterDef(
            String name, String type, String description, boolean required, Object defaultValue) {

        /// Compact constructor with validation.
        public ParameterDef {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(description, "description must not be null");
        }

        /// Creates a required parameter definition.
        ///
        /// @param name parameter identifier, not null
        /// @param type parameter type, not null
        /// @param description human-readable description, not null
        /// @return new parameter definition, never null
        public static ParameterDef required(String name, String type, String description) {
            return new ParameterDef(name, type, description, true, null);
        }

        /// Creates an optional parameter definition.
        ///
        /// @param name parameter identifier, not null
        /// @param type parameter type, not null
        /// @param description human-readable description, not null
        /// @param defaultValue default value if not provided, may be null
        /// @return new parameter definition, never null
        public static ParameterDef optional(
                String name, String type, String description, Object defaultValue) {
            return new ParameterDef(name, type, description, false, defaultValue);
        }
    }
}
