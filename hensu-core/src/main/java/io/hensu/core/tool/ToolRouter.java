package io.hensu.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Composes several {@link ToolProvider} instances into one tool surface.
///
/// The router is the single object the engine wires into an execution context:
/// it answers discovery questions as a {@link ToolRegistry} and performs
/// invocation as a {@link ToolInvoker}, routing each call to the provider that
/// owns the requested name. Runtimes differ only in which providers they
/// contribute – the engine never learns whether a tool came from an MCP server
/// or a local command.
///
/// ### Mutation
/// A router is provider-backed and therefore immutable: {@link #register} and
/// {@link #remove} throw {@link UnsupportedOperationException}. Add or remove a
/// {@link ToolProvider} instead of mutating the router.
///
/// ### Duplicate names
/// Two providers exposing the same tool name is a configuration error, because
/// the router cannot decide which one the agent meant. The constructor checks
/// for collisions, but that check is only best-effort: a provider whose catalog
/// is dynamic (tenant-scoped on the server, lazily started on the CLI) reports
/// nothing yet at construction time. The authoritative check therefore runs
/// again on every catalog materialization – {@link #all()}, {@link #get} and
/// {@link #call} all reject collisions – so a duplicate surfaces before any tool
/// executes.
///
/// ### Thread Safety
/// @implNote Immutable; safe for concurrent use. Thread safety of the catalog
/// itself is each provider's responsibility.
///
/// @see ToolProvider for contributing a tool source
public final class ToolRouter implements ToolRegistry, ToolInvoker {

    private final List<ToolProvider> providers;

    /// Creates a router over the given providers.
    ///
    /// @param providers tool sources to compose, not null (may be empty)
    /// @throws NullPointerException if providers is null or contains null
    /// @throws IllegalStateException if two providers already expose the same tool name
    public ToolRouter(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
        collectValidated();
    }

    /// Returns a router with no providers, exposing no tools.
    ///
    /// @return empty router, never null
    public static ToolRouter empty() {
        return new ToolRouter(List.of());
    }

    /// {@inheritDoc}
    ///
    /// @throws UnsupportedOperationException always – the router is provider-backed
    @Override
    public void register(ToolDefinition tool) {
        throw new UnsupportedOperationException(
                "ToolRouter is provider-backed; add a ToolProvider instead");
    }

    /// {@inheritDoc}
    ///
    /// @throws UnsupportedOperationException always – the router is provider-backed
    @Override
    public boolean remove(String name) {
        throw new UnsupportedOperationException(
                "ToolRouter is provider-backed; add a ToolProvider instead");
    }

    /// Looks up a tool across the live catalogs of all providers.
    ///
    /// @param name the tool identifier to look up, not null
    /// @return the tool definition if exactly one provider exposes it, empty otherwise
    /// @throws NullPointerException if name is null
    /// @throws IllegalStateException if two providers expose this name
    @Override
    public Optional<ToolDefinition> get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return collectValidated().stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /// Returns the union of every provider's live catalog.
    ///
    /// This is the authoritative duplicate check: the tool loop resolves an
    /// agent's declared tools through this method, so a colliding catalog fails
    /// the node instead of silently routing to an arbitrary provider.
    ///
    /// @return unmodifiable union of all provider catalogs, never null (may be empty)
    /// @throws IllegalStateException if two providers expose the same tool name
    @Override
    public List<ToolDefinition> all() {
        return collectValidated();
    }

    /// Routes an invocation to the provider owning the tool.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null (may be empty)
    /// @return the provider's result, or a failure result naming the available tools
    ///     when no provider claims the name; provider exceptions become failure results
    /// @throws IllegalStateException if two providers claim the same name
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        Objects.requireNonNull(toolName, "toolName must not be null");

        ToolProvider owner = null;
        for (ToolProvider provider : providers) {
            if (provider.provides(toolName)) {
                if (owner != null) {
                    throw duplicate(toolName, owner, provider);
                }
                owner = provider;
            }
        }

        if (owner == null) {
            return ToolCallResult.failure(
                    toolName, "Unknown tool '" + toolName + "', available: " + toolNames());
        }

        try {
            return owner.call(toolName, arguments, context);
        } catch (RuntimeException e) {
            return ToolCallResult.failure(
                    toolName,
                    "Tool provider "
                            + owner.getClass().getSimpleName()
                            + " failed: "
                            + e.getMessage());
        }
    }

    /// Materializes the union of provider catalogs, rejecting duplicate names.
    ///
    /// @return unmodifiable union, never null
    /// @throws IllegalStateException if two providers expose the same tool name
    private List<ToolDefinition> collectValidated() {
        Map<String, ToolProvider> owners = new LinkedHashMap<>();
        List<ToolDefinition> union = new ArrayList<>();

        for (ToolProvider provider : providers) {
            for (ToolDefinition tool : provider.tools()) {
                ToolProvider previous = owners.putIfAbsent(tool.name(), provider);
                if (previous != null) {
                    throw duplicate(tool.name(), previous, provider);
                }
                union.add(tool);
            }
        }
        return List.copyOf(union);
    }

    private List<String> toolNames() {
        return collectValidated().stream().map(ToolDefinition::name).toList();
    }

    private static IllegalStateException duplicate(
            String toolName, ToolProvider first, ToolProvider second) {
        return new IllegalStateException(
                "Duplicate tool '"
                        + toolName
                        + "' from "
                        + first.getClass().getSimpleName()
                        + " and "
                        + second.getClass().getSimpleName()
                        + " – rename one in configuration");
    }
}
