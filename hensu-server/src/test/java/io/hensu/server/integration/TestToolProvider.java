package io.hensu.server.integration;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;

/// Test {@link ToolProvider} exposing the `"test-tool"` tool to the agent tool loop.
///
/// Discovered by the CDI producer alongside the production providers, so the
/// integration tests drive the same seam the runtime uses. Invocation is
/// forwarded to {@link TestActionHandler} so existing payload assertions keep
/// working.
///
/// @see TestActionHandler for the recorded payloads
@Singleton
public class TestToolProvider implements ToolProvider {

    static final String TOOL_NAME = "test-tool";

    private static final ToolDefinition DEFINITION =
            ToolDefinition.of(
                    TOOL_NAME,
                    "Test tool for integration tests",
                    List.of(
                            ToolDefinition.ParameterDef.required(
                                    "input", "string", "Input parameter")));

    private final TestActionHandler handler;

    public TestToolProvider(TestActionHandler handler) {
        this.handler = handler;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(DEFINITION);
    }

    @Override
    public boolean provides(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        ActionResult result = handler.execute(arguments, context);
        return result.success()
                ? ToolCallResult.success(
                        toolName, result.output() != null ? result.output().toString() : "")
                : ToolCallResult.failure(toolName, result.message());
    }
}
