/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.mcp.outbound.ScriptedHttpClient.Resp;

/**
 * {@link McpOutboundToolCatalog} — aggregation, default-off filtering, per-server failure
 * isolation, qualified-name sanitisation, caching, and call-time re-validation. Uses a real {@link
 * McpOutboundServerRegistry} against a temp directory (cheap, and it's the registry's own behaviour
 * under test elsewhere) plus a real {@link McpOutboundClient} wired to {@link ScriptedHttpClient} (no
 * network) — {@code McpOutboundClient} is {@code final} so it can't be mocked directly.
 */
public class McpOutboundToolCatalogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String INIT_OK = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{"
            + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},"
            + "\"serverInfo\":{\"name\":\"remote\",\"version\":\"1.0\"}}}";

    private static String toolsList(String... names) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(names[i]).append("\",\"description\":\"d\"}");
        }
        return sb.append("]}}").toString();
    }

    @Test
    public void serverWithNoEnabledToolsIsSkippedEntirelyNoNetworkCall() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of(), "x"));
        ScriptedHttpClient http = ScriptedHttpClient.of(); // empty — any send() would fail the test
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        assertTrue(catalog.tools().isEmpty());
        assertFalse(catalog.hasEnabledTools());
        assertTrue("default-off must never trigger discovery", http.requests.isEmpty());
    }

    @Test
    public void enabledToolsAreDiscoveredAndFlattenedWithServerPrefix() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(toolsList("tool1", "tool2")));
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        List<McpOutboundToolDescriptor> tools = catalog.tools();

        // tool2 was discovered but never admin-enabled — filtered out.
        assertEquals(1, tools.size());
        assertEquals("mcp__srv-a__tool1", tools.get(0).qualifiedName());
        assertEquals("srv-a", tools.get(0).serverId());
        assertEquals("tool1", tools.get(0).toolName());
        assertTrue(catalog.hasEnabledTools());
    }

    @Test
    public void unreachableServerIsIsolatedFromOthers() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        registry.save(new McpOutboundServer("srv-b", "B", "https://b.example/mcp", null, null, Set.of("tool2"), "x"));
        // registry.list() is sorted by id: srv-a processed first (fails), then srv-b (succeeds).
        ScriptedHttpClient http =
                ScriptedHttpClient.of(Resp.status(500, "boom"), Resp.ok(INIT_OK), Resp.ok(toolsList("tool2")));
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        List<McpOutboundToolDescriptor> tools = catalog.tools();

        assertEquals("srv-a's failure must not take down srv-b's tools", 1, tools.size());
        assertEquals("mcp__srv-b__tool2", tools.get(0).qualifiedName());
    }

    @Test
    public void callToolDispatchesThroughTheDiscoveredDescriptor() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        String callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}";
        ScriptedHttpClient http = ScriptedHttpClient.of(
                Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")), // tools() discovery
                Resp.ok(INIT_OK), Resp.ok(callResult)); // callTool dispatch
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        String qualified = catalog.tools().get(0).qualifiedName();
        McpOutboundClient.CallResult result = catalog.callTool(qualified, "{\"x\":1}");

        assertTrue(result.ok());
        assertEquals("done", result.resultText());
    }

    @Test
    public void callToolFailsForUnknownQualifiedNameWithoutAnyNetworkCall() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        ScriptedHttpClient http = ScriptedHttpClient.of();
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        McpOutboundClient.CallResult result = catalog.callTool("mcp__bogus__tool", "{}");

        assertFalse(result.ok());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    public void disablingAToolAfterDiscoveryMakesItUncallable() throws Exception {
        Path root = tmp.newFolder("mcp").toPath();
        McpOutboundServerRegistry registry = new McpOutboundServerRegistry(root);
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        ScriptedHttpClient http = ScriptedHttpClient.of(Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")));
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        String qualified = catalog.tools().get(0).qualifiedName();

        // Admin disables it — a registry write, which changes the enablement signature.
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of(), "x"));

        McpOutboundClient.CallResult result = catalog.callTool(qualified, "{}");

        assertFalse(result.ok());
        // The refreshed cache no longer discovers srv-a at all (nothing enabled) — no extra HTTP
        // call, and the tool is simply gone from the catalogue rather than individually rejected.
        assertEquals(2, http.requests.size());
    }

    @Test
    public void cacheTtlExpiryTriggersRediscovery() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        ScriptedHttpClient http = ScriptedHttpClient.of(
                Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")), Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")));
        long[] clockNanos = {0L};
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(
                registry, new McpOutboundClient(http, TIMEOUT), Duration.ofSeconds(30), () -> clockNanos[0]);

        catalog.tools();
        assertEquals(2, http.requests.size());

        catalog.tools(); // within TTL — cached, no new requests
        assertEquals(2, http.requests.size());

        clockNanos[0] = Duration.ofSeconds(31).toNanos();
        catalog.tools(); // TTL expired — rediscovers
        assertEquals(4, http.requests.size());
    }

    @Test
    public void forceRefreshBypassesTheCache() throws Exception {
        McpOutboundServerRegistry registry =
                new McpOutboundServerRegistry(tmp.newFolder("mcp").toPath());
        registry.save(new McpOutboundServer("srv-a", "A", "https://a.example/mcp", null, null, Set.of("tool1"), "x"));
        ScriptedHttpClient http = ScriptedHttpClient.of(
                Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")), Resp.ok(INIT_OK), Resp.ok(toolsList("tool1")));
        McpOutboundToolCatalog catalog = new McpOutboundToolCatalog(registry, new McpOutboundClient(http, TIMEOUT));

        catalog.tools();
        assertEquals(2, http.requests.size());
        catalog.forceRefresh();
        catalog.tools();
        assertEquals(4, http.requests.size());
    }

    /* ------------------------------- qualify() sanitisation ------------------------------- */

    @Test
    public void qualifySanitisesUnsafeCharacters() {
        Set<String> used = new HashSet<>();
        String q = McpOutboundToolCatalog.qualify("srv", "tool!!name with spaces", used);
        assertEquals("mcp__srv__tool__name_with_spaces", q);
        assertTrue(q.matches("[a-zA-Z0-9_-]+"));
    }

    @Test
    public void qualifyDedupesCollisionsWithNumericSuffix() {
        Set<String> used = new HashSet<>();
        String first = McpOutboundToolCatalog.qualify("srv", "dup", used);
        String second = McpOutboundToolCatalog.qualify("srv", "dup", used);
        String third = McpOutboundToolCatalog.qualify("srv", "dup", used);
        assertEquals("mcp__srv__dup", first);
        assertEquals("mcp__srv__dup_2", second);
        assertEquals("mcp__srv__dup_3", third);
    }

    @Test
    public void qualifyTruncatesToMaxFunctionNameLength() {
        Set<String> used = new HashSet<>();
        String longToolName = "a".repeat(100);
        String q = McpOutboundToolCatalog.qualify("server-id", longToolName, used);
        assertTrue(q.length() <= 64);
    }

    @Test
    public void isMcpQualifiedNameRecognisesThePrefixOnly() {
        assertTrue(McpOutboundToolCatalog.isMcpQualifiedName("mcp__notion__search_docs"));
        assertFalse(McpOutboundToolCatalog.isMcpQualifiedName("emit_query"));
        assertFalse(McpOutboundToolCatalog.isMcpQualifiedName(null));
        assertFalse(McpOutboundToolCatalog.isMcpQualifiedName(""));
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
}
