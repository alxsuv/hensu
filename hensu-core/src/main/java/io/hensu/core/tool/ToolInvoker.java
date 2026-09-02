package io.hensu.core.tool;

import java.util.Map;

/// Invocation half of the tool seam, consumed by the agent tool loop.
///
/// The loop depends on this narrow interface rather than on {@link ToolRegistry}
/// so that discovery and invocation stay separable: a registry answers "which
/// tools exist", an invoker answers "run this one". {@link ToolRouter} implements
/// both and is the invoker wired into execution contexts.
///
/// @see ToolProvider for the runtime integrations behind an invoker
public interface ToolInvoker {

    /// Invokes a tool and returns its result.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null (may be empty)
    /// @return the invocation result, never null; unknown tools yield a failure result
    ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context);
}
