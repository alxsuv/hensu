package io.hensu.core.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/// Configurable {@link ToolProvider} for tests, recording what it was asked to run.
///
/// Exposes a fixed catalog and delegates invocation to a supplied function, so a
/// test can make a tool succeed, fail, or throw without a network or a process.
public final class StubToolProvider implements ToolProvider {

    /// One recorded invocation.
    ///
    /// @param toolName the tool that was invoked
    /// @param arguments the arguments passed by the caller
    public record Invocation(String toolName, Map<String, Object> arguments) {}

    private final List<ToolDefinition> tools;
    private final BiFunction<String, Map<String, Object>, ToolCallResult> behaviour;
    private final List<Invocation> invocations = new ArrayList<>();

    public StubToolProvider(
            List<ToolDefinition> tools,
            BiFunction<String, Map<String, Object>, ToolCallResult> behaviour) {
        this.tools = List.copyOf(tools);
        this.behaviour = behaviour;
    }

    /// Creates a provider whose every tool succeeds with a fixed output.
    ///
    /// @param output text returned by every invocation
    /// @param tools catalog to expose
    /// @return provider, never null
    public static StubToolProvider alwaysSucceeding(String output, ToolDefinition... tools) {
        return new StubToolProvider(
                List.of(tools), (name, _) -> ToolCallResult.success(name, output));
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public boolean provides(String toolName) {
        return tools.stream().anyMatch(t -> t.name().equals(toolName));
    }

    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        synchronized (invocations) {
            invocations.add(
                    new Invocation(
                            toolName, arguments != null ? new HashMap<>(arguments) : Map.of()));
        }
        return behaviour.apply(toolName, arguments);
    }

    /// Returns the invocations recorded so far, in call order.
    ///
    /// @return snapshot of recorded invocations, never null
    public List<Invocation> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }
}
