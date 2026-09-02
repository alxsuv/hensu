package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolRouterTest {

    private static final ToolDefinition SEARCH =
            ToolDefinition.simple("search", "Search for information");
    private static final ToolDefinition BUILD = ToolDefinition.simple("build", "Run the build");

    @Nested
    class DuplicateNames {

        @Test
        void shouldRejectAtConstructionNamingBothProviders() {
            StubToolProvider first = StubToolProvider.alwaysSucceeding("a", SEARCH);
            OtherStubProvider second = new OtherStubProvider(SEARCH);

            assertThatThrownBy(() -> new ToolRouter(List.of(first, second)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("search")
                    .hasMessageContaining("StubToolProvider")
                    .hasMessageContaining("OtherStubProvider");
        }

        @Test
        void shouldRejectWhenCollisionOnlyAppearsAfterConstruction() {
            // A dynamic catalog is empty at construction, so the constructor check
            // cannot see the collision – all() must catch it before any tool runs.
            LateCatalogProvider first = new LateCatalogProvider();
            LateCatalogProvider second = new LateCatalogProvider();
            ToolRouter router = new ToolRouter(List.of(first, second));

            assertThat(router.all()).isEmpty();

            first.publish(SEARCH);
            second.publish(SEARCH);

            assertThatThrownBy(router::all)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool 'search'");
        }

        @Test
        void shouldRejectOnCallWhenTwoProvidersClaimTheName() {
            LateCatalogProvider first = new LateCatalogProvider();
            LateCatalogProvider second = new LateCatalogProvider();
            ToolRouter router = new ToolRouter(List.of(first, second));

            first.publish(SEARCH);
            second.publish(SEARCH);

            assertThatThrownBy(() -> router.call("search", Map.of(), Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool 'search'");
        }
    }

    @Nested
    class Routing {

        @Test
        void shouldRouteToTheOwningProvider() {
            StubToolProvider searchProvider = StubToolProvider.alwaysSucceeding("hits", SEARCH);
            StubToolProvider buildProvider = StubToolProvider.alwaysSucceeding("built", BUILD);
            ToolRouter router = new ToolRouter(List.of(searchProvider, buildProvider));

            ToolCallResult result = router.call("build", Map.of("clean", true), Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("built");
            assertThat(searchProvider.invocations()).isEmpty();
            assertThat(buildProvider.invocations())
                    .singleElement()
                    .satisfies(
                            invocation -> {
                                assertThat(invocation.toolName()).isEqualTo("build");
                                assertThat(invocation.arguments()).containsEntry("clean", true);
                            });
        }

        @Test
        void shouldExposeUnionOfProviderCatalogs() {
            ToolRouter router =
                    new ToolRouter(
                            List.of(
                                    StubToolProvider.alwaysSucceeding("hits", SEARCH),
                                    StubToolProvider.alwaysSucceeding("built", BUILD)));

            assertThat(router.all())
                    .extracting(ToolDefinition::name)
                    .containsExactly("search", "build");
            assertThat(router.get("search")).contains(SEARCH);
            assertThat(router.get("missing")).isEmpty();
            assertThat(router.contains("build")).isTrue();
            assertThat(router.size()).isEqualTo(2);
        }

        @Test
        void shouldReportUnknownToolAsFailureListingAvailableTools() {
            ToolRouter router =
                    new ToolRouter(List.of(StubToolProvider.alwaysSucceeding("hits", SEARCH)));

            ToolCallResult result = router.call("deploy", Map.of(), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unknown tool 'deploy'").contains("search");
        }

        @Test
        void shouldConvertProviderExceptionIntoFailureResult() {
            StubToolProvider throwing =
                    new StubToolProvider(
                            List.of(SEARCH),
                            (_, _) -> {
                                throw new IllegalArgumentException("boom");
                            });
            ToolRouter router = new ToolRouter(List.of(throwing));

            ToolCallResult result = router.call("search", Map.of(), Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("StubToolProvider").contains("boom");
        }
    }

    @Nested
    class Mutation {

        @Test
        void shouldRejectRemovalRatherThanSilentlyNoOping() {
            // ToolRegistry.remove is a default returning false: without the router's
            // override, mutating a provider-backed router would quietly do nothing.
            ToolRouter router = ToolRouter.empty();

            assertThatThrownBy(() -> router.remove("search"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("add a ToolProvider instead");
        }
    }

    /// Second provider type, so duplicate messages can name two distinct classes.
    private static final class OtherStubProvider implements ToolProvider {

        private final List<ToolDefinition> tools;

        OtherStubProvider(ToolDefinition... tools) {
            this.tools = List.of(tools);
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
            return ToolCallResult.success(toolName, "other");
        }
    }

    /// Provider whose catalog materializes after construction, like a tenant-scoped
    /// or lazily-started source.
    private static final class LateCatalogProvider implements ToolProvider {

        private List<ToolDefinition> tools = List.of();

        void publish(ToolDefinition... published) {
            this.tools = List.of(published);
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
            return ToolCallResult.success(toolName, "late");
        }
    }
}
