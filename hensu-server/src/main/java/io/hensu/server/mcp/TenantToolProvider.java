package io.hensu.server.mcp;

import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jboss.logging.Logger;

/// Temporary bridge exposing the legacy {@link TenantToolRegistry} as a {@link ToolProvider}.
///
/// The engine now discovers and invokes tools through providers rather than
/// through a registry plus an action-executor fallback. Until the server grows
/// a first-class MCP provider, this adapter keeps the server tool loop working
/// by reading the catalog from the tenant registry and routing invocation
/// straight to {@link McpSidecar}, bypassing the action-executor envelope.
///
/// @implNote Deliberately short-lived: replaced by `McpToolProvider` when the
/// shared MCP module lands. Do not build on it.
///
/// @see ToolProvider for the seam this adapts to
@Singleton
public class TenantToolProvider implements ToolProvider {

    private static final Logger LOG = Logger.getLogger(TenantToolProvider.class);

    private final TenantToolRegistry registry;
    private final McpSidecar sidecar;

    /// Creates the bridge over a tenant registry and the MCP sidecar.
    ///
    /// @param registry tenant-scoped tool catalog, not null
    /// @param sidecar MCP invocation entry point, not null
    public TenantToolProvider(TenantToolRegistry registry, McpSidecar sidecar) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sidecar = Objects.requireNonNull(sidecar, "sidecar must not be null");
    }

    @Override
    public List<ToolDefinition> tools() {
        return registry.all();
    }

    @Override
    public boolean provides(String toolName) {
        return registry.contains(toolName);
    }

    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            Map<String, Object> result = sidecar.callTool(toolName, arguments);
            return ToolCallResult.success(toolName, String.valueOf(result));
        } catch (McpException e) {
            LOG.warnv("MCP tool call failed for {0}: {1}", toolName, e.getMessage());
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }
}
